package com.apkguard.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Ядро проверки APK. Не зависит от Android, поэтому его можно тестировать на компьютере.
 * Шесть уровней: 1 список хэшей, 2 структура, 3 подпись, 4 разрешения, 5 код, 6 скрытые файлы.
 */
public final class Scanner {

    public static final class Finding {
        public final int layer;
        public final int points;
        public final String msg;

        Finding(int layer, int points, String msg) {
            this.layer = layer;
            this.points = points;
            this.msg = msg;
        }
    }

    public static final class Result {
        public String name;
        public String id;          // имя пакета, если известно
        public String sha256;
        public int score;
        public int level;          // 0 безопасно, 1 подозрительно, 2 опасно, 3 очень опасно
        public String verdict;
        public final List<Finding> findings = new ArrayList<>();

        void add(int layer, int points, String msg) {
            findings.add(new Finding(layer, points, msg));
            score += points;
        }
    }

    private static final class Combo {
        final String[] need;
        final int points;
        final String msg;

        Combo(String[] need, int points, String msg) {
            this.need = need;
            this.points = points;
            this.msg = msg;
        }
    }

    private static final class Rule {
        final String pattern;
        final int points;
        final String msg;

        Rule(String pattern, int points, String msg) {
            this.pattern = pattern;
            this.points = points;
            this.msg = msg;
        }
    }

    private static final Map<String, Integer> PERMS = new HashMap<>();
    private static final Combo[] COMBOS = {
            new Combo(new String[]{"RECEIVE_SMS", "SEND_SMS"}, 10,
                    "чтение и отправка SMS (признак SMS-мошенничества)"),
            new Combo(new String[]{"BIND_ACCESSIBILITY_SERVICE", "SYSTEM_ALERT_WINDOW"}, 15,
                    "Accessibility и окна поверх экрана (метод банковского трояна)"),
            new Combo(new String[]{"READ_SMS", "BIND_ACCESSIBILITY_SERVICE"}, 10,
                    "SMS и Accessibility (риск кражи кодов подтверждения)"),
            new Combo(new String[]{"READ_SMS", "INTERNET"}, 8,
                    "SMS и доступ в интернет (риск передачи сообщений на сервер)"),
            new Combo(new String[]{"BIND_NOTIFICATION_LISTENER_SERVICE", "INTERNET"}, 12,
                    "чтение уведомлений и интернет (риск утечки кодов подтверждения)"),
            new Combo(new String[]{"REQUEST_INSTALL_PACKAGES", "RECEIVE_BOOT_COMPLETED", "SYSTEM_ALERT_WINDOW"}, 12,
                    "установка APK, автозапуск и окна поверх экрана")
    };
    private static final Rule[] DEX_RULES = {
            new Rule("Ldalvik/system/DexClassLoader;", 12, "Динамическая загрузка кода (DexClassLoader)"),
            new Rule("Ldalvik/system/InMemoryDexClassLoader;", 15, "Скрытая загрузка кода из памяти"),
            new Rule("Landroid/telephony/SmsManager;", 6, "API отправки SMS"),
            new Rule("sendTextMessage", 8, "Скрытая отправка SMS"),
            new Rule("Landroid/app/admin/DevicePolicyManager;", 8, "API администратора устройства"),
            new Rule("dispatchGesture", 8, "Нажатия от имени пользователя (accessibility)"),
            new Rule("performGlobalAction", 5, "Управление системными кнопками"),
            new Rule("/system/bin/su", 15, "Попытка получить root (su)"),
            new Rule("/system/xbin/su", 15, "Попытка получить root (su)"),
            new Rule("Superuser.apk", 12, "Поиск root-инструментов"),
            new Rule("pm install", 12, "Скрытая установка приложений"),
            new Rule("pm grant", 10, "Самовыдача разрешений"),
            new Rule("api.telegram.org/bot", 12, "Отправка данных через Telegram-бота"),
            new Rule("discord.com/api/webhooks", 12, "Отправка данных через Discord webhook"),
            new Rule("ngrok", 6, "Адрес туннеля ngrok"),
            new Rule("setComponentEnabledSetting", 6, "Приём скрытия значка приложения"),
            new Rule("chmod 777", 5, "Нарушение прав доступа к файлам"),
            new Rule("TYPE_APPLICATION_OVERLAY", 5, "Вывод окна поверх экрана"),
            new Rule("getRunningAppProcesses", 3, "Слежка за запущенными приложениями")
    };
    private static final String[] PACKERS = {
            "libjiagu", "libdexhelper", "libsecexe", "libprotect", "libshell",
            "libegis", "libddog", "libnqshield", "libmobisec", "libchaosvmp"
    };
    private static final Pattern IP_URL = Pattern.compile("https?://\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final Pattern PERM_RE = Pattern.compile("android\\.permission\\.([A-Z_]+)");
    private static final Pattern DEX_NAME = Pattern.compile("classes\\d*\\.dex");
    private static final Pattern V1_SIG = Pattern.compile("meta-inf/.+\\.(rsa|dsa|ec)");

    private static final long MAX_DEX = 96L * 1024 * 1024;
    private static final long MAX_ASSET = 8L * 1024 * 1024;

    static {
        Object[][] p = {
                {"READ_SMS", 15}, {"RECEIVE_SMS", 12}, {"SEND_SMS", 15}, {"WRITE_SMS", 10},
                {"READ_CALL_LOG", 8}, {"PROCESS_OUTGOING_CALLS", 8}, {"RECORD_AUDIO", 6},
                {"READ_CONTACTS", 5}, {"SYSTEM_ALERT_WINDOW", 10},
                {"BIND_ACCESSIBILITY_SERVICE", 20}, {"BIND_DEVICE_ADMIN", 20},
                {"REQUEST_INSTALL_PACKAGES", 10}, {"INSTALL_PACKAGES", 25},
                {"RECEIVE_BOOT_COMPLETED", 3}, {"MANAGE_EXTERNAL_STORAGE", 6},
                {"QUERY_ALL_PACKAGES", 4}, {"BIND_NOTIFICATION_LISTENER_SERVICE", 15},
                {"REQUEST_DELETE_PACKAGES", 5}, {"DISABLE_KEYGUARD", 5},
                {"WRITE_SETTINGS", 4}, {"READ_PHONE_NUMBERS", 3}, {"READ_PHONE_STATE", 3},
                {"ACCESS_BACKGROUND_LOCATION", 5}, {"USE_FULL_SCREEN_INTENT", 3}
        };
        for (Object[] e : p) {
            PERMS.put((String) e[0], (Integer) e[1]);
        }
    }

    private Scanner() {
    }

    public static String layerName(int layer) {
        switch (layer) {
            case 1: return "Список хэшей";
            case 2: return "Структура";
            case 3: return "Подпись";
            case 4: return "Разрешения";
            case 5: return "Код";
            case 6: return "Скрытые файлы";
            default: return "";
        }
    }

    /**
     * @param apk       файл APK
     * @param blocklist SHA-256 известных вредоносных файлов (может быть null)
     * @param extraPerms разрешения, полученные от системы (без префикса android.permission.), может быть null
     */
    public static Result scan(File apk, Set<String> blocklist, Set<String> extraPerms) {
        Result r = new Result();
        r.name = apk.getName();

        try {
            r.sha256 = sha256(apk);
        } catch (IOException e) {
            r.add(2, 10, "Файл не удалось прочитать");
            return finish(r);
        }
        if (blocklist != null && blocklist.contains(r.sha256)) {
            r.add(1, 100, "Хэш найден в чёрном списке: известный вредоносный файл");
        }

        ZipFile zf;
        try {
            zf = new ZipFile(apk);
        } catch (IOException e) {
            r.add(2, 40, "Файл повреждён или не является APK (архив не читается)");
            return finish(r);
        }
        try {
            analyze(zf, apk, r, extraPerms);
        } catch (IOException | RuntimeException e) {
            r.add(2, 10, "Часть файла не удалось проверить");
        } finally {
            try {
                zf.close();
            } catch (IOException ignored) {
                // закрытие не критично
            }
        }
        return finish(r);
    }

    private static void analyze(ZipFile zf, File apk, Result r, Set<String> extraPerms) throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            entries.add(en.nextElement());
        }
        Set<String> names = new HashSet<>();
        List<String> lower = new ArrayList<>();
        List<String> dexNames = new ArrayList<>();
        long totalUnpacked = 0;
        long totalPacked = 0;
        for (ZipEntry e : entries) {
            String n = e.getName();
            names.add(n);
            lower.add(n.toLowerCase());
            if (DEX_NAME.matcher(n).matches()) {
                dexNames.add(n);
            }
            totalUnpacked += Math.max(0, e.getSize());
            totalPacked += Math.max(0, e.getCompressedSize());
        }

        // Уровень 2: структура
        if (!names.contains("AndroidManifest.xml")) {
            r.add(2, 30, "Нет AndroidManifest.xml: это не настоящий APK");
        }
        if (dexNames.isEmpty()) {
            r.add(2, 10, "Файл classes.dex не найден");
        }
        for (String n : names) {
            String[] parts = n.replace('\\', '/').split("/");
            boolean bad = n.startsWith("/");
            for (String part : parts) {
                if (part.equals("..")) {
                    bad = true;
                }
            }
            if (bad) {
                r.add(2, 30, "Попытка выхода за пределы каталога: " + cut(n, 60));
                break;
            }
        }
        if (totalUnpacked > 3L * 1024 * 1024 * 1024
                || (totalPacked > 0 && totalUnpacked / totalPacked > 200)) {
            r.add(2, 30, "Признак zip-бомбы: слишком большой коэффициент сжатия");
        }

        // Уровень 3: подпись
        boolean v1 = false;
        for (String n : lower) {
            if (V1_SIG.matcher(n).matches()) {
                v1 = true;
                break;
            }
        }
        boolean v2 = hasSignatureBlock(apk);
        if (!v1 && !v2) {
            r.add(3, 25, "APK не подписан: Android не установит его, источник подозрителен");
        } else if (v1 && !v2) {
            r.add(3, 3, "Используется только старая подпись (v1)");
        }

        // Уровень 4: разрешения
        Set<String> perms = new HashSet<>();
        if (extraPerms != null) {
            perms.addAll(extraPerms);
        }
        ZipEntry manifest = zf.getEntry("AndroidManifest.xml");
        if (manifest != null) {
            byte[] mf = readAll(zf, manifest, 8 * 1024 * 1024);
            String a = new String(mf, StandardCharsets.ISO_8859_1);
            String b = new String(mf, StandardCharsets.UTF_16LE);
            String c = mf.length > 1
                    ? new String(mf, 1, mf.length - 1, StandardCharsets.UTF_16LE) : "";
            for (String text : new String[]{a, b, c}) {
                Matcher m = PERM_RE.matcher(text);
                while (m.find()) {
                    perms.add(m.group(1));
                }
            }
        }
        List<String> sorted = new ArrayList<>(perms);
        Collections.sort(sorted);
        List<String> hit = new ArrayList<>();
        int permPoints = 0;
        for (String p : sorted) {
            Integer pts = PERMS.get(p);
            if (pts != null) {
                hit.add(p);
                permPoints += pts;
            }
        }
        if (!hit.isEmpty()) {
            r.add(4, Math.min(permPoints, 45), "Опасные разрешения: " + join(hit));
        }
        for (Combo c : COMBOS) {
            boolean all = true;
            for (String need : c.need) {
                if (!perms.contains(need)) {
                    all = false;
                }
            }
            if (all) {
                r.add(4, c.points, "Опасное сочетание: " + c.msg);
            }
        }

        // Уровень 5: код
        Set<String> seen = new HashSet<>();
        for (String dn : dexNames) {
            try {
                scanDex(zf, zf.getEntry(dn), r, seen);
            } catch (IOException e) {
                r.add(5, 10, "Не удалось прочитать " + dn);
            }
        }

        // Уровень 6: скрытые файлы и упаковщики
        int nested = 0;
        int checked = 0;
        for (ZipEntry e : entries) {
            if (nested >= 3 || checked >= 150) {
                break;
            }
            String n = e.getName();
            String ln = n.toLowerCase();
            if (e.isDirectory() || e.getSize() < 8 || e.getSize() > MAX_ASSET) {
                continue;
            }
            boolean inAssets = n.startsWith("assets/") || n.startsWith("res/raw/");
            if (!(inAssets || ln.endsWith(".apk") || ln.endsWith(".jar")
                    || ln.endsWith(".dex") || ln.endsWith(".so"))) {
                continue;
            }
            checked++;
            byte[] head = new byte[4];
            try (InputStream in = zf.getInputStream(e)) {
                if (readFully(in, head) < 4) {
                    continue;
                }
            } catch (IOException ex) {
                continue;
            }
            boolean isDex = head[0] == 'd' && head[1] == 'e' && head[2] == 'x' && head[3] == '\n';
            boolean isElf = head[0] == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F';
            boolean isZip = head[0] == 'P' && head[1] == 'K';
            if (isDex && !DEX_NAME.matcher(n).matches()) {
                r.add(6, 20, "Скрытый DEX-файл: " + n);
                nested++;
            } else if (ln.endsWith(".apk") && isZip) {
                r.add(6, 20, "APK, спрятанный внутри: " + n);
                nested++;
            } else if (isElf && inAssets) {
                r.add(6, 10, "Исполняемый ELF внутри assets: " + n);
                nested++;
            }
        }
        outer:
        for (String n : lower) {
            for (String pk : PACKERS) {
                if (n.contains(pk)) {
                    r.add(6, 8, "Библиотека-упаковщик (скрытие кода): " + pk);
                    break outer;
                }
            }
        }
    }

    /** Читает DEX кусками по 1 МБ, чтобы не занимать много памяти. */
    private static void scanDex(ZipFile zf, ZipEntry e, Result r, Set<String> seen) throws IOException {
        final int chunk = 1 << 20;
        final int overlap = 64;
        byte[] buf = new byte[chunk + overlap];
        int keep = 0;
        long total = 0;
        boolean sawRuntime = false;
        boolean sawExec = false;
        try (InputStream in = zf.getInputStream(e)) {
            while (total < MAX_DEX) {
                int n = in.read(buf, keep, chunk);
                if (n < 0) {
                    break;
                }
                total += n;
                int len = keep + n;
                String s = new String(buf, 0, len, StandardCharsets.ISO_8859_1);
                for (Rule rule : DEX_RULES) {
                    if (!seen.contains(rule.msg) && s.contains(rule.pattern)) {
                        seen.add(rule.msg);
                        r.add(5, rule.points, "В коде: " + rule.msg);
                    }
                }
                if (!seen.contains("ip") && IP_URL.matcher(s).find()) {
                    seen.add("ip");
                    r.add(5, 6, "В коде: прямое подключение к IP-адресу");
                }
                if (s.contains("Runtime;")) {
                    sawRuntime = true;
                }
                if (s.contains("exec")) {
                    sawExec = true;
                }
                keep = Math.min(overlap, len);
                System.arraycopy(buf, len - keep, buf, 0, keep);
            }
        }
        if (sawRuntime && sawExec && !seen.contains("exec")) {
            seen.add("exec");
            r.add(5, 5, "В коде: запуск системных команд (Runtime.exec)");
        }
    }

    private static boolean hasSignatureBlock(File apk) {
        try (RandomAccessFile f = new RandomAccessFile(apk, "r")) {
            long len = f.length();
            if (len < 22) {
                return false;
            }
            int tail = (int) Math.min(len, 65557L);
            byte[] t = new byte[tail];
            f.seek(len - tail);
            f.readFully(t);
            for (int i = tail - 22; i >= 0; i--) {
                if (t[i] == 0x50 && t[i + 1] == 0x4b && t[i + 2] == 5 && t[i + 3] == 6) {
                    long cd = (t[i + 16] & 0xffL) | ((t[i + 17] & 0xffL) << 8)
                            | ((t[i + 18] & 0xffL) << 16) | ((t[i + 19] & 0xffL) << 24);
                    if (cd < 16 || cd > len) {
                        return false;
                    }
                    byte[] magic = new byte[16];
                    f.seek(cd - 16);
                    f.readFully(magic);
                    return new String(magic, StandardCharsets.ISO_8859_1).equals("APK Sig Block 42");
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    static Result finish(Result r) {
        r.score = Math.min(100, r.score);
        if (r.score >= 75) {
            r.level = 3;
            r.verdict = "Критический риск";
        } else if (r.score >= 50) {
            r.level = 2;
            r.verdict = "Высокий риск";
        } else if (r.score >= 25) {
            r.level = 1;
            r.verdict = "Подозрительно";
        } else {
            r.level = 0;
            r.verdict = "Явных угроз не найдено";
        }
        return r;
    }

    public static String advice(int level) {
        switch (level) {
            case 3:
                return "Не устанавливайте этот файл и удалите его. Если он уже установлен: отключите интернет, "
                        + "удалите приложение, смените пароли и проверьте банковские приложения.";
            case 2:
                return "Не устанавливайте. Скачайте приложение из Google Play или с официального сайта разработчика.";
            case 1:
                return "Устанавливайте только если уверены в источнике. Проверьте, зачем приложению нужны найденные разрешения.";
            default:
                return "Явных признаков угрозы нет, но это не гарантия. Устанавливайте приложения из надёжных источников.";
        }
    }

    // ---------- вспомогательное ----------

    private static String sha256(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) {
                md.update(b, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static byte[] readAll(ZipFile zf, ZipEntry e, int max) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[1 << 14];
            int n;
            while (out.size() < max) {
                int allowed = Math.min(b.length, max - out.size());
                n = in.read(b, 0, allowed);
                if (n < 0) {
                    break;
                }
                if (n > 0) {
                    out.write(b, 0, n);
                }
            }
            return out.toByteArray();
        }
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return off;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
