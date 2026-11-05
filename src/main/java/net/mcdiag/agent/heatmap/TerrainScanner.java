package net.mcdiag.agent.heatmap;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.mcdiag.agent.McDiagPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

/**
 * Снимок рельефа зоны видом сверху: один верхний блок на каждую точку X и Z внутри кубоида.
 * Запускается только по команде или после выбора зоны. Чанки грузятся асинхронно и не генерируются,
 * снимок берётся по одному чанку раз в несколько тиков, разбор идёт вне главного потока.
 */
final class TerrainScanner {

    private static final long TICKS_BETWEEN_CHUNKS = 2;
    /** На перегруженном сервере рельеф подождёт */
    private static final double MAX_MSPT = 40;

    private final McDiagPlugin plugin;
    private final HeatZone zone;
    private final Consumer<String> done;
    private final ArrayDeque<int[]> queue = new ArrayDeque<>();
    private final int[] colors;
    private final int[] heights;
    private final int total;
    private int finished;
    private volatile boolean cancelled;
    private BukkitTask task;

    TerrainScanner(McDiagPlugin plugin, HeatZone zone, Consumer<String> done) {
        this.plugin = plugin;
        this.zone = zone;
        this.done = done;
        this.colors = new int[zone.width() * zone.depth()];
        this.heights = new int[zone.width() * zone.depth()];
        for (int cx = zone.minX() >> 4; cx <= zone.maxX() >> 4; cx++) {
            for (int cz = zone.minZ() >> 4; cz <= zone.maxZ() >> 4; cz++) {
                queue.add(new int[] {cx, cz});
            }
        }
        this.total = queue.size();
    }

    int chunks() {
        return total;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::step, 1, TICKS_BETWEEN_CHUNKS);
    }

    void cancel() {
        cancelled = true;
        if (task != null) {
            task.cancel();
        }
    }

    private void step() {
        if (cancelled || Bukkit.getAverageTickTime() > MAX_MSPT) {
            return;
        }
        int[] next = queue.poll();
        if (next == null) {
            task.cancel();
            return;
        }
        World world = Bukkit.getWorld(zone.world());
        if (world == null) {
            cancel();
            return;
        }
        world.getChunkAtAsync(next[0], next[1], false).whenComplete((chunk, error) -> {
            if (cancelled) {
                return;
            }
            if (chunk == null || error != null) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, this::oneDone);
                return;
            }
            // Снимок берём в главном потоке; колбэк и так выполняется в нём.
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> process(snapshot));
        });
    }

    private void process(ChunkSnapshot snapshot) {
        try {
            int baseX = snapshot.getX() << 4;
            int baseZ = snapshot.getZ() << 4;
            for (int lx = 0; lx < 16; lx++) {
                int x = baseX + lx;
                if (x < zone.minX() || x > zone.maxX()) {
                    continue;
                }
                for (int lz = 0; lz < 16; lz++) {
                    int z = baseZ + lz;
                    if (z < zone.minZ() || z > zone.maxZ()) {
                        continue;
                    }
                    scanColumn(snapshot, lx, lz, x, z);
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "terrain scan failed", e);
        }
        oneDone();
    }

    private void scanColumn(ChunkSnapshot snapshot, int lx, int lz, int x, int z) {
        for (int y = zone.maxY(); y >= zone.minY(); y--) {
            Material type = snapshot.getBlockType(lx, y, lz);
            if (!type.isAir()) {
                int at = (z - zone.minZ()) * zone.width() + (x - zone.minX());
                synchronized (this) {
                    colors[at] = TerrainPalette.colorOf(type.name());
                    heights[at] = y;
                }
                return;
            }
        }
    }

    private void oneDone() {
        boolean all;
        synchronized (this) {
            all = ++finished >= total;
        }
        if (all && !cancelled) {
            done.accept(render());
        }
    }

    private synchronized String render() {
        int width = zone.width();
        int[] pixels = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            int north = i >= width ? heights[i - width] : heights[i];
            pixels[i] = colors[i] == 0 ? 0 : TerrainPalette.shade(colors[i], heights[i], north);
        }
        return TerrainPalette.encode(zone.id(), width, zone.depth(), pixels);
    }
}
