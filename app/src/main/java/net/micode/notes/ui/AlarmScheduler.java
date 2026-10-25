package net.micode.notes.ui;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;

/** Reminders are best effort without exact-alarm access; deleted notes always cancel. */
public final class AlarmScheduler {
    private AlarmScheduler() {}
    public static void refresh(Context context, long id) {
        long date = 0;
        try (Cursor c = context.getContentResolver().query(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id),
                new String[]{NoteColumns.ALERTED_DATE, NoteColumns.PARENT_ID, NoteColumns.TYPE},
                null, null, null)) {
            if (c != null && c.moveToFirst() && c.getLong(1) != Notes.ID_TRASH_FOLER
                    && c.getInt(2) == Notes.TYPE_NOTE) date = c.getLong(0);
        }
        Intent intent = new Intent(context, AlarmReceiver.class)
                .setData(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id));
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        manager.cancel(pending);
        if (date <= System.currentTimeMillis()) {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .cancel("note:" + id, 0);
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && manager.canScheduleExactAlarms()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, date, pending);
                return;
            } catch (SecurityException ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 23) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, date, pending);
        else manager.set(AlarmManager.RTC_WAKEUP, date, pending);
    }
}
