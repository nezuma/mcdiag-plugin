package net.mcdiag.agent.network;

import java.util.Set;
import net.mcdiag.agent.module.Channel;
import net.mcdiag.agent.module.ModuleRegistry;
import net.mcdiag.agent.profiler.AsyncTracker;
import net.mcdiag.api.AgentModule;
import net.mcdiag.api.ModuleContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Куда и как часто ходят плагины: раз в минуту сводка в раздел {@code net} батча. */
public final class NetworkModule implements AgentModule {

    public static final String ID = "network";
    private static final long DRAIN_TICKS = 60 * 20;

    private final NetTap tap;
    private final ModuleRegistry registry;
    private final NetAggregator aggregator;
    private volatile Channel channel;

    /** @param aggregator копит соединения с запуска сервера, ещё до ответа панели */
    public NetworkModule(NetTap tap, NetAggregator aggregator, ModuleRegistry registry) {
        this.tap = tap;
        this.aggregator = aggregator;
        this.registry = registry;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "внешние HTTP/сокет-соединения плагинов: хост, частота, главный поток";
    }

    @Override
    public Set<String> requiredCaps() {
        return Set.of("net");
    }

    @Override
    public void enable(ModuleContext ctx) {
        channel = registry.channel("net", "net", 512, 256);
        tap.setOwners(registry::pluginOfClass, threadOwner());
        tap.setSink(aggregator);
        ctx.repeat(this::drain, DRAIN_TICKS, DRAIN_TICKS, true);
    }

    @Override
    public void disable() {
        tap.setSink(null);
        aggregator.drain();
        Channel current = channel;
        if (current != null) {
            current.clear();
        }
    }

    @Override
    public String status() {
        return aggregator.pending() + " hosts this minute, dropped " + aggregator.dropped();
    }

    private void drain() {
        Channel current = channel;
        if (current == null) {
            return;
        }
        for (String json : aggregator.drain()) {
            current.offer(json);
        }
    }

    /** Имя потока → плагин: для пулов, в стеке которых нет кода плагина. */
    public static java.util.function.Function<String, String> threadOwner() {
        String[] names = pluginNames();
        return thread -> AsyncTracker.pluginFromName(thread, names);
    }

    private static String[] pluginNames() {
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        String[] names = new String[plugins.length];
        for (int i = 0; i < plugins.length; i++) {
            names[i] = plugins[i].getName();
        }
        return names;
    }
}
