from 运行静态分析 import run

def inspect(target, name, methods):
    inventory = run(target + "-class-" + name, "inspect-android-class", target, name)
    for method, chosen in methods:
        matches = [m for m in inventory["normalized_result"]["methods"] if m["name"] == method]
        if not matches:
            print("ABSENT", name, method, flush=True)
            continue
        index = chosen if chosen is not None else matches[0]["overload_index"]
        assert chosen is not None or len(matches) == 1
        run(target + "-detail-" + name + "." + method + str(index), "inspect-android-method", target,
            name, method, "--overload-index", str(index))

for name, methods in [
    ("net.micode.notes.model.Note.NoteData", [("pushIntoContentResolver", None)]),
    ("net.micode.notes.tool.BackupUtils.TextExport", [("exportToText", None)]),
    ("net.micode.notes.widget.NoteWidgetProvider", [("update", 0), ("onDeleted", None)]),
    ("net.micode.notes.widget.NoteWidgetProvider_2x", [("onReceive", None)]),
    ("net.micode.notes.data.NotesDatabaseHelper", [("reCreateNoteTableTriggers", None), ("reCreateDataTableTriggers", None), ("upgradeToV2", None)]),
    ("net.micode.notes.gtask.remote.GTaskClient", [("login", None)]),
    ("net.micode.notes.ui.NoteEditActivity", [("onCreate", None)]),
]:
    inspect("delivered", name, methods)
for name in ["net.micode.notes.model.Note", "net.micode.notes.model.WorkingNote"]:
    inspect("original", name, [("syncNote" if name.endswith(".Note") else "saveNote", None)])
for name in ["com.example.emptyactivity.FirstActivity", "com.example.emptyactivity.AdviseActivity", "baidu.com.Sample"]:
    inspect("smarttodo", name, [])
# API credentials can be embedded in Sample method code, so only retain its member inventory.
run("smarttodo-baidu-classes", "search-android-classes", "smarttodo", "baidu.com")
run("smarttodo-main-classes", "search-android-classes", "smarttodo", "MainActivity")
