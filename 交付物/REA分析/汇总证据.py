import hashlib
import json
import zipfile
from pathlib import Path

BASE = Path(__file__).resolve().parent
OUT = BASE / "证据"

def read(path):
    raw = path.read_bytes()
    return json.loads(raw.decode("utf-16" if raw.startswith(b"\xff\xfe") else "utf-8-sig"))

def main():
    index = []
    methods = []
    register = ["# REA Evidence 清单\n\n完整记录保留在证据目录；以下 ID 均直接读取 REA 输出。静态引用的 complete 仅表示 JADX 当前 using-node 搜索范围，不包括反射、动态加载和框架回调。\n\n| 原始记录 | Evidence ID | 操作 |\n|---|---|---|"]
    for path in sorted(OUT.glob("*.json")):
        obj = read(path)
        if not isinstance(obj, dict) or not obj.get("ok"):
            continue
        data = obj["data"]
        item = {"file":path.name, "evidence_id":data["evidence_id"],
                "operation":data["operation"], "target_sha256":data["subject"]["digest"]["sha256"],
                "parameters":data["parameters"], "limitations":data["limitations"]}
        index.append(item)
        register.append(f"| [{path.name}](证据/{path.name}) | `{data['evidence_id']}` | `{data['operation']}` |")
        result = data["normalized_result"]
        if data["operation"] == "inspect_android_package":
            with zipfile.ZipFile(data["subject"]["local_path"]) as apk:
                print(path.stem, {k:result[k] for k in ["package_name","min_sdk","target_sdk","class_count","resource_count"]},
                      "zip", len(apk.namelist()), "dex",len([x for x in apk.namelist() if x.endswith(".dex")]))
            (OUT / (path.stem + "-manifest.xml")).write_text(result["manifest"]["text"], encoding="utf-8")
        elif data["operation"] == "inspect_android_method":
            methods.append("\n### " + path.stem + "\n\nEvidence: `" + data["evidence_id"] + "`\n\n```json\n" +
                           json.dumps(result, ensure_ascii=False, indent=2) + "\n```\n")
    (OUT / "证据索引.json").write_text(json.dumps(index,ensure_ascii=False,indent=2),encoding="utf-8")
    (BASE / "方法反编译摘录.md").write_text("# REA 方法反编译摘录\n\n由完整 JSON 自动提取，仅代表静态反编译。\n" + "".join(methods),encoding="utf-8")
    (BASE / "证据清单.md").write_text("\n".join(register)+"\n",encoding="utf-8")
    print("Evidence records:",len(index),"methods:",len(methods))

if __name__ == "__main__":
    main()
