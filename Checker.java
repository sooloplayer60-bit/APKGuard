package com.apkguard.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Связывает проверку файла с системой Android: берёт у неё разрешения и название приложения. */
final class Checker {

    private Checker() {
    }

    /** Проверка установленного приложения, включая все дополнительные split-APK. */
    static Scanner.Result scanPackage(Context ctx, String pkg) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
        Set<String> perms = installedPerms(pm, pkg);
        Scanner.Result r = Scanner.scan(new File(ai.sourceDir), null, perms);
        if (ai.splitSourceDirs != null) {
            for (String split : ai.splitSourceDirs) {
                if (split == null) continue;
                try {
                    Scanner.Result part = Scanner.scan(new File(split), null, perms);
                    mergeSplit(r, part, new File(split).getName());
                } catch (RuntimeException ignored) {
                    r.add(2, 5, "Один из split-APK не удалось проверить");
                }
            }
        }
        String trust = trustReason(ctx, pkg);
        if (trust != null) {
            // Приложение из магазина или системное: одни разрешения не делают его опасным
            r.score = Math.min(r.score, 20);
            r.findings.add(new Scanner.Finding(2, 0, trust));
        }
        Scanner.finish(r);
        r.id = pkg;
        CharSequence label = pm.getApplicationLabel(ai);
        r.name = label != null ? label.toString() : pkg;
        return r;
    }

    private static final String[] STORES = {
            "com.android.vending", "com.sec.android.app.samsungapps",
            "com.xiaomi.mipicks", "com.huawei.appmarket"
    };

    /** Возвращает причину доверия (системное или из магазина) либо null, если приложение поставили из файла. */
    @SuppressWarnings("deprecation")
    static String trustReason(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                return "Системное приложение";
            }
            String installer = pm.getInstallerPackageName(pkg);
            if (installer != null) {
                for (String store : STORES) {
                    if (store.equals(installer)) {
                        return "Установлено из магазина приложений";
                    }
                }
            }
        } catch (Exception ignored) {
            // нет данных: считаем, что приложение поставили из файла
        }
        return null;
    }

    private static void mergeSplit(Scanner.Result target, Scanner.Result part, String splitName) {
        target.score = Math.min(100, target.score + part.score);
        for (Scanner.Finding f : part.findings) {
            target.findings.add(new Scanner.Finding(f.layer, f.points, splitName + ": " + f.msg));
        }
        if (part.sha256 != null) {
            target.sha256 = (target.sha256 == null ? "" : target.sha256 + "\n")
                    + splitName + ": " + part.sha256;
        }
    }

    /** Проверка файла APK, который пользователь выбрал (ещё не установлен). */
    static Scanner.Result scanFile(Context ctx, File apk, String displayName) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> perms = new HashSet<>();
        String packageName = null;
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_PERMISSIONS);
            addPerms(perms, pi);
            if (pi != null) {
                packageName = pi.packageName;
            }
        } catch (Exception ignored) {
            // разрешения возьмём из самого файла
        }
        Scanner.Result r = Scanner.scan(apk, null, perms);
        if (displayName != null && displayName.length() > 0) {
            r.name = displayName;
        }
        r.id = packageName;
        return r;
    }

    private static Set<String> installedPerms(PackageManager pm, String pkg) {
        Set<String> perms = new HashSet<>();
        try {
            addPerms(perms, pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS));
        } catch (Exception ignored) {
            // нет данных: останутся разрешения из файла
        }
        return perms;
    }

    private static void addPerms(Set<String> out, PackageInfo pi) {
        if (pi == null || pi.requestedPermissions == null) {
            return;
        }
        for (String p : pi.requestedPermissions) {
            if (p == null) {
                continue;
            }
            out.add(p.startsWith("android.permission.") ? p.substring(19) : p);
        }
    }
}
