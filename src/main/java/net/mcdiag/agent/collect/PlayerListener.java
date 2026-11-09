package net.mcdiag.agent.collect;

import java.net.InetSocketAddress;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.json.Json;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Join / quit / death → JSON-строка в буфер. Ссылки на Player/Entity/World живут только в локальных
 * переменных обработчика; наружу уходят хеш, ник, имя мира и enum-имена. Ник, IP и адрес входа
 * добавляются, только если панель о них объявила: старая панель отклонила бы батч.
 */
public final class PlayerListener implements Listener {

    private static final int MAX_WORLD = 64;
    private static final long MAX_SESSION_SEC = 7 * 24 * 3600;
    private static final int MAX_HOST = 253;

    private final McDiagPlugin plugin;

    public PlayerListener(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.cfg().collectJoins() || plugin.panel().free()) {
            return;
        }
        Player player = event.getPlayer();
        StringBuilder sb = start("join", player);
        sb.append(",\"firstJoin\":").append(!player.hasPlayedBefore()).append(",\"world\":");
        Json.str(sb, player.getWorld().getName(), MAX_WORLD);
        if (plugin.cfg().collectJoinSource() && plugin.panel().joins()) {
            appendSource(sb, player);
        }
        sb.append('}');
        plugin.buffer().addEvent(sb.toString());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.cfg().collectQuits() || plugin.panel().free()) {
            return;
        }
        Player player = event.getPlayer();
        StringBuilder sb = start("quit", player);
        // Время входа хранит сам сервер — своя Map<UUID, Long> не нужна.
        long login = player.getLastLogin();
        if (login > 0) {
            long sec = (System.currentTimeMillis() - login) / 1000;
            sb.append(",\"sessionSec\":").append(Math.max(0, Math.min(MAX_SESSION_SEC, sec)));
        }
        plugin.buffer().addEvent(sb.append('}').toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.cfg().collectDeaths() || plugin.panel().free()) {
            return;
        }
        Player player = event.getEntity();
        StringBuilder sb = start("death", player);
        EntityDamageEvent last = player.getLastDamageCause();
        String cause = last == null ? null : Json.enumName(last.getCause().name());
        if (cause != null) {
            sb.append(",\"cause\":\"").append(cause).append('"');
        }
        sb.append(",\"killerType\":\"").append(killerType(player, last)).append("\",\"world\":");
        Json.str(sb, player.getWorld().getName(), MAX_WORLD).append('}');
        plugin.buffer().addEvent(sb.toString());
    }

    private StringBuilder start(String type, Player player) {
        StringBuilder sb = new StringBuilder(256)
                .append("{\"type\":\"").append(type)
                .append("\",\"ts\":").append(System.currentTimeMillis())
                .append(",\"playerHash\":\"").append(plugin.hasher().hash(player.getUniqueId()))
                .append('"');
        if (plugin.panel().nicks()) {
            Json.str(sb.append(",\"nick\":"), CommandListener.safeNick(player.getName()), 32);
        }
        return sb;
    }

    /** С какого IP и по какому адресу зашёл игрок. За прокси без проброса IP это адрес прокси. */
    private static void appendSource(StringBuilder sb, Player player) {
        InetSocketAddress address = player.getAddress();
        if (address != null && address.getAddress() != null) {
            Json.str(sb.append(",\"ip\":"), address.getAddress().getHostAddress(), 64);
        }
        String host = null;
        try {
            InetSocketAddress virtual = player.getVirtualHost();
            host = virtual == null ? null : cleanHost(virtual.getHostString());
        } catch (RuntimeException | LinkageError ignored) {
            // Ядро без виртуального хоста: адрес входа неизвестен.
        }
        if (host != null) {
            Json.str(sb.append(",\"host\":"), host, MAX_HOST);
        }
    }

    /**
     * «Play.Example.RU.» → «play.example.ru». Forge дописывает «\0FML\0», TCPShield и подобные — служебные
     * части через «///»; всё, что не похоже на домен или IP, отбрасывается.
     */
    static String cleanHost(String raw) {
        if (raw == null) {
            return null;
        }
        String host = raw;
        int cut = host.indexOf('\0');
        if (cut >= 0) {
            host = host.substring(0, cut);
        }
        cut = host.indexOf("///");
        if (cut >= 0) {
            host = host.substring(0, cut);
        }
        host = host.trim().toLowerCase(java.util.Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > MAX_HOST) {
            return null;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_' || c == ':';
            if (!ok) {
                return null;
            }
        }
        return host;
    }

    private static String killerType(Player player, EntityDamageEvent last) {
        if (player.getKiller() != null) {
            return "PLAYER";
        }
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile
                    && projectile.getShooter() instanceof Entity shooter) {
                damager = shooter;
            }
            String name = Json.enumName(damager.getType().name());
            if (name != null) {
                return name;
            }
        }
        return "NONE";
    }
}
