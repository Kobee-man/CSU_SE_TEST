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

package net.micode.notes.model;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.RemoteException;
import android.os.Bundle;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;

import java.util.ArrayList;


public class Note {
    private ContentValues mNoteDiffValues;
    private NoteData mNoteData;
    private static final String TAG = "Note";
    /**
     * Create a new note id for adding a new note to databases
     */
    public static synchronized long getNewNoteId(Context context, long folderId) {
        // Create a new note in the database
        ContentValues values = new ContentValues();
        long createdTime = System.currentTimeMillis();
        values.put(NoteColumns.CREATED_DATE, createdTime);
        values.put(NoteColumns.MODIFIED_DATE, createdTime);
        values.put(NoteColumns.TYPE, Notes.TYPE_NOTE);
        values.put(NoteColumns.LOCAL_MODIFIED, 1);
        values.put(NoteColumns.PARENT_ID, folderId);
        Uri uri = context.getContentResolver().insert(Notes.CONTENT_NOTE_URI, values);

        long noteId = 0;
        try {
            noteId = Long.valueOf(uri.getPathSegments().get(1));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Get note id error :" + e.toString());
            noteId = 0;
        }
        if (noteId == -1) {
            throw new IllegalStateException("Wrong note id:" + noteId);
        }
        return noteId;
    }

    public Note() {
        mNoteDiffValues = new ContentValues();
        mNoteData = new NoteData();
    }

    public void setNoteValue(String key, String value) {
        mNoteDiffValues.put(key, value);
        mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
        mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
    }

    public void setTextData(String key, String value) {
        mNoteData.setTextData(key, value);
    }

    public void setTextDataId(long id) {
        mNoteData.setTextDataId(id);
    }

    public long getTextDataId() {
        return mNoteData.mTextDataId;
    }

    public void setCallDataId(long id) {
        mNoteData.setCallDataId(id);
    }

    public void setCallData(String key, String value) {
        mNoteData.setCallData(key, value);
    }

    public boolean isLocalModified() {
        return mNoteDiffValues.size() > 0 || mNoteData.isLocalModified();
    }

    public Bundle snapshot() {
        Bundle state = new Bundle();
        state.putParcelable("note", new ContentValues(mNoteDiffValues));
        state.putParcelable("text", new ContentValues(mNoteData.mTextDataValues));
        state.putParcelable("call", new ContentValues(mNoteData.mCallDataValues));
        state.putLong("textId", mNoteData.mTextDataId);
        state.putLong("callId", mNoteData.mCallDataId);
        return state;
    }

    public void restore(Bundle state) {
        mNoteDiffValues = state.getParcelable("note");
        mNoteData.mTextDataValues = state.getParcelable("text");
        mNoteData.mCallDataValues = state.getParcelable("call");
        mNoteData.mTextDataId = state.getLong("textId");
        mNoteData.mCallDataId = state.getLong("callId");
    }

    public boolean syncNote(Context context, long noteId) {
        if (noteId <= 0) throw new IllegalArgumentException("Invalid note id");
        return save(context, noteId, 0) > 0;
    }

    /** Commit the note and both data rows together. Keep all differences until commit. */
    public synchronized long save(Context context, long noteId, long folderId) {
        if (noteId > 0 && !isLocalModified()) return noteId;
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        ContentValues values = new ContentValues(mNoteDiffValues);
        boolean creating = noteId == 0;
        if (creating) {
            values.put(NoteColumns.PARENT_ID, folderId);
            values.put(NoteColumns.TYPE, Notes.TYPE_NOTE);
            values.put(NoteColumns.CREATED_DATE, System.currentTimeMillis());
            operations.add(ContentProviderOperation.newInsert(Notes.CONTENT_NOTE_URI)
                    .withValues(values).build());
        } else {
            operations.add(ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId))
                    .withValues(values).withExpectedCount(1).build());
        }
        int textIndex = mNoteData.appendOperation(operations, mNoteData.mTextDataValues,
                mNoteData.mTextDataId, TextNote.CONTENT_ITEM_TYPE, noteId);
        int callIndex = mNoteData.appendOperation(operations, mNoteData.mCallDataValues,
                mNoteData.mCallDataId, CallNote.CONTENT_ITEM_TYPE, noteId);
        try {
            ContentProviderResult[] results = context.getContentResolver()
                    .applyBatch(Notes.AUTHORITY, operations);
            if (results == null || results.length != operations.size()) return 0;
            long savedId = creating ? ContentUris.parseId(results[0].uri) : noteId;
            long textId = textIndex >= 0 && mNoteData.mTextDataId == 0
                    ? ContentUris.parseId(results[textIndex].uri) : mNoteData.mTextDataId;
            long callId = callIndex >= 0 && mNoteData.mCallDataId == 0
                    ? ContentUris.parseId(results[callIndex].uri) : mNoteData.mCallDataId;
            if (savedId <= 0 || (textIndex >= 0 && textId <= 0)
                    || (callIndex >= 0 && callId <= 0)) return 0;
            mNoteData.mTextDataId = textId;
            mNoteData.mCallDataId = callId;
            mNoteDiffValues.clear();
            mNoteData.mTextDataValues.clear();
            mNoteData.mCallDataValues.clear();
            return savedId;
        } catch (RemoteException | OperationApplicationException | RuntimeException e) {
            Log.e(TAG, "Atomic note save failed (" + e.getClass().getSimpleName() + ")");
            return 0;
        }
    }

    private class NoteData {
        private long mTextDataId;

        private ContentValues mTextDataValues;

        private long mCallDataId;

        private ContentValues mCallDataValues;

        private static final String TAG = "NoteData";

        public NoteData() {
            mTextDataValues = new ContentValues();
            mCallDataValues = new ContentValues();
            mTextDataId = 0;
            mCallDataId = 0;
        }

        boolean isLocalModified() {
            return mTextDataValues.size() > 0 || mCallDataValues.size() > 0;
        }

        void setTextDataId(long id) {
            if(id <= 0) {
                throw new IllegalArgumentException("Text data id should larger than 0");
            }
            mTextDataId = id;
        }

        void setCallDataId(long id) {
            if (id <= 0) {
                throw new IllegalArgumentException("Call data id should larger than 0");
            }
            mCallDataId = id;
        }

        void setCallData(String key, String value) {
            mCallDataValues.put(key, value);
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        void setTextData(String key, String value) {
            mTextDataValues.put(key, value);
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        int appendOperation(ArrayList<ContentProviderOperation> operations,
                ContentValues differences, long dataId, String mimeType, long noteId) {
            if (differences.size() == 0) return -1;
            ContentValues values = new ContentValues(differences);
            ContentProviderOperation.Builder builder;
            if (dataId == 0) {
                builder = ContentProviderOperation.newInsert(Notes.CONTENT_DATA_URI);
                values.put(DataColumns.MIME_TYPE, mimeType);
            } else {
                builder = ContentProviderOperation.newUpdate(
                        ContentUris.withAppendedId(Notes.CONTENT_DATA_URI, dataId))
                        .withExpectedCount(1);
            }
            if (noteId == 0) {
                builder.withValueBackReference(DataColumns.NOTE_ID, 0);
            } else {
                values.put(DataColumns.NOTE_ID, noteId);
            }
            int index = operations.size();
            operations.add(builder.withValues(values).build());
            return index;
        }

    }
}
