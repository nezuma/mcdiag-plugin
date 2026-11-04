package net.mcdiag.agent.insight;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

/**
 * Что JFR, встроенный в Java, видит без остановки JVM: кто выделяет память, долгий ввод-вывод
 * в главном потоке и остановки JVM не для сборки мусора. События приходят с задержкой 1–2 секунды.
 * Если JFR в этой сборке Java нет или он начал тратить слишком много, агент работает без него.
 */
public final class JfrInsight {

    /** Выше этой доли одного ядра JFR выключается: диагностика не должна стать нагрузкой */
    private static final double MAX_CPU_PCT = 3;
    private static final int MAX_ERRORS = 100;
    private static final int IO_RING = 128;
    private static final int VM_RING = 64;
    /** Защита от потока уникальных классов */
    private static final int MAX_KEYS = 512;
    private static final String THROTTLE = "50/s";
    private static final Duration SLOW_IO = Duration.ofMillis(10);
    private static final Duration SLOW_VM = Duration.ofMillis(10);

    /** Долгий ввод-вывод в главном потоке. */
    public record Io(long startMs, long ms, String kind, String target, String plugin) {
    }

    /** Остановка JVM не для сборки мусора. */
    public record VmStop(long startMs, long ms, String operation, String caller) {
    }

    /** Итог выделений за минуту: байты по плагинам и классам. */
    public record Allocations(Map<String, Long> plugins, Map<String, Long> classes) {
    }

    private final Logger log;
    private final long mainThreadId;
    private final Function<String, String> pluginOfClass;
    private final BiFunction<String, String[], String> pluginOfThread;
    private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();

    private final ArrayDeque<Io> io = new ArrayDeque<>(IO_RING);
    private final ArrayDeque<VmStop> vm = new ArrayDeque<>(VM_RING);
    private Map<String, Long> allocByPlugin = new HashMap<>();
    private Map<String, Long> allocByClass = new HashMap<>();
    private volatile String[] pluginNames = new String[0];

    private volatile RecordingStream stream;
    private volatile long streamThreadId = -1;
    private long lastCpuNs = -1;
    private long lastCheckAt;
    private int errors;

    public JfrInsight(Logger log, long mainThreadId, Function<String, String> pluginOfClass,
                      BiFunction<String, String[], String> pluginOfThread) {
        this.log = log;
        this.mainThreadId = mainThreadId;
        this.pluginOfClass = pluginOfClass;
        this.pluginOfThread = pluginOfThread;
    }

    public boolean active() {
        return stream != null;
    }

    public void setPluginNames(String[] names) {
        pluginNames = names;
    }

    public void start() {
        RecordingStream rs = new RecordingStream();
        boolean started = false;
        try {
            rs.setMaxAge(Duration.ofSeconds(15));
            rs.setMaxSize(16L * 1024 * 1024);
            rs.enable("jdk.ObjectAllocationSample").with("throttle", THROTTLE).withStackTrace();
            for (String event : new String[] {"jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite"}) {
                rs.enable(event).withThreshold(SLOW_IO).withStackTrace();
            }
            rs.enable("jdk.ExecuteVMOperation").withThreshold(SLOW_VM);
            rs.onEvent(this::onEvent);
            rs.startAsync();
            stream = rs;
            started = true;
        } finally {
            if (!started) {
                rs.close();
            }
        }
    }

    public void stop() {
        RecordingStream rs = stream;
        stream = null;
        if (rs != null) {
            rs.close();
        }
    }

    /** Раз в минуту: сколько процессора съел поток разбора событий. */
    public void checkHealth(long nowMs) {
        long id = streamThreadId;
        if (id < 0 || stream == null) {
            return;
        }
        long cpu = mx.getThreadCpuTime(id);
        if (cpu < 0) {
            return;
        }
        if (lastCpuNs >= 0 && nowMs > lastCheckAt) {
            double pct = (cpu - lastCpuNs) / 1_000_000.0 / (nowMs - lastCheckAt) * 100;
            if (pct > MAX_CPU_PCT) {
                log.info("JFR insight turned off: it used too much CPU");
                stop();
                return;
            }
        }
        lastCpuNs = cpu;
        lastCheckAt = nowMs;
    }

    private void onEvent(RecordedEvent event) {
        streamThreadId = Thread.currentThread().getId();
        try {
            String name = event.getEventType().getName();
            switch (name) {
                case "jdk.ObjectAllocationSample" -> onAllocation(event);
                case "jdk.FileRead", "jdk.FileWrite" -> onIo(event, "disk", fileName(event.getString("path")));
                case "jdk.SocketRead", "jdk.SocketWrite" -> onIo(event, "network", ":" + event.getInt("port"));
                case "jdk.ExecuteVMOperation" -> onVm(event);
                default -> {
                    // Других событий мы не включали.
                }
            }
        } catch (RuntimeException e) {
            if (++errors >= MAX_ERRORS) {
                log.log(Level.FINE, "JFR insight turned off after errors", e);
                // Закрывать поток изнутри его же обработчика нельзя.
                Thread stopper = new Thread(this::stop, "MCDiag JFR stop");
                stopper.setDaemon(true);
                stopper.start();
            }
        }
    }

    private void onAllocation(RecordedEvent event) {
        long weight = event.getLong("weight");
        String cls = readableClass(event.getClass("objectClass").getName());
        String plugin = ownerOf(event.getStackTrace());
        if (plugin == null) {
            RecordedThread thread = event.getThread("eventThread");
            plugin = thread == null || thread.getJavaName() == null ? null
                    : pluginOfThread.apply(thread.getJavaName(), pluginNames);
        }
        synchronized (this) {
            add(allocByClass, cls, weight);
            add(allocByPlugin, plugin == null ? "~server" : plugin, weight);
        }
    }

    private static void add(Map<String, Long> map, String key, long value) {
        if (map.size() < MAX_KEYS || map.containsKey(key)) {
            map.merge(key, value, Long::sum);
        }
    }

    private void onIo(RecordedEvent event, String kind, String target) {
        RecordedThread thread = event.getThread("eventThread");
        if (thread == null || thread.getJavaThreadId() != mainThreadId) {
            return;
        }
        Io item = new Io(event.getStartTime().toEpochMilli(), event.getDuration().toMillis(), kind, target,
                ownerOf(event.getStackTrace()));
        synchronized (this) {
            if (io.size() >= IO_RING) {
                io.pollFirst();
            }
            io.addLast(item);
        }
    }

    private void onVm(RecordedEvent event) {
        String operation = event.getString("operation");
        // Сборку мусора показывает GcWatch.
        if (!event.getBoolean("safepoint") || operation == null || isGcOperation(operation)) {
            return;
        }
        RecordedThread caller = event.getThread("caller");
        VmStop item = new VmStop(event.getStartTime().toEpochMilli(), event.getDuration().toMillis(), operation,
                caller == null ? null : caller.getJavaName());
        synchronized (this) {
            if (vm.size() >= VM_RING) {
                vm.pollFirst();
            }
            vm.addLast(item);
        }
    }

    static boolean isGcOperation(String operation) {
        return operation.contains("Collect") || operation.contains("GC") || operation.startsWith("Z")
                || operation.startsWith("Shenandoah") || operation.startsWith("G1");
    }

    private String ownerOf(RecordedStackTrace stack) {
        if (stack == null) {
            return null;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            if (frame.getMethod() == null || frame.getMethod().getType() == null) {
                continue;
            }
            String plugin = pluginOfClass.apply(frame.getMethod().getType().getName());
            if (plugin != null) {
                return plugin;
            }
        }
        return null;
    }

    /** Только имя файла: полный путь в панели не нужен. */
    static String fileName(String path) {
        if (path == null) {
            return "?";
        }
        int at = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return at >= 0 ? path.substring(at + 1) : path;
    }

    /** «[B» → «byte[]», «[Ljava.lang.String;» → «java.lang.String[]». */
    static String readableClass(String jvmName) {
        if (jvmName == null || jvmName.isEmpty()) {
            return "?";
        }
        int dims = 0;
        while (dims < jvmName.length() && jvmName.charAt(dims) == '[') {
            dims++;
        }
        if (dims == 0) {
            return jvmName;
        }
        String base = switch (jvmName.substring(dims)) {
            case "B" -> "byte";
            case "C" -> "char";
            case "I" -> "int";
            case "J" -> "long";
            case "S" -> "short";
            case "Z" -> "boolean";
            case "F" -> "float";
            case "D" -> "double";
            default -> {
                String rest = jvmName.substring(dims);
                yield rest.startsWith("L") && rest.endsWith(";") ? rest.substring(1, rest.length() - 1) : rest;
            }
        };
        return base + "[]".repeat(dims);
    }

    public synchronized List<Io> ioOverlapping(long fromMs, long toMs) {
        List<Io> out = new ArrayList<>(2);
        for (Io item : io) {
            if (item.startMs() <= toMs && item.startMs() + item.ms() >= fromMs) {
                out.add(item);
            }
        }
        return out;
    }

    public synchronized List<VmStop> vmOverlapping(long fromMs, long toMs) {
        List<VmStop> out = new ArrayList<>(2);
        for (VmStop item : vm) {
            if (item.startMs() <= toMs && item.startMs() + item.ms() >= fromMs) {
                out.add(item);
            }
        }
        return out;
    }

    public synchronized Allocations drainAllocations() {
        Allocations result = new Allocations(allocByPlugin, allocByClass);
        allocByPlugin = new HashMap<>();
        allocByClass = new HashMap<>();
        return result;
    }
}
