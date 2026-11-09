package net.mcdiag.agent.economy;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault Economy через reflection: в jar нет зависимости от Vault, а через него видны EssentialsX,
 * CMI и большинство плагинов с валютой.
 */
final class VaultSource implements EconomySource {

    private static final String ECONOMY = "net.milkbowl.vault.economy.Economy";

    private final Object provider;
    private final Method balance;
    private final String name;
    private final String unit;

    private VaultSource(Object provider, Method balance, String name, String unit) {
        this.provider = provider;
        this.balance = balance;
        this.name = name;
        this.unit = unit;
    }

    /** @return источник или null, если Vault нет или экономика не зарегистрирована */
    static VaultSource find() {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            return null;
        }
        try {
            Class<?> type = vault.getClass().getClassLoader().loadClass(ECONOMY);
            RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration(type);
            if (reg == null || reg.getProvider() == null) {
                return null;
            }
            Object provider = reg.getProvider();
            Method balance = type.getMethod("getBalance", OfflinePlayer.class);
            String name = text(type.getMethod("getName").invoke(provider));
            String unit = text(type.getMethod("currencyNamePlural").invoke(provider));
            return new VaultSource(provider, balance, name == null ? reg.getPlugin().getName() : name, unit);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    @Override
    public String id() {
        return "vault";
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String unit() {
        return unit;
    }

    @Override
    public Double balance(OfflinePlayer player) {
        try {
            Object value = balance.invoke(provider, player);
            return value instanceof Number n ? n.doubleValue() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static String text(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
