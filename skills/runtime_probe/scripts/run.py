#!/usr/bin/env python3
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        return json.loads(raw)
    except Exception:
        return {"goal": raw}


def run_command(command):
    if not command or shutil.which(command[0]) is None:
        return "command not available: " + " ".join(command)
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=6)
        output = (completed.stdout or completed.stderr or "").strip()
        if not output:
            output = "(no output)"
        return output[:1200]
    except Exception as exc:
        return f"command failed: {' '.join(command)} => {exc}"


def extract_port(goal: str) -> str:
    match = re.search(r"\b(\d{2,5})\b", goal or "")
    if not match:
        return "18080"
    return match.group(1)


def main():
    payload = parse_payload()
    goal = payload.get("goal") or "诊断当前运行状态"
    port = extract_port(goal)
    workspace = Path(os.environ.get("OPENCLAW_WORKSPACE_ROOT", os.getcwd())).resolve()

    lines = [
        f"运行诊断结果 goal={goal}",
        f"工作区={workspace}",
        f"当前端口检查={port}",
        "",
        "[jps -lvm]",
        run_command(["jps", "-lvm"]),
        "",
        f"[lsof -nP -iTCP:{port} -sTCP:LISTEN]",
        run_command(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN"]),
        "",
        "[pwd]",
        run_command(["pwd"]),
        "",
        "[java -version]",
        run_command(["java", "-version"]),
    ]
    print("\n".join(lines))


if __name__ == "__main__":
    main()
