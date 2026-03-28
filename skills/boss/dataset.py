import json
import os
import sys
from datetime import datetime
from pathlib import Path

from .common import (
    extract_input_target,
    iter_source_files,
    normalize_record,
    normalize_text,
    parse_source,
    write_csv,
    write_jsonl,
    deduplicate_records,
)


def parse_payload():
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        return json.loads(raw)
    except Exception:
        return {"goal": raw}


def render_summary(skill_name, source, raw_records, clean_records, output_dir):
    lines = [
        f"skill={skill_name}",
        f"source={source}",
        f"rawRecords={len(raw_records)}",
        f"cleanRecords={len(clean_records)}",
        f"outputDir={output_dir}",
    ]
    if clean_records:
        sample = clean_records[:3]
        lines.append("")
        lines.append("sampleRecords=")
        for item in sample:
            lines.append(json.dumps(item, ensure_ascii=False))
    else:
        lines.append("")
        lines.append("没有解析出职位记录。建议使用浏览器保存完整 HTML，或先导出 CSV/JSON 再交给本技能清洗。")
    return "\n".join(lines)


def main():
    payload = parse_payload()
    workspace_root = Path(os.environ.get("OPENCLAW_WORKSPACE_ROOT", os.getcwd())).resolve()
    skill_name = os.environ.get("OPENCLAW_SKILL_NAME", "boss_job_dataset")

    source = extract_input_target(payload, workspace_root)
    if source is None:
        print("未找到输入文件。请在问题里带上本地 HTML/CSV/JSON 文件路径，例如 data/boss/list.html")
        return
    if not source.exists():
        print(f"输入文件不存在: {source}")
        return

    files = iter_source_files(source)
    if not files:
        print(f"没有找到可处理的输入文件: {source}")
        return

    raw_records = []
    for path in files:
        raw_records.extend(parse_source(path))

    normalized_records = [normalize_record(record) for record in raw_records]
    normalized_records = [record for record in normalized_records if record.get("job_name")]
    normalized_records = deduplicate_records(normalized_records)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output_dir = workspace_root / "data" / "boss_jobs"
    output_dir.mkdir(parents=True, exist_ok=True)
    raw_output = output_dir / f"boss_jobs_raw_{timestamp}.jsonl"
    clean_output = output_dir / f"boss_jobs_clean_{timestamp}.csv"

    write_jsonl(raw_output, raw_records)
    write_csv(clean_output, normalized_records if normalized_records else [{
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

    print(render_summary(skill_name, str(source), raw_records, normalized_records, output_dir))
    print(f"rawJsonl={raw_output}")
    print(f"cleanCsv={clean_output}")


if __name__ == "__main__":
    main()
