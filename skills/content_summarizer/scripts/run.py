#!/usr/bin/env python3
from __future__ import annotations

import html.parser
import ipaddress
import json
import re
import socket
import sys
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path
import os


class TextExtractor(html.parser.HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.skip_depth = 0
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.lower() in {"script", "style", "noscript", "svg"}:
            self.skip_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() in {"script", "style", "noscript", "svg"} and self.skip_depth:
            self.skip_depth -= 1

    def handle_data(self, data: str) -> None:
        if not self.skip_depth:
            text = data.strip()
            if text:
                self.parts.append(text)


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {"text": str(payload)}
    except Exception:
        return {"text": raw}


def workspace_root() -> Path:
    return Path(os.environ.get("OPENCLAW_WORKSPACE_ROOT", os.getcwd())).resolve()


def is_private_host(hostname: str) -> bool:
    try:
        addresses = socket.getaddrinfo(hostname, None)
    except socket.gaierror:
        return True
    for address in addresses:
        ip = ipaddress.ip_address(address[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved:
            return True
    return False


def validate_public_url(url: str) -> urllib.parse.ParseResult:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("only http/https urls are supported")
    if is_private_host(parsed.hostname):
        raise ValueError("private or localhost urls are blocked")
    return parsed


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_public_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_url(url: str) -> str:
    validate_public_url(url)
    request = urllib.request.Request(url, headers={"User-Agent": "SpringClawSkill/1.0"})
    opener = urllib.request.build_opener(SafeRedirectHandler)
    with opener.open(request, timeout=8) as response:
        raw = response.read(2_000_000)
        content_type = response.headers.get("content-type", "")
        charset = "utf-8"
        match = re.search(r"charset=([^;\s]+)", content_type, re.I)
        if match:
            charset = match.group(1)
    html = raw.decode(charset, errors="replace")
    extractor = TextExtractor()
    extractor.feed(html)
    return " ".join(extractor.parts)


def read_workspace_file(file_name: str) -> str:
    root = workspace_root()
    target = (root / file_name).resolve()
    if root not in target.parents and target != root:
        raise ValueError("file must be inside workspace")
    if not target.is_file():
        raise ValueError("file does not exist")
    return target.read_text(errors="replace")[:2_000_000]


def split_sentences(text: str) -> list[str]:
    normalized = re.sub(r"\s+", " ", text).strip()
    if not normalized:
        return []
    pieces = re.split(r"(?<=[。！？.!?])\s+", normalized)
    if len(pieces) == 1:
        pieces = re.split(r"[;\n]", normalized)
    return [piece.strip() for piece in pieces if len(piece.strip()) >= 8]


def tokenize(text: str) -> list[str]:
    latin = re.findall(r"[A-Za-z][A-Za-z0-9_-]{2,}", text.lower())
    chinese = re.findall(r"[\u4e00-\u9fff]", text)
    stop = {"the", "and", "for", "with", "that", "this", "from", "uses", "can"}
    return [word for word in latin if word not in stop] + chinese


def summarize(text: str, length: str) -> str:
    sentences = split_sentences(text)
    if not sentences:
        return "(no summarizable content)"
    target_count = {"short": 2, "medium": 4, "long": 6}.get(length, 3)
    frequencies = Counter(tokenize(text))
    if not frequencies:
        return " ".join(sentences[:target_count])

    scored = []
    for index, sentence in enumerate(sentences):
        words = tokenize(sentence)
        score = sum(frequencies[word] for word in words) / max(len(words), 1)
        scored.append((score, index, sentence))
    selected = sorted(scored, key=lambda item: (-item[0], item[1]))[:target_count]
    selected = sorted(selected, key=lambda item: item[1])
    return " ".join(item[2] for item in selected)


def main() -> None:
    payload = parse_payload()
    length = str(payload.get("length") or "medium").lower()
    source = "text"
    try:
        if payload.get("text") or payload.get("content"):
            text = str(payload.get("text") or payload.get("content"))
        elif payload.get("file"):
            source = f"file:{payload.get('file')}"
            text = read_workspace_file(str(payload.get("file")))
        elif payload.get("url"):
            source = f"url:{payload.get('url')}"
            text = fetch_url(str(payload.get("url")))
        else:
            text = str(payload.get("goal") or "")
        result = summarize(text, length)
        print("\n".join([
            "skill=content_summarizer",
            f"source={source}",
            f"length={length}",
            f"characters={len(text)}",
            f"summary={result}",
        ]))
    except Exception as exc:
        print("\n".join([
            "skill=content_summarizer",
            "status=failed",
            f"error={exc}",
        ]))


if __name__ == "__main__":
    main()
