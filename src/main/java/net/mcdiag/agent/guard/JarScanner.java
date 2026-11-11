package net.mcdiag.agent.guard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.mcdiag.agent.json.Json;

/**
 * Проверка jar-файла плагина без загрузки его классов: хеш файла, опасные вызовы и строки,
 * обфускация, нативные библиотеки. Ничего не блокирует — только находки с уровнем риска.
 * Чистая логика по файлу: для unit-тестов.
 */
public final class JarScanner {

    public static final String[] SEVERITIES = {"info", "low", "medium", "high", "critical"};
    private static final int MAX_CLASSES = 20_000;
    private static final long MAX_CLASS_BYTES = 1L << 20;
    private static final long MAX_TOTAL_BYTES = 96L << 20;
    private static final int MAX_EXAMPLE = 200;
    /** Подписи классов для общей базы: только подозрительные и из корневого пакета. */
    public static final int MAX_SIGNATURES = 20;

    /** Вызов → находка. Ключ — «класс.метод» в виде из пула констант. */
    private static final Map<String, String[]> CALLS = new LinkedHashMap<>();

    static {
        call("exec", "high", "java/lang/Runtime.exec", "java/lang/ProcessBuilder.start", "java/lang/ProcessBuilder.<init>");
        call("define_class", "high", "java/lang/ClassLoader.defineClass", "java/lang/invoke/MethodHandles$Lookup.defineClass",
                "sun/misc/Unsafe.defineClass", "sun/misc/Unsafe.defineAnonymousClass", "jdk/internal/misc/Unsafe.defineClass",
                "java/security/SecureClassLoader.defineClass");
        call("url_classloader", "medium", "java/net/URLClassLoader.<init>", "java/net/URLClassLoader.newInstance");
        call("base64", "info", "java/util/Base64$Decoder.decode");
        call("set_op", "medium", "org/bukkit/OfflinePlayer.setOp", "org/bukkit/entity/Player.setOp",
                "org/bukkit/permissions/ServerOperator.setOp", "org/bukkit/command/CommandSender.setOp");
        call("exit", "medium", "java/lang/System.exit", "java/lang/Runtime.halt", "java/lang/Runtime.exit");
        call("native_load", "medium", "java/lang/System.load", "java/lang/System.loadLibrary", "java/lang/Runtime.load");
    }

    private static final Pattern DEFINE = Pattern.compile("\\.(?:defineClass|defineAnonymousClass|defineHiddenClass)$");
    private static final Pattern EXFIL = Pattern.compile(
            "(?i)discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/|api\\.telegram\\.org/bot|pastebin\\.com/raw|hastebin|paste\\.ee"
                    + "|webhook\\.site|requestbin|pipedream\\.net|ngrok|duckdns\\.org|trycloudflare\\.com");
    private static final Pattern RAW_IP_URL = Pattern.compile("(?i)\\b(?:https?|tcp)://(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern SENSITIVE_FILE = Pattern.compile("(?i)(?:^|[/\\\\])(?:ops|whitelist|banned-players|banned-ips)\\.json$|server\\.properties$");
    private static final Pattern OBFUSCATOR = Pattern.compile("(?i)allatori|zelix|zkm|branchlock|skidfuscator|stringer|paramorphism|radon|bozar");

    private JarScanner() {
    }

    private static void call(String kind, String severity, String... refs) {
        for (String r : refs) {
            CALLS.put(r, new String[] {kind, severity});
        }
    }

    /** Итог проверки одного jar. */
    public static final class Report {
        public String file;
        public String name;
        public String version;
        public String sha256;
        /** SHA-1 и SHA-512: по ним Modrinth находит официальную версию (агент 0.11.0+) */
        public String sha1;
        public String sha512;
        public long size;
        public int classes;
        public final Map<String, Finding> findings = new LinkedHashMap<>();
        /** Хеши классов в корневом пакете: одинаковый такой класс в разных jar — признак заражения */
        public final Map<String, String> rootClasses = new LinkedHashMap<>();
        /** Имя класса → подпись (первые 128 бит SHA-256 его байтов): одинаковый внедрённый класс в разных jar */
        public final Map<String, String> signatures = new LinkedHashMap<>();
        public boolean truncated;

        public String risk() {
            int max = -1;
            for (Finding f : findings.values()) {
                max = Math.max(max, rank(f.severity));
            }
            return max < 0 ? "info" : SEVERITIES[max];
        }

        public String toJson(long ts) {
            StringBuilder sb = new StringBuilder(512).append("{\"t\":\"scan\",\"ts\":").append(ts);
            Json.str(sb.append(",\"file\":"), file, 256);
            if (name != null) {
                Json.str(sb.append(",\"name\":"), name, 64);
            }
            if (version != null) {
                Json.str(sb.append(",\"ver\":"), version, 64);
            }
            sb.append(",\"sha\":\"").append(sha256).append('"');
            if (sha1 != null) {
                sb.append(",\"s1\":\"").append(sha1).append("\",\"s5\":\"").append(sha512).append('"');
            }
            sb.append(",\"size\":").append(size)
                    .append(",\"cls\":").append(classes).append(",\"risk\":\"").append(risk()).append('"');
            if (truncated) {
                sb.append(",\"part\":true");
            }
            sb.append(",\"fnd\":[");
            int n = 0;
            for (Finding f : findings.values()) {
                if (n++ >= 30) {
                    break;
                }
                sb.append(n > 1 ? "," : "").append("{\"k\":\"").append(f.kind).append("\",\"sev\":\"").append(f.severity)
                        .append("\",\"n\":").append(f.count);
                if (f.example != null) {
                    Json.str(sb.append(",\"ex\":"), f.example, MAX_EXAMPLE);
                }
                sb.append('}');
            }
            sb.append(']');
            if (!signatures.isEmpty()) {
                sb.append(",\"cs\":[");
                int i = 0;
                for (Map.Entry<String, String> e : signatures.entrySet()) {
                    sb.append(i++ > 0 ? "," : "").append("{\"h\":\"").append(e.getValue()).append('"');
                    Json.str(sb.append(",\"c\":"), e.getKey(), 160);
                    sb.append('}');
                }
                sb.append(']');
            }
            return sb.append('}').toString();
        }
    }

    public static final class Finding {
        final String kind;
        String severity;
        int count;
        String example;

        Finding(String kind, String severity) {
            this.kind = kind;
            this.severity = severity;
        }
    }

    public static Report scan(Path jar, String shownName) throws IOException {
        Report report = new Report();
        report.file = shownName;
        report.size = Files.size(jar);
        String[] hashes = hashes(jar);
        report.sha1 = hashes[0];
        report.sha256 = hashes[1];
        report.sha512 = hashes[2];
        long total = 0;
        int obfuscatedNames = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String entry = e.getName();
                if (entry.equals("plugin.yml") || entry.equals("paper-plugin.yml") || entry.equals("bungee.yml")) {
                    readMeta(zip, e, report);
                    continue;
                }
                String lower = entry.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".so") || lower.endsWith(".dll") || lower.endsWith(".dylib") || lower.endsWith(".jnilib")) {
                    add(report, "native_lib", "medium", entry);
                    continue;
                }
                if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) {
                    continue;
                }
                if (report.classes >= MAX_CLASSES || total > MAX_TOTAL_BYTES || e.getSize() > MAX_CLASS_BYTES) {
                    report.truncated = true;
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) {
                    bytes = in.readNBytes((int) MAX_CLASS_BYTES + 1);
                }
                if (bytes.length > MAX_CLASS_BYTES) {
                    report.truncated = true;
                    continue;
                }
                total += bytes.length;
                report.classes++;
                boolean root = entry.indexOf('/') < 0;
                String classHash = root ? sha256(bytes) : null;
                if (root) {
                    report.rootClasses.put(entry, classHash);
                }
                if (looksObfuscated(entry)) {
                    obfuscatedNames++;
                }
                ClassInfo info;
                try {
                    info = ClassInfo.parse(bytes);
                } catch (IOException | RuntimeException ex) {
                    continue;
                }
                if (info != null && (inspect(report, info) || root) && report.signatures.size() < MAX_SIGNATURES) {
                    String className = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
                    report.signatures.put(className, (classHash != null ? classHash : sha256(bytes)).substring(0, 32));
                }
            }
        }
        if (report.classes >= 20 && obfuscatedNames * 2 > report.classes) {
            add(report, "obfuscated", "low", obfuscatedNames + "/" + report.classes + " classes");
        }
        escalate(report);
        return report;
    }

    /** @return true, если в классе есть заметная находка (средняя и выше) — его подпись уходит в общую базу */
    static boolean inspect(Report report, ClassInfo info) {
        boolean notable = false;
        String where = info.name == null ? "?" : info.name.replace('/', '.');
        for (String ref : info.methodRefs) {
            String[] rule = CALLS.get(ref);
            if (rule == null && DEFINE.matcher(ref).find()) {
                // Подкласс ClassLoader вызывает defineClass через себя, а не через java/lang/ClassLoader.
                rule = new String[] {"define_class", "high"};
            }
            if (rule != null) {
                add(report, rule[0], rule[1], where + " → " + ref.replace('/', '.'));
                notable |= rank(rule[1]) >= 2;
            }
        }
        for (String s : info.strings) {
            if (s.length() < 6 || s.length() > 2000) {
                continue;
            }
            if (EXFIL.matcher(s).find()) {
                add(report, "exfil_host", "high", maskSecrets(s));
                notable = true;
            } else if (RAW_IP_URL.matcher(s).find()) {
                add(report, "raw_ip", "medium", maskSecrets(s));
                notable = true;
            } else if (SENSITIVE_FILE.matcher(s).find()) {
                add(report, "sensitive_files", "low", s);
            }
            if (OBFUSCATOR.matcher(s).find() && s.length() < 120) {
                add(report, "obfuscator", "low", s);
            }
        }
        return notable;
    }

    /** Сочетания опаснее отдельных находок: запуск процессов + сеть, код из base64. */
    static void escalate(Report report) {
        boolean exec = report.findings.containsKey("exec");
        boolean define = report.findings.containsKey("define_class");
        boolean network = report.findings.containsKey("exfil_host") || report.findings.containsKey("raw_ip");
        if (define && report.findings.containsKey("base64")) {
            add(report, "hidden_code", "critical", "base64 + defineClass");
        }
        if (exec && network) {
            add(report, "exec_network", "critical", "process launch + external host");
        }
        if (define && network) {
            add(report, "remote_code", "critical", "defineClass + external host");
        }
    }

    private static void add(Report report, String kind, String severity, String example) {
        Finding f = report.findings.computeIfAbsent(kind, k -> new Finding(k, severity));
        f.count++;
        if (rank(severity) > rank(f.severity)) {
            f.severity = severity;
        }
        if (f.example == null) {
            f.example = example.length() > MAX_EXAMPLE ? example.substring(0, MAX_EXAMPLE) : example;
        }
    }

    /** Токены вебхуков и ботов в найденных строках не отправляем. */
    static String maskSecrets(String s) {
        return s.replaceAll("(webhooks/\\d+/)[A-Za-z0-9_\\-]{8,}", "$1***")
                .replaceAll("(/bot)\\d+:[A-Za-z0-9_\\-]{8,}", "$1***")
                .replaceAll("(?i)([?&;](?:token|access_token|key|api_?key|secret|password|passwd|pass|auth|sig|signature|session)=)[^&#\\s\"']+", "$1***")
                .replaceAll("(://[^/:@\\s]+:)[^@/\\s]+@", "$1***@");
    }

    /** Имена вида «a/b/c», «Il1I», «O0O»: типичны для обфускаторов. */
    private static boolean looksObfuscated(String entry) {
        int slash = entry.lastIndexOf('/');
        String simple = entry.substring(slash + 1, entry.length() - ".class".length());
        if (simple.contains("$")) {
            simple = simple.substring(0, simple.indexOf('$'));
        }
        if (simple.length() <= 2) {
            return true;
        }
        boolean confusable = true;
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (c > 0x7e) {
                return true;
            }
            if ("Il1iO0".indexOf(c) < 0) {
                confusable = false;
            }
        }
        return confusable;
    }

    private static void readMeta(ZipFile zip, ZipEntry e, Report report) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            String yml = new String(in.readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : yml.split("\\R")) {
                if (line.startsWith("name:") && report.name == null) {
                    report.name = unquote(line.substring(5));
                } else if (line.startsWith("version:") && report.version == null) {
                    report.version = unquote(line.substring(8));
                }
            }
        }
    }

    private static String unquote(String v) {
        String t = v.trim();
        if (t.length() >= 2 && (t.charAt(0) == '\'' || t.charAt(0) == '"')) {
            t = t.substring(1, t.length() - 1);
        }
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    static int rank(String severity) {
        for (int i = 0; i < SEVERITIES.length; i++) {
            if (SEVERITIES[i].equals(severity)) {
                return i;
            }
        }
        return 0;
    }

    public static String sha256(Path file) throws IOException {
        return hashes(file)[1];
    }

    /** SHA-1, SHA-256 и SHA-512 за одно чтение файла. */
    public static String[] hashes(Path file) throws IOException {
        MessageDigest d1 = digest("SHA-1");
        MessageDigest d256 = digest("SHA-256");
        MessageDigest d512 = digest("SHA-512");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                d1.update(buf, 0, n);
                d256.update(buf, 0, n);
                d512.update(buf, 0, n);
            }
        }
        HexFormat hex = HexFormat.of();
        return new String[] {hex.formatHex(d1.digest()), hex.formatHex(d256.digest()), hex.formatHex(d512.digest())};
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest("SHA-256").digest(bytes));
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Классы корневого пакета, которые встречаются в нескольких jar одинаковыми байтами. */
    public static List<String[]> sharedRootClasses(List<Report> reports) {
        Map<String, List<String>> byHash = new LinkedHashMap<>();
        for (Report r : reports) {
            for (Map.Entry<String, String> e : r.rootClasses.entrySet()) {
                byHash.computeIfAbsent(e.getKey() + "#" + e.getValue(), k -> new ArrayList<>()).add(r.file);
            }
        }
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byHash.entrySet()) {
            if (e.getValue().size() >= 2) {
                out.add(new String[] {e.getKey().substring(0, e.getKey().indexOf('#')), String.join(", ", e.getValue())});
            }
        }
        return out;
    }
}
