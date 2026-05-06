#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {"goal": str(payload)}
    except Exception:
        return {"goal": raw}


def human_bytes(value: float) -> str:
    units = ["B", "KB", "MB", "GB", "TB"]
    size = float(value)
    for unit in units:
        if size < 1024 or unit == units[-1]:
            return f"{size:.1f}{unit}"
        size /= 1024
    return f"{size:.1f}TB"


def run_command(command: list[str], timeout: int = 3) -> str:
    if not command or shutil.which(command[0]) is None:
        return "unavailable"
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        output = (completed.stdout or completed.stderr or "").strip()
        return output if output else "unavailable"
    except Exception:
        return "unavailable"


def psutil_status() -> dict | None:
    try:
        import psutil  # type: ignore
    except Exception:
        return None

    disk = psutil.disk_usage("/")
    memory = psutil.virtual_memory()
    return {
        "cpu": f"{psutil.cpu_percent(interval=0.2):.1f}%",
        "load": ", ".join(f"{value:.2f}" for value in os.getloadavg()) if hasattr(os, "getloadavg") else "unavailable",
        "memory": f"{human_bytes(memory.used)} used / {human_bytes(memory.total)} total ({memory.percent:.1f}%)",
        "disk": f"{human_bytes(disk.used)} used / {human_bytes(disk.total)} total ({disk.percent:.1f}%)",
        "processes": str(len(psutil.pids())),
    }


def fallback_memory() -> str:
    if Path("/proc/meminfo").exists():
        values: dict[str, int] = {}
        for line in Path("/proc/meminfo").read_text(errors="ignore").splitlines():
            parts = line.replace(":", "").split()
            if len(parts) >= 2 and parts[1].isdigit():
                values[parts[0]] = int(parts[1]) * 1024
        total = values.get("MemTotal")
        available = values.get("MemAvailable")
        if total and available is not None:
            used = total - available
            return f"{human_bytes(used)} used / {human_bytes(total)} total"

    output = run_command(["vm_stat"])
    if output == "unavailable":
        return "unavailable"
    page_size = 4096
    pages: dict[str, int] = {}
    for raw_line in output.splitlines():
        line = raw_line.replace(".", "").replace(":", "")
        parts = line.split()
        if len(parts) >= 2 and parts[-1].isdigit():
            pages[" ".join(parts[:-1])] = int(parts[-1])
    free = pages.get("Pages free", 0) * page_size
    active = pages.get("Pages active", 0) * page_size
    inactive = pages.get("Pages inactive", 0) * page_size
    wired = pages.get("Pages wired down", 0) * page_size
    compressed = pages.get("Pages occupied by compressor", 0) * page_size
    used = active + inactive + wired + compressed
    total = used + free
    if total <= 0:
        return "unavailable"
    return f"{human_bytes(used)} used / {human_bytes(total)} observed"


def fallback_status() -> dict:
    disk = shutil.disk_usage("/")
    process_output = run_command(["ps", "-ax"])
    process_count = "unavailable"
    if process_output != "unavailable":
        process_count = str(max(len(process_output.splitlines()) - 1, 0))

    return {
        "cpu": "unavailable",
        "load": ", ".join(f"{value:.2f}" for value in os.getloadavg()) if hasattr(os, "getloadavg") else "unavailable",
        "memory": fallback_memory(),
        "disk": f"{human_bytes(disk.used)} used / {human_bytes(disk.total)} total ({disk.used / disk.total * 100:.1f}%)",
        "processes": process_count,
    }


def main() -> None:
    payload = parse_payload()
    goal = payload.get("goal") or "查看系统状态"
    workspace = Path(os.environ.get("OPENCLAW_WORKSPACE_ROOT", os.getcwd())).resolve()
    status = psutil_status() or fallback_status()

    lines = [
        "skill=system_status",
        f"goal={goal}",
        f"workspace={workspace}",
        f"os={platform.platform()}",
        f"cpu={status['cpu']}",
        f"load={status['load']}",
        f"memory={status['memory']}",
        f"disk={status['disk']}",
        f"processes={status['processes']}",
    ]
    print("\n".join(lines))


if __name__ == "__main__":
    main()
