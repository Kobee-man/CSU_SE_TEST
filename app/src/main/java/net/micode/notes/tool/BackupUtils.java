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

package net.micode.notes.tool;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.DataColumns;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/** UTF-8 export to a user-selected document; no broad storage permission. */
public final class BackupUtils {
    private BackupUtils() {}
    public static void exportToText(Context context, Uri destination) throws IOException {
        try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("Document unavailable");
            writeText(context, output);
        }
    }
    public static void writeText(Context context, OutputStream output) throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        HashMap<Long, String> folders = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                new String[]{NoteColumns.ID, NoteColumns.SNIPPET}, NoteColumns.TYPE + "=?",
                new String[]{String.valueOf(Notes.TYPE_FOLDER)}, null)) {
            if (c == null) throw new IOException("Folder query failed");
            while (c.moveToNext()) folders.put(c.getLong(0), c.getString(1));
        }
        try (Cursor notes = context.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                new String[]{NoteColumns.ID, NoteColumns.PARENT_ID, NoteColumns.MODIFIED_DATE},
                NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>?",
                new String[]{String.valueOf(Notes.TYPE_NOTE), String.valueOf(Notes.ID_TRASH_FOLER)},
                NoteColumns.PARENT_ID + "," + NoteColumns.ID)) {
            if (notes == null) throw new IOException("Note query failed");
            while (notes.moveToNext()) {
                String folderName = folders.get(notes.getLong(1));
                writer.println("[" + (folderName == null ? context.getString(net.micode.notes.R.string.app_name) : folderName) + "]");
                writer.println(new java.util.Date(notes.getLong(2)));
                try (Cursor data = context.getContentResolver().query(Notes.CONTENT_DATA_URI,
                        new String[]{DataColumns.MIME_TYPE, DataColumns.CONTENT, DataColumns.DATA1, DataColumns.DATA3},
                        DataColumns.NOTE_ID + "=?", new String[]{notes.getString(0)}, DataColumns.ID)) {
                    if (data == null) throw new IOException("Content query failed");
                    while (data.moveToNext()) {
                        if (Notes.CallNote.CONTENT_ITEM_TYPE.equals(data.getString(0))) {
                            writer.println(data.getString(3));
                            writer.println(new java.util.Date(data.getLong(2)));
                        }
                        writer.println(data.getString(1));
                    }
                }
                writer.println();
            }
        }
        writer.flush();
        if (writer.checkError()) throw new IOException("Document write failed");
    }
}
