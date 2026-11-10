package net.mcdiag.agent.command;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.buffer.TelemetryBuffer;
import net.mcdiag.agent.config.AgentConfig;
import net.mcdiag.agent.heatmap.HeatmapControl;
import net.mcdiag.agent.profiler.FrameClassifier;
import net.mcdiag.agent.profiler.ProfileWindow;
import net.mcdiag.agent.profiler.Profiler;
import net.mcdiag.agent.profiler.SpikeRecord;
import net.mcdiag.agent.transport.BatchSender;
import net.mcdiag.api.ModuleInfo;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;

/** /diag status | modules | module &lt;id&gt; on|off | plugins | spikes | token [value] | reload */
public final class DiagCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("status", "modules", "module", "plugins", "spikes", "token", "reload", "pos1", "pos2", "poi", "heatmap");
    private static final long MB = 1024 * 1024;

    private final McDiagPlugin plugin;

    public DiagCommand(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "modules" -> modules(sender);
            case "module" -> module(sender, args);
            case "plugins" -> plugins(sender);
            case "spikes" -> spikes(sender);
            case "token" -> token(sender, args);
            case "pos1", "pos2" -> plugin.heatmap().setPos(sender, sub);
            case "poi" -> plugin.heatmap().poi(sender, args);
            case "heatmap" -> plugin.heatmap().command(sender, args);
            case "reload" -> {
                String problem = plugin.reload();
                sender.sendMessage("[MCDiag] Reloaded" + (problem == null ? "" : " — " + problem));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("heatmap")) {
            return HeatmapControl.subcommands().stream().filter(x -> x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("module")) {
            return plugin.modules().ids().stream().filter(x -> x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("module")) {
            return List.of("on", "off").stream().filter(x -> x.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    private void modules(CommandSender sender) {
        sender.sendMessage("[MCDiag] Modules (panel caps: " + String.join(", ", plugin.panel().caps()) + ")");
        for (ModuleInfo m : plugin.modules().modules()) {
            String owner = m.owner().equals(plugin.getName()) ? "" : " [" + m.owner() + "]";
            sender.sendMessage(" " + m.id() + owner + ": " + m.state() + (m.status().isEmpty() ? "" : " — " + m.status()));
        }
    }

    /** Включение модуля пишется в config.yml: переживает перезапуск. */
    private void module(CommandSender sender, String[] args) {
        if (args.length < 3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
            sender.sendMessage("[MCDiag] Usage: /diag module <id> on|off");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!plugin.modules().has(id)) {
            sender.sendMessage("[MCDiag] Unknown module: " + id);
            return;
        }
        boolean on = args[2].equalsIgnoreCase("on");
        plugin.getConfig().set("modules." + id + ".enabled", on);
        plugin.saveConfig();
        String problem = plugin.reload();
        sender.sendMessage("[MCDiag] Module " + id + " " + (on ? "enabled" : "disabled")
                + (problem == null ? "" : " — " + problem)
                + (on && (id.equals("logs") || id.equals("network")) ? " (the capture hook itself is installed at server start)" : ""));
    }

    private void status(CommandSender sender) {
        AgentConfig cfg = plugin.cfg();
        TelemetryBuffer.Stats b = plugin.buffer().stats();
        BatchSender.Status s = plugin.sender().status();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / MB;

        sender.sendMessage("[MCDiag] Agent " + plugin.getDescription().getVersion());
        sender.sendMessage(" Upload: " + (cfg.canSend() ? "enabled → " + cfg.backendUrl().getHost() : "DISABLED — " + cfg.problem()));
        sender.sendMessage(" Token: " + cfg.maskedToken());
        sender.sendMessage(" Buffer: " + b.events() + "/" + b.maxEvents() + " events, "
                + b.metrics() + "/" + TelemetryBuffer.MAX_METRICS + " metrics, "
                + b.profiles() + "/" + TelemetryBuffer.MAX_PROFILES + " profiles, ~"
                + (b.approxBytes() + 1023) / 1024 + " KB, dropped " + b.dropped());
        sender.sendMessage(" Last upload: " + s.lastResult()
                + (s.lastSuccessAt() > 0 ? ", last OK " + (System.currentTimeMillis() - s.lastSuccessAt()) / 1000 + "s ago" : "")
                + ", batches sent " + s.sentBatches()
                + (s.pendingItems() > 0 ? ", retry pending (" + s.pendingItems() + " items)" : "")
                + (s.inFlight() ? ", in flight" : ""));
        Profiler p = plugin.profiler();
        if (p == null) {
            sender.sendMessage(" Profiler: off");
        } else {
            double overhead = p.overheadPct();
            sender.sendMessage(" Profiler: " + fmt(p.stackReadsPerSec(), 1) + " stack reads/s"
                    + (p.usesHandshake() ? " (no JVM stop)" : " (JVM stop, Java 19+ avoids it)")
                    + ", " + fmt(p.samplingPct(), 3) + "% of time"
                    + (overhead >= 0 ? ", own CPU " + fmt(overhead, 2) + "% of one core" : "")
                    + ", " + p.cachedClasses() + " classes cached"
                    + (plugin.jfr() != null && plugin.jfr().active() ? ", JFR on" : ", JFR off"));
        }
        sender.sendMessage(" Chat log: " + (plugin.chatEnabled() ? "chat on" : "chat off")
                + (plugin.commandsEnabled() ? ", commands on" : ", commands off"));
        sender.sendMessage(" Economy: " + plugin.economyStatus());
        if (plugin.panel().free()) {
            sender.sendMessage(" Mode: free. Only basic metrics are sent, once per 5 minutes.");
        }
        sender.sendMessage(" JVM heap: " + used + " / " + rt.maxMemory() / MB + " MB");
    }

    private void plugins(CommandSender sender) {
        Profiler p = plugin.profiler();
        Profiler.Summary summary = p == null ? null : p.lastSummary();
        if (summary == null) {
            sender.sendMessage("[MCDiag] " + (p == null
                    ? "Profiler is off (profiler.enabled in config.yml)."
                    : "No data yet — the first minute is still being collected."));
            return;
        }
        long ticks = Math.max(1, summary.ticks().ticks());
        double busy = Math.max(1, summary.busyMs());
        sender.sendMessage("[MCDiag] Main thread, last full minute: avg tick " + fmt(summary.ticks().avgMs(), 1)
                + " ms, max " + fmt(summary.ticks().maxMs(), 0) + " ms");
        int n = 0;
        for (ProfileWindow.Entry e : summary.top()) {
            if (e.mainMs() <= 0) {
                continue;
            }
            sender.sendMessage(String.format(Locale.ROOT, " %2d. %-24s %6.2f ms/tick  %4.1f%%",
                    ++n, e.name(), e.mainMs() / ticks, e.mainMs() / busy * 100));
        }
        sender.sendMessage(String.format(Locale.ROOT, "     %-24s %6.2f ms/tick  %4.1f%%",
                "server (core)", summary.coreMs() / ticks, summary.coreMs() / busy * 100));
        sender.sendMessage(" Estimated by sampling. Charts and forecast: MCDiag panel → server → Plugins.");
    }

    private void spikes(CommandSender sender) {
        Profiler p = plugin.profiler();
        List<SpikeRecord> spikes = p == null ? List.of() : p.recentSpikes();
        if (spikes.isEmpty()) {
            sender.sendMessage("[MCDiag] No lag spikes recorded" + (p == null ? " (profiler is off)." : " since start."));
            return;
        }
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        sender.sendMessage("[MCDiag] Recent lag spikes:");
        for (int i = spikes.size() - 1; i >= 0; i--) {
            SpikeRecord s = spikes.get(i);
            StringBuilder line = new StringBuilder(" ").append(time.format(new Date(s.ts()))).append("  ")
                    .append(fmt(s.ms(), 0)).append(" ms");
            for (SpikeRecord.Share share : s.top()) {
                String name = FrameClassifier.isCoreKey(share.name()) ? "server:" + share.name().substring(1) : share.name();
                line.append("  ").append(name).append(' ').append(fmt(share.share() * 100, 0)).append('%');
            }
            sender.sendMessage(line.toString());
        }
    }

    private void token(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("[MCDiag] Token: " + plugin.cfg().maskedToken());
            return;
        }
        // Команды игроков пишутся в лог («issued server command») — токен туда попасть не должен.
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("[MCDiag] For safety the token can only be set from the server console.");
            return;
        }
        String token = args[1].trim();
        if (!AgentConfig.TOKEN.matcher(token).matches()) {
            sender.sendMessage("[MCDiag] Invalid token format (expected mcd_<serverId>_<uuid>).");
            return;
        }
        plugin.getConfig().set("server_token", token);
        plugin.saveConfig();
        String problem = plugin.reload();
        sender.sendMessage("[MCDiag] Token saved: " + plugin.cfg().maskedToken()
                + (problem == null ? "" : " — " + problem));
    }

    private static String fmt(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }
}
