#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path


SKILL_ID = "pdf_generator"


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {"text": str(payload)}
    except Exception:
        return {"text": raw}


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
    base = re.sub(r"[^A-Za-z0-9._-]+", "_", str(value or "document")).strip("._-")
    if not base:
        base = "document"
    if base.lower().endswith(suffix.lower()):
        base = base[:-len(suffix)]
    base = base.strip("._-") or "document"
    max_base_len = max(1, 120 - len(suffix))
    return base[:max_base_len] + suffix


def default_output(filename: str) -> Path:
    return workspace_root() / "data" / "skills" / SKILL_ID / safe_filename(filename, ".pdf")


def read_input_text(payload: dict) -> tuple[str, str]:
    if payload.get("text") or payload.get("content"):
        return str(payload.get("text") or payload.get("content")), "inline"
    file_value = payload.get("inputFile") or payload.get("file") or payload.get("path")
    if file_value:
        target = resolve_workspace_path(str(file_value), must_exist=True)
        return target.read_text(encoding="utf-8", errors="replace")[:2_000_000], str(target)
    return str(payload.get("goal") or ""), "goal"


def result(**kwargs) -> None:
    payload = {"skill": SKILL_ID, **kwargs}
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def register_font():
    try:
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
    except Exception:
        return "Helvetica"
    font_candidates = [
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/STHeiti Light.ttc",
        "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/simhei.ttf",
    ]
    for font_path in font_candidates:
        if Path(font_path).exists():
            try:
                pdfmetrics.registerFont(TTFont("SpringClawCJK", font_path))
                return "SpringClawCJK"
            except Exception:
                continue
    return "Helvetica"


def generate_pdf(text: str, title: str, output_path: Path) -> None:
    try:
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
        from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer
    except ImportError:
        result(
            status="missingDependency",
            missingDependency="reportlab",
            installHint="python3 -m pip install reportlab",
            dryRun=False,
        )
        return

    output_path.parent.mkdir(parents=True, exist_ok=True)
    font_name = register_font()
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle("SpringClawTitle", parent=styles["Title"], fontName=font_name, fontSize=18, leading=24)
    body_style = ParagraphStyle("SpringClawBody", parent=styles["BodyText"], fontName=font_name, fontSize=10.5, leading=16)
    story = [Paragraph(escape_xml(title), title_style), Spacer(1, 16)]
    paragraphs = [part.strip() for part in re.split(r"\n\s*\n", text.strip()) if part.strip()]
    if not paragraphs:
        paragraphs = ["(empty document)"]
    for paragraph in paragraphs:
        for line in paragraph.splitlines():
            story.append(Paragraph(escape_xml(line), body_style))
        story.append(Spacer(1, 8))
    doc = SimpleDocTemplate(str(output_path), pagesize=A4, title=title)
    doc.build(story)
    result(status="success", dryRun=False, outputFile=str(output_path), title=title, characters=len(text))


def escape_xml(text: str) -> str:
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main() -> None:
    payload = parse_payload()
    try:
        text, source = read_input_text(payload)
        title = str(payload.get("title") or "SpringClaw Document").strip() or "SpringClaw Document"
        filename = str(payload.get("filename") or title)
        output = resolve_workspace_path(str(payload["outputFile"])) if payload.get("outputFile") else default_output(filename)
        dry_run = is_truthy(payload.get("dryRun") or payload.get("dry_run"))
        if dry_run:
            result(status="dryRun", dryRun=True, source=source, outputFile=str(output), title=title, characters=len(text))
            return
        generate_pdf(text, title, output)
    except Exception as exc:
        result(status="failed", error=str(exc))


if __name__ == "__main__":
    main()
