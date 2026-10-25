package net.micode.notes.tool;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Private local recovery files, one per editor. Never export a draft or its text to logs. */
public final class DraftStore {
    private DraftStore() {}
    private static AtomicFile file(Context context, String key) {
        if (key == null || !key.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Invalid draft key");
        File directory = new File(context.getFilesDir(), "drafts");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Draft directory unavailable");
        return new AtomicFile(new File(directory, key));
    }
    public static boolean save(Context context, String key, Bundle state) {
        Parcel parcel = Parcel.obtain();
        FileOutputStream output = null;
        AtomicFile target = null;
        try {
            target = file(context, key);
            parcel.writeBundle(state);
            output = target.startWrite();
            output.write(parcel.marshall());
            target.finishWrite(output);
            return true;
        } catch (IOException | RuntimeException e) {
            if (target != null && output != null) target.failWrite(output);
            return false;
        } finally {
            parcel.recycle();
        }
    }
    public static Bundle load(Context context, String key) {
        Parcel parcel = Parcel.obtain();
        try {
            byte[] bytes = file(context, key).readFully();
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            return parcel.readBundle(DraftStore.class.getClassLoader());
        } catch (IOException | RuntimeException e) {
            return null;
        } finally { parcel.recycle(); }
    }
    public static void remove(Context context, String key) {
        try { file(context, key).delete(); } catch (RuntimeException ignored) { }
    }
    public static String pending(Context context) {
        File[] drafts = new File(context.getFilesDir(), "drafts").listFiles();
        if (drafts != null) for (File draft : drafts) {
            if (draft.getName().matches("[a-zA-Z0-9-]+")) return draft.getName();
        }
        return null;
    }
}
