package net.mcdiag.agent.collect;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mcdiag.agent.McDiagPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Журнал чата. Пишется, только если владелец включил чат на сайте и не запретил журнал в config.yml;
 * иначе обработчик сразу выходит. Событие приходит не из главного потока, буфер это допускает.
 *
 * <p>Отменённые сообщения тоже записываются, с пометкой: так модератор видит, что писал игрок
 * в муте или что отсёк фильтр. Плагины чата, которые отменяют событие и рассылают сообщение сами,
 * тоже попадают в журнал именно так.
 */
public final class ChatListener implements Listener {

    private final McDiagPlugin plugin;

    public ChatListener(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.chatEnabled()) {
            return;
        }
        try {
            String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            if (text.isEmpty()) {
                return;
            }
            Player player = event.getPlayer();
            plugin.buffer().addChat(CommandListener.line(System.currentTimeMillis(), "chat", player.getName(),
                    player.getWorld().getName(), plugin.mask().text(text), null, event.isCancelled()));
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.FINE, "chat log failed", e);
        }
    }
}
