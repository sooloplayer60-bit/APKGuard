package com.apkguard.app;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Set;

/** Хранит, какие приложения установлены не из магазина и какие из них пользователь уже подтвердил. */
final class TrustStore {

    private static final String FILE = "apkguard_gate";
    private static final String KEY_WATCH = "watch";
    private static final String KEY_OK = "ok";

    private TrustStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static Set<String> read(Context c, String key) {
        // getStringSet нельзя менять напрямую, поэтому копируем
        return new HashSet<>(sp(c).getStringSet(key, new HashSet<String>()));
    }

    /** Новое приложение не из магазина: при первом запуске покажем предупреждение. */
    static synchronized void watch(Context c, String pkg) {
        Set<String> w = read(c, KEY_WATCH);
        Set<String> ok = read(c, KEY_OK);
        w.add(pkg);
        ok.remove(pkg);
        sp(c).edit().putStringSet(KEY_WATCH, w).putStringSet(KEY_OK, ok).apply();
    }

    /** Пользователь нажал «Открыть — я доверяю». */
    static synchronized void trust(Context c, String pkg) {
        Set<String> ok = read(c, KEY_OK);
        ok.add(pkg);
        sp(c).edit().putStringSet(KEY_OK, ok).apply();
    }

    static synchronized boolean needsGate(Context c, String pkg) {
        return read(c, KEY_WATCH).contains(pkg) && !read(c, KEY_OK).contains(pkg);
    }

    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean canOverlay(Context c) {
        return Settings.canDrawOverlays(c);
    }
}
