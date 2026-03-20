#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        return json.loads(raw)
    except Exception:
        return {"goal": raw}


def main():
    payload = parse_payload()
    goal = str(payload.get("goal", "")).strip()
    workspace_root = Path(os.environ.get("OPENCLAW_WORKSPACE_ROOT", os.getcwd())).resolve()
    skill_name = os.environ.get("OPENCLAW_SKILL_NAME", "example_skill")

    lines = [
        f"skill={skill_name}",
        f"goal={goal or '未提供 goal'}",
        f"workspace={workspace_root}",
        "",
        "这是一个脚本 skill 模板。",
        "你应该把下面这部分替换成真实逻辑：",
        "1. 解析 goal",
        "2. 执行本地分析或诊断",
        "3. 输出稳定的纯文本结果"
    ]
    print("\n".join(lines))


if __name__ == "__main__":
    main()
