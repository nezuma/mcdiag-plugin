package net.mcdiag.agent.privileges;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.mcdiag.agent.collect.TextMask;
import net.mcdiag.agent.module.Channel;
import net.mcdiag.agent.module.ModuleRegistry;
import net.mcdiag.api.AgentModule;
import net.mcdiag.api.ModuleContext;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * Выдача OP и прав. Список OP сверяется раз в 10 секунд, права игроков онлайн — по одному игроку в
 * две секунды; команды управления правами и журнал LuckPerms пишутся сразу.
 *
 * <p>Наблюдение начинается при включении агента ({@link #startEarly}), а не после ответа панели:
 * иначе OP, выданный бэкдором при запуске сервера, попал бы в исходный список как норма. До
 * включения модуля записи копятся в ограниченной очереди.
 */
public final class PrivilegesModule implements AgentModule, Listener {

    public static final String ID = "privileges";
    private static final long OPS_TICKS = 10 * 20;
    private static final long ATTACHMENT_TICKS = 2 * 20;
    private static final long SWEEP_TICKS = 2 * 20;
    /** Плагины прав выдают права по должности: их вложения не подозрительны. */
    private static final Set<String> PERMISSION_PLUGINS = Set.of(
            "luckperms", "permissionsex", "groupmanager", "ultrapermissions", "powerranks", "vault", "mcdiag");

    private final ModuleRegistry registry;
    private final java.util.function.Supplier<TextMask> mask;
    private static final int MAX_EARLY = 200;

    private volatile Channel channel;
    private volatile PrivilegeAudit audit;
    private LuckPermsBridge luckPerms;
    private int scanIndex;
    private final List<org.bukkit.scheduler.BukkitTask> tasks = new ArrayList<>();
    private final java.util.ArrayDeque<String> early = new java.util.ArrayDeque<>();

    public PrivilegesModule(ModuleRegistry registry, java.util.function.Supplier<TextMask> mask) {
        this.registry = registry;
        this.mask = mask;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "выдача OP и опасных прав: команды, LuckPerms, вложения плагинов";
    }

    @Override
    public Set<String> requiredCaps() {
        return Set.of("audit");
    }

    /** Главный поток, при включении агента. Повторный вызов ничего не делает. */
    public void startEarly(org.bukkit.plugin.Plugin agent, List<String> dangerous) {
        if (audit != null) {
            return;
        }
        Set<String> extra = new HashSet<>();
        for (String p : dangerous) {
            extra.add(p.toLowerCase(Locale.ROOT).trim());
        }
        PrivilegeAudit next = new PrivilegeAudit(this::emit, Set.copyOf(extra));
        audit = next;
        Bukkit.getPluginManager().registerEvents(this, agent);
        scanOps();
        org.bukkit.scheduler.BukkitScheduler scheduler = Bukkit.getScheduler();
        java.util.logging.Logger log = agent.getLogger();
        tasks.add(scheduler.runTaskTimer(agent, net.mcdiag.agent.util.Safe.task(log, "ops scan", this::scanOps), OPS_TICKS, OPS_TICKS));
        tasks.add(scheduler.runTaskTimer(agent, net.mcdiag.agent.util.Safe.task(log, "permission scan", this::scanAttachments), ATTACHMENT_TICKS, ATTACHMENT_TICKS));
        tasks.add(scheduler.runTaskTimerAsynchronously(agent, net.mcdiag.agent.util.Safe.task(log, "privilege sweep",
                () -> next.sweep(System.currentTimeMillis())), SWEEP_TICKS, SWEEP_TICKS));
        // LuckPerms грузится на STARTUP, раньше агента: если он есть, он уже включён.
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                luckPerms = LuckPermsBridge.hook(agent, next);
            } catch (LinkageError | RuntimeException e) {
                log.log(Level.FINE, "LuckPerms audit is unavailable", e);
            }
        }
    }

    /** Наблюдение не нужно (панель без журнала прав, бесплатный тариф, модуль выключен). */
    public void stop() {
        HandlerList.unregisterAll(this);
        for (org.bukkit.scheduler.BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        if (luckPerms != null) {
            luckPerms.close();
            luckPerms = null;
        }
        audit = null;
        synchronized (early) {
            early.clear();
        }
    }

    public boolean running() {
        return audit != null;
    }

    @Override
    public void enable(ModuleContext ctx) {
        Channel ch = registry.channel("audit", "audit", 500, 200);
        if (audit == null) {
            startEarly(ctx.agent(), ctx.config().getStringList("dangerous_permissions"));
        }
        synchronized (early) {
            for (String json : early) {
                ch.offer(json);
            }
            early.clear();
            channel = ch;
        }
    }

    @Override
    public void disable() {
        channel = null;
        stop();
    }

    private void emit(String json) {
        Channel ch = channel;
        if (ch != null) {
            ch.offer(json);
            return;
        }
        synchronized (early) {
            Channel now = channel;
            if (now != null) {
                now.offer(json);
                return;
            }
            if (early.size() >= MAX_EARLY) {
                early.pollFirst();
            }
            early.addLast(json);
        }
    }

    @Override
    public String status() {
        Channel ch = channel;
        return (luckPerms != null ? "LuckPerms hooked" : "without LuckPerms") + (ch == null ? "" : ", " + ch.size() + " queued");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        command(PrivilegeAudit.Actor.PLAYER, p.getName(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent event) {
        command(actorOf(event.getSender()), senderName(event.getSender()), event.getCommand());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemoteCommand(RemoteServerCommandEvent event) {
        command(PrivilegeAudit.Actor.RCON, null, event.getCommand());
    }

    private void command(PrivilegeAudit.Actor actor, String name, String line) {
        PrivilegeAudit current = audit;
        if (current == null || line == null || !AdminCommands.isAdmin(AdminCommands.label(line))) {
            return;
        }
        try {
            current.command(System.currentTimeMillis(), actor, name, line, mask.get().command(line), ownerOf(AdminCommands.label(line)));
        } catch (RuntimeException ignored) {
            // Учёт не должен мешать выполнению команды.
        }
    }

    private void scanOps() {
        PrivilegeAudit current = audit;
        if (current == null) {
            return;
        }
        Map<UUID, String> ops = new LinkedHashMap<>();
        for (OfflinePlayer op : Bukkit.getOperators()) {
            String name = op.getName();
            ops.put(op.getUniqueId(), name == null ? "" : name);
        }
        current.ops(System.currentTimeMillis(), ops);
    }

    /** Один игрок за запуск: на сервере с сотнями игроков проверка размазана по времени. */
    private void scanAttachments() {
        PrivilegeAudit current = audit;
        if (current == null) {
            return;
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            return;
        }
        Player player = online.get(scanIndex++ % online.size());
        long now = System.currentTimeMillis();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            PermissionAttachment attachment = info.getAttachment();
            if (attachment == null || !info.getValue()) {
                continue;
            }
            String owner = attachment.getPlugin().getName();
            if (PERMISSION_PLUGINS.contains(owner.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (AdminCommands.dangerousPermission(info.getPermission(), Set.of())) {
                current.attachment(now, player.getUniqueId(), player.getName(), info.getPermission(), owner);
            }
        }
    }

    private static PrivilegeAudit.Actor actorOf(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            return PrivilegeAudit.Actor.CONSOLE;
        }
        if (sender instanceof RemoteConsoleCommandSender) {
            return PrivilegeAudit.Actor.RCON;
        }
        if (sender instanceof BlockCommandSender) {
            return PrivilegeAudit.Actor.COMMAND_BLOCK;
        }
        return PrivilegeAudit.Actor.OTHER;
    }

    private static String senderName(CommandSender sender) {
        return sender instanceof ConsoleCommandSender ? null : sender.getName();
    }

    private static String ownerOf(String label) {
        try {
            Command command = Bukkit.getCommandMap().getCommand(label);
            return command instanceof PluginIdentifiableCommand owned ? owned.getPlugin().getName() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
