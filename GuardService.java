package com.apkguard.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.DownloadManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Работает в фоне и проверяет каждое новое или обновлённое приложение после установки. */
public class GuardService extends Service {

    static final String CH_GUARD = "guard";
    static final String CH_ALERT = "alert";
    public static final String EXTRA_PACKAGE = "com.apkguard.app.extra.PACKAGE";
    private static final int ID_FOREGROUND = 1;

    private BroadcastReceiver receiver;
    private BroadcastReceiver downloadReceiver;
    private ExecutorService scans;
    private HandlerThread watchThread;
    private Handler watchHandler;
    private long lastEventTime;

    @Override
    public void onCreate() {
        super.onCreate();
        scans = Executors.newSingleThreadExecutor();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel guard = new NotificationChannel(CH_GUARD, "Фоновая защита",
                NotificationManager.IMPORTANCE_LOW);
        guard.setShowBadge(false);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "Результаты автопроверки",
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(guard);
        nm.createNotificationChannel(alert);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!getSharedPreferences("apkguard", MODE_PRIVATE).getBoolean("guard", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_GUARD)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("APK Guard: защита включена")
                .setContentText("Новые приложения проверяются автоматически")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ID_FOREGROUND, n);
            }
        } catch (RuntimeException e) {
            getSharedPreferences("apkguard", MODE_PRIVATE).edit().putBoolean("guard", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        registerOnce();
        registerDownloads();
        startLaunchWatcher();
        return START_STICKY;
    }

    private void registerOnce() {
        if (receiver != null) {
            return;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Uri data = intent.getData();
                if (data == null) {
                    return;
                }
                final String pkg = data.getSchemeSpecificPart();
                if (pkg == null || pkg.equals(getPackageName())) {
                    return;
                }
                try {
                    scans.execute(new Runnable() {
                        @Override
                        public void run() {
                            check(pkg);
                        }
                    });
                } catch (RuntimeException ignored) {
                    // служба уже останавливается
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
    }

    // ---------- ступень 1: скачан APK ----------

    private void registerDownloads() {
        if (downloadReceiver != null) {
            return;
        }
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id < 0) {
                    return;
                }
                try {
                    scans.execute(new Runnable() {
                        @Override
                        public void run() {
                            onDownloadDone(id);
                        }
                    });
                } catch (RuntimeException ignored) {
                    // служба уже останавливается
                }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, f);
        }
    }

    private void onDownloadDone(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c == null || !c.moveToFirst()) {
                return;
            }
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                return;
            }
            String mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
            String name = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
            String uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            boolean apk = "application/vnd.android.package-archive".equals(mime)
                    || (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))
                    || (uri != null && uri.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"));
            if (!apk) {
                return;
            }
            Intent gate = new Intent(this, GateActivity.class);
            gate.putExtra(GateActivity.EXTRA_MODE, GateActivity.MODE_DOWNLOAD);
            gate.putExtra(GateActivity.EXTRA_DL_ID, id);
            showGate(gate, (int) id, "Вы скачали APK-файл",
                    "Он может быть опасным. Нажмите, чтобы проверить и решить.");
        } catch (RuntimeException ignored) {
            // загрузка удалена или недоступна
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    // ---------- ступень 2: запуск приложения не из магазина ----------

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollForeground();
            if (watchHandler != null) {
                watchHandler.postDelayed(this, 1000);
            }
        }
    };

    private void startLaunchWatcher() {
        if (watchThread != null) {
            return;
        }
        watchThread = new HandlerThread("apkguard-watch");
        watchThread.start();
        watchHandler = new Handler(watchThread.getLooper());
        lastEventTime = System.currentTimeMillis();
        watchHandler.postDelayed(poll, 1000);
    }

    @SuppressWarnings("deprecation")
    private void pollForeground() {
        try {
            if (!TrustStore.hasUsageAccess(this)) {
                lastEventTime = System.currentTimeMillis();
                return;
            }
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(lastEventTime + 1, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getTimeStamp() > lastEventTime) {
                    lastEventTime = e.getTimeStamp();
                }
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    onForeground(e.getPackageName());
                }
            }
        } catch (RuntimeException ignored) {
            // нет доступа или система отклонила запрос
        }
    }

    private void onForeground(String pkg) {
        if (pkg == null || pkg.equals(getPackageName())) {
            return;
        }
        if (!TrustStore.needsGate(this, pkg)) {
            return;
        }
        Intent gate = new Intent(this, GateActivity.class);
        gate.putExtra(GateActivity.EXTRA_MODE, GateActivity.MODE_LAUNCH);
        gate.putExtra(GateActivity.EXTRA_PKG, pkg);
        showGate(gate, pkg.hashCode(), "Приложение установлено из APK",
                "Нажмите, чтобы проверить его перед использованием.");
    }

    /** Показывает экран предупреждения сразу, если есть разрешение «поверх других окон», иначе уведомлением. */
    private void showGate(Intent gate, int notifId, String title, String text) {
        gate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (TrustStore.canOverlay(this)) {
            try {
                startActivity(gate);
                return;
            } catch (RuntimeException ignored) {
                // запуск из фона запрещён: покажем уведомление
            }
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (!nm.areNotificationsEnabled()) {
            return;
        }
        PendingIntent pi = PendingIntent.getActivity(this, notifId, gate,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(notifId, n);
    }

    private void check(String pkg) {
        try {
            Thread.sleep(1500); // даём системе закончить установку
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (Checker.trustReason(this, pkg) == null) {
            TrustStore.watch(this, pkg);
        }
        try {
            Scanner.Result r = Checker.scanPackage(this, pkg);
            getSharedPreferences("apkguard", MODE_PRIVATE).edit()
                    .putLong("last_auto_scan", System.currentTimeMillis())
                    .putString("last_auto_name", r.name)
                    .putString("last_auto_package", pkg)
                    .putInt("last_auto_level", r.level)
                    .apply();
            if (r.level >= 1) {
                alert(pkg, r);
            }
        } catch (Exception ignored) {
            // приложение уже удалено или недоступно
        }
    }

    private void alert(String pkg, Scanner.Result r) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (!nm.areNotificationsEnabled()) {
            return;
        }
        String title = (r.level >= 2 ? "Высокий риск: " : "Подозрительное приложение: ") + r.name;
        String text = "Риск " + r.score + "/100. " + (r.level >= 2
                ? "Проверьте находки и при необходимости удалите приложение."
                : "Проверьте, зачем приложению нужны найденные разрешения.");

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.putExtra(EXTRA_PACKAGE, pkg);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, pkg.hashCode(), openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent delete = PendingIntent.getActivity(this, pkg.hashCode() + 1,
                new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true);
        Notification.Action del = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), "Удалить", delete).build();
        b.addAction(del);
        nm.notify(pkg.hashCode(), b.build());
    }

    @Override
    public void onDestroy() {
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // уже снят
            }
            receiver = null;
        }
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (IllegalArgumentException ignored) {
                // уже снят
            }
            downloadReceiver = null;
        }
        if (watchHandler != null) {
            watchHandler.removeCallbacksAndMessages(null);
            watchHandler = null;
        }
        if (watchThread != null) {
            watchThread.quitSafely();
            watchThread = null;
        }
        if (scans != null) {
            scans.shutdownNow();
            scans = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
