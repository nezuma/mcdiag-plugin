package net.mcdiag.agent.collect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.json.Json;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Сколько чанков сервер сгенерировал впервые. Генерация во время игры — частая причина падения
 * TPS на мирах без предварительной генерации. На каждый загруженный чанк — одна проверка флага и
 * сложение; счётчики потокобезопасны, так что подходит и для Folia с её потоками регионов.
 */
public final class ChunkGenCounter implements Listener {

    private static final int MAX_WORLDS = 32;
    private static final int MAX_WORLD = 64;

    private final McDiagPlugin plugin;
    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

    public ChunkGenCounter(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk() || !enabled()) {
            return;
        }
        String world = event.getWorld().getName();
        LongAdder adder = counts.get(world);
        if (adder == null) {
            if (counts.size() >= MAX_WORLDS) {
                return;
            }
            adder = counts.computeIfAbsent(world, w -> new LongAdder());
        }
        adder.increment();
    }

    private boolean enabled() {
        return plugin.cfg().collectChunks() && plugin.panel().chunkgen() && !plugin.panel().free();
    }

    /**
     * Минутный срез: {@code {"ts":…,"w":[{"n":"world","c":12}]}}. Минуты без генерации не
     * отправляются, панель считает их нулём.
     *
     * @return JSON или null, если новых чанков не было
     */
    public String drainMinute(long minuteTs) {
        StringBuilder sb = null;
        for (Map.Entry<String, LongAdder> e : counts.entrySet()) {
            long n = e.getValue().sumThenReset();
            if (n <= 0) {
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(96).append("{\"ts\":").append(minuteTs).append(",\"w\":[");
            } else {
                sb.append(',');
            }
            Json.str(sb.append("{\"n\":"), e.getKey(), MAX_WORLD).append(",\"c\":").append(Math.min(n, 10_000_000L)).append('}');
        }
        return sb == null ? null : sb.append("]}").toString();
    }

    /** Панель выключила приём: накопленное не нужно. */
    public void clear() {
        counts.clear();
    }
}
