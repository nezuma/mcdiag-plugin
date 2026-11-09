package net.mcdiag.agent.economy;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/** PlayerPoints — донатная валюта многих серверов, через reflection, без зависимости в jar. */
final class PlayerPointsSource implements EconomySource {

    private final Object api;
    private final Method look;

    private PlayerPointsSource(Object api, Method look) {
        this.api = api;
        this.look = look;
    }

    static PlayerPointsSource find() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            Object api = plugin.getClass().getMethod("getAPI").invoke(plugin);
            if (api == null) {
                return null;
            }
            Method look = api.getClass().getMethod("look", UUID.class);
            return new PlayerPointsSource(api, look);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    @Override
    public String id() {
        return "playerpoints";
    }

    @Override
    public String name() {
        return "PlayerPoints";
    }

    @Override
    public String unit() {
        return null;
    }

    @Override
    public Double balance(OfflinePlayer player) {
        try {
            Object value = look.invoke(api, player.getUniqueId());
            return value instanceof Number n ? n.doubleValue() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
