#!/usr/bin/env python3
import html
import json
import os
import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen

TIMEOUT_SECONDS = 12
MAX_CHARS = 6000

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
        return json.loads(raw)
    except Exception:
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
    if not host:
        raise ValueError(f'URL 缺少 host: {raw}')
    if host == 'localhost' or host == '::1' or host.endswith('.local'):
        raise ValueError(f'禁止访问本机或内网地址: {host}')
    if host.startswith(PRIVATE_IPV4_PREFIXES):
        raise ValueError(f'禁止访问本机或内网地址: {host}')
    if host.startswith('172.'):
        try:
            second = int(host.split('.')[1])
            if 16 <= second <= 31:
                raise ValueError(f'禁止访问本机或内网地址: {host}')
        except Exception:
            pass
    return value


def fetch(url):
    request = Request(
        url,
        headers={
            'User-Agent': 'OpenClaw-WebCrawlerSkill/1.0',
            'Accept': 'text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
        },
        method='GET',
    )
    with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        body = response.read(MAX_CHARS * 4)
        content_type = response.headers.get_content_type()
        charset = response.headers.get_content_charset() or 'utf-8'
        return body.decode(charset, errors='ignore'), content_type


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
