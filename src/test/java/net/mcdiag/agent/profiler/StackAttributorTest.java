package net.mcdiag.agent.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StackAttributorTest {

    private static final Map<String, String> PLUGINS = Map.of(
            "com.sk89q.worldguard.bukkit.listener.EventAbstractionListener", "WorldGuard",
            "net.essentialsx.Essentials", "Essentials");

    private static StackTraceElement frame(String cls) {
        return new StackTraceElement(cls, "run", null, -1);
    }

    private static StackTraceElement[] stack(String... classes) {
        StackTraceElement[] out = new StackTraceElement[classes.length];
        for (int i = 0; i < classes.length; i++) {
            out[i] = frame(classes[i]);
        }
        return out;
    }

    @Test
    void picksTheTopmostPluginFrame() {
        List<String> asked = new ArrayList<>();
        StackAttributor a = new StackAttributor(cls -> {
            asked.add(cls);
            return PLUGINS.get(cls);
        });
        // Плагин вызвал API ядра, которое грузит чанк: время — плагину, а не чанкам.
        String owner = a.attribute(stack(
                "net.minecraft.server.level.ServerChunkCache",
                "org.bukkit.craftbukkit.v1_19_R3.CraftWorld",
                "com.sk89q.worldguard.bukkit.listener.EventAbstractionListener",
                "org.bukkit.plugin.SimplePluginManager",
                "net.minecraft.server.MinecraftServer"));
        assertEquals("WorldGuard", owner);
        a.attribute(stack("com.sk89q.worldguard.bukkit.listener.EventAbstractionListener"));
        // Ядро и JDK не проверяются, а плагинный класс разбирается один раз.
        assertEquals(List.of("com.sk89q.worldguard.bukkit.listener.EventAbstractionListener"), asked);
    }

    @Test
    void fallsBackToCoreCategory() {
        StackAttributor a = new StackAttributor(cls -> null);
        assertEquals(FrameClassifier.ENTITIES, a.attribute(stack(
                "java.util.ArrayList", "net.minecraft.world.entity.Mob", "net.minecraft.server.level.ServerLevel")));
        assertEquals(FrameClassifier.SAVING, a.attribute(stack(
                "net.minecraft.world.level.chunk.storage.RegionFile", "net.minecraft.server.level.ChunkMap")));
        assertEquals(FrameClassifier.CHUNKS, a.attribute(stack("net.minecraft.server.level.ChunkMap")));
        assertEquals(FrameClassifier.OTHER, a.attribute(stack("net.minecraft.server.MinecraftServer")));
        assertEquals(FrameClassifier.OTHER, a.attribute(new StackTraceElement[0]));
    }

    @Test
    void classifierBasics() {
        assertTrue(FrameClassifier.isCore("org.bukkit.craftbukkit.v1_19_R3.CraftServer"));
        assertFalse(FrameClassifier.isCore("me.example.MyPlugin"));
        assertTrue(FrameClassifier.isNeutral("jdk.internal.misc.Unsafe"));
        assertNull(FrameClassifier.category("me.example.MyPlugin"));
        StackTraceElement park = new StackTraceElement("jdk.internal.misc.Unsafe", "park", null, -1);
        assertTrue(FrameClassifier.isWaiting(Thread.State.TIMED_WAITING, park));
        assertFalse(FrameClassifier.isWaiting(Thread.State.RUNNABLE, park));
    }

    @Test
    void threadNamesMapToPlugins() {
        String[] plugins = {"LuckPerms", "WorldEdit", "Ess"};
        assertEquals("LuckPerms", AsyncTracker.pluginFromName("Craft Scheduler Thread - 12 - LuckPerms", plugins));
        assertNull(AsyncTracker.pluginFromName("Craft Scheduler Thread - 3", plugins));
        assertEquals("LuckPerms", AsyncTracker.pluginFromName("luckperms-scheduler-worker-1", plugins));
        // Короткие имена не сопоставляем по префиксу — слишком много ложных совпадений.
        assertNull(AsyncTracker.pluginFromName("Essential worker", plugins));
    }
}
