/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.database.Cursor;
import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.tool.Notifications;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) return;
        try {
            long id = ContentUris.parseId(intent.getData());
            try (Cursor c = context.getContentResolver().query(
                    ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id),
                    new String[]{NoteColumns.SNIPPET, NoteColumns.ALERTED_DATE,
                            NoteColumns.PARENT_ID, NoteColumns.TYPE}, null, null, null)) {
                if (c == null || !c.moveToFirst() || c.getLong(1) <= 0
                        || c.getLong(1) > System.currentTimeMillis() + 1000
                        || c.getLong(2) == Notes.ID_TRASH_FOLER || c.getInt(3) != Notes.TYPE_NOTE
                        || !Notifications.allowed(context)) return;
                Intent open = new Intent(context, NoteEditActivity.class)
                        .setAction(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_UID, id)
                        .setData(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, id));
                PendingIntent pending = PendingIntent.getActivity(context, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification notification = Notifications.builder(context, "reminders", true)
                        .setSmallIcon(R.drawable.notification).setContentTitle(context.getString(R.string.app_name))
                        .setContentText(c.getString(0)).setContentIntent(pending).setAutoCancel(true)
                        .setCategory(android.os.Build.VERSION.SDK_INT >= 23
                                ? Notification.CATEGORY_REMINDER : Notification.CATEGORY_EVENT)
                        .setPriority(Notification.PRIORITY_HIGH)
                        .setVisibility(Notification.VISIBILITY_PRIVATE).build();
                ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                        .notify("note:" + id, 0, notification);
            }
        } catch (RuntimeException ignored) {
            // The note or notification permission can disappear while an alarm fires.
        }
    }
}
