package net.micode.notes.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import net.micode.notes.tool.AppLanguage;
import net.micode.notes.R;

/** Android 13 handles recreation itself; older Android versions need a wrapped context. */
public abstract class LocalizedActivity extends Activity {
    private String mLanguage;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mLanguage = AppLanguage.getLanguage(this);
        setTitle(R.string.app_name);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && !AppLanguage.getLanguage(this).equals(mLanguage)) {
            recreate();
        }
    }
}
