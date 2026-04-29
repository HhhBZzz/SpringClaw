#!/usr/bin/env python3
import json
import os
import sys


def parse_payload():
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        return json.loads(raw)
    except Exception:
        return {"goal": raw}


def main():
    payload = parse_payload()
    goal = payload.get("goal", "")
    print(f"skill={os.environ.get('OPENCLAW_SKILL_NAME', 'example_skill')}")
    print(f"goal={goal}")
    print("这里写你的技能逻辑。")


if __name__ == "__main__":
    main()
