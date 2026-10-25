package net.micode.notes.tool;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import net.micode.notes.R;

public final class Notifications {
    private Notifications() {}
    public static Notification.Builder builder(Context context, String id, boolean reminder) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(id,
                    context.getString(reminder ? R.string.channel_reminders : R.string.channel_sync),
                    reminder ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_LOW));
            return new Notification.Builder(context, id);
        }
        return new Notification.Builder(context);
    }
    public static boolean allowed(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
    public static void request(Activity activity) {
        if (!allowed(activity)) activity.requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 501);
    }
}
