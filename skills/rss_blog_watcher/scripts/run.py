#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path

try:
    import defusedxml.ElementTree as ET
    HAS_SAFE_XML = True
except ImportError:
    import xml.etree.ElementTree as ET
    HAS_SAFE_XML = False


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {"action": str(payload)}
    except json.JSONDecodeError:
        parts = raw.split()
        return {"action": parts[0] if parts else "list", "args": parts[1:]}


def skill_root() -> Path:
    configured = os.environ.get("OPENCLAW_SKILL_ROOT")
    if configured:
        return Path(configured).resolve()
    return Path(__file__).resolve().parents[1]


def data_file() -> Path:
    return skill_root() / "data" / "blogs.json"


def load_blogs() -> dict:
    target = data_file()
    if not target.exists():
        return {}
    try:
        data = json.loads(target.read_text())
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def save_blogs(blogs: dict) -> None:
    target = data_file()
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(blogs, ensure_ascii=False, indent=2) + "\n")


def validate_url(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("RSS url must be http/https")
    return url


def fetch_feed(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "SpringClawSkill/1.0"})
    with urllib.request.urlopen(request, timeout=8) as response:
        return response.read(2_000_000)


def child_text(element: ET.Element, names: list[str]) -> str:
    for child in list(element):
        local = child.tag.split("}")[-1].lower()
        if local in names and child.text:
            return child.text.strip()
    return ""


def entry_link(element: ET.Element) -> str:
    text_link = child_text(element, ["link"])
    if text_link:
        return text_link
    for child in list(element):
        if child.tag.split("}")[-1].lower() == "link":
            href = child.attrib.get("href")
            if href:
                return href
    return ""


def parse_feed(xml_bytes: bytes, limit: int) -> list[dict]:
    if not HAS_SAFE_XML:
        raise RuntimeError("missingDependency=defusedxml; please install defusedxml before scanning external RSS XML")
    root = ET.fromstring(xml_bytes)
    entries = []
    for element in root.iter():
        local = element.tag.split("}")[-1].lower()
        if local not in {"item", "entry"}:
            continue
        title = child_text(element, ["title"]) or "(untitled)"
        link = entry_link(element)
        published = child_text(element, ["pubdate", "published", "updated", "date"])
        entries.append({"title": title, "link": link, "published": published})
        if len(entries) >= limit:
            break
    return entries


def list_action(blogs: dict) -> str:
    lines = ["skill=rss_blog_watcher", "action=list", f"count={len(blogs)}"]
    if not blogs:
        lines.append("subscriptions=(empty)")
    else:
        for name, url in sorted(blogs.items()):
            lines.append(f"- {name}: {url}")
    return "\n".join(lines)


def scan_action(blogs: dict, limit: int) -> str:
    lines = ["skill=rss_blog_watcher", "action=scan", f"subscriptions={len(blogs)}", f"limit={limit}"]
    if not blogs:
        lines.append("result=(empty subscriptions)")
        return "\n".join(lines)
    for name, url in sorted(blogs.items()):
        lines.append(f"[{name}] {url}")
        try:
            entries = parse_feed(fetch_feed(url), limit)
            if not entries:
                lines.append("- no entries found")
            for entry in entries:
                suffix = f" ({entry['published']})" if entry.get("published") else ""
                link = f" -> {entry['link']}" if entry.get("link") else ""
                lines.append(f"- {entry['title']}{suffix}{link}")
        except Exception as exc:
            lines.append(f"- failed: {exc}")
    return "\n".join(lines)


def main() -> None:
    payload = parse_payload()
    action = str(payload.get("action") or payload.get("command") or "list").lower()
    blogs = load_blogs()
    try:
        if action == "list":
            print(list_action(blogs))
        elif action == "add":
            name = str(payload.get("name") or "").strip()
            url = validate_url(str(payload.get("url") or "").strip())
            if not name:
                raise ValueError("name is required")
            blogs[name] = url
            save_blogs(blogs)
            print("\n".join(["skill=rss_blog_watcher", "action=add", f"status=success", f"name={name}", f"url={url}"]))
        elif action == "remove":
            name = str(payload.get("name") or "").strip()
            existed = name in blogs
            blogs.pop(name, None)
            save_blogs(blogs)
            print("\n".join(["skill=rss_blog_watcher", "action=remove", f"name={name}", f"removed={str(existed).lower()}"]))
        elif action == "scan":
            limit = int(payload.get("limit") or 5)
            print(scan_action(blogs, max(1, min(limit, 20))))
        else:
            raise ValueError("action must be list/add/remove/scan")
    except Exception as exc:
        print("\n".join(["skill=rss_blog_watcher", f"action={action}", "status=failed", f"error={exc}"]))


if __name__ == "__main__":
    main()
