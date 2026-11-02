package net.mcdiag.agent;

import java.util.Locale;
import java.util.logging.Logger;
import net.mcdiag.agent.buffer.TelemetryBuffer;
import net.mcdiag.agent.collect.ErrorCaptureHandler;
import net.mcdiag.agent.collect.MetricsTask;
import net.mcdiag.agent.collect.PlayerListener;
import net.mcdiag.agent.command.DiagCommand;
import net.mcdiag.agent.config.AgentConfig;
import net.mcdiag.agent.json.Json;
import net.mcdiag.agent.transport.BatchSender;
import net.mcdiag.agent.transport.HttpTransport;
import net.mcdiag.agent.util.UuidHasher;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

public final class McDiagPlugin extends JavaPlugin {

    private static final long TICKS_PER_SEC = 20;

    private volatile AgentConfig cfg;
    private volatile UuidHasher hasher;
    private TelemetryBuffer buffer;
    private MetricsTask metrics;
    private BatchSender sender;
    private ErrorCaptureHandler errorHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigSnapshot();
        buffer = new TelemetryBuffer(cfg.maxBufferSize());
        metrics = new MetricsTask(this);
        sender = new BatchSender(this, new HttpTransport("MCDiag-Agent/" + getPluginMeta().getVersion()), pluginJson());

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        errorHandler = new ErrorCaptureHandler(this);
        Logger.getLogger("").addHandler(errorHandler);

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
        Bukkit.getScheduler().cancelTasks(this);
        // Обязательно снимаем хендлер: иначе root Logger держит наш ClassLoader после /reload.
        if (errorHandler != null) {
            Logger.getLogger("").removeHandler(errorHandler);
            errorHandler = null;
        }
    }

    /** @return проблема конфигурации или null */
    public String reload() {
        reloadConfig();
        loadConfigSnapshot();
        buffer.resize(cfg.maxBufferSize());
        sender.resetBackoff();
        Bukkit.getScheduler().cancelTasks(this);
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
    }

    private void scheduleTasks() {
        BukkitScheduler scheduler = Bukkit.getScheduler();
        long metricsPeriod = cfg.metricsIntervalSec() * TICKS_PER_SEC;
        long batchPeriod = cfg.batchIntervalSec() * TICKS_PER_SEC;
        // Сущности снимаем чуть раньше async-таска метрик, чтобы точка видела свежее значение.
        scheduler.runTaskTimer(this, metrics.entitySampler(), metricsPeriod - 1, metricsPeriod);
        scheduler.runTaskTimerAsynchronously(this, metrics, metricsPeriod, metricsPeriod);
        scheduler.runTaskTimerAsynchronously(this, sender, batchPeriod, batchPeriod);
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
        Json.str(sb, getPluginMeta().getVersion(), 32).append(",\"core\":\"").append(core).append("\",\"mcVersion\":");
        return Json.str(sb, Bukkit.getMinecraftVersion(), 16).append('}').toString();
    }
}
