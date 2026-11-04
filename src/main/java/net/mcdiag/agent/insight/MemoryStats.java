package net.mcdiag.agent.insight;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import net.mcdiag.agent.json.Json;

/**
 * Куда уходит память всего процесса, а не только куча. Раз в минуту из MXBean и /proc: ни один
 * вызов здесь не останавливает JVM. Элемент поля {@code memory} батча.
 */
public final class MemoryStats {

    private static final long MB = 1024 * 1024;
    private static final int TOP = 10;
    private static final Path STATUS = Path.of("/proc/self/status");
    private static final Pattern PROC_KB = Pattern.compile("^(VmRSS|RssAnon|RssFile):\\s+(\\d+) kB", Pattern.MULTILINE);
    /** «-   Java Heap (reserved=4096MB, committed=4096MB)» в отчёте NMT */
    private static final Pattern NMT_LINE = Pattern.compile(
            "^-\\s+(.+?) \\(reserved=\\d+MB, committed=(\\d+)MB\\)", Pattern.MULTILINE);
    private static final long NMT_PERIOD_MS = 5 * 60_000;

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final boolean nmtEnabled = nmtEnabled();
    private long nmtAt;

    private static boolean nmtEnabled() {
        try {
            var hotspot = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            return hotspot != null && !"off".equals(hotspot.getVMOption("NativeMemoryTracking").getValue());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * @param threadOwner имя потока → плагин или null
     * @param gc          итог GC за минуту или null
     * @param alloc       итог выделений за минуту или null, если JFR нет
     */
    public String snapshotJson(long ts, Function<String, String> threadOwner, GcWatch.Minute gc, JfrInsight.Allocations alloc) {
        StringBuilder sb = new StringBuilder(1024).append("{\"ts\":").append(ts);
        appendProc(sb);
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        sb.append(",\"heap\":").append(heap.getUsed() / MB).append(",\"heapCommitted\":").append(heap.getCommitted() / MB);
        if (heap.getMax() > 0) {
            sb.append(",\"heapMax\":").append(heap.getMax() / MB);
        }
        long live = 0;
        long meta = 0;
        long classSpace = 0;
        long code = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                MemoryUsage after = pool.getCollectionUsage();
                if (after != null) {
                    live += after.getUsed();
                }
            } else {
                long committed = pool.getUsage().getCommitted();
                String name = pool.getName();
                if (name.equals("Metaspace")) {
                    meta = committed;
                } else if (name.contains("Class Space")) {
                    classSpace = committed;
                } else {
                    code += committed;
                }
            }
        }
        sb.append(",\"live\":").append(live / MB).append(",\"meta\":").append(meta / MB)
                .append(",\"classSpace\":").append(classSpace / MB).append(",\"code\":").append(code / MB);
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (pool.getName().equals("direct")) {
                sb.append(",\"direct\":").append(pool.getMemoryUsed() / MB);
            } else if (pool.getName().equals("mapped")) {
                sb.append(",\"mapped\":").append(pool.getMemoryUsed() / MB);
            }
        }
        var classes = ManagementFactory.getClassLoadingMXBean();
        sb.append(",\"classes\":").append(classes.getLoadedClassCount())
                .append(",\"threads\":").append(threads.getThreadCount());
        appendThreads(sb, threadOwner);
        if (gc != null) {
            sb.append(",\"gc\":{\"c\":").append(gc.count()).append(",\"ms\":").append(gc.totalMs())
                    .append(",\"max\":").append(gc.maxMs()).append('}');
        }
        if (alloc != null) {
            sb.append(",\"alloc\":{\"plugins\":");
            appendTop(sb, alloc.plugins(), "n");
            sb.append(",\"classes\":");
            appendTop(sb, alloc.classes(), "c");
            sb.append('}');
        }
        appendNmt(sb, ts);
        return sb.append('}').toString();
    }

    private void appendProc(StringBuilder sb) {
        if (!Files.isReadable(STATUS)) {
            return;
        }
        try {
            appendProcText(sb, Files.readString(STATUS));
        } catch (IOException | RuntimeException e) {
            // Нет /proc — значит нет и этих полей.
        }
    }

    static void appendProcText(StringBuilder sb, String status) {
        Matcher m = PROC_KB.matcher(status);
        while (m.find()) {
            String key = switch (m.group(1)) {
                case "VmRSS" -> "rss";
                case "RssAnon" -> "rssAnon";
                default -> "rssFile";
            };
            sb.append(",\"").append(key).append("\":").append(Long.parseLong(m.group(2)) / 1024);
        }
    }

    /** Глубина 0: имена потоков читаются без остановки JVM. */
    private void appendThreads(StringBuilder sb, Function<String, String> threadOwner) {
        Map<String, Long> byPlugin = new HashMap<>();
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
            if (info != null) {
                String owner = threadOwner.apply(info.getThreadName());
                if (owner != null) {
                    byPlugin.merge(owner, 1L, Long::sum);
                }
            }
        }
        if (byPlugin.isEmpty()) {
            return;
        }
        sb.append(",\"threadsBy\":[");
        List<Map.Entry<String, Long>> list = sorted(byPlugin);
        for (int i = 0; i < list.size(); i++) {
            Json.str(sb.append(i > 0 ? ",{\"n\":" : "{\"n\":"), list.get(i).getKey(), 64).append(",\"c\":").append(list.get(i).getValue()).append('}');
        }
        sb.append(']');
    }

    private static void appendTop(StringBuilder sb, Map<String, Long> map, String key) {
        List<Map.Entry<String, Long>> list = sorted(map);
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            sb.append(i > 0 ? ",{\"" : "{\"").append(key).append("\":");
            Json.str(sb, list.get(i).getKey(), 120).append(",\"b\":").append(list.get(i).getValue()).append('}');
        }
        sb.append(']');
    }

    private static List<Map.Entry<String, Long>> sorted(Map<String, Long> map) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list.size() > TOP ? list.subList(0, TOP) : list;
    }

    /** Нативная память подробно видна, только если сервер запущен с -XX:NativeMemoryTracking. */
    private void appendNmt(StringBuilder sb, long ts) {
        if (!nmtEnabled || ts - nmtAt < NMT_PERIOD_MS) {
            return;
        }
        nmtAt = ts;
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            String report = (String) server.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "vmNativeMemory", new Object[] {new String[] {"summary", "scale=MB"}},
                    new String[] {String[].class.getName()});
            appendNmtText(sb, report);
        } catch (Exception | LinkageError e) {
            // Без этого отчёта карта памяти просто менее подробная.
        }
    }

    static void appendNmtText(StringBuilder sb, String report) {
        Map<String, Long> committed = new HashMap<>();
        Matcher m = NMT_LINE.matcher(report);
        while (m.find()) {
            committed.put(m.group(1).trim(), Long.parseLong(m.group(2)));
        }
        if (committed.isEmpty()) {
            return;
        }
        sb.append(",\"nmt\":");
        List<Map.Entry<String, Long>> list = sorted(committed);
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            Json.str(sb.append(i > 0 ? ",{\"n\":" : "{\"n\":"), list.get(i).getKey(), 64).append(",\"mb\":").append(list.get(i).getValue()).append('}');
        }
        sb.append(']');
    }
}
