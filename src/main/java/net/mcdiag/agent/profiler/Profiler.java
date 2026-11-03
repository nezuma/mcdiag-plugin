package net.mcdiag.agent.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.json.Json;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Сэмплирующий профилировщик главного потока, по принципу spark.
 *
 * <p>Отдельный daemon-поток раз в {@code intervalMs} снимает стек главного потока и записывает
 * интервал на плагин, чей код ближе всего к вершине стека ({@link StackAttributor}). Главный поток
 * при этом ничего не делает, кроме двух событий тика ({@link TickMonitor}). Раз в минуту итог
 * уходит в буфер одним JSON-срезом ~1–3 KB.
 *
 * <p>Это оценка по сэмплам: плагин, работающий реже интервала, может не попасть в срез вовсе.
 */
public final class Profiler implements Runnable {

    private static final long MINUTE_MS = 60_000;
    private static final int STACK_DEPTH = 128;
    /** Сэмплы последних ~20 секунд — чтобы разобрать, кто занимал долгий тик */
    private static final int RING = 1024;
    private static final long ASYNC_PERIOD_MS = 1_000;
    private static final long INVENTORY_PERIOD_MS = 30 * MINUTE_MS;
    private static final long REINDEX_PERIOD_MS = 10 * MINUTE_MS;
    private static final int RECENT_SPIKES = 10;

    private final McDiagPlugin plugin;
    private final Thread mainThread;
    private final TickMonitor ticks;
    private final long intervalMs;
    private final boolean asyncEnabled;
    private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
    private final PluginLookup lookup;
    private final StackAttributor attributor;
    private final AsyncTracker async;
    private final JvmStats jvm = new JvmStats();

    private final long[] ringTick = new long[RING];
    private final String[] ringOwner = new String[RING];
    private int ringPos;

    private volatile boolean running = true;
    private Thread thread;
    private ProfileWindow window;
    private String[] pluginNames = new String[0];
    private int pluginCount = -1;
    private long reindexedAt;
    private long inventoryAt;
    private int inventoryHash;
    private final List<SpikeRecord> windowSpikes = new ArrayList<>();

    // Для /diag plugins и /diag spikes: пишет поток профилировщика, читают команды.
    private volatile Summary last;
    private final ArrayDeque<SpikeRecord> recentSpikes = new ArrayDeque<>();
    private volatile long selfCpuNs;
    private volatile long startedAt;

    public Profiler(McDiagPlugin plugin, Thread mainThread, TickMonitor ticks, long intervalMs, boolean asyncEnabled) {
        this.plugin = plugin;
        this.mainThread = mainThread;
        this.ticks = ticks;
        this.intervalMs = intervalMs;
        this.asyncEnabled = asyncEnabled;
        this.lookup = new PluginLookup(plugin.getLogger());
        this.attributor = new StackAttributor(lookup);
        this.async = new AsyncTracker(mainThread.getId());
    }

    public void start() {
        startedAt = System.currentTimeMillis();
        thread = new Thread(this, "MCDiag Profiler");
        thread.setDaemon(true);
        // Ниже обычного: профилировщик не должен отнимать процессор у сервера.
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        long prev = System.nanoTime();
        long nextAsync = 0;
        while (running) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                break;
            }
            try {
                long now = System.nanoTime();
                // Вес сэмпла — реально прошедшее время (sleep неточен), но не больше 5 интервалов:
                // после паузы JVM один сэмпл не должен «съесть» минуту.
                double weightMs = Math.min((now - prev) / 1_000_000.0, intervalMs * 5.0);
                prev = now;
                long wall = System.currentTimeMillis();
                ensureWindow(wall);
                sample(weightMs);
                drainSpikes();
                if (asyncEnabled && wall >= nextAsync) {
                    async.collect(window, attributor, pluginNames, wall);
                    nextAsync = wall + ASYNC_PERIOD_MS;
                }
                selfCpuNs = mx.isCurrentThreadCpuTimeSupported() ? mx.getCurrentThreadCpuTime() : -1;
            } catch (RuntimeException | LinkageError e) {
                // Профилировщик никогда не должен ронять сервер или свой поток.
                plugin.getLogger().log(Level.FINE, "profiler sample failed", e);
            }
        }
    }

    private void sample(double weightMs) {
        boolean inTick = ticks.inTick();
        long tick = ticks.tickSeq();
        ThreadInfo info = mx.getThreadInfo(mainThread.getId(), STACK_DEPTH);
        if (info == null) {
            return;
        }
        StackTraceElement[] stack = info.getStackTrace();
        // Между тиками главный поток спит до следующего — это не нагрузка.
        if (!inTick && FrameClassifier.isWaiting(info.getThreadState(), stack.length > 0 ? stack[0] : null)) {
            window.addIdle(weightMs);
            return;
        }
        String owner = attributor.attribute(stack);
        window.addBusy(owner, weightMs);
        if (inTick) {
            ringTick[ringPos] = tick;
            ringOwner[ringPos] = owner;
            ringPos = (ringPos + 1) % RING;
        }
    }

    /** Долгие тики: кто занимал главный поток по сэмплам именно этого тика. */
    private void drainSpikes() {
        TickMonitor.Spike spike;
        while ((spike = ticks.pollSpike()) != null) {
            List<String> owners = new ArrayList<>();
            for (int i = 0; i < RING; i++) {
                if (ringTick[i] == spike.tick() && ringOwner[i] != null) {
                    owners.add(ringOwner[i]);
                }
            }
            SpikeRecord record = owners.isEmpty()
                    ? new SpikeRecord(spike.startedAt(), spike.ms(), List.of())
                    : SpikeRecord.of(spike.startedAt(), spike.ms(), owners);
            if (windowSpikes.size() < 20) {
                windowSpikes.add(record);
            }
            synchronized (recentSpikes) {
                if (recentSpikes.size() >= RECENT_SPIKES) {
                    recentSpikes.pollFirst();
                }
                recentSpikes.addLast(record);
            }
        }
    }

    private void ensureWindow(long wall) {
        long minute = wall / MINUTE_MS * MINUTE_MS;
        if (window == null) {
            refreshPlugins(wall);
            ticks.drain();
            window = new ProfileWindow(minute);
            return;
        }
        if (minute == window.startMs()) {
            return;
        }
        flush();
        refreshPlugins(wall);
        window = new ProfileWindow(minute);
    }

    private void flush() {
        TickMonitor.TickStats stats = ticks.drain();
        ProfileWindow done = window;
        if (done.wallMs() <= 0) {
            return;
        }
        int online = Bukkit.getOnlinePlayers().size();
        String json = done.toJson(stats, online, jvm.snapshotJson(), windowSpikes);
        windowSpikes.clear();
        last = new Summary(done.startMs(), stats, done.busyMs(), done.coreMs(), done.top(10));
        if (plugin.cfg().canSend()) {
            plugin.buffer().addProfile(json);
        }
    }

    /** Список плагинов: индекс пакетов, имена для асинхронных потоков, инвентарь для панели. */
    private void refreshPlugins(long wall) {
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        if (plugins.length != pluginCount || wall - reindexedAt > REINDEX_PERIOD_MS) {
            lookup.rebuild(plugins);
            attributor.reset();
            pluginCount = plugins.length;
            reindexedAt = wall;
            pluginNames = Arrays.stream(plugins).map(Plugin::getName).filter(ProfileWindow::validName)
                    .toArray(String[]::new);
        }
        StringBuilder sb = new StringBuilder(plugins.length * 48).append('[');
        int n = 0;
        for (Plugin p : plugins) {
            if (!ProfileWindow.validName(p.getName()) || n >= 300) {
                continue;
            }
            if (n++ > 0) {
                sb.append(',');
            }
            sb.append("{\"n\":");
            Json.str(sb, p.getName(), 64).append(",\"v\":");
            Json.str(sb, String.valueOf(p.getDescription().getVersion()), 64)
                    .append(",\"on\":").append(p.isEnabled()).append('}');
        }
        String inventory = sb.append(']').toString();
        int hash = inventory.hashCode();
        if (hash != inventoryHash || wall - inventoryAt > INVENTORY_PERIOD_MS) {
            inventoryHash = hash;
            inventoryAt = wall;
            if (plugin.cfg().canSend()) {
                plugin.buffer().setInventory(inventory);
            }
        }
    }

    public Summary lastSummary() {
        return last;
    }

    public List<SpikeRecord> recentSpikes() {
        synchronized (recentSpikes) {
            return new ArrayList<>(recentSpikes);
        }
    }

    public long intervalMs() {
        return intervalMs;
    }

    /** Доля одного ядра, которую съел сам профилировщик с момента запуска, %. */
    public double overheadPct() {
        long cpu = selfCpuNs;
        long elapsed = System.currentTimeMillis() - startedAt;
        if (cpu < 0 || elapsed <= 0) {
            return -1;
        }
        return cpu / 1_000_000.0 / elapsed * 100;
    }

    public int cachedClasses() {
        return attributor.cacheSize();
    }

    public record Summary(long minute, TickMonitor.TickStats ticks, double busyMs, double coreMs,
                          List<ProfileWindow.Entry> top) {
    }
}
