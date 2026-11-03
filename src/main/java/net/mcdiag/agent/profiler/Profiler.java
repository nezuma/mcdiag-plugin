package net.mcdiag.agent.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.collect.LogRing;
import net.mcdiag.agent.json.Json;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Сэмплирующий профилировщик главного потока, по принципу spark.
 *
 * <p>Отдельный daemon-поток периодически снимает стек главного потока ({@link MainThreadSampler})
 * и записывает прошедшее время на плагин, чей код ближе всего к вершине стека
 * ({@link StackAttributor}). Сам главный поток делает только два события тика ({@link TickMonitor}).
 * Раз в минуту итог уходит в буфер одним JSON-срезом ~1–3 KB.
 *
 * <p>Это оценка по сэмплам: плагин, работающий реже интервала, может не попасть в срез вовсе.
 */
public final class Profiler implements Runnable {

    private static final long MINUTE_MS = 60_000;
    /** CPU потоков накапливается и не теряется, поэтому читать его часто незачем */
    private static final long ASYNC_PERIOD_MS = 5_000;
    private static final long SELF_CPU_PERIOD_MS = 5_000;
    private static final long INVENTORY_PERIOD_MS = 30 * MINUTE_MS;
    private static final int RECENT_SPIKES = 10;

    private final McDiagPlugin plugin;
    private final TickMonitor ticks;
    private final long intervalMs;
    private final boolean asyncEnabled;
    private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
    private final PluginLookup lookup;
    private final StackAttributor attributor;
    private final AsyncTracker async;

    private final MainThreadSampler sampler;
    /** Лог прикладываем только к первым спайкам минуты: при шторме спайков он был бы одинаковым */
    private static final int SPIKES_WITH_LOGS = 3;
    private static final long SPIKE_LOG_WINDOW_MS = 60_000;
    private static final int SPIKE_LOG_LINES = 20;
    private int sentSpikes;

    private volatile boolean running = true;
    private Thread thread;
    private ProfileWindow window;
    private String[] pluginNames = new String[0];
    private int pluginsHash;
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
        this.ticks = ticks;
        this.intervalMs = intervalMs;
        this.asyncEnabled = asyncEnabled;
        this.lookup = new PluginLookup(plugin.getLogger());
        this.attributor = new StackAttributor(lookup);
        this.async = new AsyncTracker(mainThread.getId());
        this.sampler = new MainThreadSampler(mainThread, ticks, attributor, intervalMs);
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
        long nextSelfCpu = 0;
        long delay = intervalMs;
        while (running) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                break;
            }
            try {
                long now = System.nanoTime();
                // Вес сэмпла — реально прошедшее время (интервал случайный, а sleep неточен).
                double weightMs = Math.min((now - prev) / 1_000_000.0, sampler.maxWeightMs());
                prev = now;
                long wall = System.currentTimeMillis();
                ensureWindow(wall);
                delay = sampler.sample(window, weightMs);
                drainSpikes();
                if (asyncEnabled && wall >= nextAsync) {
                    async.collect(window, attributor, pluginNames, wall);
                    nextAsync = wall + ASYNC_PERIOD_MS;
                }
                if (wall >= nextSelfCpu) {
                    selfCpuNs = mx.isCurrentThreadCpuTimeSupported() ? mx.getCurrentThreadCpuTime() : -1;
                    nextSelfCpu = wall + SELF_CPU_PERIOD_MS;
                }
            } catch (RuntimeException | LinkageError e) {
                // Профилировщик никогда не должен ронять сервер или свой поток.
                plugin.getLogger().log(Level.FINE, "profiler sample failed", e);
            }
        }
    }

    /** Долгие тики: кто занимал главный поток по сэмплам именно этого тика. */
    private void drainSpikes() {
        TickMonitor.Spike spike;
        while ((spike = ticks.pollSpike()) != null) {
            List<String> owners = new ArrayList<>();
            List<String> frames = new ArrayList<>();
            sampler.collect(spike.tick(), owners, frames);
            SpikeRecord record = owners.isEmpty()
                    ? new SpikeRecord(spike.startedAt(), spike.ms(), List.of())
                    : SpikeRecord.of(spike.startedAt(), spike.ms(), owners);
            if (plugin.panel().richSpikes()) {
                // Новая панель: спайк уходит сразу с ближайшим батчем, а не с минутным срезом.
                LogRing ring = plugin.logRing();
                List<LogRing.Line> logs = ring != null && sentSpikes < SPIKES_WITH_LOGS
                        ? ring.recent(spike.startedAt() - SPIKE_LOG_WINDOW_MS, SPIKE_LOG_LINES)
                        : List.of();
                SpikeRecord rich = new SpikeRecord(record.ts(), record.ms(), record.top(),
                        frames.isEmpty() ? List.of() : SpikeRecord.shares(frames, SpikeRecord.MAX_HOT), logs);
                plugin.buffer().addSpike(rich.richJson());
                sentSpikes++;
            } else if (windowSpikes.size() < 20) {
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
        // Процессор и память теперь уходят отдельным системным срезом (SystemStats).
        String json = done.toJson(stats, online, null, windowSpikes, sentSpikes);
        windowSpikes.clear();
        sentSpikes = 0;
        last = new Summary(done.startMs(), stats, done.busyMs(), done.coreMs(), done.top(10));
        if (plugin.cfg().canSend()) {
            plugin.buffer().addProfile(json);
        }
    }

    /** Список плагинов: индекс пакетов, имена для асинхронных потоков, инвентарь для панели. */
    private void refreshPlugins(long wall) {
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        // Индекс читает оглавления всех jar: только если набор плагинов действительно изменился.
        int setHash = pluginSetHash(plugins);
        if (setHash != pluginsHash) {
            lookup.rebuild(plugins);
            attributor.reset();
            pluginsHash = setHash;
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

    private static int pluginSetHash(Plugin[] plugins) {
        int hash = 1;
        for (Plugin p : plugins) {
            // Сумма, а не цепочка: порядок плагинов роли не играет.
            hash += p.getName().hashCode() * 31 + String.valueOf(p.getDescription().getVersion()).hashCode();
        }
        return hash;
    }

    public Summary lastSummary() {
        return last;
    }

    public List<SpikeRecord> recentSpikes() {
        synchronized (recentSpikes) {
            return new ArrayList<>(recentSpikes);
        }
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

    /** Снимков стека в секунду с момента запуска. */
    public double stackReadsPerSec() {
        long elapsed = System.currentTimeMillis() - startedAt;
        return elapsed <= 0 ? 0 : sampler.stackReads() * 1000.0 / elapsed;
    }

    /** Доля времени, которую занимают снимки стека, %. */
    public double samplingPct() {
        long elapsed = System.currentTimeMillis() - startedAt;
        return elapsed <= 0 ? 0 : sampler.stackReadNanos() / 1_000_000.0 / elapsed * 100;
    }

    /** На Java 19+ снимок стека не останавливает остальные потоки. */
    public boolean usesHandshake() {
        return sampler.usesHandshake();
    }

    /** Индекс «пакет → плагин»: общий, только чтение — годится и для разовых снимков. */
    public PluginLookup lookup() {
        return lookup;
    }

    public int cachedClasses() {
        return attributor.cacheSize();
    }

    public record Summary(long minute, TickMonitor.TickStats ticks, double busyMs, double coreMs,
                          List<ProfileWindow.Entry> top) {
    }
}
