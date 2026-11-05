package net.mcdiag.agent.heatmap;

import net.mcdiag.agent.McDiagPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Рамка зоны частицами. Частицы видит только тот, кто задал зону, и только полминуты.
 * END_ROD есть во всех поддерживаемых версиях: имена многих других частиц менялись.
 */
final class ZoneFrame {

    private static final int RUNS = 30;
    private static final long PERIOD_TICKS = 20;
    /** Сколько частиц на всю рамку за один показ */
    private static final int MAX_PARTICLES = 180;

    private ZoneFrame() {
    }

    static void show(McDiagPlugin plugin, Player player, HeatZone zone) {
        int perimeter = 4 * (zone.width() + zone.depth() + zone.maxY() - zone.minY() + 1);
        int step = Math.max(1, perimeter / MAX_PARTICLES);
        BukkitTask[] task = new BukkitTask[1];
        int[] left = {RUNS};
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            World world = player.getWorld();
            if (!player.isOnline() || left[0]-- <= 0 || !world.getName().equals(zone.world())) {
                task[0].cancel();
                return;
            }
            draw(player, world, zone, step);
        }, 0, PERIOD_TICKS);
    }

    private static void draw(Player player, World world, HeatZone z, int step) {
        int x1 = z.minX();
        int x2 = z.maxX() + 1;
        int y1 = z.minY();
        int y2 = z.maxY() + 1;
        int z1 = z.minZ();
        int z2 = z.maxZ() + 1;
        for (int y : new int[] {y1, y2}) {
            for (int x = x1; x <= x2; x += step) {
                spot(player, world, x, y, z1);
                spot(player, world, x, y, z2);
            }
            for (int v = z1; v <= z2; v += step) {
                spot(player, world, x1, y, v);
                spot(player, world, x2, y, v);
            }
        }
        for (int y = y1; y <= y2; y += step) {
            spot(player, world, x1, y, z1);
            spot(player, world, x2, y, z1);
            spot(player, world, x1, y, z2);
            spot(player, world, x2, y, z2);
        }
    }

    private static void spot(Player player, World world, int x, int y, int z) {
        player.spawnParticle(Particle.END_ROD, new Location(world, x, y, z), 1, 0, 0, 0, 0);
    }
}
