package net.mcdiag.agent.collect;

import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.json.Json;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Метрики сервера. {@link #run()} выполняется асинхронно: TPS, MSPT и online читаются из
 * потокобезопасных счётчиков Paper. Число сущностей так читать нельзя, поэтому его снимает
 * {@link #entitySampler()} — крошечный синхронный таск, кладущий результат в volatile-поле.
 */
public final class MetricsTask implements Runnable {

    private static final long BUCKET_MS = 5_000;

    private final McDiagPlugin plugin;
    private volatile int entities = -1;

    public MetricsTask(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.cfg().collectMetrics()) {
            return;
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
        plugin.buffer().addMetric(sb.append('}').toString());
    }

    /** Основной поток: сумма {@code World#getEntityCount()} по мирам, без копирования списков сущностей. */
    public Runnable entitySampler() {
        return () -> {
            if (!plugin.cfg().collectMetrics()) {
                return;
            }
            int total = 0;
            for (World world : Bukkit.getWorlds()) {
                total += world.getEntityCount();
            }
            entities = total;
        };
    }
}
