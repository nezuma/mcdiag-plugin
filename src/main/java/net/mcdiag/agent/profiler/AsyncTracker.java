package net.mcdiag.agent.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Нагрузка плагинов вне главного потока. Раз в секунду смотрит CPU-время потоков планировщика
 * Paper и пулов, и только у тех, что успели поработать, снимает стек — чтобы понять, чей код.
 */
final class AsyncTracker {

    /** Paper подписывает асинхронные задачи: «Craft Scheduler Thread - 12 - LuckPerms» */
    private static final Pattern SCHEDULER = Pattern.compile("^Craft Scheduler Thread - \\d+(?: - (.+))?$");
    private static final long REFRESH_THREADS_MS = 10_000;
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
            if (deltaMs < MIN_DELTA_MS) {
                continue;
            }
            ThreadInfo info = mx.getThreadInfo(id, STACK_DEPTH);
            if (info == null) {
                continue;
            }
            String plugin = attributor.pluginIn(info.getStackTrace());
            if (plugin == null) {
                plugin = pluginFromName(info.getThreadName(), pluginNames);
            }
            if (plugin != null) {
                window.addAsync(plugin, deltaMs);
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
        ids = java.util.Arrays.copyOf(next, n);
        lastCpu.keySet().retainAll(java.util.Arrays.stream(ids).boxed().toList());
    }

    /** Плагин по имени потока: подпись планировщика Paper или имя, начинающееся с имени плагина. */
    static String pluginFromName(String threadName, String[] pluginNames) {
        Matcher m = SCHEDULER.matcher(threadName);
        if (m.matches()) {
            String owner = m.group(1);
            return owner != null && ProfileWindow.validName(owner) ? owner : null;
        }
        String lower = threadName.toLowerCase(Locale.ROOT);
        for (String plugin : pluginNames) {
            if (plugin.length() >= 4 && lower.startsWith(plugin.toLowerCase(Locale.ROOT))) {
                return plugin;
            }
        }
        return null;
    }
}
