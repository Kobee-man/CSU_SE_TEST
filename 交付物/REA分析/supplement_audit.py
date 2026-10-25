"""Read-only source/package checks; emits no credential values."""
import ast
import hashlib
import json
import pathlib
import re
import struct
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).resolve().parent / "证据"
SMART = ROOT / "补充文件/SmartTodo-NoteCraft/Smarttodo-NoteCraft"
APK = SMART / "app/build/outputs/apk/debug/app-debug.apk"
SDK = pathlib.Path(r"C:\Users\32536\AppData\Local\Android\Sdk")


def dex_classes(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX magic")
    string_count, string_off = struct.unpack_from("<II", data, 56)
    type_count, type_off = struct.unpack_from("<II", data, 64)
    class_count, class_off = struct.unpack_from("<II", data, 96)
    strings = []
    for i in range(string_count):
        pos = struct.unpack_from("<I", data, string_off + 4 * i)[0]
        while data[pos] & 128:
            pos += 1
        pos += 1
        strings.append(data[pos:data.index(b"\0", pos)].decode("utf-8", "replace"))
    types = [strings[struct.unpack_from("<I", data, type_off + i * 4)[0]]
             for i in range(type_count)]
    return [types[struct.unpack_from("<I", data, class_off + i * 32)[0]]
            for i in range(class_count)]


def run(args, cwd=None):
    result = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=30)
    return {"exit_code": result.returncode,
            "stdout": result.stdout.decode("utf-8", "replace"),
            "stderr": result.stderr.decode("utf-8", "replace")}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    classes, dex_members, dex_bytes = [], [], []
    with zipfile.ZipFile(APK) as archive:
        for item in archive.infolist():
            if re.fullmatch(r"classes\d*\.dex", item.filename):
                raw = archive.read(item)
                dex_bytes.append(raw)
                found = dex_classes(raw)
                classes.extend(found)
                dex_members.append({"name": item.filename, "class_count": len(found)})
    badging = run([str(SDK / "build-tools/36.0.0/aapt2.exe"), "dump", "badging", str(APK)])
    launchers = re.findall(r"launchable-activity: name='([^']+)'", badging["stdout"])
    preview = ROOT / ".work/android-preview/server.py"
    ast.parse(preview.read_text(encoding="utf-8"))
    sample = SMART / "app/src/main/java/baidu/com/Sample.java"
    sample_text = sample.read_text(encoding="utf-8")
    credential_presence = {key: bool(re.search(r"String\s+" + key + r'\s*=\s*"[^"]+"', sample_text))
                           for key in ("API_KEY", "SECRET_KEY")}
    credential_packaged = {}
    for key in credential_presence:
        match = re.search(r"String\s+" + key + r'\s*=\s*"([^"]+)"', sample_text)
        credential_packaged[key] = bool(match and any(match[1].encode("utf-8") in d for d in dex_bytes))
    repo = ROOT / ".work/CSU_SE_TEST/repo"
    source_files = list((SMART / "app/src").rglob("*.java"))
    source_files += list((repo / "app/src/main/java").rglob("*.java"))
    source_files += [preview, ROOT / "start-chinese-notes.ps1", ROOT / "启动中文版便签.cmd",
                     SMART / "app/src/main/AndroidManifest.xml", SMART / "app/build.gradle",
                     SMART / "app/build.gradle.kts", SMART / "settings.gradle"]
    result = {
        "date": "2026-10-23", "evidence_type": "local-file/source/package-static",
        "limitations": ["Not REA evidence; no APK execution or cloud API requests.",
                        "DEX parser inventories class definitions only; no method disassembly.",
                        "Historical test notes are not re-execution of those tests."],
        "apk": {"path": str(APK), "sha256": hashlib.sha256(APK.read_bytes()).hexdigest(),
                "size": APK.stat().st_size, "dex_members": dex_members,
                "first_party_class_definitions": sorted(c for c in classes if c.startswith((
                    "Lcom/example/emptyactivity/", "Lnet/micode/", "Lbaidu/com/"))),
                "launchers": [{"class": x, "has_dex_class_definition":
                              "L" + x.replace(".", "/") + ";" in classes} for x in launchers],
                "aapt2_badging": badging},
        "credential_declarations_present": credential_presence,
        "source_credential_literals_present_in_apk_dex": credential_packaged,
        "preview_python_ast": "pass",
        "source_file_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in source_files},
        "frontend": {"path": str(repo),
                     "branch": run(["git", "branch", "--show-current"], repo),
                     "commit": run(["git", "rev-parse", "HEAD"], repo),
                     "status": run(["git", "status", "--short"], repo),
                     "worktrees": run(["git", "worktree", "list"], repo)},
        "startup_check_only": run(["powershell.exe", "-NoLogo", "-NoProfile",
                                    "-ExecutionPolicy", "Bypass", "-File",
                                    str(ROOT / "start-chinese-notes.ps1"), "-CheckOnly"]),
    }
    path = OUT / "supplement-source-package-audit.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(path), "sha256": result["apk"]["sha256"],
                      "dex_members": dex_members, "launchers": result["apk"]["launchers"],
                      "startup_check_exit": result["startup_check_only"]["exit_code"]},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
