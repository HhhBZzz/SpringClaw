import json
import os
import re
import sys
import time
from datetime import datetime
from pathlib import Path
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

from .common import deduplicate_records, normalize_record, normalize_text, parse_source, resolve_local_path, write_csv, write_jsonl


JSON_PATH_PATTERN = re.compile(r"([A-Za-z0-9_./\\-]+\.json)", re.IGNORECASE)
HTML_PATH_PATTERN = re.compile(r"([A-Za-z0-9_./\\-]+\.(?:html|htm))", re.IGNORECASE)
DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
}


def parse_payload():
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {"goal": str(payload)}
    except json.JSONDecodeError:
        return {"goal": raw}


def ensure_workspace_path(path, workspace_root, allowed_extensions, must_exist=False):
    root = Path(workspace_root).resolve()
    resolved = Path(path).resolve()
    if resolved != root and root not in resolved.parents:
        raise ValueError(f"输入路径必须位于 workspace 内: {resolved}")
    if ".." in Path(path).parts:
        raise ValueError(f"输入路径不能包含路径穿越片段: {path}")
    if allowed_extensions and resolved.suffix.lower() not in allowed_extensions:
        raise ValueError(f"不支持的输入文件类型: {resolved.suffix}")
    if must_exist and not resolved.exists():
        raise ValueError(f"输入文件不存在: {resolved}")
    return resolved


def extract_config_path(payload, workspace_root):
    for key in ("configPath", "config", "sourcePath"):
        value = normalize_text(payload.get(key))
        if value and value.lower().endswith(".json"):
            return ensure_workspace_path(resolve_local_path(value, workspace_root), workspace_root, {".json"})
    goal = normalize_text(payload.get("goal") or payload.get("message"))
    for token in JSON_PATH_PATTERN.findall(goal):
        candidate = ensure_workspace_path(resolve_local_path(token, workspace_root), workspace_root, {".json"})
        if candidate.exists():
            return candidate
    return None


def extract_inline_html_paths(payload, workspace_root):
    result = []
    for key in ("inputPath", "htmlPath", "file"):
        value = normalize_text(payload.get(key))
        if value and value.lower().endswith((".html", ".htm")):
            result.append(ensure_workspace_path(resolve_local_path(value, workspace_root), workspace_root, {".html", ".htm"}))
    goal = normalize_text(payload.get("goal") or payload.get("message"))
    for token in HTML_PATH_PATTERN.findall(goal):
        candidate = ensure_workspace_path(resolve_local_path(token, workspace_root), workspace_root, {".html", ".htm"})
        if candidate.exists():
            result.append(candidate)
    deduped = []
    seen = set()
    for item in result:
        if item in seen:
            continue
        seen.add(item)
        deduped.append(item)
    return deduped


def load_json_config(path):
    try:
        with path.open("r", encoding="utf-8") as handle:
            payload = json.load(handle)
            return payload if isinstance(payload, dict) else {}
    except (json.JSONDecodeError, OSError) as exc:
        print(f"Warning: failed to load config from {path}: {exc}", file=sys.stderr)
        return {}


def merge_headers(config, workspace_root):
    headers = dict(DEFAULT_HEADERS)
    if isinstance(config.get("headers"), dict):
        for key, value in config["headers"].items():
            if normalize_text(key):
                headers[str(key)] = str(value)
    headers_path = normalize_text(config.get("headersPath"))
    if headers_path:
        path = ensure_workspace_path(resolve_local_path(headers_path, workspace_root), workspace_root, {".json"})
        if path.exists():
            try:
                with path.open("r", encoding="utf-8") as handle:
                    payload = json.load(handle)
                    if isinstance(payload, dict):
                        for key, value in payload.items():
                            if normalize_text(key):
                                headers[str(key)] = str(value)
            except (json.JSONDecodeError, OSError) as exc:
                print(f"Warning: failed to load headers from {path}: {exc}", file=sys.stderr)
    cookie_env = normalize_text(config.get("cookieEnv") or config.get("cookiesEnv") or "BOSS_AUTH_COOKIE")
    cookies = normalize_text(config.get("cookie") or config.get("cookies") or os.environ.get(cookie_env))
    if cookies:
        headers["Cookie"] = cookies
    referer = normalize_text(config.get("referer"))
    if referer:
        headers["Referer"] = referer
    return headers


def normalize_config(payload, workspace_root):
    config_path = extract_config_path(payload, workspace_root)
    config = {}
    if config_path and config_path.exists():
        config = load_json_config(config_path)
    if not isinstance(config, dict):
        config = {}

    inline_html = extract_inline_html_paths(payload, workspace_root)
    if inline_html:
        config.setdefault("localHtmlFiles", [str(path) for path in inline_html])

    return config, config_path


def create_session(headers):
    session = requests.Session()
    session.headers.update(headers)
    return session


def save_raw_html(output_dir, prefix, page_no, url, body):
    safe_prefix = re.sub(r"[^a-zA-Z0-9_-]+", "_", prefix).strip("_") or "boss"
    safe_name = f"{safe_prefix}_page_{page_no:03d}.html"
    path = output_dir / safe_name
    try:
        path.write_text(body, encoding="utf-8", errors="ignore")
        return path
    except OSError as exc:
        print(f"Warning: failed to save raw html {path}: {exc}", file=sys.stderr)
        return None


def first_text(node, selectors):
    for selector in selectors:
        target = node.select_one(selector)
        if target:
            text = normalize_text(target.get_text(" ", strip=True))
            if text:
                return text
    return ""


def company_meta(node):
    meta = node.select_one(".company-text p") or node.select_one(".company-info p")
    if not meta:
        return "", "", ""
    link_text = [normalize_text(item.get_text(" ", strip=True)) for item in meta.select("a")]
    plain_text = [normalize_text(text) for text in meta.stripped_strings]
    plain_text = [text for text in plain_text if text and text not in link_text]
    com_type = link_text[0] if link_text else ""
    finance_stage = plain_text[0] if len(plain_text) > 0 else ""
    com_size = plain_text[1] if len(plain_text) > 1 else ""
    return com_type, finance_stage, com_size


def job_limit(node):
    limit = node.select_one(".info-primary p") or node.select_one(".job-limit")
    if not limit:
        return "", ""
    parts = [normalize_text(text) for text in limit.stripped_strings if normalize_text(text)]
    work_year = parts[0] if len(parts) > 0 else ""
    education = parts[1] if len(parts) > 1 else ""
    return work_year, education


def job_benefits(node):
    candidates = [
        ".info-append .info-desc",
        ".job-card-footer .info-desc",
        ".info-append .tags",
        ".job-card-footer .tag-list",
    ]
    for selector in candidates:
        target = node.select_one(selector)
        if target:
            text = normalize_text(target.get_text(",", strip=True))
            if text:
                return text.replace("，", ",")
    tags = [normalize_text(tag.get_text(" ", strip=True)) for tag in node.select(".tag-list li, .job-card-footer li, .tags span")]
    tags = [tag for tag in tags if tag]
    return ",".join(dict.fromkeys(tags))


def parse_boss_list_html(body, source_label):
    soup = BeautifulSoup(body, "html.parser")
    cards = soup.select("div.job-list ul li")
    records = []
    for li in cards:
        job_name = first_text(li, [".job-title a", ".job-title span a", ".info-primary .job-name"])
        job_area = first_text(li, [".job-title .job-area", ".job-area", ".job-title span:nth-of-type(2) span"])
        job_salary = first_text(li, ["span.red", ".salary"])
        com_name = first_text(li, [".company-text h3 a", ".company-info h3 a", ".company-name"])
        com_type, finance_stage, com_size = company_meta(li)
        work_year, education = job_limit(li)
        benefits = job_benefits(li)
        if not job_name or not job_salary:
            continue
        records.append({
            "job_name": job_name,
            "job_area": job_area,
            "job_salary": job_salary,
            "com_name": com_name,
            "com_type": com_type,
            "com_size": com_size,
            "finance_stage": finance_stage,
            "work_year": work_year,
            "education": education,
            "job_benefits": benefits,
            "source_file": source_label,
        })
    return records, extract_next_url(soup, source_label)


def extract_next_url(soup, current_url):
    page = soup.select_one("div.page")
    if not page:
        return ""
    anchors = page.select("a")
    if not anchors:
        return ""
    href = anchors[-1].get("href", "").strip()
    if not href or href == "javascript:;":
        return ""
    return urljoin(current_url, href)


def crawl_start_url(session, start_url, output_dir, max_pages, delay_seconds, timeout_seconds):
    current = start_url
    page_no = 1
    records = []
    raw_paths = []
    while current and page_no <= max_pages:
        response = None
        for attempt in range(1, 4):
            try:
                response = session.get(current, timeout=timeout_seconds, allow_redirects=True)
                response.raise_for_status()
                break
            except requests.RequestException as exc:
                if attempt >= 3:
                    print(f"Warning: failed to fetch {current}: {exc}", file=sys.stderr)
                    return records, raw_paths
                time.sleep(min(delay_seconds + attempt, 8))
        if response is None:
            break
        response.encoding = response.encoding or "utf-8"
        raw_path = save_raw_html(output_dir, "boss_authorized", page_no, current, response.text)
        if raw_path is not None:
            raw_paths.append(str(raw_path))
        page_records, next_url = parse_boss_list_html(response.text, current)
        records.extend(page_records)
        if not next_url or next_url == current:
            break
        current = next_url
        page_no += 1
        if delay_seconds > 0:
            time.sleep(delay_seconds)
    return records, raw_paths


def parse_local_html(path):
    try:
        body = path.read_text(encoding="utf-8", errors="ignore")
    except OSError as exc:
        print(f"Warning: failed to read local html {path}: {exc}", file=sys.stderr)
        return []
    records, _ = parse_boss_list_html(body, path.name)
    if records:
        return records
    return parse_source(path)


def render_summary(skill_name, mode, config_ref, raw_records, clean_records, output_dir, raw_paths):
    lines = [
        f"skill={skill_name}",
        f"mode={mode}",
        f"config={config_ref}",
        f"rawRecords={len(raw_records)}",
        f"cleanRecords={len(clean_records)}",
        f"outputDir={output_dir}",
        f"savedPages={len(raw_paths)}",
    ]
    if raw_paths:
        lines.append("rawPagesPreview=" + ",".join(raw_paths[:3]))
    if clean_records:
        lines.append("")
        lines.append("sampleRecords=")
        for item in clean_records[:3]:
            lines.append(json.dumps(item, ensure_ascii=False))
    else:
        lines.append("")
        lines.append("没有解析出职位记录。请检查授权 headers/cookie、页面是否是职位列表页，或先手动保存 HTML 再解析。")
    return "\n".join(lines)


def run():
    payload = parse_payload()
    workspace_root = Path(os.environ.get("OPENCLAW_WORKSPACE_ROOT", os.getcwd())).resolve()
    skill_name = os.environ.get("OPENCLAW_SKILL_NAME", "boss_authorized_collector")

    config, config_path = normalize_config(payload, workspace_root)
    headers = merge_headers(config, workspace_root)
    start_urls = config.get("startUrls") or config.get("start_urls") or []
    local_html_files = config.get("localHtmlFiles") or config.get("local_html_files") or []
    delay_seconds = float(config.get("delaySeconds", 2) or 2)
    timeout_seconds = int(config.get("timeoutSeconds", 20) or 20)
    max_pages = int(config.get("maxPages", 3) or 3)

    if isinstance(start_urls, str):
        start_urls = [start_urls]
    if isinstance(local_html_files, str):
        local_html_files = [local_html_files]

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output_dir = workspace_root / "data" / "boss_jobs" / f"authorized_collect_{timestamp}"
    output_dir.mkdir(parents=True, exist_ok=True)

    raw_records = []
    raw_paths = []
    mode = "local-html"

    if local_html_files:
        for raw_path in local_html_files:
            path = ensure_workspace_path(resolve_local_path(raw_path, workspace_root), workspace_root, {".html", ".htm"}, must_exist=True)
            raw_records.extend(parse_local_html(path))
            raw_paths.append(str(path))
    elif start_urls:
        mode = "authorized-http"
        session = create_session(headers)
        for start_url in start_urls:
            records, saved_paths = crawl_start_url(
                session,
                str(start_url).strip(),
                output_dir,
                max_pages,
                delay_seconds,
                timeout_seconds,
            )
            raw_records.extend(records)
            raw_paths.extend(saved_paths)
    else:
        print("未找到采集配置。请提供 config JSON，或在 goal 里带上本地 HTML 文件路径。")
        return

    clean_records = [normalize_record(record) for record in raw_records]
    clean_records = [record for record in clean_records if record.get("job_name")]
    clean_records = deduplicate_records(clean_records)

    raw_output = output_dir / "boss_jobs_raw.jsonl"
    clean_output = output_dir / "boss_jobs_clean.csv"
    write_jsonl(raw_output, raw_records)
    if clean_records:
        write_csv(clean_output, clean_records)
    else:
        write_csv(clean_output, [{
            "job_name": "",
            "city": "",
            "district": "",
            "business_area": "",
            "salary_raw": "",
            "salary_min": "",
            "salary_max": "",
            "salary_pay_count": "",
            "salary_unit": "",
            "work_year_raw": "",
            "experience_level": "",
            "experience_min_years": "",
            "experience_max_years": "",
            "education": "",
            "company_name": "",
            "company_type": "",
            "company_size_raw": "",
            "company_size_level": "",
            "finance_stage": "",
            "job_benefits": "",
            "source_file": "",
        }])

    config_ref = str(config_path) if config_path else "inline"
    print(render_summary(skill_name, mode, config_ref, raw_records, clean_records, output_dir, raw_paths))
    print(f"rawJsonl={raw_output}")
    print(f"cleanCsv={clean_output}")


def main():
    try:
        run()
    except Exception as exc:
        skill_name = os.environ.get("OPENCLAW_SKILL_NAME", "boss_authorized_collector")
        print("\n".join([f"skill={skill_name}", "status=failed", f"error={exc}"]))


if __name__ == "__main__":
    main()
