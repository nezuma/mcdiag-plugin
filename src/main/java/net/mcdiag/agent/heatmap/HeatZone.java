package net.mcdiag.agent.heatmap;

/** Кубоид зоны спавна. Границы включительно. */
public record HeatZone(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** Сторона карты: больше делать нечего, а данные и рельеф растут квадратично. */
    public static final int MAX_SIDE = 512;
    public static final int CELL = 2;

    public static HeatZone of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new HeatZone(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY;
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public boolean tooBig() {
        return width() > MAX_SIDE || depth() > MAX_SIDE;
    }

    /** По этому значению панель не смешивает данные разных зон. */
    public String id() {
        return world + ":" + minX + ":" + minY + ":" + minZ + ":" + maxX + ":" + maxY + ":" + maxZ;
    }
}
