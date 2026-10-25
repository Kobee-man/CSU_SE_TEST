"""Use the installed REA CLI; keep complete Evidence and never execute the APK."""
import json
import hashlib
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent / "证据"
REA = Path(r"C:\Users\32536\AppData\Local\npm-cache\_npx\9b9809298fe40d2c\node_modules\rea-agents\scripts\rea.mjs")
TARGETS = {
    "delivered": ROOT / "交付物/安装包/便签-中文版-debug.apk",
    "original": ROOT / "源码/MiCode-Notes/app/build/outputs/apk/debug/app-debug.apk",
    "smarttodo": ROOT / "补充文件/SmartTodo-NoteCraft/Smarttodo-NoteCraft/app/build/outputs/apk/debug/app-debug.apk",
}

def run(key, command, target, *args):
    output = OUT / (key + ".json")
    if output.exists():
        try:
            cached = json.loads(output.read_text(encoding="utf-8-sig"))
        except (ValueError, UnicodeError):
            cached = json.loads(output.read_text(encoding="utf-16"))
        expected_sha = hashlib.sha256(TARGETS[target].read_bytes()).hexdigest()
        actual_sha = cached.get("data", {}).get("subject", {}).get("digest", {}).get("sha256")
        if cached.get("ok") and actual_sha == expected_sha:
            print(key, "reused", flush=True)
            return cached["data"]
    result = subprocess.run(["node", str(REA), command, str(TARGETS[target]), *args,
                             "--format", "json", "--full-output"],
                            capture_output=True, timeout=180)
    output.write_bytes(result.stdout)
    (OUT / (key + ".stderr.txt")).write_bytes(result.stderr)
    data = json.loads(result.stdout)
    print(key, "OK" if data.get("ok") else "FAILED", flush=True)
    if not data.get("ok"):
        raise RuntimeError(key + ": " + str(data.get("error")))
    return data["data"]

def main():
    for target in TARGETS:
        run(target + "-package", "inspect-android-package", target)
        run(target + "-classes", "search-android-classes", target, "net.micode.notes" if target != "smarttodo" else "com.example.emptyactivity")
    classes = ["model.WorkingNote", "model.Note", "data.NotesProvider", "data.NotesDatabaseHelper",
               "ui.NoteEditActivity", "ui.NotesListActivity", "ui.AlarmReceiver", "ui.AlarmInitReceiver",
               "widget.NoteWidgetProvider", "gtask.remote.GTaskSyncService", "tool.BackupUtils",
               "NotesApplication", "tool.AppLanguage", "ui.LocalizedActivity"]
    for name in classes:
        run("delivered-class-" + name, "inspect-android-class", "delivered", "net.micode.notes." + name)
    methods = {
        "model.WorkingNote": ["saveNote"], "model.Note": ["syncNote"],
        "data.NotesProvider": ["query"], "data.NotesDatabaseHelper": ["onCreate"],
        "ui.NoteEditActivity": ["saveNote", "initActivityState", "onNewIntent", "onPause", "onSaveInstanceState", "onClockAlertChanged"],
        "ui.NotesListActivity": ["createNewNote"],
        "ui.AlarmReceiver": ["onReceive"], "ui.AlarmInitReceiver": ["onReceive"],
        "widget.NoteWidgetProvider": ["onReceive"],
        "gtask.remote.GTaskSyncService": ["onStartCommand"],
        "tool.BackupUtils": ["exportToText"],
        "tool.AppLanguage": ["setLanguage", "wrap"], "ui.LocalizedActivity": ["onResume"],
    }
    for name, selected in methods.items():
        inventory = run("delivered-class-" + name, "inspect-android-class", "delivered", "net.micode.notes." + name)
        for method in selected:
            matches = [m for m in inventory["normalized_result"]["methods"] if m["name"] == method]
            if len(matches) != 1:
                print(name, method, "needs selection", matches, flush=True)
                continue
            run("delivered-method-" + name + "." + method, "inspect-android-method", "delivered",
                "net.micode.notes." + name, method, "--overload-index", str(matches[0]["overload_index"]))
    for name, method in [("model.WorkingNote", "saveNote"), ("model.Note", "syncNote"), ("ui.AlarmReceiver", "onReceive")]:
        run("delivered-refs-" + name + "." + method, "trace-android-references", "delivered",
            "net.micode.notes." + name, "--method-name", method, "--overload-index", "0")

if __name__ == "__main__":
    main()
