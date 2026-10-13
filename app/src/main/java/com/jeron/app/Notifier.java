package com.jeron.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

final class Notifier {
    static final String CH_MSG = "jeron_messages";
    static final String CH_SVC = "jeron_service";
    static final String GROUP = "jeron_group";
    static final int SUMMARY_ID = 1001;

    private Notifier() {}

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel m = new NotificationChannel(CH_MSG, "الرسائل", NotificationManager.IMPORTANCE_HIGH);
            m.enableVibration(true);
            m.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(m);
            NotificationChannel s = new NotificationChannel(CH_SVC, "التشغيل بالخلفية", NotificationManager.IMPORTANCE_MIN);
            s.setShowBadge(false);
            nm.createNotificationChannel(s);
        }
    }

    static PendingIntent open(Context c, String tag) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (tag != null) i.putExtra("open_tag", tag);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, tag == null ? 0 : tag.hashCode(), i, flags);
    }

    static Notification service(Context c, String text) {
        return new NotificationCompat.Builder(c, CH_SVC)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("جيرون")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(open(c, null))
                .build();
    }

    static void message(Context c, String title, String body, String tag, String group) {
        try {
            if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return;
            if (tag == null || tag.length() == 0) tag = "msg";
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_MSG)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setColor(0xFFD6338A)
                    .setContentTitle(title == null ? "جيرون" : title)
                    .setContentText(body == null ? "" : body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body == null ? "" : body))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setGroup(GROUP)
                    .setContentIntent(open(c, tag));
            NotificationManagerCompat nm = NotificationManagerCompat.from(c);
            nm.notify(tag.hashCode(), b.build());
            Notification sum = new NotificationCompat.Builder(c, CH_MSG)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setColor(0xFFD6338A)
                    .setContentTitle("جيرون")
                    .setContentText("رسائل جديدة")
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setContentIntent(open(c, null))
                    .build();
            nm.notify(SUMMARY_ID, sum);
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
    }
}
