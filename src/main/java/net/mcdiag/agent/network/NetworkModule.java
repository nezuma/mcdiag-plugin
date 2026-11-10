package net.mcdiag.agent.network;

import java.util.List;
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
    private volatile Channel requests;
    private volatile ModuleContext ctx;

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
        // Пути, параметры и место вызова — отдельный раздел: панель 0.8 его не знает.
        requests = registry.channel("netreq", "netreq", 512, 128);
        this.ctx = ctx;
        Set<String> extra = new java.util.HashSet<>();
        for (String name : ctx.config().getStringList("mask_params")) {
            extra.add(name.toLowerCase(java.util.Locale.ROOT).trim());
        }
        tap.setPathCapture(ctx.config().getBoolean("paths", true), Set.copyOf(extra));
        tap.setOwners(registry::pluginOfClass, threadOwner());
        tap.setSink(aggregator);
        ctx.repeat(this::drain, DRAIN_TICKS, DRAIN_TICKS, true);
    }

    @Override
    public void disable() {
        tap.setSink(null);
        aggregator.clear();
        ctx = null;
        for (Channel current : new Channel[] {channel, requests}) {
            if (current != null) {
                current.clear();
            }
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
        Channel details = requests;
        ModuleContext context = ctx;
        List<String> found = aggregator.drainRequests();
        if (details != null && context != null && context.panelSupports("netreq")) {
            for (String json : found) {
                details.offer(json);
            }
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
