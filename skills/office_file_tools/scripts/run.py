#!/usr/bin/env python3
from __future__ import annotations

import csv
import json
import os
import re
import sys
from pathlib import Path


SKILL_ID = "office_file_tools"
SUPPORTED_EXTENSIONS = {".txt", ".md", ".csv", ".json"}


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {"inputFile": str(payload)}
    except Exception:
        return {"inputFile": raw}


def is_truthy(value) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


def workspace_root() -> Path:
    return Path(os.environ.get("SPRINGCLAW_WORKSPACE_ROOT") or os.environ.get("OPENCLAW_WORKSPACE_ROOT") or os.getcwd()).resolve()


def is_inside(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def resolve_workspace_path(raw: str, *, must_exist: bool = False) -> Path:
    root = workspace_root()
    candidate = Path(str(raw).strip()).expanduser()
    if not candidate.is_absolute():
        candidate = root / candidate
    target = candidate.resolve()
    if not is_inside(target, root):
        raise ValueError("path must be inside workspace")
    if must_exist and not target.is_file():
        raise ValueError("input file does not exist")
    return target


def safe_filename(value: str, suffix: str) -> str:
    base = re.sub(r"[^A-Za-z0-9._-]+", "_", str(value or "office_report")).strip("._-")
    if not base:
        base = "office_report"
    if base.lower().endswith(suffix.lower()):
        base = base[:-len(suffix)]
    base = base.strip("._-") or "office_report"
    max_base_len = max(1, 120 - len(suffix))
    return base[:max_base_len] + suffix


def default_report_output(input_file: Path) -> Path:
    return workspace_root() / "data" / "skills" / SKILL_ID / safe_filename(input_file.stem + "_report", ".md")


def result(**kwargs) -> None:
    print(json.dumps({"skill": SKILL_ID, **kwargs}, ensure_ascii=False, indent=2))


def read_supported_file(path: Path) -> str:
    if path.suffix.lower() not in SUPPORTED_EXTENSIONS:
        raise ValueError("unsupported file type: " + path.suffix)
    return path.read_text(encoding="utf-8", errors="replace")[:2_000_000]


def word_count(text: str) -> int:
    latin = re.findall(r"[A-Za-z0-9_]+", text)
    chinese = re.findall(r"[\u4e00-\u9fff]", text)
    return len(latin) + len(chinese)


def preview(text: str, limit: int = 600) -> str:
    normalized = re.sub(r"\s+", " ", text).strip()
    return normalized[:limit]


def csv_stats(text: str) -> dict:
    rows = list(csv.reader(text.splitlines()))
    if not rows:
        return {"rowCount": 0, "columnCount": 0, "headers": []}
    return {
        "rowCount": max(len(rows) - 1, 0),
        "columnCount": max(len(row) for row in rows),
        "headers": rows[0][:20],
    }


def json_stats(text: str) -> dict:
    try:
        data = json.loads(text)
    except Exception as exc:
        return {"jsonValid": False, "jsonError": str(exc)}
    if isinstance(data, dict):
        return {"jsonValid": True, "jsonType": "object", "keyCount": len(data), "keys": list(data.keys())[:20]}
    if isinstance(data, list):
        return {"jsonValid": True, "jsonType": "array", "itemCount": len(data)}
    return {"jsonValid": True, "jsonType": type(data).__name__}


def analyze(path: Path, text: str) -> dict:
    lines = text.splitlines()
    stats = {
        "inputFile": str(path),
        "extension": path.suffix.lower(),
        "characterCount": len(text),
        "lineCount": len(lines),
        "wordCount": word_count(text),
        "preview": preview(text),
    }
    if path.suffix.lower() == ".csv":
        stats.update(csv_stats(text))
    if path.suffix.lower() == ".json":
        stats.update(json_stats(text))
    return stats


def render_report(stats: dict) -> str:
    lines = [
        "# Office File Report",
        "",
        f"- File: `{stats.get('inputFile')}`",
        f"- Extension: `{stats.get('extension')}`",
        f"- Lines: {stats.get('lineCount')}",
        f"- Words: {stats.get('wordCount')}",
        f"- Characters: {stats.get('characterCount')}",
    ]
    if "rowCount" in stats:
        lines.extend([f"- CSV rows: {stats.get('rowCount')}", f"- CSV columns: {stats.get('columnCount')}"])
    if "jsonValid" in stats:
        lines.append(f"- JSON valid: {stats.get('jsonValid')}")
    lines.extend(["", "## Preview", "", str(stats.get("preview") or "")])
    return "\n".join(lines).strip() + "\n"


def main() -> None:
    payload = parse_payload()
    try:
        raw_file = payload.get("inputFile") or payload.get("file") or payload.get("path")
        if not raw_file:
            raise ValueError("inputFile is required")
        action = str(payload.get("action") or "summarize").strip().lower()
        dry_run = is_truthy(payload.get("dryRun") or payload.get("dry_run"))
        target = resolve_workspace_path(str(raw_file), must_exist=True)
        text = read_supported_file(target)
        stats = analyze(target, text)
        if action == "report":
            output = resolve_workspace_path(str(payload["outputFile"])) if payload.get("outputFile") else default_report_output(target)
            stats["outputFile"] = str(output)
            if dry_run:
                result(status="dryRun", dryRun=True, action=action, **stats)
                return
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(render_report(stats), encoding="utf-8")
            result(status="success", dryRun=False, action=action, **stats)
            return
        result(status="success", dryRun=dry_run, action=action, **stats)
    except Exception as exc:
        result(status="failed", error=str(exc))


if __name__ == "__main__":
    main()
