package net.mcdiag.agent.logs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.mcdiag.agent.collect.LogRing;
import net.mcdiag.api.Severity;

/**
 * Точка входа для всех строк лога уровня WARN и выше, откуда бы они ни пришли: Log4j-appender или
 * JUL-хендлер. Здесь только быстрые проверки и передача в {@link LogAggregator} и кольцо строк для
 * лаг-спайков.
 *
 * <p>Многострочный вывод приходит по строке: {@code printStackTrace} через System.err, дамп потоков
 * watchdog, трейсы, которые ядро печатает построчно. Строки-продолжения (с отступом, «at …»,
 * «Caused by») одного потока склеиваются обратно в одну запись; дамп watchdog склеивается целиком.
 *
 * <p>Вызывается в потоке того, кто пишет в лог, в том числе в главном.
 */
public final class LogCapture {

    /** Строки одного потока, пришедшие подряд с таким промежутком, считаются одним сообщением. */
    static final long LINE_GAP_MS = 250;
    /** Дамп потоков печатается дольше: ядро пишет сотни строк. */
    static final long DUMP_GAP_MS = 2_000;
    /** Строки одного логгера и потока «пачкой» — это одно многострочное сообщение (предупреждение ядра в 4 строки). */
    static final long BURST_MS = 15;
    private static final int HEAD = 20;
    private static final int TAIL = 15;
    /** В дампе потоков важна голова: стек главного потока и кадр плагина, который его держит. */
    private static final int DUMP_HEAD = 45;
    private static final int DUMP_TAIL = 5;
    private static final int MAX_CLASSES = 48;
    private static final int MAX_OPEN = 8;
    private static final Pattern DUMP = Pattern.compile(
            "has not responded for|thread dump|Thread Dump|Paper Watchdog|DO NOT REPORT THIS|Current Thread:");

    private final LogAggregator aggregator;
    private final String agentName;
    private final long mainThreadId;
    private volatile boolean accepting = true;
    private volatile boolean errorsOnly;
    private volatile LogRing ring;
    private volatile String source = "none";

    /** Незакрытые многострочные сообщения по потокам. */
    private final Map<Long, Block> open = new LinkedHashMap<>();

    public LogCapture(LogAggregator aggregator, String agentName, long mainThreadId) {
        this.aggregator = aggregator;
        this.agentName = agentName;
        this.mainThreadId = mainThreadId;
    }

    /**
     * @param level {@code WARN}, {@code ERROR} или {@code FATAL}
     */
    public void accept(long ts, String level, String logger, long threadId, String message, Throwable thrown) {
        LogRing currentRing = ring;
        if (!accepting && currentRing == null) {
            return;
        }
        if (logger != null && (logger.equals(agentName) || logger.startsWith("net.mcdiag"))) {
            return;
        }
        String text = message == null ? "" : message;
        boolean main = threadId == mainThreadId;
        if (currentRing != null && !text.isBlank() && !startsIndented(text)) {
            currentRing.add(ts, level.equals("WARN") ? "WARNING" : "SEVERE", logger, text);
        }
        if (!accepting || (errorsOnly && level.equals("WARN"))) {
            return;
        }
        String stderr = stderrLine(logger, text);
        String owner = stderr != null ? stderrOwner(logger, text) : logger;
        String line = stderr != null ? stderr : text;
        if (thrown != null) {
            // Исключение пришло объектом: строка самодостаточна, незакрытое сообщение потока закрываем.
            synchronized (this) {
                close(open.remove(threadId));
            }
            aggregator.add(ts, level, owner, main, line, thrown);
            return;
        }
        lines(ts, level, owner, threadId, main, line);
    }

    /** Проблема из публичного API. */
    public void issue(String plugin, Severity severity, String category, String message) {
        if (accepting) {
            aggregator.addIssue(System.currentTimeMillis(), plugin, severity, category, message);
        }
    }

    /** Записи для раздела {@code logs}; затихшие многострочные сообщения закрываются. */
    public List<String> drain(long now) {
        synchronized (this) {
            for (Iterator<Block> it = open.values().iterator(); it.hasNext(); ) {
                Block b = it.next();
                if (now - b.last > b.gap()) {
                    it.remove();
                    close(b);
                }
            }
        }
        return aggregator.drain();
    }

    public void setAccepting(boolean accepting) {
        this.accepting = accepting;
        if (!accepting) {
            synchronized (this) {
                open.clear();
            }
            aggregator.clear();
        }
    }

    public boolean accepting() {
        return accepting;
    }

    public void setErrorsOnly(boolean errorsOnly) {
        this.errorsOnly = errorsOnly;
    }

    /** Кольцо строк для лаг-спайков питается отсюда, если перехват лога работает. */
    public void setRing(LogRing ring) {
        this.ring = ring;
    }

    public LogAggregator aggregator() {
        return aggregator;
    }

    /** {@code log4j}, {@code jul} или {@code none}: для /diag modules. */
    public String source() {
        return source;
    }

    void setSource(String source) {
        this.source = source;
    }

    // ── Многострочные сообщения ─────────────────────────────────────────

    /** Строка, которую ядро перехватило из System.err, без служебного префикса, или null. */
    static String stderrLine(String logger, String text) {
        if ("STDERR".equals(logger)) {
            return text;
        }
        int at = text.indexOf("[STDERR] ");
        if (at == 0 || (at > 0 && text.charAt(0) == '[' && text.charAt(at - 2) == ']')) {
            return text.substring(at + "[STDERR] ".length());
        }
        return null;
    }

    /** «[SomePlugin] [STDERR] …» — Paper подписывает вывод плагина его именем. */
    static String stderrOwner(String logger, String text) {
        if (text.startsWith("[")) {
            int close = text.indexOf("] ");
            int at = text.indexOf("[STDERR] ");
            if (close > 1 && close < at) {
                return text.substring(1, close);
            }
        }
        return logger;
    }

    static boolean isContinuation(String line) {
        if (startsIndented(line)) {
            return true;
        }
        String t = line.trim();
        return t.startsWith("at ") || t.startsWith("Caused by: ") || t.startsWith("Suppressed: ")
                || (t.startsWith("... ") && t.endsWith(" more"));
    }

    /**
     * Многострочные предупреждения печатает ядро; у плагинов подряд идущие строки — обычно разные
     * сообщения, и склеивать их нельзя.
     */
    static boolean coreLogger(String logger) {
        return logger == null || logger.isEmpty() || logger.equals("Minecraft") || logger.equals("STDERR")
                || logger.startsWith("net.minecraft.") || logger.startsWith("org.bukkit.") || logger.startsWith("io.papermc.");
    }

    private static boolean startsIndented(String line) {
        return !line.isEmpty() && (line.charAt(0) == '\t' || line.charAt(0) == ' ');
    }

    private synchronized void lines(long ts, String level, String logger, long threadId, boolean main, String line) {
        Block block = open.get(threadId);
        if (block != null && block.accepts(ts, logger, line)) {
            block.add(line, ts);
            return;
        }
        if (block != null) {
            open.remove(threadId);
            close(block);
        }
        if (open.size() >= MAX_OPEN) {
            Iterator<Block> it = open.values().iterator();
            Block oldest = it.next();
            it.remove();
            close(oldest);
        }
        open.put(threadId, new Block(ts, level, logger, main, line, DUMP.matcher(line).find()));
    }

    private void close(Block block) {
        if (block == null) {
            return;
        }
        if (block.total == 0) {
            aggregator.add(block.first, block.level, block.logger, block.main, block.header, null);
        } else {
            aggregator.addLines(block.first, block.level, block.logger, block.main, block.header, block.lines(), block.classes);
        }
    }

    /** Голова и хвост без середины: как {@link net.mcdiag.agent.collect.StackTraceFormatter}. */
    private static final class Block {
        final long first;
        final String level;
        final String logger;
        final boolean main;
        String header;
        final boolean dump;
        final List<String> head;
        final ArrayDeque<String> tail;
        /** Классы из всех строк, а не только из головы и хвоста: по ним ищется плагин. */
        final List<String> classes = new ArrayList<>();
        long last;
        int total;

        Block(long ts, String level, String logger, boolean main, String header, boolean dump) {
            this.first = ts;
            this.last = ts;
            this.level = level;
            this.logger = logger;
            this.main = main;
            this.header = header;
            this.dump = dump;
            this.head = new ArrayList<>(dump ? DUMP_HEAD : HEAD);
            this.tail = new ArrayDeque<>(dump ? DUMP_TAIL : TAIL);
        }

        long gap() {
            return dump ? DUMP_GAP_MS : LINE_GAP_MS;
        }

        boolean accepts(long ts, String lineLogger, String line) {
            boolean sameLogger = logger == null ? lineLogger == null : logger.equals(lineLogger);
            return sameLogger && ts - last <= gap()
                    && (dump || isContinuation(line) || (coreLogger(logger) && ts - last <= BURST_MS));
        }

        void add(String line, long ts) {
            last = ts;
            // Leaf печатает дамп с баннера «DO NOT REPORT»: заголовком делаем саму суть.
            if (dump && !header.contains("has not responded") && line.contains("has not responded")) {
                String banner = header;
                header = line;
                line = banner;
            }
            total++;
            String clipped = line.length() > 300 ? line.substring(0, 300) : line;
            if (classes.size() < MAX_CLASSES) {
                String cls = LogAggregator.frameClass(clipped);
                if (cls != null && !cls.startsWith("java.") && !cls.startsWith("jdk.") && !cls.startsWith("sun.")) {
                    classes.add(cls);
                }
            }
            if (head.size() < (dump ? DUMP_HEAD : HEAD)) {
                head.add(clipped);
                return;
            }
            if (tail.size() == (dump ? DUMP_TAIL : TAIL)) {
                tail.pollFirst();
            }
            tail.addLast(clipped);
        }

        List<String> lines() {
            List<String> out = new ArrayList<>(head);
            int omitted = total - head.size() - tail.size();
            if (omitted > 0) {
                out.add("\t... " + omitted + " lines omitted ...");
            }
            out.addAll(tail);
            return out;
        }
    }
}
