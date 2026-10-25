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

package net.micode.notes.data;


import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.OperationApplicationException;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.NotesDatabaseHelper.TABLE;
import net.micode.notes.ui.AlarmScheduler;
import java.util.ArrayList;
import java.util.LinkedHashSet;


public class NotesProvider extends ContentProvider {
    private static final UriMatcher mMatcher;

    private NotesDatabaseHelper mHelper;
    private final ThreadLocal<LinkedHashSet<Uri>> mPendingChanges = new ThreadLocal<>();
    private final ThreadLocal<LinkedHashSet<Long>> mPendingAlarms = new ThreadLocal<>();

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        LinkedHashSet<Uri> changes = new LinkedHashSet<>();
        LinkedHashSet<Long> alarms = new LinkedHashSet<>();
        mPendingChanges.set(changes);
        mPendingAlarms.set(alarms);
        db.beginTransaction();
        boolean committed = false;
        try {
            ContentProviderResult[] results = super.applyBatch(operations);
            db.setTransactionSuccessful();
            committed = true;
            return results;
        } finally {
            try {
                db.endTransaction();
            } finally {
                mPendingChanges.remove();
                mPendingAlarms.remove();
            }
            if (committed) {
                for (Uri changed : changes) notifyCommitted(changed);
                for (Long id : alarms) refreshAlarm(id);
            }
        }
    }

    private void notifyCommitted(Uri uri) {
        try {
            getContext().getContentResolver().notifyChange(uri, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "Observer notification failed after commit");
        }
    }

    private void changed(Uri uri) {
        LinkedHashSet<Uri> changes = mPendingChanges.get();
        if (changes != null) changes.add(uri);
        else notifyCommitted(uri);
    }

    private void refreshAlarm(long id) {
        try {
            AlarmScheduler.refresh(getContext(), id);
        } catch (RuntimeException e) {
            Log.w(TAG, "Reminder refresh failed after commit");
        }
    }

    private void alarmsChanged(LinkedHashSet<Long> ids) {
        LinkedHashSet<Long> pending = mPendingAlarms.get();
        if (pending != null) pending.addAll(ids);
        else for (Long id : ids) refreshAlarm(id);
    }

    private LinkedHashSet<Long> activeAlarmIds(SQLiteDatabase db) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        try (Cursor c = db.query(TABLE.NOTE, new String[]{NoteColumns.ID},
                NoteColumns.ALERTED_DATE + ">0", null, null, null, null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        return ids;
    }

    private static final String TAG = "NotesProvider";

    private static final int URI_NOTE            = 1;
    private static final int URI_NOTE_ITEM       = 2;
    private static final int URI_DATA            = 3;
    private static final int URI_DATA_ITEM       = 4;

    private static final int URI_SEARCH          = 5;
    private static final int URI_SEARCH_SUGGEST  = 6;

    static {
        mMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        mMatcher.addURI(Notes.AUTHORITY, "note", URI_NOTE);
        mMatcher.addURI(Notes.AUTHORITY, "note/#", URI_NOTE_ITEM);
        mMatcher.addURI(Notes.AUTHORITY, "data", URI_DATA);
        mMatcher.addURI(Notes.AUTHORITY, "data/#", URI_DATA_ITEM);
        mMatcher.addURI(Notes.AUTHORITY, "search", URI_SEARCH);
        mMatcher.addURI(Notes.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, URI_SEARCH_SUGGEST);
        mMatcher.addURI(Notes.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", URI_SEARCH_SUGGEST);
    }

    /**
     * x'0A' represents the '\n' character in sqlite. For title and content in the search result,
     * we will trim '\n' and white space in order to show more information.
     */
    private static final String NOTES_SEARCH_PROJECTION = NoteColumns.ID + ","
        + NoteColumns.ID + " AS " + SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA + ","
        + "TRIM(REPLACE(" + NoteColumns.SNIPPET + ", x'0A','')) AS " + SearchManager.SUGGEST_COLUMN_TEXT_1 + ","
        + "TRIM(REPLACE(" + NoteColumns.SNIPPET + ", x'0A','')) AS " + SearchManager.SUGGEST_COLUMN_TEXT_2 + ","
        + R.drawable.search_result + " AS " + SearchManager.SUGGEST_COLUMN_ICON_1 + ","
        + "'" + Intent.ACTION_VIEW + "' AS " + SearchManager.SUGGEST_COLUMN_INTENT_ACTION + ","
        + "'" + Notes.TextNote.CONTENT_TYPE + "' AS " + SearchManager.SUGGEST_COLUMN_INTENT_DATA;

    private static String NOTES_SNIPPET_SEARCH_QUERY = "SELECT " + NOTES_SEARCH_PROJECTION
        + " FROM " + TABLE.NOTE
        + " WHERE " + NoteColumns.SNIPPET + " LIKE ?"
        + " AND " + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER
        + " AND " + NoteColumns.TYPE + "=" + Notes.TYPE_NOTE;

    @Override
    public boolean onCreate() {
        mHelper = NotesDatabaseHelper.getInstance(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        Cursor c = null;
        SQLiteDatabase db = mHelper.getReadableDatabase();
        String id = null;
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                c = db.query(TABLE.NOTE, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                c = db.query(TABLE.NOTE, projection, NoteColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs, null, null, sortOrder);
                break;
            case URI_DATA:
                c = db.query(TABLE.DATA, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                c = db.query(TABLE.DATA, projection, DataColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs, null, null, sortOrder);
                break;
            case URI_SEARCH:
            case URI_SEARCH_SUGGEST:
                if (sortOrder != null || projection != null) {
                    throw new IllegalArgumentException(
                            "do not specify sortOrder, selection, selectionArgs, or projection" + "with this query");
                }

                String searchString = null;
                if (mMatcher.match(uri) == URI_SEARCH_SUGGEST) {
                    if (uri.getPathSegments().size() > 1) {
                        searchString = uri.getPathSegments().get(1);
                    }
                } else {
                    searchString = uri.getQueryParameter("pattern");
                }

                if (TextUtils.isEmpty(searchString)) {
                    return null;
                }

                try {
                    searchString = String.format("%%%s%%", searchString);
                    c = db.rawQuery(NOTES_SNIPPET_SEARCH_QUERY,
                            new String[] { searchString });
                } catch (IllegalStateException ex) {
                    Log.e(TAG, "got exception: " + ex.toString());
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (c != null) {
            c.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        long dataId = 0, noteId = 0, insertedId = 0;
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                insertedId = noteId = db.insertOrThrow(TABLE.NOTE, null, values);
                break;
            case URI_DATA:
                if (values.containsKey(DataColumns.NOTE_ID)) {
                    noteId = values.getAsLong(DataColumns.NOTE_ID);
                } else {
                    Log.d(TAG, "Wrong data format without note id:" + values.toString());
                }
                insertedId = dataId = db.insertOrThrow(TABLE.DATA, null, values);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        // Notify the note uri
        if (noteId > 0) {
            changed(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId));
            if (mMatcher.match(uri) == URI_NOTE) {
                LinkedHashSet<Long> alarms = new LinkedHashSet<>();
                alarms.add(noteId);
                alarmsChanged(alarms);
            }
        }

        // Notify the data uri
        if (dataId > 0) {
            changed(ContentUris.withAppendedId(Notes.CONTENT_DATA_URI, dataId));
        }

        return ContentUris.withAppendedId(uri, insertedId);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int count = 0;
        String id = null;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        boolean deleteData = false;
        LinkedHashSet<Long> alarms = activeAlarmIds(db);
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                selection = NoteColumns.ID + ">0" + parseSelection(selection);
                count = db.delete(TABLE.NOTE, selection, selectionArgs);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                /**
                 * ID that smaller than 0 is system folder which is not allowed to
                 * trash
                 */
                long noteId = Long.valueOf(id);
                if (noteId <= 0) {
                    break;
                }
                count = db.delete(TABLE.NOTE,
                        NoteColumns.ID + "=" + id + parseSelection(selection), selectionArgs);
                break;
            case URI_DATA:
                count = db.delete(TABLE.DATA, selection, selectionArgs);
                deleteData = true;
                break;
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                count = db.delete(TABLE.DATA,
                        DataColumns.ID + "=" + id + parseSelection(selection), selectionArgs);
                deleteData = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (count > 0) {
            if (deleteData) {
                changed(Notes.CONTENT_NOTE_URI);
            }
            changed(uri);
            alarmsChanged(alarms);
        }
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int count = 0;
        String id = null;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        boolean updateData = false;
        LinkedHashSet<Long> alarms = activeAlarmIds(db);
        db.beginTransaction();
        try {
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                count = updateNotes(db, values, selection, selectionArgs);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                count = updateNotes(db, values, NoteColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs);
                break;
            case URI_DATA:
                count = db.update(TABLE.DATA, values, selection, selectionArgs);
                updateData = true;
                break;
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                count = db.update(TABLE.DATA, values, DataColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs);
                updateData = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (count > 0) {
            alarms.addAll(activeAlarmIds(db));
            alarmsChanged(alarms);
            if (updateData) {
                changed(Notes.CONTENT_NOTE_URI);
            }
            changed(uri);
        }
        return count;
    }

    private String parseSelection(String selection) {
        return (!TextUtils.isEmpty(selection) ? " AND (" + selection + ')' : "");
    }

    private int updateNotes(SQLiteDatabase db, ContentValues values, String selection, String[] args) {
        ArrayList<Long> ids = new ArrayList<>();
        String sql = "SELECT " + NoteColumns.ID + " FROM " + TABLE.NOTE
                + (TextUtils.isEmpty(selection) ? "" : " WHERE " + selection);
        try (Cursor c = db.rawQueryWithFactory((database, driver, editTable, query) -> {
            if (args != null) for (int i = 0; i < args.length; i++) {
                if (args[i] == null) query.bindNull(i + 1);
                else query.bindString(i + 1, args[i]);
            }
            return new android.database.sqlite.SQLiteCursor(driver, editTable, query);
        }, sql, null, null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        int count = db.update(TABLE.NOTE, values, selection, args);
        for (long id : ids) db.execSQL("UPDATE " + TABLE.NOTE + " SET " + NoteColumns.VERSION
                + "=" + NoteColumns.VERSION + "+1 WHERE " + NoteColumns.ID + "=?", new Object[]{id});
        return count;
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

}
