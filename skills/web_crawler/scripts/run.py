#!/usr/bin/env python3
import html
import http.client
import ipaddress
import json
import os
import re
import socket
import ssl
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlparse

TIMEOUT_SECONDS = 12
MAX_CHARS = 6000
MAX_REDIRECTS = 5

URL_PATTERN = re.compile(r'((?:https?://|www\.)[^\s]+)', re.IGNORECASE)
TITLE_PATTERN = re.compile(r'(?is)<title[^>]*>(.*?)</title>')
PRIVATE_IPV4_PREFIXES = (
    '10.', '127.', '169.254.', '192.168.'
)


class TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
        self.skip_depth = 0

    def handle_starttag(self, tag, attrs):
        name = (tag or '').lower()
        if name in {'script', 'style', 'noscript'}:
            self.skip_depth += 1
        elif self.skip_depth == 0 and name in {'p', 'div', 'br', 'li', 'section', 'article', 'h1', 'h2', 'h3', 'h4'}:
            self.parts.append('\n')

    def handle_endtag(self, tag):
        name = (tag or '').lower()
        if name in {'script', 'style', 'noscript'} and self.skip_depth > 0:
            self.skip_depth -= 1
        elif self.skip_depth == 0 and name in {'p', 'div', 'li', 'section', 'article'}:
            self.parts.append('\n')

    def handle_data(self, data):
        if self.skip_depth == 0 and data:
            self.parts.append(data)

    def text(self):
        joined = ''.join(self.parts)
        joined = html.unescape(joined).replace('\r', '')
        joined = re.sub(r'[\t\x0b\f ]+', ' ', joined)
        joined = re.sub(r' *\n *', '\n', joined)
        joined = re.sub(r'\n{3,}', '\n\n', joined)
        return joined.strip()


def parse_payload():
    raw = sys.argv[1] if len(sys.argv) > 1 else '{}'
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {'goal': str(payload)}
    except json.JSONDecodeError:
        return {'goal': raw}


def extract_url(payload):
    for key in ('url', 'targetUrl', 'target', 'link'):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return normalize_url(value.strip())
    goal = str(payload.get('goal', '') or payload.get('message', '')).strip()
    match = URL_PATTERN.search(goal)
    if not match:
        return ''
    return normalize_url(match.group(1))


def normalize_url(raw):
    value = (raw or '').strip().rstrip('.,);]}>\"\'')
    if not value:
        return ''
    if value.startswith('www.'):
        value = 'https://' + value
    parsed = urlparse(value)
    if not parsed.scheme:
        value = 'https://' + value
        parsed = urlparse(value)
    if parsed.scheme not in {'http', 'https'}:
        raise ValueError(f'仅支持 http/https URL: {raw}')
    host = (parsed.hostname or '').lower()
    validate_public_host(host, raw)
    return value


def validate_public_host(host, raw):
    if not host:
        raise ValueError(f'URL 缺少 host: {raw}')
    normalized_host = host.strip('[]').rstrip('.').lower()
    if normalized_host == 'localhost' or normalized_host.endswith('.local'):
        raise ValueError(f'禁止访问本机或内网地址: {host}')
    try:
        ip = ipaddress.ip_address(normalized_host)
    except ValueError:
        return
    if ip.version == 4:
        if normalized_host.startswith(PRIVATE_IPV4_PREFIXES):
            raise ValueError(f'禁止访问本机或内网地址: {host}')
        if normalized_host.startswith('172.'):
            try:
                second = int(normalized_host.split('.')[1])
                if 16 <= second <= 31:
                    raise ValueError(f'禁止访问本机或内网地址: {host}')
            except (IndexError, ValueError) as exc:
                raise ValueError(f'URL host 格式异常: {host}') from exc
    if is_blocked_ip(ip):
        raise ValueError(f'禁止访问本机或内网地址: {host}')


def is_blocked_ip(ip):
    mapped_ipv4 = getattr(ip, 'ipv4_mapped', None)
    if mapped_ipv4 is not None:
        ip = mapped_ipv4
    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_unspecified
        or ip.is_reserved
        or ip.is_multicast
    )


def resolve_public_addresses(host, port):
    normalized_host = host.strip('[]').rstrip('.').lower()
    try:
        addresses = socket.getaddrinfo(normalized_host, port, type=socket.SOCK_STREAM)
    except socket.gaierror as exc:
        raise ValueError(f'URL host 无法解析: {host}') from exc
    public_addresses = []
    for family, socktype, proto, _canonname, sockaddr in addresses:
        ip = ipaddress.ip_address(sockaddr[0])
        if is_blocked_ip(ip):
            raise ValueError(f'禁止访问本机或内网地址: {host}')
        public_addresses.append((family, socktype, proto, str(ip)))
    if not public_addresses:
        raise ValueError(f'URL host 无可用公网地址: {host}')
    return public_addresses


class PinnedHTTPConnection(http.client.HTTPConnection):
    def __init__(self, host, port, pinned_ip, timeout):
        super().__init__(host, port=port, timeout=timeout)
        self.pinned_ip = pinned_ip

    def connect(self):
        self.sock = socket.create_connection((self.pinned_ip, self.port), self.timeout, self.source_address)


class PinnedHTTPSConnection(PinnedHTTPConnection):
    default_port = 443

    def __init__(self, host, port, pinned_ip, timeout):
        super().__init__(host, port, pinned_ip, timeout)
        self.context = ssl.create_default_context()

    def connect(self):
        super().connect()
        self.sock = self.context.wrap_socket(self.sock, server_hostname=self.host)


def fetch(url, redirects=0):
    if redirects > MAX_REDIRECTS:
        raise ValueError('网页跳转次数过多')
    normalized_url = normalize_url(url)
    parsed = urlparse(normalized_url)
    host = parsed.hostname or ''
    port = parsed.port or (443 if parsed.scheme == 'https' else 80)
    _family, _socktype, _proto, pinned_ip = resolve_public_addresses(host, port)[0]
    connection_cls = PinnedHTTPSConnection if parsed.scheme == 'https' else PinnedHTTPConnection
    connection = connection_cls(host, port, pinned_ip, TIMEOUT_SECONDS)
    path = parsed.path or '/'
    if parsed.query:
        path += '?' + parsed.query
    host_header = host
    if (parsed.scheme == 'http' and port != 80) or (parsed.scheme == 'https' and port != 443):
        host_header = f'{host}:{port}'
    headers = {
        'Host': host_header,
        'User-Agent': 'OpenClaw-WebCrawlerSkill/1.0',
        'Accept': 'text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8',
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    }
    try:
        connection.request('GET', path, headers=headers)
        response = connection.getresponse()
        if response.status in {301, 302, 303, 307, 308}:
            location = response.getheader('Location')
            response.read(1024)
            if not location:
                raise ValueError('网页返回跳转但缺少 Location')
            return fetch(urljoin(normalized_url, location), redirects + 1)
        body = response.read(MAX_CHARS * 4)
        content_type = response.msg.get_content_type()
        charset = response.msg.get_content_charset() or 'utf-8'
        return body.decode(charset, errors='ignore'), content_type
    finally:
        connection.close()


def extract_title(raw_html):
    match = TITLE_PATTERN.search(raw_html or '')
    if not match:
        return ''
    return html.unescape(re.sub(r'\s+', ' ', match.group(1))).strip()


def normalize_text(raw_body, content_type):
    if not raw_body:
        return '（网页返回为空）'
    if content_type in {'text/plain', 'application/json'}:
        return raw_body.strip()[:MAX_CHARS]
    parser = TextExtractor()
    parser.feed(raw_body)
    text = parser.text()
    return (text or '（网页正文提取为空）')[:MAX_CHARS]


def main():
    payload = parse_payload()
    goal = str(payload.get('goal', '')).strip()
    workspace_root = Path(os.environ.get('OPENCLAW_WORKSPACE_ROOT', os.getcwd())).resolve()
    skill_name = os.environ.get('OPENCLAW_SKILL_NAME', 'web_crawler')

    try:
        url = extract_url(payload)
        if not url:
            print('未找到可抓取的 URL。请在问题里直接带上 http(s) 链接。')
            return
        raw_body, content_type = fetch(url)
        title = extract_title(raw_body)
        text = normalize_text(raw_body, content_type)
        lines = [
            f'skill={skill_name}',
            f'goal={goal or "读取网页正文"}',
            f'workspace={workspace_root}',
            f'url={url}',
            f'contentType={content_type}',
        ]
        if title:
            lines.append(f'title={title}')
        lines.append('')
        lines.append(text)
        print('\n'.join(lines))
    except Exception as exc:
        print(f'网页抓取失败: {exc}')


if __name__ == '__main__':
    main()
