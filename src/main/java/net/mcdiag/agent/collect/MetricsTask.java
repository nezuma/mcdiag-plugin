package net.mcdiag.agent.collect;

import java.util.ArrayList;
import java.util.List;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.json.Json;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Метрики сервера. {@link #run()} выполняется асинхронно: TPS, MSPT и online читаются из
 * потокобезопасных счётчиков Paper. Сущности и чанки так читать нельзя, поэтому их снимает
 * {@link #entitySampler()} — синхронный таск, кладущий результат в volatile-поля.
 */
public final class MetricsTask implements Runnable {

    private static final long BUCKET_MS = 5_000;
    private static final long FREE_POINT_MS = 290_000;

    private final McDiagPlugin plugin;
    private volatile int entities = -1;
    private long lastFreePoint;
    private volatile int chunks = -1;
    private volatile int tiles = -1;

    public MetricsTask(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.cfg().collectMetrics()) {
            return;
        }
        // Бесплатный режим: отправляется одна точка в 5 минут, чаще считать незачем.
        if (plugin.panel().free()) {
            long now = System.currentTimeMillis();
            if (now - lastFreePoint < FREE_POINT_MS) {
                return;
            }
            lastFreePoint = now;
        }
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        double mspt = Math.min(10_000.0, Bukkit.getAverageTickTime());
        int online = Bukkit.getOnlinePlayers().size();
        // Бэкенд хранит точки 5-секундными бакетами.
        long ts = System.currentTimeMillis() / BUCKET_MS * BUCKET_MS;

        StringBuilder sb = new StringBuilder(96).append("{\"ts\":").append(ts).append(",\"tps\":");
        Json.decimal2(sb, tps).append(",\"mspt\":");
        Json.decimal2(sb, mspt).append(",\"online\":").append(online);
        int snapshot = entities;
        if (snapshot >= 0) {
            sb.append(",\"entities\":").append(snapshot);
        }
        int loaded = chunks;
        if (loaded >= 0) {
            sb.append(",\"chunks\":").append(loaded).append(",\"tiles\":").append(Math.max(0, tiles));
        }
        plugin.buffer().addMetric(sb.append('}').toString());
    }

    /** Основной поток: один мир за запуск, см. {@link WorldCounter}. */
    public Runnable entitySampler() {
        WorldCounter counter = new WorldCounter();
        return () -> {
            if (!plugin.cfg().collectMetrics() || plugin.panel().free()) {
                return;
            }
            List<WorldProbe> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                worlds.add(new WorldProbe(world));
            }
            counter.update(worlds);
            entities = counter.entities();
            chunks = counter.chunks();
            tiles = counter.tiles();
        };
    }

    private record WorldProbe(World world) implements WorldCounter.Probe {
        @Override
        public String name() {
            return world.getName();
        }

        @Override
        public int entities() {
            return world.getEntityCount();
        }

        @Override
        public int chunks() {
            return world.getChunkCount();
        }

        @Override
        public int tiles() {
            return world.getTileEntityCount();
        }
    }
}
