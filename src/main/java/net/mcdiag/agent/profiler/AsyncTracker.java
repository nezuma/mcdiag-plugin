package net.mcdiag.agent.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Нагрузка плагинов вне главного потока. Смотрит CPU-время потоков планировщика Paper и пулов.
 * Чей это код, обычно видно по имени потока, а имя читается без остановки JVM. Стеки снимаются
 * только у безымянных потоков и одним запросом: это одна остановка JVM вместо одной на поток.
 */
final class AsyncTracker {

    /** Paper подписывает асинхронные задачи: «Craft Scheduler Thread - 12 - LuckPerms» */
    private static final Pattern SCHEDULER = Pattern.compile("^Craft Scheduler Thread - \\d+(?: - (.+))?$");
    private static final String SCHEDULER_PREFIX = "Craft Scheduler Thread - ";
    private static final long REFRESH_THREADS_MS = 30_000;
    private static final double MIN_DELTA_MS = 0.2;
    private static final int STACK_DEPTH = 64;

    private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
    private final long mainThreadId;
    private final boolean supported;
    private long[] ids = new long[0];
    private long threadsRefreshedAt;
    private final Map<Long, Long> lastCpu = new HashMap<>();

    AsyncTracker(long mainThreadId) {
        this.mainThreadId = mainThreadId;
        boolean ok;
        try {
            ok = mx.isThreadCpuTimeSupported() && mx.isThreadCpuTimeEnabled();
        } catch (UnsupportedOperationException e) {
            ok = false;
        }
        this.supported = ok;
    }

    boolean supported() {
        return supported;
    }

    /** Добавляет в окно CPU-время асинхронных потоков, ушедшее в плагины с прошлого вызова. */
    void collect(ProfileWindow window, StackAttributor attributor, String[] pluginNames, long now) {
        if (!supported) {
            return;
        }
        if (now - threadsRefreshedAt > REFRESH_THREADS_MS) {
            refreshThreads(pluginNames);
            threadsRefreshedAt = now;
        }
        long[] active = new long[ids.length];
        double[] deltas = new double[ids.length];
        int count = 0;
        for (long id : ids) {
            long cpu = mx.getThreadCpuTime(id);
            if (cpu < 0) {
                continue;
            }
            Long prev = lastCpu.put(id, cpu);
            if (prev == null) {
                continue;
            }
            double deltaMs = (cpu - prev) / 1_000_000.0;
            if (deltaMs >= MIN_DELTA_MS) {
                active[count] = id;
                deltas[count++] = deltaMs;
            }
        }
        if (count == 0) {
            return;
        }
        // Глубина 0: имена читаются без safepoint.
        ThreadInfo[] named = mx.getThreadInfo(Arrays.copyOf(active, count), 0);
        long[] unknown = new long[count];
        double[] unknownDeltas = new double[count];
        int unresolved = 0;
        for (int i = 0; i < count; i++) {
            ThreadInfo info = named[i];
            if (info == null) {
                continue;
            }
            String plugin = pluginFromName(info.getThreadName(), pluginNames);
            if (plugin != null) {
                window.addAsync(plugin, deltas[i]);
            } else {
                unknown[unresolved] = active[i];
                unknownDeltas[unresolved++] = deltas[i];
            }
        }
        if (unresolved == 0) {
            return;
        }
        ThreadInfo[] stacks = mx.getThreadInfo(Arrays.copyOf(unknown, unresolved), STACK_DEPTH);
        for (int i = 0; i < unresolved; i++) {
            String plugin = stacks[i] == null ? null : attributor.pluginIn(stacks[i].getStackTrace());
            if (plugin != null) {
                window.addAsync(plugin, unknownDeltas[i]);
            }
        }
    }

    private void refreshThreads(String[] pluginNames) {
        long[] all = mx.getAllThreadIds();
        ThreadInfo[] infos = mx.getThreadInfo(all, 0);
        long[] next = new long[all.length];
        int n = 0;
        for (ThreadInfo info : infos) {
            if (info == null || info.getThreadId() == mainThreadId) {
                continue;
            }
            String name = info.getThreadName();
            if (SCHEDULER.matcher(name).matches() || name.startsWith("ForkJoinPool")
                    || pluginFromName(name, pluginNames) != null) {
                next[n++] = info.getThreadId();
            }
        }
        ids = Arrays.copyOf(next, n);
        Set<Long> alive = new HashSet<>(n * 2);
        for (long id : ids) {
            alive.add(id);
        }
        lastCpu.keySet().retainAll(alive);
    }

    /** Плагин по имени потока: подпись планировщика Paper или имя, начинающееся с имени плагина. */
    static String pluginFromName(String threadName, String[] pluginNames) {
        if (threadName.startsWith(SCHEDULER_PREFIX)) {
            Matcher m = SCHEDULER.matcher(threadName);
            if (m.matches()) {
                String owner = m.group(1);
                return owner != null && ProfileWindow.validName(owner) ? owner : null;
            }
        }
        for (String plugin : pluginNames) {
            if (plugin.length() >= 4 && threadName.regionMatches(true, 0, plugin, 0, plugin.length())) {
                return plugin;
            }
        }
        return null;
    }
}
