package net.mcdiag.agent.remote;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.buffer.TelemetryBuffer;
import net.mcdiag.agent.config.AgentConfig;
import net.mcdiag.agent.json.Json;
import net.mcdiag.agent.profiler.PluginLookup;
import net.mcdiag.agent.profiler.ProfileCapture;
import net.mcdiag.agent.profiler.Profiler;
import net.mcdiag.agent.profiler.StackAttributor;
import net.mcdiag.agent.transport.BatchSender;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Выполняет команды диагностики от поддержки в отдельном daemon-потоке и кладёт ответы в буфер —
 * они уйдут со следующим батчем. Только чтение: ни одна команда ничего не меняет на сервере,
 * не выполняет консольных команд и не раскрывает токен или соль.
 */
public final class RemoteCommands {

    private static final int MAX_FRAMES = 60;
    private static final int MIN_PROFILE_SEC = 5;
    private static final int MAX_PROFILE_SEC = 120;
    /** Последние id — повтор той же команды (например, после ретрая батча) не выполняется дважды */
    private static final int SEEN = 64;

    private final McDiagPlugin plugin;
    private final ArrayDeque<String> seen = new ArrayDeque<>(SEEN);
    private ExecutorService executor;

    public RemoteCommands(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    /** Вызывается из потока отправки — только ставит команды в очередь. */
    public synchronized void accept(List<RemoteCommand> commands) {
        for (RemoteCommand c : commands) {
            if (seen.contains(c.id())) {
                continue;
            }
            if (seen.size() >= SEEN) {
                seen.pollFirst();
            }
            seen.addLast(c.id());
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "MCDiag Remote");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
            }
            try {
                executor.execute(() -> run(c));
            } catch (RejectedExecutionException ignored) {
                // плагин выключается
            }
        }
    }

    public synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void run(RemoteCommand c) {
        String data;
        String error = null;
        try {
            data = switch (c.type()) {
                case "ping" -> ping();
                case "status" -> status();
                case "main_stack" -> mainStack();
                case "profile" -> profile(c.seconds());
                case "plugins" -> plugins();
                case "jvm" -> jvm();
                default -> null;
            };
            if (data == null) {
                error = "unknown command";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.FINE, "remote command failed", e);
            data = null;
            error = e.getClass().getSimpleName();
        }
        plugin.getLogger().info("Support diagnostics: " + c.type() + (error == null ? " done" : " failed: " + error));
        StringBuilder sb = new StringBuilder(256).append("{\"id\":\"").append(c.id()).append("\",\"type\":");
        Json.str(sb, c.type(), 32).append(",\"ts\":").append(System.currentTimeMillis())
                .append(",\"ok\":").append(error == null);
        if (error != null) {
            Json.str(sb.append(",\"error\":"), error, 120);
        } else {
            sb.append(",\"data\":").append(data);
        }
        plugin.buffer().addResult(sb.append('}').toString());
    }

    private String ping() {
        BatchSender.Status s = plugin.sender().status();
        TelemetryBuffer.Stats b = plugin.buffer().stats();
        StringBuilder sb = new StringBuilder(256).append("{\"agent\":");
        Json.str(sb, plugin.getDescription().getVersion(), 32)
                .append(",\"serverUptimeSec\":").append(ManagementFactory.getRuntimeMXBean().getUptime() / 1000)
                .append(",\"sentBatches\":").append(s.sentBatches())
                .append(",\"queue\":{\"events\":").append(b.events()).append(",\"metrics\":").append(b.metrics())
                .append(",\"dropped\":").append(b.dropped()).append('}')
                .append(",\"lastResult\":");
        return Json.str(sb, s.lastResult(), 120).append('}').toString();
    }

    private String status() {
        AgentConfig cfg = plugin.cfg();
        double[] tps = Bukkit.getTPS();
        StringBuilder sb = new StringBuilder(768).append("{\"server\":");
        Json.str(sb, Bukkit.getName() + " " + Bukkit.getVersion(), 120).append(",\"mcVersion\":");
        Json.str(sb, Bukkit.getMinecraftVersion(), 16).append(",\"tps\":[");
        for (int i = 0; i < Math.min(3, tps.length); i++) {
            Json.decimal2(i > 0 ? sb.append(',') : sb, Math.min(20, tps[i]));
        }
        sb.append("],\"mspt\":");
        Json.decimal2(sb, Bukkit.getAverageTickTime())
                .append(",\"online\":").append(Bukkit.getOnlinePlayers().size())
                .append(",\"maxPlayers\":").append(Bukkit.getMaxPlayers())
                .append(",\"worlds\":").append(Bukkit.getWorlds().size())
                .append(",\"plugins\":").append(Bukkit.getPluginManager().getPlugins().length)
                .append(",\"config\":{\"host\":");
        Json.str(sb, cfg.backendUrl() == null ? "" : cfg.backendUrl().getHost(), 120)
                .append(",\"batchIntervalSec\":").append(cfg.batchIntervalSec())
                .append(",\"metricsIntervalSec\":").append(cfg.metricsIntervalSec())
                .append(",\"collect\":{\"joins\":").append(cfg.collectJoins())
                .append(",\"quits\":").append(cfg.collectQuits())
                .append(",\"deaths\":").append(cfg.collectDeaths())
                .append(",\"errors\":").append(cfg.collectErrors())
                .append(",\"metrics\":").append(cfg.collectMetrics())
                .append("},\"profiler\":{\"enabled\":").append(cfg.profilerEnabled())
                .append(",\"intervalMs\":").append(cfg.profilerIntervalMs())
                .append(",\"spikeThresholdMs\":").append(cfg.spikeThresholdMs())
                .append(",\"async\":").append(cfg.profilerAsync()).append("}}");
        Profiler p = plugin.profiler();
        if (p != null && p.overheadPct() >= 0) {
            Json.decimal2(sb.append(",\"profilerCpuPct\":"), p.overheadPct());
        }
        return sb.append('}').toString();
    }

    private String mainStack() {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(plugin.mainThread().getId(), MAX_FRAMES);
        if (info == null) {
            return "{\"frames\":[]}";
        }
        StackTraceElement[] stack = info.getStackTrace();
        StringBuilder sb = new StringBuilder(4096).append("{\"state\":");
        Json.str(sb, info.getThreadState().name(), 32)
                .append(",\"inTick\":").append(plugin.tickMonitor().inTick())
                .append(",\"owner\":");
        Json.str(sb, new StackAttributor(lookup()).attribute(stack), 65).append(",\"frames\":[");
        for (int i = 0; i < stack.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Json.str(sb, stack[i].toString(), 240);
        }
        return sb.append("]}").toString();
    }

    private String profile(int seconds) throws InterruptedException {
        int sec = Math.max(MIN_PROFILE_SEC, Math.min(MAX_PROFILE_SEC, seconds == 0 ? 60 : seconds));
        return ProfileCapture.run(plugin.mainThread(), lookup(), plugin.tickMonitor(), sec);
    }

    private String plugins() {
        Plugin[] all = Bukkit.getPluginManager().getPlugins();
        StringBuilder sb = new StringBuilder(all.length * 64).append("{\"count\":").append(all.length).append(",\"plugins\":[");
        int n = 0;
        for (Plugin p : all) {
            if (n >= 300) {
                break;
            }
            if (n++ > 0) {
                sb.append(',');
            }
            sb.append("{\"n\":");
            Json.str(sb, p.getName(), 64).append(",\"v\":");
            Json.str(sb, String.valueOf(p.getDescription().getVersion()), 64).append(",\"on\":").append(p.isEnabled()).append('}');
        }
        return sb.append("]}").toString();
    }

    private String jvm() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        StringBuilder sb = new StringBuilder(1024).append("{\"java\":");
        Json.str(sb, System.getProperty("java.version", ""), 64).append(",\"vendor\":");
        Json.str(sb, System.getProperty("java.vendor", ""), 64).append(",\"os\":");
        Json.str(sb, System.getProperty("os.name", "") + " " + System.getProperty("os.arch", ""), 64)
                .append(",\"cpus\":").append(Runtime.getRuntime().availableProcessors())
                .append(",\"heapMaxMb\":").append(Runtime.getRuntime().maxMemory() >> 20)
                .append(",\"gc\":[");
        int i = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            Json.str(i++ > 0 ? sb.append(',') : sb, gc.getName(), 64);
        }
        sb.append("],\"args\":[");
        i = 0;
        for (String arg : rt.getInputArguments()) {
            if (i >= 60) {
                break;
            }
            Json.str(i++ > 0 ? sb.append(',') : sb, maskArg(arg), 200);
        }
        return sb.append("]}").toString();
    }

    /** -Dkey=value → -Dkey=*** : в системных свойствах иногда лежат пароли. */
    static String maskArg(String arg) {
        String lower = arg.toLowerCase(Locale.ROOT);
        if (arg.startsWith("-D") && arg.indexOf('=') > 0) {
            return arg.substring(0, arg.indexOf('=') + 1) + "***";
        }
        if (lower.contains("password") || lower.contains("secret") || lower.contains("token")) {
            return "***";
        }
        return arg;
    }

    private PluginLookup lookup() {
        Profiler p = plugin.profiler();
        if (p != null) {
            return p.lookup();
        }
        PluginLookup lookup = new PluginLookup(plugin.getLogger());
        lookup.rebuild(Bukkit.getPluginManager().getPlugins());
        return lookup;
    }
}
