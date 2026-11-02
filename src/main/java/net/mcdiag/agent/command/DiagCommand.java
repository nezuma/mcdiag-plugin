package net.mcdiag.agent.command;

import java.util.List;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.buffer.TelemetryBuffer;
import net.mcdiag.agent.config.AgentConfig;
import net.mcdiag.agent.transport.BatchSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;

/** /diag status | token [value] | reload */
public final class DiagCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("status", "token", "reload");
    private static final long MB = 1024 * 1024;

    private final McDiagPlugin plugin;

    public DiagCommand(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "token" -> token(sender, args);
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
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
        return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    private void status(CommandSender sender) {
        AgentConfig cfg = plugin.cfg();
        TelemetryBuffer.Stats b = plugin.buffer().stats();
        BatchSender.Status s = plugin.sender().status();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / MB;

        sender.sendMessage("[MCDiag] Agent " + plugin.getPluginMeta().getVersion());
        sender.sendMessage(" Upload: " + (cfg.canSend() ? "enabled → " + cfg.backendUrl().getHost() : "DISABLED — " + cfg.problem()));
        sender.sendMessage(" Token: " + cfg.maskedToken());
        sender.sendMessage(" Buffer: " + b.events() + "/" + b.maxEvents() + " events, "
                + b.metrics() + "/" + TelemetryBuffer.MAX_METRICS + " metrics, ~"
                + (b.approxBytes() + 1023) / 1024 + " KB, dropped " + b.dropped());
        sender.sendMessage(" Last upload: " + s.lastResult()
                + (s.lastSuccessAt() > 0 ? ", last OK " + (System.currentTimeMillis() - s.lastSuccessAt()) / 1000 + "s ago" : "")
                + ", batches sent " + s.sentBatches()
                + (s.pendingItems() > 0 ? ", retry pending (" + s.pendingItems() + " items)" : "")
                + (s.inFlight() ? ", in flight" : ""));
        sender.sendMessage(" JVM heap: " + used + " / " + rt.maxMemory() / MB + " MB");
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
}
