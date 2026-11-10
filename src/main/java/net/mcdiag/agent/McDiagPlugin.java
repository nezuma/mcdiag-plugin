package net.mcdiag.agent;

import java.util.Locale;
import java.util.logging.Logger;
import net.mcdiag.agent.buffer.TelemetryBuffer;
import net.mcdiag.agent.collect.ChatListener;
import net.mcdiag.agent.collect.ChunkGenCounter;
import net.mcdiag.agent.collect.CommandListener;
import net.mcdiag.agent.collect.ErrorCaptureHandler;
import net.mcdiag.agent.collect.LogRing;
import net.mcdiag.agent.collect.MetricsTask;
import net.mcdiag.agent.collect.PlayerListener;
import net.mcdiag.agent.collect.SystemStats;
import net.mcdiag.agent.collect.TextMask;
import net.mcdiag.agent.command.DiagCommand;
import net.mcdiag.agent.config.AgentConfig;
import net.mcdiag.agent.economy.EconomyModule;
import net.mcdiag.agent.heatmap.HeatmapControl;
import net.mcdiag.agent.insight.GcWatch;
import net.mcdiag.agent.insight.JfrInsight;
import net.mcdiag.agent.insight.MemoryStats;
import net.mcdiag.agent.json.Json;
import net.mcdiag.agent.logs.LogAggregator;
import net.mcdiag.agent.logs.LogCapture;
import net.mcdiag.agent.logs.LogTaps;
import net.mcdiag.agent.logs.LogsModule;
import net.mcdiag.agent.module.ModuleRegistry;
import net.mcdiag.agent.network.NetAggregator;
import net.mcdiag.agent.network.NetTap;
import net.mcdiag.agent.network.NetworkModule;
import net.mcdiag.agent.privileges.PrivilegesModule;
import net.mcdiag.agent.profiler.AsyncTracker;
import net.mcdiag.agent.profiler.PluginLookup;
import net.mcdiag.agent.profiler.Profiler;
import net.mcdiag.agent.profiler.TickMonitor;
import net.mcdiag.agent.remote.RemoteCommands;
import net.mcdiag.agent.transport.BatchSender;
import net.mcdiag.agent.transport.HttpTransport;
import net.mcdiag.agent.transport.PanelState;
import net.mcdiag.agent.util.Safe;
import net.mcdiag.agent.util.UuidHasher;
import net.mcdiag.api.McDiagApi;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

public final class McDiagPlugin extends JavaPlugin {

    private static final long TICKS_PER_SEC = 20;
    private static final long JFR_START_DELAY_MS = 20_000;
    private volatile Thread jfrStarter;

    private volatile AgentConfig cfg;
    private volatile UuidHasher hasher;
    private TelemetryBuffer buffer;
    private MetricsTask metrics;
    private BatchSender sender;
    private ErrorCaptureHandler errorHandler;
    private Thread mainThread;
    private TickMonitor tickMonitor;
    private volatile Profiler profiler;
    private RemoteCommands remote;
    private SystemStats system;
    private String pluginStatic;
    private final PanelState panel = new PanelState();
    private volatile TextMask mask;
    private volatile LogRing logRing;
    private volatile GcWatch gcWatch;
    private volatile JfrInsight jfr;
    private MemoryStats memory;
    private HeatmapControl heatmap;
    private ChunkGenCounter chunkGen;
    private EconomyModule economy;
    private volatile ModuleRegistry modules;
    private LogCapture logCapture;
    private LogTaps.Tap logTap;
    private NetTap netTap;
    private NetAggregator netAggregator;
    private PrivilegesModule privileges;

    /**
     * Перехват лога и сети ставится до включения других плагинов: так видны ошибки их запуска,
     * а HttpClient, созданные при их включении, уже идут через наш ProxySelector.
     */
    @Override
    public void onLoad() {
        saveDefaultConfig();
        long mainId = Thread.currentThread().getId();
        if (getConfig().getBoolean("modules.logs.enabled", true)) {
            try {
                LogAggregator aggregator = new LogAggregator(
                        cls -> { ModuleRegistry r = modules; return r == null ? null : r.pluginOfClass(cls); },
                        McDiagPlugin::isPluginName,
                        text -> { TextMask m = mask; return m == null ? text : m.text(text); });
                logCapture = new LogCapture(aggregator, getName(), mainId);
                logTap = LogTaps.install(logCapture, Bukkit.getServer().getClass().getClassLoader(), getLogger());
            } catch (RuntimeException | LinkageError e) {
                getLogger().log(java.util.logging.Level.WARNING, "Log capture is unavailable: " + e);
                logCapture = null;
            }
        }
        if (getConfig().getBoolean("modules.network.enabled", true)) {
            try {
                netTap = NetTap.install(mainId);
                // Копим с самого начала: до ответа панели модуль ещё не включён.
                netAggregator = new NetAggregator();
                netTap.setSink(netAggregator);
            } catch (RuntimeException | LinkageError e) {
                getLogger().log(java.util.logging.Level.WARNING, "Network audit is unavailable: " + e);
            }
        }
    }

    private static boolean isPluginName(String name) {
        try {
            return name != null && !name.isEmpty() && Bukkit.getPluginManager().getPlugin(name) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigSnapshot();
        buffer = new TelemetryBuffer(cfg.maxBufferSize());
        metrics = new MetricsTask(this);
        pluginStatic = pluginJson();
        remote = new RemoteCommands(this);
        system = new SystemStats(json -> buffer.addSystem(json));
        memory = new MemoryStats();
        heatmap = new HeatmapControl(this);
        // Интервал отправки меняется по /diag reload, поэтому блок plugin собирается на каждый батч.
        sender = new BatchSender(this, new HttpTransport("MCDiag-Agent/" + getDescription().getVersion()),
                () -> pluginStatic.substring(0, pluginStatic.length() - 1) + ",\"batchIntervalSec\":" + cfg.batchIntervalSec() + "}");

        mainThread = Thread.currentThread();
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        tickMonitor = new TickMonitor(cfg.spikeThresholdMs());
        getServer().getPluginManager().registerEvents(tickMonitor, this);
        errorHandler = new ErrorCaptureHandler(this);
        Logger.getLogger("").addHandler(errorHandler);
        getServer().getPluginManager().registerEvents(new CommandListener(this), this);
        chunkGen = new ChunkGenCounter(this);
        getServer().getPluginManager().registerEvents(chunkGen, this);
        try {
            getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        } catch (LinkageError e) {
            // Ядро без событий чата Paper: журнал чата недоступен, остальное работает.
            getLogger().info("Chat log is unavailable on this server core");
        }

        ModuleRegistry registry = new ModuleRegistry(this);
        modules = registry;
        registry.buildLookupNow();
        if (logCapture != null) {
            registry.registerBuiltin(new LogsModule(logCapture, logTap, registry));
            // Сообщения из API принимаются сразу; журнал решает, копить ли их, как и строки лога.
            registry.setIssueSink(logCapture::issue);
        }
        if (netTap != null) {
            netTap.setOwners(registry::pluginOfClass, NetworkModule.threadOwner());
            // Загрузчик класса указывает на плагин точнее индекса: так видны и затенённые библиотеки.
            netTap.setLoaderOwner(type -> {
                try {
                    org.bukkit.plugin.Plugin owner = JavaPlugin.getProvidingPlugin(type);
                    return owner == this ? null : owner.getName();
                } catch (RuntimeException e) {
                    return null;
                }
            });
            registry.registerBuiltin(new NetworkModule(netTap, netAggregator, registry));
        }
        PrivilegesModule privileges = new PrivilegesModule(registry, () -> mask);
        this.privileges = privileges;
        registry.registerBuiltin(privileges);
        if (registry.enabledInConfig(PrivilegesModule.ID)) {
            privileges.startEarly(this, getConfig().getStringList("modules.privileges.dangerous_permissions"));
        }
        getServer().getPluginManager().registerEvents(registry, this);
        getServer().getServicesManager().register(McDiagApi.class, registry, this, ServicePriority.Normal);
        // Плагины, догруженные после старта, попадут в индекс при обновлении в фоне.
        Bukkit.getScheduler().runTaskLater(this, registry::rebuildLookup, 20 * 60);

        PluginCommand command = getCommand("diag");
        if (command != null) {
            DiagCommand diag = new DiagCommand(this);
            command.setExecutor(diag);
            command.setTabCompleter(diag);
        }

        scheduleTasks();
        logState();
    }

    @Override
    public void onDisable() {
        ModuleRegistry registry = modules;
        if (registry != null) {
            registry.stopAll();
            getServer().getServicesManager().unregisterAll(this);
        }
        if (logTap != null) {
            logTap.uninstall();
            logTap = null;
        }
        if (netTap != null) {
            netTap.uninstall();
            netTap = null;
        }
        if (heatmap != null) {
            heatmap.stop();
        }
        stopEconomy();
        Bukkit.getScheduler().cancelTasks(this);
        stopProfiler();
        if (remote != null) {
            remote.shutdown();
        }
        // Обязательно снимаем хендлер: иначе root Logger держит наш ClassLoader после /reload.
        if (errorHandler != null) {
            Logger.getLogger("").removeHandler(errorHandler);
            errorHandler = null;
        }
        removeLogRing();
        stopInsight();
    }

    /** @return проблема конфигурации или null */
    public String reload() {
        reloadConfig();
        loadConfigSnapshot();
        buffer.resize(cfg.maxBufferSize());
        sender.resetBackoff();
        heatmap.stop();
        stopEconomy();
        // Задачи модулей отменятся вместе со всеми: модули выключаем заранее и включаем заново.
        modules.stopAll();
        modules.resetFailures();
        Bukkit.getScheduler().cancelTasks(this);
        stopProfiler();
        scheduleTasks();
        logState();
        return cfg.problem();
    }

    public AgentConfig cfg() {
        return cfg;
    }

    public UuidHasher hasher() {
        return hasher;
    }

    public TelemetryBuffer buffer() {
        return buffer;
    }

    public BatchSender sender() {
        return sender;
    }

    public PanelState panel() {
        return panel;
    }

    public TextMask mask() {
        return mask;
    }

    public ModuleRegistry modules() {
        return modules;
    }

    public LogRing logRing() {
        return logRing;
    }

    /** Чат пишем, только если разрешено и в config.yml, и владельцем на сайте. */
    public boolean chatEnabled() {
        return cfg.chatLogAllowed() && panel.chat() && !panel.free();
    }

    /** Для /diag status. */
    public String economyStatus() {
        EconomyModule current = economy;
        if (!cfg.economyEnabled()) {
            return "off (economy.enabled in config.yml)";
        }
        if (current == null) {
            return panel.free() ? "off in free mode" : panel.economy() ? "starting" : "waiting for the panel";
        }
        return current.describe();
    }

    /** Команды пишем, только если разрешено и в config.yml, и владельцем на сайте. */
    public boolean commandsEnabled() {
        return cfg.chatLogAllowed() && panel.commands();
    }

    public RemoteCommands remote() {
        return remote;
    }

    public Thread mainThread() {
        return mainThread;
    }

    public TickMonitor tickMonitor() {
        return tickMonitor;
    }

    /** @return профилировщик или null, если он выключен в конфиге */
    public Profiler profiler() {
        return profiler;
    }

    private void loadConfigSnapshot() {
        if (getConfig().getString("uuid_salt", "").isBlank()) {
            getConfig().set("uuid_salt", UuidHasher.newSalt());
            saveConfig();
        }
        AgentConfig next = AgentConfig.from(getConfig());
        if (hasher == null || cfg == null || !next.uuidSalt().equals(cfg.uuidSalt())) {
            hasher = new UuidHasher(next.uuidSalt());
        }
        cfg = next;
        mask = new TextMask(next.hiddenCommands());
        if (logRing != null) {
            logRing.setMask(mask);
        }
    }

    /** Хендлер стоит на каждой строке лога сервера, поэтому без спайков с логами его нет вовсе. */
    private void syncLogRing() {
        if (!cfg.profilerEnabled() || !cfg.spikeLogs() || panel.free()) {
            removeLogRing();
        } else if (logRing == null) {
            logRing = new LogRing(mask, panel::richSpikes);
            // Перехват Log4j видит намного больше строк, чем корневой JUL Logger.
            if (logCapture != null && "log4j".equals(logCapture.source())) {
                logCapture.setRing(logRing);
            } else {
                Logger.getLogger("").addHandler(logRing);
            }
        }
    }

    private void removeLogRing() {
        LogRing ring = logRing;
        logRing = null;
        if (logCapture != null) {
            logCapture.setRing(null);
        }
        if (ring != null) {
            Logger.getLogger("").removeHandler(ring);
        }
    }

    /** Каждый источник подключается отдельно: если его нет, остальное работает. */
    private void syncInsight() {
        stopInsight();
        if (!cfg.profilerEnabled() || panel.free()) {
            return;
        }
        try {
            GcWatch watch = new GcWatch();
            watch.start();
            gcWatch = watch;
        } catch (Exception | LinkageError e) {
            getLogger().log(java.util.logging.Level.FINE, "GC notifications are unavailable", e);
        }
        if (!cfg.jfrInsight()) {
            return;
        }
        // Запуск JFR занимает доли секунды, поэтому он идёт в своём потоке и после старта сервера.
        Thread starter = new Thread(() -> {
            try {
                Thread.sleep(JFR_START_DELAY_MS);
                PluginLookup lookup = new PluginLookup(getLogger());
                lookup.rebuild(Bukkit.getPluginManager().getPlugins());
                JfrInsight next = new JfrInsight(getLogger(), mainThread.getId(), new CachedOwner(lookup), AsyncTracker::pluginFromName);
                next.start();
                if (jfrStarter == Thread.currentThread()) {
                    jfr = next;
                } else {
                    next.stop();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                getLogger().log(java.util.logging.Level.FINE, "JFR insight is unavailable", e);
            }
        }, "MCDiag Insight start");
        starter.setDaemon(true);
        starter.setPriority(Thread.MIN_PRIORITY);
        jfrStarter = starter;
        starter.start();
    }

    private void stopInsight() {
        Thread starter = jfrStarter;
        jfrStarter = null;
        if (starter != null) {
            starter.interrupt();
        }
        GcWatch watch = gcWatch;
        gcWatch = null;
        if (watch != null) {
            Safe.run(getLogger(), "GC watch stop", watch::stop);
        }
        JfrInsight current = jfr;
        jfr = null;
        if (current != null) {
            // Закрытие не должно задержать главный поток при выключении.
            Thread stopper = new Thread(() -> Safe.run(getLogger(), "JFR stop", current::stop), "MCDiag Insight stop");
            stopper.setDaemon(true);
            stopper.start();
            try {
                stopper.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void collectMemory() {
        if (!panel.memory() || panel.free()) {
            return;
        }
        long now = System.currentTimeMillis();
        JfrInsight currentJfr = jfr;
        if (currentJfr != null) {
            currentJfr.checkHealth(now);
        }
        Profiler p = profiler;
        GcWatch watch = gcWatch;
        buffer.addMemory(memory.snapshotJson(now / 60_000 * 60_000,
                name -> p == null ? null : p.pluginOfThread(name),
                watch == null ? null : watch.drainMinute(),
                currentJfr != null && currentJfr.active() ? currentJfr.drainAllocations() : null));
    }

    /** Кеш «класс → плагин» для потока разбора JFR: только он сюда и обращается. */
    private static final class CachedOwner implements java.util.function.Function<String, String> {
        private final java.util.Map<String, String> cache = new java.util.HashMap<>();
        private final PluginLookup lookup;

        CachedOwner(PluginLookup lookup) {
            this.lookup = lookup;
        }

        @Override
        public String apply(String cls) {
            String cached = cache.get(cls);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }
            if (cache.size() > 50_000) {
                cache.clear();
            }
            String owner = net.mcdiag.agent.profiler.FrameClassifier.isNeutral(cls) || net.mcdiag.agent.profiler.FrameClassifier.isCore(cls)
                    ? null : lookup.apply(cls);
            cache.put(cls, owner == null ? "" : owner);
            return owner;
        }
    }

    public GcWatch gcWatch() {
        return gcWatch;
    }

    public JfrInsight jfr() {
        return jfr;
    }

    public HeatmapControl heatmap() {
        return heatmap;
    }

    /**
     * Панель сообщила о смене режима. Вызывается из потока отправки, а модули работают с Bukkit,
     * поэтому переключение идёт в главном потоке.
     */
    public void onPanelChanged() {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, Safe.task(getLogger(), "mode change", this::applyMode));
        }
    }

    private void applyMode() {
        syncProfiler();
        syncLogRing();
        syncInsight();
        heatmap.sync();
        syncEconomy();
        syncModules();
    }

    /** Модули по тарифу и возможностям панели; до первого ответа панели журнал лога копит ошибки запуска. */
    private void syncModules() {
        ModuleRegistry registry = modules;
        if (registry == null) {
            return;
        }
        registry.sync();
        if (logCapture != null && !registry.isActive(LogsModule.ID)) {
            logCapture.setAccepting(!panel.answered() && registry.enabledInConfig(LogsModule.ID));
        }
        if (privileges != null && !registry.isActive(PrivilegesModule.ID)
                && (panel.answered() || !registry.enabledInConfig(PrivilegesModule.ID))) {
            privileges.stop();
        }
        if (netTap != null && !registry.isActive(NetworkModule.ID)
                && (panel.answered() || !registry.enabledInConfig(NetworkModule.ID))) {
            netTap.setSink(null);
            netAggregator.clear();
        }
    }

    private void syncEconomy() {
        boolean want = cfg.economyEnabled() && panel.economy() && !panel.free();
        if (!want) {
            stopEconomy();
        } else if (economy == null) {
            EconomyModule next = new EconomyModule(this, cfg.economyOffline());
            next.start();
            economy = next;
        }
    }

    private void stopEconomy() {
        EconomyModule current = economy;
        economy = null;
        if (current != null) {
            current.stop();
        }
    }

    private void collectChunkGen() {
        if (!panel.chunkgen() || panel.free() || !cfg.collectChunks()) {
            chunkGen.clear();
            return;
        }
        String json = chunkGen.drainMinute(System.currentTimeMillis() / 60_000 * 60_000 - 60_000);
        if (json != null) {
            buffer.addChunkGen(json);
        }
    }

    /** В бесплатном режиме профилировщика нет совсем: сервер клиента не платит за то, что не показывается. */
    private void syncProfiler() {
        boolean want = cfg.profilerEnabled() && !panel.free();
        if (!want) {
            stopProfiler();
        } else if (profiler == null) {
            Profiler next = new Profiler(this, mainThread, tickMonitor, cfg.profilerIntervalMs(), cfg.spikeIntervalMs(), cfg.profilerAsync());
            next.start();
            profiler = next;
        }
    }

    private void scheduleTasks() {
        syncLogRing();
        syncInsight();
        BukkitScheduler scheduler = Bukkit.getScheduler();
        long metricsPeriod = cfg.metricsIntervalSec() * TICKS_PER_SEC;
        long batchPeriod = cfg.batchIntervalSec() * TICKS_PER_SEC;
        // Сущности снимаем чуть раньше async-таска метрик, чтобы точка видела свежее значение.
        // Исключение в задаче Bukkit написал бы в консоль сервера как ошибку.
        java.util.logging.Logger log = getLogger();
        scheduler.runTaskTimer(this, Safe.task(log, "entity counter", metrics.entitySampler()), metricsPeriod - 1, metricsPeriod);
        scheduler.runTaskTimerAsynchronously(this, Safe.task(log, "metrics", metrics), metricsPeriod, metricsPeriod);
        scheduler.runTaskTimerAsynchronously(this, Safe.task(log, "sender", sender), batchPeriod, batchPeriod);
        if (cfg.collectMetrics()) {
            // Процессор, память, диск — раз в минуту, независимо от профилировщика.
            scheduler.runTaskTimerAsynchronously(this, Safe.task(log, "system stats", system), 60 * TICKS_PER_SEC, 60 * TICKS_PER_SEC);
            scheduler.runTaskTimerAsynchronously(this, Safe.task(log, "memory stats", this::collectMemory), 60 * TICKS_PER_SEC, 60 * TICKS_PER_SEC);
        }
        scheduler.runTaskTimerAsynchronously(this, Safe.task(log, "chunk generation", this::collectChunkGen), 60 * TICKS_PER_SEC, 60 * TICKS_PER_SEC);

        tickMonitor.setSpikeThreshold(cfg.spikeThresholdMs());
        syncProfiler();
        heatmap.sync();
        syncEconomy();
        syncModules();
    }

    private void stopProfiler() {
        Profiler current = profiler;
        profiler = null;
        if (current != null) {
            current.stop();
        }
    }

    private void logState() {
        if (cfg.canSend()) {
            getLogger().info("Telemetry upload enabled → " + cfg.backendUrl().getHost()
                    + " every " + cfg.batchIntervalSec() + "s, token " + cfg.maskedToken());
        } else {
            getLogger().warning("Telemetry upload disabled: " + cfg.problem());
        }
    }

    /** Неизменная часть батча — собирается один раз. */
    private String pluginJson() {
        String name = Bukkit.getName().toLowerCase(Locale.ROOT);
        String core = name.contains("leaf") ? "leaf"
                : name.contains("purpur") ? "purpur"
                : name.contains("folia") ? "folia"
                : name.contains("paper") ? "paper"
                : "other";
        StringBuilder sb = new StringBuilder(96).append("{\"version\":");
        Json.str(sb, getDescription().getVersion(), 32).append(",\"core\":\"").append(core).append("\",\"mcVersion\":");
        return Json.str(sb, Bukkit.getMinecraftVersion(), 16).append('}').toString();
    }
}
