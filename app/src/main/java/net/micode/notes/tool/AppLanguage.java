package net.micode.notes.tool;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** App-only language selection; the empty language tag means follow the system. */
public final class AppLanguage {
    private static final String PREFERENCES = "notes_language";
    private static final String LANGUAGE = "language";
    private static final String INITIALIZED = "initialized";
    private static final String DEFAULT_LANGUAGE = "zh-CN";

    private AppLanguage() {
    }

    public static void initialize(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SharedPreferences preferences = preferences(context);
            if (!preferences.getBoolean(INITIALIZED, false)) {
                LocaleManager manager = context.getSystemService(LocaleManager.class);
                // Keep an existing selection restored by Android instead of overwriting it.
                if (manager.getApplicationLocales().isEmpty()) {
                    manager.setApplicationLocales(LocaleList.forLanguageTags(
                            preferences.getString(LANGUAGE, DEFAULT_LANGUAGE)));
                }
                preferences.edit().putBoolean(INITIALIZED, true).apply();
            }
        }
    }

    public static String getLanguage(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.getSystemService(LocaleManager.class)
                    .getApplicationLocales().toLanguageTags();
        }
        return preferences(context).getString(LANGUAGE, DEFAULT_LANGUAGE);
    }

    public static void setLanguage(Context context, String language) {
        if (!language.isEmpty() && !"zh-CN".equals(language)
                && !"zh-TW".equals(language) && !"en".equals(language)) {
            throw new IllegalArgumentException("Unsupported app language: " + language);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager.class)
                    .setApplicationLocales(LocaleList.forLanguageTags(language));
        } else {
            preferences(context).edit().putString(LANGUAGE, language).apply();
            updateResources(context.getApplicationContext());
        }
    }

    public static Context wrap(Context context) {
        // Also cover the first Activity before Android delivers its native locale update.
        Configuration configuration = selectedConfiguration(context);
        return context.createConfigurationContext(configuration);
    }

    @SuppressWarnings("deprecation")
    public static void updateResources(Context context) {
        Resources resources = context.getResources();
        resources.updateConfiguration(selectedConfiguration(context), resources.getDisplayMetrics());
    }

    @SuppressWarnings("deprecation")
    private static Configuration selectedConfiguration(Context context) {
        String language = getLanguage(context);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        if (language.isEmpty()) {
            Configuration system = Resources.getSystem().getConfiguration();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                configuration.setLocales(system.getLocales());
                Locale.setDefault(system.getLocales().get(0));
            } else {
                configuration.setLocale(system.locale);
                Locale.setDefault(system.locale);
            }
        } else {
            Locale locale = Locale.forLanguageTag(language);
            configuration.setLocale(locale);
            Locale.setDefault(locale);
        }
        return configuration;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
