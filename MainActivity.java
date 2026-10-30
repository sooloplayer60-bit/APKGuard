package com.apkguard.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 11;
    private static final int REQ_NOTIF = 12;
    private static final long MAX_APK_BYTES = 512L * 1024 * 1024;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    private int cBg, cSurface, cLine, cInk, cMuted, cAccent, cSafe, cWarn, cBad, cCrit;

    private LinearLayout results;
    private TextView status;
    private TextView guardInfo;
    private TextView summary;
    private TextView lastScan;
    private TextView resultsTitle;
    private TextView gateInfo;
    private Button btnUsage;
    private Button btnOverlay;
    private Button btnGuard;
    private Button btnInstalled;
    private Button btnFile;
    private Button btnShare;
    private ProgressBar progress;
    private String currentReport = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickColors();
        buildUi();
        refreshGuard();
        showHint();
        handleOpenIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGuard();
        refreshGate();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------- интерфейс ----------

    private void pickColors() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            cBg = Color.parseColor("#0B121A");
            cSurface = Color.parseColor("#131D28");
            cLine = Color.parseColor("#243242");
            cInk = Color.parseColor("#E5ECF3");
            cMuted = Color.parseColor("#92A2B5");
            cAccent = Color.parseColor("#7C9AFF");
            cSafe = Color.parseColor("#3DD6A0");
            cWarn = Color.parseColor("#F0B04A");
            cBad = Color.parseColor("#FF7B6B");
            cCrit = Color.parseColor("#FF6E93");
        } else {
            cBg = Color.parseColor("#ECEFF3");
            cSurface = Color.parseColor("#FFFFFF");
            cLine = Color.parseColor("#D3DAE2");
            cInk = Color.parseColor("#0F1A25");
            cMuted = Color.parseColor("#55657A");
            cAccent = Color.parseColor("#1B4DFF");
            cSafe = Color.parseColor("#11805B");
            cWarn = Color.parseColor("#B06400");
            cBad = Color.parseColor("#C23A2D");
            cCrit = Color.parseColor("#92173A");
        }
        getWindow().setStatusBarColor(cBg);
        getWindow().setNavigationBarColor(cBg);
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(36));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = text("ЛОКАЛЬНАЯ ЗАЩИТА ANDROID", 11, cAccent, true);
        eyebrow.setLetterSpacing(0.08f);
        root.addView(eyebrow);

        TextView brand = text("APK Guard", 30, cInk, true);
        brand.setPadding(0, dp(3), 0, 0);
        root.addView(brand);
        TextView lede = text("Проверка приложений и APK без отправки файлов в интернет. "
                + "Результат показывает признаки риска и объясняет, что именно найдено.", 14, cMuted, false);
        lede.setLineSpacing(0, 1.12f);
        lede.setPadding(0, dp(6), 0, dp(16));
        root.addView(lede);

        LinearLayout guardCard = new LinearLayout(this);
        guardCard.setOrientation(LinearLayout.VERTICAL);
        guardCard.setBackground(box(cSurface, cLine, 16));
        guardCard.setPadding(dp(16), dp(15), dp(16), dp(15));
        guardCard.addView(text("Фоновая защита", 17, cInk, true));
        guardInfo = text("", 14, cMuted, false);
        guardInfo.setLineSpacing(0, 1.1f);
        guardInfo.setPadding(0, dp(6), 0, dp(4));
        guardCard.addView(guardInfo);
        btnGuard = button("Включить защиту", true);
        btnGuard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGuard();
            }
        });
        guardCard.addView(btnGuard);
        root.addView(guardCard, matchWrap(0));

        LinearLayout gateCard = new LinearLayout(this);
        gateCard.setOrientation(LinearLayout.VERTICAL);
        gateCard.setBackground(box(cSurface, cLine, 16));
        gateCard.setPadding(dp(16), dp(15), dp(16), dp(15));
        gateCard.addView(text("Двойная защита от APK", 17, cInk, true));
        gateInfo = text("", 14, cMuted, false);
        gateInfo.setLineSpacing(0, 1.1f);
        gateInfo.setPadding(0, dp(6), 0, dp(4));
        gateCard.addView(gateInfo);
        btnUsage = button("Разрешить доступ к статистике использования", false);
        btnUsage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
        });
        gateCard.addView(btnUsage);
        btnOverlay = button("Разрешить показ поверх других приложений", false);
        btnOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        gateCard.addView(btnOverlay);
        root.addView(gateCard, matchWrap(14));

        TextView actionsTitle = text("Проверка", 18, cInk, true);
        actionsTitle.setPadding(0, dp(20), 0, dp(2));
        root.addView(actionsTitle);

        btnInstalled = button("Проверить установленные приложения", true);
        btnInstalled.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanInstalled();
            }
        });
        root.addView(btnInstalled);

        btnFile = button("Выбрать и проверить APK-файл", false);
        btnFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                String[] mime = {"application/vnd.android.package-archive", "application/octet-stream", "application/zip"};
                i.putExtra(Intent.EXTRA_MIME_TYPES, mime);
                startActivityForResult(i, REQ_PICK);
            }
        });
        root.addView(btnFile);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setVisibility(View.GONE);
        progress.setLayoutParams(matchWrap(12));
        root.addView(progress);

        status = text("", 13, cMuted, false);
        status.setLineSpacing(0, 1.08f);
        status.setPadding(0, dp(12), 0, dp(4));
        root.addView(status);

        summary = text("", 15, cInk, true);
        summary.setPadding(dp(15), dp(13), dp(15), dp(13));
        summary.setBackground(box(cSurface, cLine, 14));
        summary.setVisibility(View.GONE);
        root.addView(summary, matchWrap(8));

        lastScan = text("", 12, cMuted, false);
        lastScan.setPadding(0, dp(8), 0, 0);
        root.addView(lastScan);

        resultsTitle = text("Результаты", 18, cInk, true);
        resultsTitle.setPadding(0, dp(18), 0, dp(2));
        resultsTitle.setVisibility(View.GONE);
        root.addView(resultsTitle);

        btnShare = button("Поделиться отчётом", false);
        btnShare.setVisibility(View.GONE);
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareReport();
            }
        });
        root.addView(btnShare);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);

        TextView foot = text("APK Guard выполняет статический анализ: он находит подозрительные признаки, "
                + "но не заменяет антивирус и не даёт абсолютной гарантии. Устанавливайте приложения "
                + "только из Google Play или с официального сайта разработчика.", 12, cMuted, false);
        foot.setLineSpacing(0, 1.12f);
        foot.setPadding(0, dp(24), 0, 0);
        root.addView(foot);

        setContentView(scroll);
    }

    private void showHint() {
        status.setText("Выберите тип проверки. Для большого списка приложений это может занять некоторое время.");
        updateLastScanLabel();
    }

    private void updateLastScanLabel() {
        long when = prefs().getLong("last_manual_scan", 0);
        lastScan.setText(when > 0 ? "Последняя ручная проверка: " + formatTime(when) : "");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            g.setStroke(dp(1), stroke);
        }
        return g;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private LinearLayout.LayoutParams matchWrap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(primary ? Color.WHITE : cAccent);
        b.setBackground(box(primary ? cAccent : cSurface, primary ? 0 : cLine, 12));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setMinHeight(dp(52));
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(matchWrap(10));
        return b;
    }

    private int levelColor(int level) {
        switch (level) {
            case 3: return cCrit;
            case 2: return cBad;
            case 1: return cWarn;
            default: return cSafe;
        }
    }

    // ---------- защита в фоне ----------

    private void refreshGate() {
        if (gateInfo == null) {
            return;
        }
        boolean usage = TrustStore.hasUsageAccess(this);
        boolean overlay = TrustStore.canOverlay(this);
        gateInfo.setText("1) При скачивании APK покажу предупреждение: не устанавливать или «он безопасный».\n"
                + "2) Когда вы откроете приложение, установленное из APK, предупрежу ещё раз.\n\n"
                + (usage ? "✓ Доступ к статистике использования выдан\n" : "✗ Нужен доступ к статистике использования\n")
                + (overlay ? "✓ Показ поверх других приложений разрешён" : "✗ Нужен показ поверх других приложений"));
        btnUsage.setVisibility(usage ? View.GONE : View.VISIBLE);
        btnOverlay.setVisibility(overlay ? View.GONE : View.VISIBLE);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("apkguard", Context.MODE_PRIVATE);
    }

    private void refreshGuard() {
        if (guardInfo == null) {
            return;
        }
        boolean on = prefs().getBoolean("guard", false);
        String text = on
                ? "Защита работает. Новые и обновлённые приложения проверяются автоматически."
                : "Защита выключена. Новые приложения не проверяются автоматически.";
        long auto = prefs().getLong("last_auto_scan", 0);
        if (on && auto > 0) {
            String name = prefs().getString("last_auto_name", "приложение");
            text += "\nПоследняя автопроверка: " + name + ", " + formatTime(auto) + ".";
        }
        guardInfo.setText(text);
        btnGuard.setText(on ? "Выключить защиту" : "Включить защиту");
    }

    private void toggleGuard() {
        boolean on = prefs().getBoolean("guard", false);
        if (on) {
            prefs().edit().putBoolean("guard", false).apply();
            stopService(new Intent(this, GuardService.class));
            status.setText("Фоновая защита выключена.");
            refreshGuard();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        } else {
            enableGuard(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            enableGuard(!granted);
        }
    }

    private void enableGuard(boolean notificationsDenied) {
        prefs().edit().putBoolean("guard", true).apply();
        try {
            startForegroundService(new Intent(this, GuardService.class));
            status.setText(notificationsDenied
                    ? "Защита включена, но уведомления запрещены. Разрешите их в настройках, чтобы видеть предупреждения."
                    : "Фоновая защита включена.");
        } catch (RuntimeException e) {
            prefs().edit().putBoolean("guard", false).apply();
            status.setText("Не удалось запустить защиту. Разрешите приложению работу в фоне в настройках.");
        }
        refreshGuard();
    }

    // ---------- проверка ----------

    private void setBusy(boolean value, boolean determinate) {
        busy = value;
        btnInstalled.setEnabled(!value);
        btnFile.setEnabled(!value);
        btnInstalled.setAlpha(value ? 0.55f : 1f);
        btnFile.setAlpha(value ? 0.55f : 1f);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(!determinate);
        if (value && determinate) {
            progress.setProgress(0);
        }
    }

    private void prepareResults() {
        results.removeAllViews();
        resultsTitle.setVisibility(View.GONE);
        summary.setVisibility(View.GONE);
        btnShare.setVisibility(View.GONE);
        currentReport = "";
    }

    private void scanInstalled() {
        if (busy) {
            return;
        }
        prepareResults();
        setBusy(true, false);
        status.setText("Собираю список приложений…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                PackageManager pm = getPackageManager();
                final List<ApplicationInfo> apps = new ArrayList<>();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    boolean updatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                    if ((!system || updatedSystem) && !ai.packageName.equals(getPackageName())) {
                        apps.add(ai);
                    }
                }
                Collections.sort(apps, new Comparator<ApplicationInfo>() {
                    @Override
                    public int compare(ApplicationInfo a, ApplicationInfo b) {
                        PackageManager pm = getPackageManager();
                        return String.CASE_INSENSITIVE_ORDER.compare(
                                String.valueOf(pm.getApplicationLabel(a)),
                                String.valueOf(pm.getApplicationLabel(b)));
                    }
                });
                final List<Scanner.Result> found = new ArrayList<>();
                int unreadable = 0;
                for (int i = 0; i < apps.size(); i++) {
                    final int idx = i + 1;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            progress.setIndeterminate(false);
                            progress.setMax(Math.max(1, apps.size()));
                            progress.setProgress(idx);
                            status.setText("Проверяю " + idx + " из " + apps.size() + "…");
                        }
                    });
                    try {
                        found.add(Checker.scanPackage(MainActivity.this, apps.get(i).packageName));
                    } catch (Throwable t) {
                        unreadable++;
                    }
                }
                Collections.sort(found, new Comparator<Scanner.Result>() {
                    @Override
                    public int compare(Scanner.Result a, Scanner.Result b) {
                        int byScore = b.score - a.score;
                        return byScore != 0 ? byScore : String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
                    }
                });
                final int skipped = unreadable;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        showInstalled(found, skipped);
                        setBusy(false, true);
                    }
                });
            }
        }, "manual-installed-scan").start();
    }

    private void showInstalled(List<Scanner.Result> list, int skipped) {
        int danger = 0;
        int warn = 0;
        for (Scanner.Result r : list) {
            if (r.level >= 2) {
                danger++;
            } else if (r.level == 1) {
                warn++;
            }
        }
        int safe = list.size() - danger - warn;
        status.setText("Проверка завершена.");
        summary.setText("Проверено: " + list.size() + "\nБез явных угроз: " + safe
                + "   •   Подозрительных: " + warn + "\nВысокий риск: " + danger
                + (skipped > 0 ? "\nНе удалось прочитать: " + skipped : ""));
        summary.setTextColor(danger > 0 ? cBad : (warn > 0 ? cWarn : cSafe));
        summary.setVisibility(View.VISIBLE);
        resultsTitle.setText("Приложения — сначала самые рискованные");
        resultsTitle.setVisibility(View.VISIBLE);
        for (Scanner.Result r : list) {
            results.addView(card(r, true));
        }
        if (list.isEmpty()) {
            results.addView(text("Не найдено пользовательских приложений для проверки.", 14, cMuted, false));
        }
        long now = System.currentTimeMillis();
        prefs().edit().putLong("last_manual_scan", now).apply();
        updateLastScanLabel();
        currentReport = buildReport("Проверка установленных приложений", list, skipped);
        btnShare.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            scanUri(data.getData());
        }
    }

    private void scanUri(final Uri uri) {
        if (busy) {
            return;
        }
        prepareResults();
        setBusy(true, false);
        status.setText("Читаю и проверяю файл…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(getCacheDir(), "scan-" + System.nanoTime() + ".apk");
                Scanner.Result res = null;
                String error = null;
                try {
                    String name = displayName(uri);
                    copyWithLimit(uri, tmp);
                    res = Checker.scanFile(MainActivity.this, tmp, name);
                } catch (Exception e) {
                    error = e.getMessage();
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                final Scanner.Result done = res;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (done == null) {
                            status.setText(failure != null && failure.startsWith("FILE_TOO_LARGE")
                                    ? "Файл больше 512 МБ и не может быть проверен."
                                    : "Не удалось прочитать выбранный файл. Убедитесь, что это APK.");
                        } else {
                            showSingle(done, false);
                        }
                        setBusy(false, true);
                    }
                });
            }
        }, "manual-file-scan").start();
    }

    private void copyWithLimit(Uri uri, File target) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("EMPTY_STREAM");
            }
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                total += n;
                if (total > MAX_APK_BYTES) {
                    throw new IOException("FILE_TOO_LARGE");
                }
                out.write(buf, 0, n);
            }
        }
    }

    private void handleOpenIntent(Intent intent) {
        if (intent == null) return;
        final String pkg = intent.getStringExtra(GuardService.EXTRA_PACKAGE);
        if (pkg == null || pkg.length() == 0) return;
        intent.removeExtra(GuardService.EXTRA_PACKAGE);
        scanPackage(pkg);
    }

    private void scanPackage(final String pkg) {
        if (busy) return;
        prepareResults();
        setBusy(true, false);
        status.setText("Проверяю " + pkg + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Scanner.Result result = null;
                try {
                    result = Checker.scanPackage(MainActivity.this, pkg);
                } catch (Exception ignored) {
                    // приложение могло быть удалено
                }
                final Scanner.Result done = result;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (done == null) {
                            status.setText("Приложение недоступно или уже удалено.");
                        } else {
                            showSingle(done, true);
                        }
                        setBusy(false, true);
                    }
                });
            }
        }, "notification-package-scan").start();
    }

    private void showSingle(Scanner.Result result, boolean installed) {
        status.setText("Проверка завершена.");
        summary.setText(result.verdict + "\nОценка риска: " + result.score + "/100");
        summary.setTextColor(levelColor(result.level));
        summary.setVisibility(View.VISIBLE);
        resultsTitle.setText("Результат проверки");
        resultsTitle.setVisibility(View.VISIBLE);
        results.addView(card(result, installed));
        long now = System.currentTimeMillis();
        prefs().edit().putLong("last_manual_scan", now).apply();
        updateLastScanLabel();
        List<Scanner.Result> one = new ArrayList<>();
        one.add(result);
        currentReport = buildReport(installed ? "Проверка приложения" : "Проверка APK-файла", one, 0);
        btnShare.setVisibility(View.VISIBLE);
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    return c.getString(i);
                }
            }
        } catch (Exception ignored) {
            // имя не обязательно
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return "файл.apk";
    }

    // ---------- карточка и отчёт ----------

    private View card(final Scanner.Result r, boolean installed) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(cSurface, cLine, 14));
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.setLayoutParams(matchWrap(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(r.name, 16, cInk, true));
        names.addView(text(r.verdict, 13, levelColor(r.level), true));
        if (r.id != null && r.id.length() > 0) {
            TextView id = text(r.id, 11, cMuted, false);
            id.setPadding(0, dp(2), 0, 0);
            names.addView(id);
        }
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView score = text(r.score + "/100", 20, levelColor(r.level), true);
        score.setGravity(Gravity.END);
        score.setPadding(dp(10), 0, 0, 0);
        head.addView(score);
        card.addView(head);

        final TextView details = text(detailsText(r), 13, cInk, false);
        details.setLineSpacing(0, 1.12f);
        details.setPadding(0, dp(10), 0, 0);
        details.setTextIsSelectable(true);
        details.setVisibility(r.level >= 1 ? View.VISIBLE : View.GONE);
        card.addView(details);

        TextView toggle = text("Нажмите, чтобы показать или скрыть подробности", 11, cMuted, false);
        toggle.setPadding(0, dp(7), 0, 0);
        card.addView(toggle);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                details.setVisibility(details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        if (installed && r.level >= 2 && r.id != null) {
            Button del = button("Удалить приложение", false);
            del.setTextColor(cBad);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + r.id)));
                }
            });
            card.addView(del);
        }
        return card;
    }

    private String detailsText(Scanner.Result r) {
        StringBuilder sb = new StringBuilder();
        List<Scanner.Finding> sorted = new ArrayList<>(r.findings);
        Collections.sort(sorted, new Comparator<Scanner.Finding>() {
            @Override
            public int compare(Scanner.Finding a, Scanner.Finding b) {
                return b.points - a.points;
            }
        });
        for (Scanner.Finding f : sorted) {
            if (f.points > 0) {
                sb.append("+").append(f.points).append("  ").append(f.msg)
                        .append("  (").append(Scanner.layerName(f.layer)).append(")\n");
            }
        }
        if (sb.length() == 0) {
            sb.append("Подозрительных признаков не найдено.\n");
        }
        if (r.sha256 != null && r.sha256.length() > 0) {
            sb.append("\nSHA-256:\n").append(r.sha256).append("\n");
        }
        sb.append("\n").append(Scanner.advice(r.level));
        return sb.toString();
    }

    private String buildReport(String title, List<Scanner.Result> list, int skipped) {
        StringBuilder sb = new StringBuilder();
        sb.append("APK Guard — ").append(title).append('\n');
        sb.append(formatTime(System.currentTimeMillis())).append('\n');
        sb.append("Статический анализ на устройстве; результат не является гарантией безопасности.\n\n");
        for (Scanner.Result r : list) {
            sb.append(r.name).append(" — ").append(r.verdict)
                    .append(" (").append(r.score).append("/100)\n");
            if (r.id != null) sb.append("Пакет: ").append(r.id).append('\n');
            sb.append(detailsText(r)).append("\n\n");
        }
        if (skipped > 0) {
            sb.append("Не удалось прочитать приложений: ").append(skipped).append('\n');
        }
        return sb.toString();
    }

    private void shareReport() {
        if (currentReport.length() == 0) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Отчёт APK Guard");
        send.putExtra(Intent.EXTRA_TEXT, currentReport);
        startActivity(Intent.createChooser(send, "Поделиться отчётом"));
    }

    private String formatTime(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(time));
    }
}
