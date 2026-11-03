package net.mcdiag.agent.collect;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.json.Json;
import net.mcdiag.agent.profiler.ProfileWindow;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Журнал чата и команд. Работает, только если владелец включил его на сайте; иначе обработчики
 * сразу выходят. Команды командных блоков не пишутся: они срабатывают каждый тик и засоряют журнал.
 */
public final class ChatListener implements Listener {

    private static final int MAX_TEXT = 256;
    private static final int MAX_NICK = 32;
    private static final int MAX_WORLD = 64;

    private final McDiagPlugin plugin;

    public ChatListener(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.chatEnabled()) {
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        add("chat", event.getPlayer(), plugin.mask().text(text), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.commandsEnabled()) {
            return;
        }
        String line = event.getMessage();
        add("cmd", event.getPlayer(), plugin.mask().command(line), ownerOf(line));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsole(ServerCommandEvent event) {
        if (plugin.commandsEnabled() && event.getSender() instanceof ConsoleCommandSender) {
            add("console", null, plugin.mask().command(event.getCommand()), ownerOf(event.getCommand()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRcon(RemoteServerCommandEvent event) {
        if (plugin.commandsEnabled()) {
            add("console", null, plugin.mask().command(event.getCommand()), ownerOf(event.getCommand()));
        }
    }

    private void add(String kind, Player player, String text, String owner) {
        plugin.buffer().addChat(line(System.currentTimeMillis(), kind,
                player == null ? null : player.getName(),
                player == null ? null : player.getWorld().getName(), text, owner));
    }

    /** Элемент поля {@code chatlog} батча. */
    static String line(long ts, String kind, String nick, String world, String text, String owner) {
        StringBuilder sb = new StringBuilder(128).append("{\"ts\":").append(ts).append(",\"k\":\"").append(kind).append('"');
        String safeNick = safeNick(nick);
        if (safeNick != null) {
            Json.str(sb.append(",\"n\":"), safeNick, MAX_NICK);
        }
        Json.str(sb.append(",\"t\":"), text, MAX_TEXT);
        if (ProfileWindow.validName(owner)) {
            Json.str(sb.append(",\"p\":"), owner, 64);
        }
        if (world != null && !world.isEmpty()) {
            Json.str(sb.append(",\"w\":"), world, MAX_WORLD);
        }
        return sb.append('}').toString();
    }

    /** Ник под шаблон панели: непонятный символ не должен стоить всего батча. */
    static String safeNick(String nick) {
        if (nick == null) {
            return null;
        }
        String clean = nick.replaceAll("[^A-Za-z0-9_.*-]", "");
        return clean.isEmpty() ? "unknown" : clean;
    }

    /** Какой плагин обрабатывает команду: по нему панель связывает команду со спайком. */
    private static String ownerOf(String line) {
        String trimmed = line.trim();
        int space = trimmed.indexOf(' ');
        String head = space < 0 ? trimmed : trimmed.substring(0, space);
        String label = head.startsWith("/") ? head.substring(1) : head;
        if (label.isEmpty()) {
            return null;
        }
        try {
            Command command = Bukkit.getCommandMap().getCommand(label);
            return command instanceof PluginIdentifiableCommand owned ? owned.getPlugin().getName() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
