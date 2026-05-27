#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


SKILL_ID = "video_frames"


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {"videoPath": str(payload)}
    except Exception:
        return {"videoPath": raw}


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
    base = re.sub(r"[^A-Za-z0-9._-]+", "_", str(value or "frame")).strip("._-")
    if not base:
        base = "frame"
    allowed_extensions = (".jpg", ".jpeg", ".png", ".webp")
    extension = next((ext for ext in allowed_extensions if base.lower().endswith(ext)), suffix)
    if base.lower().endswith(extension):
        base = base[:-len(extension)]
    base = base.strip("._-") or "frame"
    max_base_len = max(1, 120 - len(extension))
    return base[:max_base_len] + extension


def default_output(video_path: Path) -> Path:
    return workspace_root() / "data" / "skills" / SKILL_ID / safe_filename(video_path.stem + "_frame", ".jpg")


def result(**kwargs) -> None:
    print(json.dumps({"skill": SKILL_ID, **kwargs}, ensure_ascii=False, indent=2))


def ffmpeg_available() -> tuple[bool, str | None]:
    missing = []
    if shutil.which("ffmpeg") is None:
        missing.append("ffmpeg")
    if shutil.which("ffprobe") is None:
        missing.append("ffprobe")
    return (not missing, ",".join(missing) if missing else None)


def parse_frame_index(index_value) -> int:
    try:
        index = int(str(index_value).strip())
    except Exception as exc:
        raise ValueError(f"Invalid frame index: {index_value}") from exc
    if index < 0:
        raise ValueError(f"Invalid frame index: {index_value}")
    return index


def video_info(video_path: Path) -> None:
    cmd = [
        "ffprobe", "-hide_banner", "-loglevel", "error",
        "-print_format", "json", "-show_format", "-show_streams", str(video_path),
    ]
    completed = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
    if completed.returncode != 0:
        result(status="failed", error=completed.stderr.strip() or completed.stdout.strip())
        return
    try:
        info = json.loads(completed.stdout or "{}")
    except Exception:
        info = {"raw": completed.stdout[:2000]}
    streams = info.get("streams") or []
    video_stream = next((stream for stream in streams if stream.get("codec_type") == "video"), {})
    fmt = info.get("format") or {}
    result(
        status="success",
        mode="info",
        inputFile=str(video_path),
        duration=fmt.get("duration"),
        size=fmt.get("size"),
        codec=video_stream.get("codec_name"),
        width=video_stream.get("width"),
        height=video_stream.get("height"),
    )


def extract_frame(video_path: Path, output_path: Path, timestamp: str | None, index_value) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    if index_value is not None and str(index_value).strip() != "":
        int_index = parse_frame_index(index_value)
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(video_path), "-vf", f"select=eq(n\\,{int_index})", "-vframes", "1", str(output_path),
        ]
    elif timestamp:
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-ss", str(timestamp), "-i", str(video_path), "-frames:v", "1", str(output_path),
        ]
    else:
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(video_path), "-vf", "select=eq(n\\,0)", "-vframes", "1", str(output_path),
        ]
    completed = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    if completed.returncode != 0:
        result(status="failed", mode="frame", error=completed.stderr.strip() or completed.stdout.strip())
        return
    result(status="success", mode="frame", inputFile=str(video_path), outputFile=str(output_path), bytes=output_path.stat().st_size if output_path.exists() else 0)


def main() -> None:
    payload = parse_payload()
    try:
        raw_video = payload.get("videoPath") or payload.get("inputFile") or payload.get("file") or payload.get("path")
        if not raw_video:
            if is_truthy(payload.get("dryRun") or payload.get("dry_run")):
                result(status="dryRun", dryRun=True, message="videoPath is required for real execution")
                return
            raise ValueError("videoPath is required")
        dry_run = is_truthy(payload.get("dryRun") or payload.get("dry_run"))
        mode = str(payload.get("mode") or payload.get("action") or "frame").strip().lower()
        video = resolve_workspace_path(str(raw_video), must_exist=not dry_run)
        output = resolve_workspace_path(str(payload["outputFile"])) if payload.get("outputFile") else default_output(video)
        timestamp = payload.get("time") or payload.get("timestamp")
        index_value = payload.get("index") or payload.get("frameIndex")
        if dry_run:
            result(status="dryRun", dryRun=True, mode=mode, inputFile=str(video), outputFile=str(output), time=timestamp, index=index_value)
            return
        available, missing = ffmpeg_available()
        if not available:
            result(status="missingDependency", missingDependency=missing, installHint="Install ffmpeg and ensure ffmpeg/ffprobe are on PATH", dryRun=False)
            return
        if mode == "info":
            video_info(video)
        else:
            extract_frame(video, output, str(timestamp) if timestamp else None, index_value)
    except Exception as exc:
        result(status="failed", error=str(exc))


if __name__ == "__main__":
    main()
