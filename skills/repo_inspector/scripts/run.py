#!/usr/bin/env python3
import json
import os
import re
import sys
from pathlib import Path

TEXT_EXTS = {".java", ".xml", ".yml", ".yaml", ".md", ".txt", ".json", ".sql", ".properties", ".py", ".sh"}
IGNORE_PARTS = {".git", "target", "node_modules", ".idea", "logs"}
STOPWORDS = {
    "帮我", "分析", "项目", "代码", "实现", "在哪", "哪个", "文件", "相关", "这个", "那个",
    "spring", "skill", "请", "一下", "当前", "java", "openclaw", "相关文件", "核心"
}

DOMAIN_HINTS = [
    (("skill", "skills", "技能"), [
        "SkillRegistryService",
        "SkillPackageCatalogService",
        "ScriptSkillToolPack",
        "SkillLibraryToolPack",
        "SKILL.md",
    ]),
    (("定时任务", "任务调度", "xxl", "cron"), [
        "TaskExecutionService",
        "ScheduledTask",
        "ScheduledTaskDispatchJob",
    ]),
    (("本地文件", "授权文件", "电脑文件"), [
        "LocalFilesystemToolPack",
        "LocalFilesystemService",
        "local-files",
    ]),
    (("前端", "页面", "vue", "登录"), [
        "frontend",
        "AgentView",
        "ConsoleHomeView",
    ]),
]


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        return json.loads(raw)
    except Exception:
        return {"goal": raw}


def extract_keywords(goal: str):
    tokens = re.findall(r"[A-Za-z_][A-Za-z0-9_\-]{1,}|[\u4e00-\u9fff]{2,}", goal or "")
    result = []
    for token in tokens:
        value = token.strip()
        if not value or value in STOPWORDS:
            continue
        if value not in result:
            result.append(value)
        if len(result) >= 6:
            break
    normalized_goal = (goal or "").lower()
    for triggers, hints in DOMAIN_HINTS:
        if any(trigger.lower() in normalized_goal for trigger in triggers):
            for hint in hints:
                if hint not in result:
                    result.append(hint)
            break
    return result[:10] or [goal.strip() or "ChatServiceImpl"]


def should_skip(path: Path) -> bool:
    return any(part in IGNORE_PARTS for part in path.parts)


def iter_candidate_files(root: Path):
    count = 0
    for path in root.rglob("*"):
        if count >= 1500:
            break
        if not path.is_file() or should_skip(path):
            continue
        if path.suffix.lower() not in TEXT_EXTS and path.name not in {"Dockerfile", "Jenkinsfile", "README.md"}:
            continue
        count += 1
        yield path


def score_file(path: Path, keywords):
    rel = path.as_posix().lower()
    score = 0
    snippet = ""
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return 0, ""
    if "/src/main/" in rel or rel.startswith("src/main/"):
        score += 5
    if "/src/test/" in rel or rel.startswith("src/test/"):
        score -= 4
    if path.name.lower() in {"changelog.md", "readme.md"}:
        score -= 12
    text_lower = text.lower()
    for keyword in keywords:
        lower = keyword.lower()
        if lower in rel:
            score += 6
        hits = text_lower.count(lower)
        if hits:
            score += min(hits, 6) * 2
            if not snippet:
                idx = text_lower.find(lower)
                start = max(0, idx - 120)
                end = min(len(text), idx + 220)
                snippet = text[start:end].replace("\n", " ").strip()
    return score, snippet


def main():
    payload = parse_payload()
    goal = payload.get("goal") or payload.get("message") or "分析当前项目"
    root = Path(os.environ.get("OPENCLAW_WORKSPACE_ROOT", os.getcwd())).resolve()
    keywords = extract_keywords(goal)

    ranked = []
    for path in iter_candidate_files(root):
        score, snippet = score_file(path, keywords)
        if score > 0:
            ranked.append((score, path, snippet))

    ranked.sort(key=lambda item: (-item[0], str(item[1])))
    top = ranked[:5]

    if not top:
        print(f"项目分析 skill 未找到明显相关文件。goal={goal}，keywords={keywords}")
        return

    lines = [
        f"项目分析结果 goal={goal}",
        f"工作区={root}",
        f"关键词={', '.join(keywords)}",
        "候选文件:"
    ]
    for idx, (score, path, snippet) in enumerate(top, start=1):
        rel = path.relative_to(root).as_posix()
        lines.append(f"{idx}. {rel} (score={score})")
        if snippet:
            lines.append(f"   snippet: {snippet[:260]}")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
