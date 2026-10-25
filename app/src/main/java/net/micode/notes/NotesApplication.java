package net.micode.notes;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

import net.micode.notes.tool.AppLanguage;

public class NotesApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        AppLanguage.initialize(base);
        super.attachBaseContext(AppLanguage.wrap(base));
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        AppLanguage.updateResources(this);
    }
}
