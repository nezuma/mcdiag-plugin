package net.mcdiag.agent.collect;

import java.util.logging.Level;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.json.Json;
import net.mcdiag.agent.profiler.ProfileWindow;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Журнал команд игроков. Работает, только если владелец включил его на сайте; иначе обработчик
 * сразу выходит. Чат, консоль и командные блоки не записываются.
 */
public final class CommandListener implements Listener {

    private static final int MAX_TEXT = 256;
    private static final int MAX_NICK = 32;
    private static final int MAX_WORLD = 64;

    private final McDiagPlugin plugin;

    public CommandListener(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.commandsEnabled()) {
            return;
        }
        // Исключение здесь Bukkit написал бы в консоль сервера как ошибку.
        try {
            String line = event.getMessage();
            add("cmd", event.getPlayer(), plugin.mask().command(line), ownerOf(line));
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.FINE, "command log failed", e);
        }
    }

    private void add(String kind, Player player, String text, String owner) {
        plugin.buffer().addChat(line(System.currentTimeMillis(), kind, player.getName(), player.getWorld().getName(), text, owner));
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
        StringBuilder clean = null;
        for (int i = 0; i < nick.length(); i++) {
            char c = nick.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_' || c == '.' || c == '*' || c == '-';
            if (!ok && clean == null) {
                clean = new StringBuilder(nick.length()).append(nick, 0, i);
            } else if (ok && clean != null) {
                clean.append(c);
            }
        }
        if (clean == null) {
            return nick.isEmpty() ? "unknown" : nick;
        }
        return clean.length() == 0 ? "unknown" : clean.toString();
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
