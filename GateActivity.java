package com.apkguard.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Экран предупреждения.
 * Ступень 1 (MODE_DOWNLOAD): пользователь скачал APK.
 * Ступень 2 (MODE_LAUNCH): пользователь открывает приложение, установленное не из магазина.
 */
public class GateActivity extends Activity {

    static final String EXTRA_MODE = "mode";
    static final String EXTRA_DL_ID = "dl_id";
    static final String EXTRA_PKG = "pkg";
    static final String MODE_DOWNLOAD = "download";
    static final String MODE_LAUNCH = "launch";

    private static final long MAX_BYTES = 512L * 1024 * 1024;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private String mode = MODE_LAUNCH;
    private long dlId = -1;
    private String pkg;

    private TextView title;
    private TextView body;
    private TextView result;
    private Button btnSafe;
    private Button btnBlock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(40), dp(22), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title = new TextView(this);
        title.setTextSize(24);
        title.setTextColor(0xFFB3261E);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        body = new TextView(this);
        body.setTextSize(16);
        body.setTextColor(0xFF222222);
        body.setLineSpacing(0, 1.15f);
        body.setPadding(0, dp(14), 0, dp(10));
        root.addView(body);

        result = new TextView(this);
        result.setTextSize(15);
        result.setTextColor(0xFF444444);
        result.setLineSpacing(0, 1.15f);
        result.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF4F1EE);
        bg.setCornerRadius(dp(12));
        result.setBackground(bg);
        root.addView(result);

        btnBlock = makeButton(true);
        btnSafe = makeButton(false);
        root.addView(btnBlock);
        root.addView(btnSafe);

        setContentView(scroll);
    }

    private Button makeButton(boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(54));
        b.setTextColor(primary ? Color.WHITE : 0xFFB3261E);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        if (primary) {
            d.setColor(0xFF1B6E3C);
        } else {
            d.setColor(Color.WHITE);
            d.setStroke(dp(1), 0xFFB3261E);
        }
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        b.setLayoutParams(lp);
        return b;
    }

    private void handle(Intent intent) {
        String m = intent.getStringExtra(EXTRA_MODE);
        mode = MODE_DOWNLOAD.equals(m) ? MODE_DOWNLOAD : MODE_LAUNCH;
        result.setText("Проверяю…");
        if (MODE_DOWNLOAD.equals(mode)) {
            dlId = intent.getLongExtra(EXTRA_DL_ID, -1);
            showDownload();
        } else {
            pkg = intent.getStringExtra(EXTRA_PKG);
            showLaunch();
        }
    }

    // ---------- ступень 1: скачан APK ----------

    private void showDownload() {
        final String name = downloadTitle();
        title.setText("Вы скачали APK-файл");
        body.setText("Файл «" + name + "» может быть опасным. APK из интернета могут красть пароли, "
                + "коды из SMS и деньги. Устанавливайте только то, чему полностью доверяете.");
        btnBlock.setText("Не устанавливать (удалить файл)");
        btnSafe.setText("Он безопасный, продолжить");
        btnBlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteDownload();
                Toast.makeText(GateActivity.this, "Файл удалён", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        btnSafe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(GateActivity.this,
                        "Откройте файл в «Загрузках» и установите. При первом запуске я предупрежу ещё раз.",
                        Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                    // нет приложения «Загрузки»
                }
                finish();
            }
        });
        final long id = dlId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text;
                File tmp = new File(getCacheDir(), "gate-" + System.nanoTime() + ".apk");
                try {
                    copyDownload(id, tmp);
                    Scanner.Result r = Checker.scanFile(GateActivity.this, tmp, name);
                    text = summary(r);
                } catch (Exception e) {
                    text = "Не удалось проверить файл. Считайте его потенциально опасным.";
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                post(text);
            }
        }).start();
    }

    private String downloadTitle() {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(dlId));
            if (c != null && c.moveToFirst()) {
                String t = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                if (t != null && t.length() > 0) {
                    return t;
                }
            }
        } catch (RuntimeException ignored) {
            // имя неизвестно
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return "APK";
    }

    private void copyDownload(long id, File out) throws IOException {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        ParcelFileDescriptor pfd = dm.openDownloadedFile(id);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("FILE_TOO_LARGE");
                }
                os.write(buf, 0, n);
            }
        }
    }

    private void deleteDownload() {
        try {
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).remove(dlId);
        } catch (RuntimeException ignored) {
            // файл уже удалён
        }
    }

    // ---------- ступень 2: запуск приложения ----------

    private void showLaunch() {
        if (pkg == null) {
            finish();
            return;
        }
        String label = pkg;
        PackageManager pm = getPackageManager();
        try {
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            finish();
            return;
        }
        title.setText("Осторожно: «" + label + "»");
        body.setText("Это приложение установлено не из магазина, а из APK-файла. Оно могло быть "
                + "изменено злоумышленниками. Открывайте его, только если уверены в источнике.");
        btnBlock.setText("Закрыть приложение");
        btnSafe.setText("Открыть, я доверяю");
        final String target = pkg;
        btnBlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeApp(target);
            }
        });
        btnSafe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TrustStore.trust(GateActivity.this, target);
                Intent launch = getPackageManager().getLaunchIntentForPackage(target);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(launch);
                    } catch (RuntimeException ignored) {
                        // не удалось открыть
                    }
                }
                finish();
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    text = summary(Checker.scanPackage(GateActivity.this, target));
                } catch (Exception e) {
                    text = "Не удалось проверить приложение.";
                }
                post(text);
            }
        }).start();
    }

    private void closeApp(String target) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            am.killBackgroundProcesses(target);
        } catch (RuntimeException ignored) {
            // нет права или процесс уже завершён
        }
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (RuntimeException ignored) {
            // нет лаунчера
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (MODE_LAUNCH.equals(mode) && pkg != null) {
            closeApp(pkg);
        } else {
            super.onBackPressed();
        }
    }

    // ---------- вспомогательное ----------

    private String summary(Scanner.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.verdict).append(" (").append(r.score).append("/100)");
        int shown = 0;
        for (Scanner.Finding f : r.findings) {
            if (f.points <= 0) {
                continue;
            }
            if (shown++ >= 4) {
                break;
            }
            sb.append("\n• ").append(f.msg);
        }
        sb.append("\n\nЭто статический анализ, а не гарантия безопасности.");
        return sb.toString();
    }

    private void post(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                result.setText(text);
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
