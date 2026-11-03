package net.mcdiag.agent.profiler;

/**
 * Куда отнести кадр стека, если это не код плагина. Чистые функции по имени класса —
 * без Bukkit, чтобы проверять их unit-тестами.
 *
 * <p>Ключи частей ядра начинаются с «~»: имена плагинов Bukkit такой символ содержать не могут.
 */
public final class FrameClassifier {

    public static final String ENTITIES = "~entities";
    public static final String CHUNKS = "~chunks";
    public static final String BLOCKS = "~blocks";
    public static final String NETWORK = "~network";
    public static final String SAVING = "~saving";
    public static final String OTHER = "~other";

    /** Порядок важен: chunk.storage — это сохранение, а не генерация чанков. */
    private static final String[][] CATEGORIES = {
        {SAVING, "net.minecraft.world.level.storage.", "net.minecraft.world.level.chunk.storage.",
            "net.minecraft.nbt.", "net.minecraft.util.worldupdate."},
        {ENTITIES, "net.minecraft.world.entity.", "net.minecraft.world.level.entity.",
            "net.minecraft.world.level.pathfinder.", "net.minecraft.server.level.ChunkMap$TrackedEntity",
            "org.bukkit.craftbukkit.entity."},
        {CHUNKS, "net.minecraft.server.level.ChunkMap", "net.minecraft.server.level.ServerChunkCache",
            "net.minecraft.server.level.ChunkHolder", "net.minecraft.server.level.DistanceManager",
            "net.minecraft.world.level.chunk.", "net.minecraft.world.level.levelgen.",
            "net.minecraft.world.level.lighting.", "ca.spottedleaf.", "io.papermc.paper.chunk."},
        {BLOCKS, "net.minecraft.world.level.block.", "net.minecraft.world.level.redstone.",
            "net.minecraft.world.ticks.", "net.minecraft.world.level.material."},
        {NETWORK, "net.minecraft.network.", "net.minecraft.server.network.", "io.netty."},
    };

    /** Код ядра и его библиотек: в плагины такие классы не попадают, forName для них не нужен. */
    private static final String[] CORE_PREFIXES = {
        "net.minecraft.", "org.bukkit.", "io.papermc.", "com.destroystokyo.paper.", "org.spigotmc.",
        "co.aikar.", "com.mojang.", "ca.spottedleaf.", "io.netty.", "it.unimi.dsi.", "com.google.",
        "org.apache.", "org.slf4j.", "net.kyori.", "org.purpurmc.", "gg.pufferfish.", "org.leavesmc.",
        "org.dreeam.", "com.velocitypowered.", "org.yaml.", "joptsimple.", "org.jline.", "net.minecrell.",
    };

    /** JDK: «нейтральные» кадры, смотрим глубже по стеку. */
    private static final String[] NEUTRAL_PREFIXES = {"java.", "javax.", "jdk.", "sun.", "com.sun."};

    private FrameClassifier() {
    }

    public static boolean isNeutral(String className) {
        return startsWithAny(className, NEUTRAL_PREFIXES);
    }

    public static boolean isCore(String className) {
        return startsWithAny(className, CORE_PREFIXES);
    }

    /** Часть ядра для класса или {@code null}, если класс не из конкретной части. */
    public static String category(String className) {
        for (String[] row : CATEGORIES) {
            for (int i = 1; i < row.length; i++) {
                if (className.startsWith(row[i])) {
                    return row[0];
                }
            }
        }
        return null;
    }

    /** Поток спит или ждёт: верхний кадр — park/sleep/wait. */
    public static boolean isWaiting(Thread.State state, StackTraceElement top) {
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
            return false;
        }
        if (top == null) {
            return true;
        }
        String m = top.getMethodName();
        return m.startsWith("park") || m.startsWith("sleep") || m.startsWith("wait") || m.equals("yield");
    }

    public static boolean isCoreKey(String key) {
        return key.startsWith("~");
    }

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
