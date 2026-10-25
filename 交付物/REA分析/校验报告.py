"""Check saved analysis integrity and redaction; do not execute target apps."""
import ast
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[1]
issues = []
records = []

def read_json(path):
    raw = path.read_bytes()
    return json.loads(raw.decode("utf-16" if raw.startswith(b"\xff\xfe") else "utf-8-sig"))

all_files = list(BASE.rglob("*"))
for path in all_files:
    if not path.is_file():
        continue
    if path.suffix == ".json":
        obj = read_json(path)
        if isinstance(obj, dict) and obj.get("ok"):
            record = obj["data"]
            records.append(record)
            actual = hashlib.sha256(Path(record["subject"]["local_path"]).read_bytes()).hexdigest()
            if actual != record["subject"]["digest"]["sha256"]:
                issues.append("Artifact digest mismatch: " + path.name)
    if path.suffix == ".py":
        ast.parse(path.read_text(encoding="utf-8"), filename=str(path))

md_files = list(BASE.glob("*.md")) + [ROOT / "变更记录/17-REA项目分析.md"]
known_ids = {r["evidence_id"] for r in records}
for path in md_files:
    text = path.read_text(encoding="utf-8")
    if "\ufffd" in text:
        issues.append("Replacement character: " + path.name)
    for evidence_id in re.findall(r"ev_[0-9a-f]+", text):
        if evidence_id not in known_ids:
            issues.append("Unknown Evidence ID: " + evidence_id)
    for link in re.findall(r"\]\(([^\n)]+)\)", text):
        link = link.strip("<>")
        if "://" in link or link.startswith("#"):
            continue
        link = re.sub(r":\d+$", "", link).split("#", 1)[0]
        if not (path.parent / link).exists():
            issues.append("Missing file link in " + path.name + ": " + link)

# Never emit the secret values; only report whether output files contain them.
sample = ROOT / "补充文件/SmartTodo-NoteCraft/Smarttodo-NoteCraft/app/src/main/java/baidu/com/Sample.java"
source = sample.read_text(encoding="utf-8-sig")
secrets = re.findall(r'(?:API_KEY|SECRET_KEY)\s*=\s*"([^"\n]+)"', source)
for path in all_files + [ROOT / "变更记录/17-REA项目分析.md"]:
    if path.is_file() and path.suffix in {".md", ".json", ".xml", ".txt", ".py"}:
        raw = path.read_bytes()
        if any(secret.encode("utf-8") in raw or secret.encode("utf-16-le") in raw for secret in secrets):
            issues.append("Credential literal in output: " + path.name)

operations = Counter(r["operation"] for r in records)
method_status = Counter(r["normalized_result"].get("body_status", "unknown") for r in records if r["operation"] == "inspect_android_method")
result = {
    "date": "2026-10-23", "scope": "Static analysis artifact verification; no APK execution",
    "success_records": len(records), "unique_evidence_ids":len(known_ids),
    "operations": dict(operations), "method_body_status":dict(method_status),
    "json_parsed": len(list(BASE.rglob("*.json"))), "markdown_checked":len(md_files),
    "credential_constants_checked":len(secrets), "issues":issues,
}
(BASE / "证据/校验结果.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(result,ensure_ascii=True,indent=2))
if issues:
    raise SystemExit(1)
