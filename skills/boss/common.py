import csv
import html
import json
import re
from html.parser import HTMLParser
from pathlib import Path


ALLOWED_INPUT_EXTENSIONS = {".html", ".htm", ".txt", ".csv", ".json", ".jsonl"}
SALARY_PATTERN = re.compile(
    r"(?:(?:\d+(?:\.\d+)?)(?:[-~](?:\d+(?:\.\d+)?))?(?:[Kk万千])(?:·\d+薪)?|薪资面议|面议|(?:\d+(?:-\d+)?)元/天)"
)
TAG_PATTERN = re.compile(r"<[^>]+>")
PATH_PATTERN = re.compile(r"([A-Za-z0-9_./\\-]+\.(?:html|htm|txt|csv|json|jsonl))", re.IGNORECASE)
URL_PATTERN = re.compile(r"https?://[^\s]+", re.IGNORECASE)

EDUCATION_VALUES = {"不限", "初中及以下", "中专/中技", "高中", "大专", "本科", "硕士", "博士"}
STOP_LINES = {
    "在线", "立即沟通", "投递简历", "收藏", "分享", "更多", "热招中", "招聘中",
    "加载中", "查看全部", "工商信息", "公司介绍", "职位描述", "公司地址"
}
BENEFIT_KEYWORDS = {
    "五险一金", "补充医疗保险", "年终奖", "带薪年假", "股票期权", "定期体检", "节日福利",
    "免费班车", "餐补", "房补", "交通补助", "零食下午茶", "员工旅游", "加班补助"
}
FINANCE_KEYWORDS = {"已上市", "不需要融资", "天使轮", "A轮", "B轮", "C轮", "D轮", "战略融资", "未融资"}
CITY_NAMES = {
    "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "苏州",
    "天津", "重庆", "长沙", "郑州", "青岛", "济南", "厦门", "福州", "宁波", "合肥"
}
INDUSTRY_KEYWORDS = {
    "互联网", "电子商务", "计算机软件", "企业服务", "移动互联网", "人工智能",
    "数据服务", "云计算", "信息安全", "游戏", "教育培训", "医疗健康", "人力资源服务",
    "生活服务", "咨询", "贸易", "制造业", "金融", "广告营销", "文化传媒"
}
COMPANY_NAME_HINTS = {"科技", "网络", "软件", "信息", "数据", "传媒", "人力资源", "集团", "有限公司", "有限责任公司", "股份"}


class TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
        self.skip_depth = 0

    def handle_starttag(self, tag, attrs):
        name = (tag or "").lower()
        if name in {"script", "style", "noscript"}:
            self.skip_depth += 1
        elif self.skip_depth == 0 and name in {"div", "p", "li", "br", "section", "article", "span", "a", "h1", "h2", "h3"}:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        name = (tag or "").lower()
        if name in {"script", "style", "noscript"} and self.skip_depth > 0:
            self.skip_depth -= 1
        elif self.skip_depth == 0 and name in {"div", "p", "li", "section", "article", "span", "a"}:
            self.parts.append("\n")

    def handle_data(self, data):
        if self.skip_depth == 0 and data:
            self.parts.append(data)

    def text(self):
        joined = "".join(self.parts)
        joined = html.unescape(joined).replace("\r", "")
        joined = re.sub(r"[\t\x0b\f ]+", " ", joined)
        joined = re.sub(r" *\n *", "\n", joined)
        joined = re.sub(r"\n{2,}", "\n", joined)
        return joined.strip()


def normalize_text(value):
    text = str(value or "").replace("\r", "").strip()
    text = re.sub(r"\s+", " ", text)
    return text


def resolve_local_path(raw, workspace_root):
    candidate = Path(raw).expanduser()
    if not candidate.is_absolute():
        candidate = workspace_root / candidate
    return candidate.resolve()


def extract_input_target(payload, workspace_root):
    for key in ("inputPath", "path", "file", "source", "sourcePath"):
        candidate = normalize_text(payload.get(key))
        if candidate:
            return resolve_local_path(candidate, workspace_root)

    goal = normalize_text(payload.get("goal") or payload.get("message"))
    for token in PATH_PATTERN.findall(goal):
        resolved = resolve_local_path(token, workspace_root)
        if resolved.exists():
            return resolved
    return None


def iter_source_files(source):
    if source.is_file():
        return [source]
    if not source.is_dir():
        return []
    files = []
    for path in sorted(source.rglob("*")):
        if path.is_file() and path.suffix.lower() in ALLOWED_INPUT_EXTENSIONS:
            files.append(path)
    return files


def parse_structured_csv(path):
    records = []
    with path.open("r", encoding="utf-8", errors="ignore", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            if not row:
                continue
            record = {key.strip(): normalize_text(value) for key, value in row.items() if key}
            if record:
                records.append(record)
    return records


def parse_structured_json(path):
    text = path.read_text(encoding="utf-8", errors="ignore")
    stripped = text.strip()
    if not stripped:
        return []
    if path.suffix.lower() == ".jsonl":
        records = []
        for line in stripped.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                payload = json.loads(line)
            except Exception:
                continue
            if isinstance(payload, dict):
                records.append({key: normalize_text(value) for key, value in payload.items()})
        return records
    try:
        payload = json.loads(stripped)
    except Exception:
        return []
    if isinstance(payload, dict):
        payload = payload.get("data") or payload.get("records") or []
    if not isinstance(payload, list):
        return []
    records = []
    for item in payload:
        if isinstance(item, dict):
            records.append({key: normalize_text(value) for key, value in item.items()})
    return records


def parse_text_lines(text):
    text = re.sub(r"(?is)<script.*?</script>", "\n", text)
    text = re.sub(r"(?is)<style.*?</style>", "\n", text)
    if TAG_PATTERN.search(text):
        parser = TextExtractor()
        parser.feed(text)
        text = parser.text()
    text = html.unescape(text)
    text = re.sub(r"\u3000", " ", text)
    lines = []
    for raw in text.splitlines():
        line = normalize_text(raw)
        if not line or line in STOP_LINES:
            continue
        if URL_PATTERN.search(line):
            continue
        lines.append(line)
    return lines


def looks_like_salary(line):
    return bool(SALARY_PATTERN.search(line))


def looks_like_area(line):
    if "·" in line:
        first = line.split("·", 1)[0]
        return first in CITY_NAMES or len(first) <= 6
    return any(line.startswith(city) for city in CITY_NAMES)


def looks_like_experience(line):
    return any(token in line for token in ("经验", "年", "应届", "在校", "不限"))


def looks_like_education(line):
    return line in EDUCATION_VALUES


def looks_like_company_size(line):
    return "人" in line and any(token in line for token in ("20-99", "100-499", "500-999", "1000-9999", "10000", "人以上", "人以下"))


def looks_like_finance(line):
    return any(keyword in line for keyword in FINANCE_KEYWORDS)


def looks_like_company_type(line):
    if any(token in line for token in COMPANY_NAME_HINTS):
        return False
    return line in INDUSTRY_KEYWORDS or any(token in line for token in INDUSTRY_KEYWORDS)


def looks_like_job_name(line):
    if len(line) < 2 or len(line) > 40:
        return False
    if looks_like_salary(line) or looks_like_area(line):
        return False
    if line in EDUCATION_VALUES or line in STOP_LINES:
        return False
    return True


def fill_record_fields(record, details):
    welfare_parts = []
    leftovers = []
    for line in details:
        if not line or line in STOP_LINES:
            continue
        if not record.get("job_area") and looks_like_area(line):
            record["job_area"] = line
            continue
        if not record.get("work_year") and looks_like_experience(line):
            record["work_year"] = line
            continue
        if not record.get("education") and looks_like_education(line):
            record["education"] = line
            continue
        if not record.get("com_size") and looks_like_company_size(line):
            record["com_size"] = line
            continue
        if not record.get("finance_stage") and looks_like_finance(line):
            record["finance_stage"] = line
            continue
        if not record.get("com_type") and looks_like_company_type(line):
            record["com_type"] = line
            continue
        if any(keyword in line for keyword in BENEFIT_KEYWORDS):
            welfare_parts.append(line)
            continue
        leftovers.append(line)

    if leftovers:
        record["com_name"] = leftovers[0]
    if len(leftovers) > 1 and not record.get("com_type"):
        record["com_type"] = leftovers[1]
    if len(leftovers) > 2:
        welfare_parts.extend(leftovers[2:5])

    if welfare_parts:
        cleaned = []
        for part in welfare_parts:
            tokens = [normalize_text(token) for token in re.split(r"[、,，/ ]+", part) if normalize_text(token)]
            cleaned.extend(tokens)
        record["job_benefits"] = ",".join(dict.fromkeys(cleaned))
    return record


def parse_records_from_lines(lines, source_name):
    records = []
    index = 0
    while index < len(lines):
        line = lines[index]
        if not looks_like_salary(line) or index == 0:
            index += 1
            continue
        job_name = lines[index - 1]
        if not looks_like_job_name(job_name):
            index += 1
            continue

        details = []
        cursor = index + 1
        while cursor < len(lines) and not looks_like_salary(lines[cursor]) and len(details) < 12:
            details.append(lines[cursor])
            cursor += 1

        record = {
            "job_name": job_name,
            "job_salary": line,
            "job_area": "",
            "work_year": "",
            "education": "",
            "com_name": "",
            "com_type": "",
            "com_size": "",
            "finance_stage": "",
            "job_benefits": "",
            "source_file": source_name,
        }
        record = fill_record_fields(record, details)
        if record["job_name"] and record["job_salary"]:
            records.append(record)
        index = cursor
    return records


def parse_unstructured_source(path):
    text = path.read_text(encoding="utf-8", errors="ignore")
    lines = parse_text_lines(text)
    return parse_records_from_lines(lines, path.name)


def parse_source(path):
    suffix = path.suffix.lower()
    if suffix == ".csv":
        return parse_structured_csv(path)
    if suffix in {".json", ".jsonl"}:
        return parse_structured_json(path)
    return parse_unstructured_source(path)


def normalize_area(area):
    value = normalize_text(area)
    parts = [part.strip() for part in value.split("·") if part.strip()]
    city = parts[0] if len(parts) > 0 else ""
    district = parts[1] if len(parts) > 1 else ""
    business = parts[2] if len(parts) > 2 else ""
    return city, district, business


def normalize_salary(salary_raw):
    salary = normalize_text(salary_raw)
    if not salary or salary in {"薪资面议", "面议"}:
        return "", "", "", "negotiable"

    pay_count_match = re.search(r"·(\d+)薪", salary)
    pay_count = pay_count_match.group(1) if pay_count_match else ""
    value = salary.replace("·" + pay_count + "薪", "") if pay_count else salary

    if "元/天" in value:
        numbers = re.findall(r"\d+(?:\.\d+)?", value)
        minimum = numbers[0] if numbers else ""
        maximum = numbers[1] if len(numbers) > 1 else minimum
        return minimum, maximum, pay_count, "CNY_PER_DAY"

    numbers = re.findall(r"\d+(?:\.\d+)?", value)
    if not numbers:
        return "", "", pay_count, ""

    if "万" in value:
        multiplier = 10.0
    else:
        multiplier = 1.0

    minimum = str(round(float(numbers[0]) * multiplier, 2)).rstrip("0").rstrip(".")
    maximum_source = numbers[1] if len(numbers) > 1 else numbers[0]
    maximum = str(round(float(maximum_source) * multiplier, 2)).rstrip("0").rstrip(".")
    return minimum, maximum, pay_count, "K"


def normalize_experience(work_year_raw):
    text = normalize_text(work_year_raw)
    if not text:
        return "", "", ""
    if any(token in text for token in ("不限", "应届", "在校")):
        return "0", "0", "0"
    if "10年以上" in text:
        return "4", "10", ""
    match = re.search(r"(\d+)\s*[-~]\s*(\d+)", text)
    if match:
        minimum = match.group(1)
        maximum = match.group(2)
        level = "1"
        if int(minimum) >= 3:
            level = "2"
        if int(minimum) >= 5:
            level = "3"
        return level, minimum, maximum
    match = re.search(r"(\d+)\+?年", text)
    if match:
        minimum = match.group(1)
        level = "1"
        if int(minimum) >= 3:
            level = "2"
        if int(minimum) >= 5:
            level = "3"
        return level, minimum, ""
    return "", "", ""


def normalize_company_size(size_raw):
    text = normalize_text(size_raw)
    if not text:
        return ""
    if "10000" in text or "万人" in text:
        return "3"
    if "1000-9999" in text or "1000人以上" in text:
        return "2"
    if "500-999" in text:
        return "1"
    return "0"


def normalize_record(record):
    city, district, business = normalize_area(record.get("job_area", ""))
    salary_min, salary_max, pay_count, salary_unit = normalize_salary(record.get("job_salary", ""))
    exp_level, exp_min, exp_max = normalize_experience(record.get("work_year", ""))
    company_size_level = normalize_company_size(record.get("com_size", ""))
    return {
        "job_name": normalize_text(record.get("job_name")),
        "city": city,
        "district": district,
        "business_area": business,
        "salary_raw": normalize_text(record.get("job_salary")),
        "salary_min": salary_min,
        "salary_max": salary_max,
        "salary_pay_count": pay_count,
        "salary_unit": salary_unit,
        "work_year_raw": normalize_text(record.get("work_year")),
        "experience_level": exp_level,
        "experience_min_years": exp_min,
        "experience_max_years": exp_max,
        "education": normalize_text(record.get("education")),
        "company_name": normalize_text(record.get("com_name")),
        "company_type": normalize_text(record.get("com_type")),
        "company_size_raw": normalize_text(record.get("com_size")),
        "company_size_level": company_size_level,
        "finance_stage": normalize_text(record.get("finance_stage")),
        "job_benefits": normalize_text(record.get("job_benefits")).replace("，", ","),
        "source_file": normalize_text(record.get("source_file")),
    }


def deduplicate_records(records):
    seen = set()
    unique = []
    for record in records:
        key = (
            record.get("job_name", ""),
            record.get("company_name", "") or record.get("com_name", ""),
            record.get("salary_raw", "") or record.get("job_salary", ""),
            record.get("city", "") or record.get("job_area", ""),
        )
        if key in seen:
            continue
        seen.add(key)
        unique.append(record)
    return unique


def write_jsonl(path, records):
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def write_csv(path, records):
    fieldnames = list(records[0].keys()) if records else []
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for record in records:
            writer.writerow(record)
