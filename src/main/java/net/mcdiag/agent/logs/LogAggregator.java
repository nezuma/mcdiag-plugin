package net.mcdiag.agent.logs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import net.mcdiag.agent.collect.ErrorCaptureHandler;
import net.mcdiag.agent.collect.StackTraceFormatter;
import net.mcdiag.agent.json.Json;
import net.mcdiag.api.Severity;

/**
 * Группирует предупреждения и ошибки по отпечатку: одинаковая проблема в цикле даёт одну запись со
 * счётчиком, а не тысячи строк. Текст и трейс формируются только для нового отпечатка; если он уже
 * уходил в панель за последний час, отправляется только счётчик.
 *
 * <p>Чистая логика без Bukkit: вызывается из потока, который пишет в лог, поэтому всё под одним
 * монитором и без лишних аллокаций для повторов.
 */
public final class LogAggregator {

    public static final int MAX_ENTRIES = 200;
    static final long RESEND_MS = 60 * 60 * 1000;
    private static final int MAX_SENT = 2048;
    private static final int MAX_MESSAGE = 512;
    private static final int MAX_LOGGER = 128;
    private static final int MAX_FINGERPRINT_TEXT = 300;
    private static final int FINGERPRINT_FRAMES = 3;

    /** UUID, IP, длинный hex и числа: то, что меняется от повтора к повтору одной проблемы. */
    private static final Pattern VOLATILE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "|\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b|\\b[0-9a-fA-F]{8,}\\b|\\d+(?:[.,]\\d+)?");
    private static final Pattern PREFIX = Pattern.compile("^\\[([A-Za-z0-9_.-]{1,64})] ");

    private final Function<String, String> classOwner;
    private final Predicate<String> knownPlugin;
    private final UnaryOperator<String> mask;
    private final Map<Long, Entry> window = new HashMap<>();
    private final LinkedHashMap<Long, Long> sent = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > MAX_SENT;
        }
    };
    private long dropped;

    /**
     * @param classOwner  класс → плагин (по jar-ам) или null
     * @param knownPlugin имя установленного плагина?
     * @param mask        скрытие токенов и IP в тексте
     */
    public LogAggregator(Function<String, String> classOwner, Predicate<String> knownPlugin, UnaryOperator<String> mask) {
        this.classOwner = classOwner;
        this.knownPlugin = knownPlugin;
        this.mask = mask;
    }

    /** Строка лога, возможно с исключением. */
    public synchronized void add(long ts, String level, String logger, boolean main, String message, Throwable thrown) {
        String text = message == null || message.isBlank() ? (thrown == null ? "" : thrown.toString()) : message;
        StackTraceElement[] frames = thrown == null ? null : thrown.getStackTrace();
        String exClass = thrown == null ? null : rootClass(thrown);
        long fp = fingerprint(level, logger, text, exClass, frames);
        Entry e = touch(fp, ts, main);
        if (e == null || e.message != null || e.countOnly) {
            return;
        }
        String plugin = attribute(logger, text, thrown);
        fillHeader(e, level, logger, main, text, exClass, plugin);
        if (plugin == null) {
            e.deferred(text, classesOf(thrown));
        }
        if (wasSentRecently(fp, ts)) {
            e.countOnly = true;
            return;
        }
        e.message = clip(mask.apply(text), MAX_MESSAGE);
        if (thrown != null) {
            e.trace = mask.apply(StackTraceFormatter.format(thrown));
        }
    }

    /** Исключение, напечатанное построчно через System.err: заголовок и строки «at …». */
    public synchronized void addLines(long ts, String level, String logger, boolean main, String header, List<String> lines) {
        List<String> classes = new ArrayList<>(lines.size());
        for (String line : lines) {
            String cls = frameClass(line);
            if (cls != null) {
                classes.add(cls);
            }
        }
        addLines(ts, level, logger, main, header, lines, classes);
    }

    /** @param classes классы из всех строк сообщения, а не только из оставленных головы и хвоста */
    public synchronized void addLines(long ts, String level, String logger, boolean main, String header,
                                      List<String> lines, List<String> classes) {
        String exClass = exceptionClassOf(header);
        long fp = fingerprintText(level, logger, header, exClass, classes);
        Entry e = touch(fp, ts, main);
        if (e == null || e.message != null || e.countOnly) {
            return;
        }
        String plugin = attributeText(logger, header, classes);
        fillHeader(e, level, logger, main, header, exClass, plugin);
        if (plugin == null) {
            e.deferred(header, classes);
        }
        if (wasSentRecently(fp, ts)) {
            e.countOnly = true;
            return;
        }
        e.message = clip(mask.apply(header), MAX_MESSAGE);
        StringBuilder trace = new StringBuilder(header.length() + lines.size() * 64).append(header);
        for (String line : lines) {
            trace.append('\n').append(line);
        }
        e.trace = mask.apply(clip(trace.toString(), StackTraceFormatter.MAX_CHARS));
    }

    /** Проблема, о которой сообщил плагин через API. */
    public synchronized void addIssue(long ts, String plugin, Severity severity, String category, String message) {
        long fp = fnv(fnv(fnv(FNV_OFFSET, "api"), plugin == null ? "" : plugin), category + '|' + normalize(message));
        Entry e = touch(fp, ts, false);
        if (e == null || e.message != null || e.countOnly) {
            return;
        }
        fillHeader(e, severity.ordinal() <= Severity.HIGH.ordinal() ? "ERROR" : "WARN", null, false, message, null, plugin);
        e.severity = severity.key();
        e.category = category;
        if (wasSentRecently(fp, ts)) {
            e.countOnly = true;
            return;
        }
        e.message = clip(mask.apply(message), MAX_MESSAGE);
    }

    /** Записи окна в формате раздела {@code logs} батча; окно очищается. */
    public synchronized List<String> drain() {
        List<String> out = new ArrayList<>(window.size());
        for (Iterator<Entry> it = window.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            it.remove();
            if (e.level == null) {
                continue;
            }
            // Индекс jar-ов мог появиться позже ошибки (запуск сервера): ищем плагин ещё раз.
            if (e.plugin == null && e.attrText != null) {
                e.plugin = attributeText(e.logger, e.attrText, e.classes);
            }
            out.add(e.toJson());
            if (e.message != null) {
                sent.put(e.fp, e.first);
            }
        }
        return out;
    }

    public synchronized int pending() {
        return window.size();
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized void clear() {
        window.clear();
    }

    // ── Внутреннее ──────────────────────────────────────────────────────

    private Entry touch(long fp, long ts, boolean main) {
        Entry e = window.get(fp);
        if (e == null) {
            if (window.size() >= MAX_ENTRIES) {
                dropped++;
                return null;
            }
            e = new Entry(fp, ts);
            window.put(fp, e);
        }
        e.count++;
        e.last = Math.max(e.last, ts);
        e.main |= main;
        return e;
    }

    private boolean wasSentRecently(long fp, long ts) {
        Long at = sent.get(fp);
        return at != null && ts - at < RESEND_MS;
    }

    private void fillHeader(Entry e, String level, String logger, boolean main, String text, String exClass, String plugin) {
        e.level = level;
        e.logger = logger == null || logger.isEmpty() ? null : clip(logger, MAX_LOGGER);
        e.main |= main;
        e.exClass = exClass;
        e.plugin = plugin;
    }

    /** Логгер плагина → «[Плагин] » в начале текста → типовые фразы ядра → первый кадр плагина в трейсе. */
    private String attribute(String logger, String text, Throwable thrown) {
        String byName = attributeByName(logger, text);
        if (byName != null) {
            return byName;
        }
        Throwable t = thrown;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            for (StackTraceElement frame : t.getStackTrace()) {
                String owner = classOwner.apply(frame.getClassName());
                if (owner != null) {
                    return owner;
                }
            }
        }
        return null;
    }

    private String attributeText(String logger, String text, List<String> classes) {
        String byName = attributeByName(logger, text);
        if (byName != null) {
            return byName;
        }
        for (String cls : classes) {
            String owner = classOwner.apply(cls);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    private String attributeByName(String logger, String text) {
        if (logger != null && !logger.isEmpty()) {
            if (knownPlugin.test(logger)) {
                return logger;
            }
            // Логгер Bukkit PluginLogger назван по главному классу плагина.
            if (logger.indexOf('.') > 0) {
                String owner = classOwner.apply(logger);
                if (owner != null) {
                    return owner;
                }
            }
        }
        java.util.regex.Matcher prefix = PREFIX.matcher(text);
        if (prefix.find() && knownPlugin.test(prefix.group(1))) {
            return prefix.group(1);
        }
        String named = ErrorCaptureHandler.pluginInMessage(text);
        return named != null && knownPlugin.test(named.trim()) ? named.trim() : null;
    }

    static String normalize(String text) {
        String cut = text.length() > MAX_FINGERPRINT_TEXT ? text.substring(0, MAX_FINGERPRINT_TEXT) : text;
        return VOLATILE.matcher(cut).replaceAll("#");
    }

    static long fingerprint(String level, String logger, String text, String exClass, StackTraceElement[] frames) {
        long h = fnv(fnv(fnv(FNV_OFFSET, level), logger == null ? "" : logger), normalize(text));
        if (exClass != null) {
            h = fnv(h, exClass);
        }
        if (frames != null) {
            for (int i = 0; i < Math.min(FINGERPRINT_FRAMES, frames.length); i++) {
                h = fnv(fnv(h, frames[i].getClassName()), frames[i].getMethodName());
            }
        }
        return h;
    }

    static long fingerprintText(String level, String logger, String header, String exClass, List<String> classes) {
        long h = fnv(fnv(fnv(FNV_OFFSET, level), logger == null ? "" : logger), normalize(header));
        if (exClass != null) {
            h = fnv(h, exClass);
        }
        for (int i = 0; i < Math.min(FINGERPRINT_FRAMES, classes.size()); i++) {
            h = fnv(h, classes.get(i));
        }
        return h;
    }

    private static final int MAX_DEFERRED_CLASSES = 48;

    /** Классы цепочки исключения для повторной атрибуции, без JDK. */
    private static List<String> classesOf(Throwable thrown) {
        List<String> out = new ArrayList<>();
        Throwable t = thrown;
        for (int depth = 0; t != null && depth < 8 && out.size() < MAX_DEFERRED_CLASSES; depth++, t = t.getCause()) {
            for (StackTraceElement frame : t.getStackTrace()) {
                String cls = frame.getClassName();
                if (!cls.startsWith("java.") && !cls.startsWith("jdk.") && !cls.startsWith("sun.")) {
                    out.add(cls);
                    if (out.size() >= MAX_DEFERRED_CLASSES) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;

    private static long fnv(long h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return (h ^ '|') * 0x100000001b3L;
    }

    /** Класс корневой причины: OutOfMemoryError под обёрткой важнее самой обёртки. */
    private static String rootClass(Throwable thrown) {
        Throwable t = thrown;
        for (int depth = 0; t.getCause() != null && t.getCause() != t && depth < 8; depth++) {
            t = t.getCause();
        }
        return t.getClass().getName();
    }

    /** «java.lang.IllegalStateException: boom» → «java.lang.IllegalStateException». */
    static String exceptionClassOf(String header) {
        String h = header.startsWith("Exception in thread ") ? header.replaceFirst("^Exception in thread \"[^\"]*\" ", "") : header;
        int colon = h.indexOf(':');
        String head = (colon > 0 ? h.substring(0, colon) : h).trim();
        return head.matches("[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+") ? head : null;
    }

    /** «\tat com.example.Foo.bar(Foo.java:1)» → «com.example.Foo». */
    static String frameClass(String line) {
        String t = line.trim();
        if (t.startsWith("at ")) {
            t = t.substring(3);
        }
        int paren = t.indexOf('(');
        // Кадр всегда «класс.метод(источник)»; в дампе потоков он без «at ».
        if (paren <= 0 || !t.endsWith(")") || t.indexOf(' ') >= 0 && t.indexOf(' ') < paren) {
            return null;
        }
        String method = t.substring(0, paren).trim();
        int slash = method.lastIndexOf('/');
        if (slash >= 0) {
            method = method.substring(slash + 1);
        }
        int dot = method.lastIndexOf('.');
        return dot > 0 ? method.substring(0, dot) : null;
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static final class Entry {
        final long fp;
        final long first;
        long last;
        int count;
        boolean main;
        boolean countOnly;
        String level;
        String logger;
        String plugin;
        String message;
        String trace;
        String exClass;
        String severity;
        String category;
        /** Плагин не найден сразу: текст и классы для повторной попытки при выгрузке */
        String attrText;
        List<String> classes = List.of();

        Entry(long fp, long ts) {
            this.fp = fp;
            this.first = ts;
            this.last = ts;
        }

        void deferred(String text, List<String> found) {
            attrText = text.length() > 300 ? text.substring(0, 300) : text;
            classes = found.size() > MAX_DEFERRED_CLASSES ? List.copyOf(found.subList(0, MAX_DEFERRED_CLASSES)) : List.copyOf(found);
        }

        String toJson() {
            StringBuilder sb = new StringBuilder(160 + (message == null ? 0 : message.length()) + (trace == null ? 0 : trace.length()));
            sb.append("{\"fp\":\"").append(String.format("%016x", fp)).append("\",\"lv\":\"").append(level).append('"');
            if (logger != null) {
                Json.str(sb.append(",\"lg\":"), logger, MAX_LOGGER);
            }
            if (plugin != null) {
                Json.str(sb.append(",\"p\":"), plugin, 64);
            }
            sb.append(",\"th\":\"").append(main ? "main" : "async").append("\",\"c\":").append(count)
                    .append(",\"f\":").append(first).append(",\"l\":").append(last);
            if (exClass != null) {
                Json.str(sb.append(",\"x\":"), exClass, 200);
            }
            if (severity != null) {
                sb.append(",\"src\":\"api\",\"sev\":\"").append(severity).append("\",\"cat\":\"").append(category).append('"');
            }
            if (message != null) {
                Json.str(sb.append(",\"m\":"), message, MAX_MESSAGE);
            }
            if (trace != null) {
                Json.str(sb.append(",\"t\":"), trace, StackTraceFormatter.MAX_CHARS);
            }
            return sb.append('}').toString();
        }
    }
}
