package net.micode.notes.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.widget.EditText;
import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.NotesDatabaseHelper;
import net.micode.notes.model.Note;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.tool.BackupUtils;
import net.micode.notes.tool.DraftStore;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;

/** Framework-only device regression suite; all created rows are removed in finally blocks. */
public final class RepairInstrumentation extends Instrumentation {
    private final ArrayList<Long> created = new ArrayList<>();
    private final ArrayList<String> passed = new ArrayList<>();
    private Context context;
    private boolean deniedNotifications;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        deniedNotifications = arguments != null && "denied".equals(arguments.getString("notifications"));
        start();
    }
    private void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
    private long count() {
        try (Cursor c = context.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                new String[]{"count(*)"}, null, null, null)) { c.moveToFirst(); return c.getLong(0); }
    }
    private String content(long id) {
        try (Cursor c = context.getContentResolver().query(Notes.CONTENT_DATA_URI,
                new String[]{DataColumns.CONTENT}, DataColumns.NOTE_ID + "=?",
                new String[]{String.valueOf(id)}, null)) { return c.moveToFirst() ? c.getString(0) : null; }
    }
    private long version(long id) {
        try (Cursor c = context.getContentResolver().query(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id),
                new String[]{NoteColumns.VERSION}, null, null, null)) { c.moveToFirst(); return c.getLong(0); }
    }
    @Override public void onStart() {
        context = getTargetContext();
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_OK;
        try {
            if (deniedNotifications) {
                deniedReminder();
            } else {
            saveAndRollback(); migration(); specialSelections(); draftRoundTrip(); exportAndPermission();
            reminders(); nullServiceIntent(); editorIntents();
            }
            result.putString("stream", "PASS " + passed.size() + " regression groups: " + passed + "\n");
        } catch (Throwable error) {
            result.putString("stream", "FAIL after " + passed + ": " + error + "\n" + android.util.Log.getStackTraceString(error));
            resultCode = Activity.RESULT_CANCELED;
        } finally {
            for (long id : created) context.getContentResolver().delete(
                    ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id), null, null);
        }
        finish(resultCode, result);
    }
    private void saveAndRollback() throws Exception {
        WorkingNote working = WorkingNote.createEmptyNote(context, 0, 0, -1, 0);
        working.setWorkingText("修复测试：初始正文");
        check(working.saveNote(), "First text insertion must report success");
        long id = working.getNoteId(); created.add(id);
        check("修复测试：初始正文".equals(content(id)), "New note must reload");
        Note note = new Note();
        note.setTextDataId(Long.MAX_VALUE);
        note.setTextData(DataColumns.CONTENT, "uncommitted");
        note.setNoteValue(NoteColumns.BG_COLOR_ID, "2");
        long before = version(id);
        check(!note.syncNote(context, id), "Zero-row data update must fail");
        check(note.isLocalModified(), "Failed changes must remain retryable");
        check(version(id) == before && "修复测试：初始正文".equals(content(id)), "Note, version and text must roll back");
        try (Cursor c = context.getContentResolver().query(Notes.CONTENT_DATA_URI,
                new String[]{DataColumns.ID}, DataColumns.NOTE_ID + "=?", new String[]{String.valueOf(id)}, null)) {
            c.moveToFirst(); note.setTextDataId(c.getLong(0));
        }
        check(note.syncNote(context, id), "Original diff must support retry");
        check("uncommitted".equals(content(id)) && !note.isLocalModified(), "Retry must commit and clear changes");
        long total = count();
        ArrayList<ContentProviderOperation> batch = new ArrayList<>();
        batch.add(ContentProviderOperation.newInsert(Notes.CONTENT_NOTE_URI)
                .withValue(NoteColumns.SNIPPET, "rollback orphan").build());
        batch.add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, Long.MAX_VALUE))
                .withValue(NoteColumns.SNIPPET, "fail").withExpectedCount(1).build());
        boolean failed = false;
        try { context.getContentResolver().applyBatch(Notes.AUTHORITY, batch); }
        catch (Exception expected) { failed = true; }
        check(failed && count() == total, "New note must roll back if a later operation fails");
        Note missing = new Note(); missing.setTextData(DataColumns.CONTENT, "lost?");
        check(!missing.syncNote(context, Long.MAX_VALUE) && missing.isLocalModified(), "Missing note must fail and preserve diff");
        passed.add("atomic-save/zero-row/rollback/retry");
    }
    private void migration() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE note (_id INTEGER PRIMARY KEY,parent_id INTEGER,alert_date INTEGER,snippet TEXT,type INTEGER)");
            db.execSQL("CREATE TABLE data (_id INTEGER PRIMARY KEY,note_id INTEGER,mime_type TEXT,content TEXT)");
            db.execSQL("INSERT INTO note VALUES (10,0,0,'folder',1)");
            db.execSQL("INSERT INTO note VALUES (11,10,2000000000000,'legacy',0)");
            db.execSQL("INSERT INTO data VALUES (20,11,'vnd.android.cursor.item/text_note','旧版完整正文')");
            new NotesDatabaseHelper(context).onUpgrade(db, 1, 4);
            try (Cursor c = db.rawQuery("SELECT n.parent_id,n.alert_date,d.content,n.version FROM note n JOIN data d ON d.note_id=n._id WHERE n._id=11", null)) {
                check(c.moveToFirst() && c.getLong(0) == 10 && c.getLong(1) == 2000000000000L
                        && "旧版完整正文".equals(c.getString(2)) && c.getLong(3) == 0, "Migration must preserve hierarchy, alert and content");
            }
        }
        passed.add("v1-migration");
    }
    private void specialSelections() {
        WorkingNote working = WorkingNote.createEmptyNote(context, 0, 0, -1, 0);
        String special = "quote' dollar$ slash\\ question? 中文";
        working.setWorkingText(special); check(working.saveNote(), "Create selection fixture");
        long id = working.getNoteId(); created.add(id);
        long previous = version(id);
        ContentValues values = new ContentValues(); values.put(NoteColumns.BG_COLOR_ID, 1);
        check(context.getContentResolver().update(Notes.CONTENT_NOTE_URI, values,
                NoteColumns.SNIPPET + "=? AND " + NoteColumns.VERSION + "=?",
                new String[]{special, String.valueOf(previous)}) == 1, "Bind special text and numeric predicate");
        check(version(id) == previous + 1, "Increment exactly the updated row");
        check(context.getContentResolver().update(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id),
                values, NoteColumns.SNIPPET + " IS NOT NULL", null) == 1, "Null argument array without placeholders");
        check(context.getContentResolver().update(Notes.CONTENT_NOTE_URI, values,
                NoteColumns.SNIPPET + "=?", new String[]{null}) == 0, "Null bound value");
        passed.add("bound-selections");
    }
    private void draftRoundTrip() {
        String key = "repair-instrumentation";
        WorkingNote working = WorkingNote.createEmptyNote(context, 0, 0, -1, 0);
        working.setWorkingText("草稿正文"); working.setAlertDate(2000000000000L, true);
        try {
            check(DraftStore.save(context, key, working.snapshot()), "Draft must persist");
            WorkingNote restored = WorkingNote.restore(context, DraftStore.load(context, key));
            check("草稿正文".equals(restored.getContent()) && restored.getAlertDate() == 2000000000000L,
                    "Draft must restore model and differences");
            check(restored.saveNote(), "Restored draft must save"); created.add(restored.getNoteId());
            check("草稿正文".equals(content(restored.getNoteId())), "Saved draft must reload");
        } finally { DraftStore.remove(context, key); }
        passed.add("draft-persistence");
    }
    private void exportAndPermission() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BackupUtils.writeText(context, output);
        check(output.toString("UTF-8").contains("草稿正文"), "Export must contain full UTF-8 content");
        boolean failed = false;
        try { BackupUtils.writeText(context, new java.io.OutputStream() {
            public void write(int value) throws java.io.IOException { throw new java.io.IOException("Full"); }
        }); } catch (java.io.IOException expected) { failed = true; }
        check(failed, "Write failure must not report success");
        check(net.micode.notes.data.Contact.getContact(context, "00000000000") == null, "Denied contact permission must be safe");
        passed.add("export-write-failure/contact-denial");
    }
    private void editorIntents() {
        Intent open = new Intent(context, NoteEditActivity.class).setAction(Intent.ACTION_INSERT_OR_EDIT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NoteEditActivity activity = (NoteEditActivity) startActivitySync(open);
        waitForIdleSync();
        runOnMainSync(() -> {
            java.util.Calendar time = java.util.Calendar.getInstance();
            time.set(java.util.Calendar.HOUR_OF_DAY, 18); time.set(java.util.Calendar.MINUTE, 30);
            long date = time.getTimeInMillis();
            DateTimePickerDialog picker = new DateTimePickerDialog(activity, date);
            picker.show();
            int titleId = activity.getResources().getIdentifier("alertTitle", "id", "android");
            android.widget.TextView title = picker.findViewById(titleId);
            int flags = android.text.format.DateUtils.FORMAT_SHOW_YEAR | android.text.format.DateUtils.FORMAT_SHOW_DATE
                    | android.text.format.DateUtils.FORMAT_SHOW_TIME;
            picker.set24HourView(false);
            check(android.text.format.DateUtils.formatDateTime(activity, date, flags | android.text.format.DateUtils.FORMAT_12HOUR)
                    .contentEquals(title.getText()), "12-hour title must match picker");
            picker.set24HourView(true);
            check(android.text.format.DateUtils.formatDateTime(activity, date, flags | android.text.format.DateUtils.FORMAT_24HOUR)
                    .contentEquals(title.getText()), "24-hour title must match picker");
            picker.dismiss();
            ((EditText) activity.findViewById(R.id.note_edit_view)).setText("无效 Intent 前的编辑内容");
            Intent invalid = new Intent(Intent.ACTION_VIEW).putExtra(android.app.SearchManager.EXTRA_DATA_KEY, "not-a-number");
            activity.onNewIntent(invalid);
            check(!activity.isFinishing(), "Invalid singleTop intent must retain the current editor");
            check("无效 Intent 前的编辑内容".equals(((EditText) activity.findViewById(R.id.note_edit_view)).getText().toString()), "Invalid intent must not replace text");
            activity.onNewIntent(new Intent(Intent.ACTION_VIEW).putExtra(android.app.SearchManager.EXTRA_DATA_KEY, "999999999999999999999999"));
            activity.onNewIntent(new Intent(Intent.ACTION_VIEW).putExtra(android.app.SearchManager.EXTRA_DATA_KEY, -1L));
            activity.onNewIntent(null);
            activity.onBackPressed();
        });
        try (Cursor c = context.getContentResolver().query(Notes.CONTENT_NOTE_URI, new String[]{NoteColumns.ID},
                NoteColumns.SNIPPET + "=?", new String[]{"无效 Intent 前的编辑内容"}, null)) {
            while (c.moveToNext()) created.add(c.getLong(0));
        }
        passed.add("invalid-singleTop-intents/12-24h-title");
    }

    private void nullServiceIntent() {
        net.micode.notes.gtask.remote.GTaskSyncService service = new net.micode.notes.gtask.remote.GTaskSyncService();
        check(service.onStartCommand(null, 0, 1) == android.app.Service.START_NOT_STICKY,
                "Null service intent must safely stop without restart");
        check(service.onStartCommand(new Intent(), 0, 2) == android.app.Service.START_NOT_STICKY,
                "Unknown service intent must not start sync");
        passed.add("null-service-restart");
    }

    private void reminders() throws Exception {
        WorkingNote note = WorkingNote.createEmptyNote(context, 0, 0, -1, 0);
        note.setWorkingText("提醒删除测试");
        note.setAlertDate(System.currentTimeMillis() - 1000, true);
        check(note.saveNote(), "Create reminder fixture");
        long id = note.getNoteId(); created.add(id);
        Intent reminder = new Intent(context, AlarmReceiver.class)
                .setData(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id));
        AlarmReceiver receiver = new AlarmReceiver(); receiver.onReceive(context, reminder);
        android.app.NotificationManager manager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        check(hasReminder(manager, id), "Allowed notification must display on reminder channel");
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id))
                .withValue(NoteColumns.PARENT_ID, Notes.ID_TRASH_FOLER).withExpectedCount(1).build());
        operations.add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, Long.MAX_VALUE))
                .withValue(NoteColumns.SNIPPET, "failure").withExpectedCount(1).build());
        try { context.getContentResolver().applyBatch(Notes.AUTHORITY, operations); } catch (Exception expected) { }
        check(hasReminder(manager, id), "Rolled-back delete must not cancel a notification");
        ContentValues trash = new ContentValues(); trash.put(NoteColumns.PARENT_ID, Notes.ID_TRASH_FOLER);
        context.getContentResolver().update(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id), trash, null, null);
        check(!hasReminder(manager, id), "Moving to trash must cancel displayed reminders");
        receiver.onReceive(context, reminder);
        check(!hasReminder(manager, id), "Trash note must not create a notification");
        context.getContentResolver().delete(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id), null, null);
        receiver.onReceive(context, reminder);
        check(!hasReminder(manager, id), "Missing note must not create a notification");
        passed.add("reminder-display/trash/delete/rollback");
    }
    private boolean hasReminder(android.app.NotificationManager manager, long id) {
        for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
            if (("note:" + id).equals(notification.getTag())) return true;
        }
        return false;
    }

    private void deniedReminder() {
        check(!net.micode.notes.tool.Notifications.allowed(context), "Notification permission must be denied");
        WorkingNote note = WorkingNote.createEmptyNote(context, 0, 0, -1, 0);
        note.setWorkingText("通知拒绝测试"); note.setAlertDate(System.currentTimeMillis() - 1000, true);
        check(note.saveNote(), "Create denied notification fixture");
        long id = note.getNoteId(); created.add(id);
        new AlarmReceiver().onReceive(context, new Intent(context, AlarmReceiver.class)
                .setData(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id)));
        android.app.NotificationManager manager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        check(!hasReminder(manager, id), "Denied permission must safely skip notification");
        passed.add("notification-permission-denial");
    }
}
