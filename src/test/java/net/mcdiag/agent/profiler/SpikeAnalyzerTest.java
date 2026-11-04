package net.mcdiag.agent.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpikeAnalyzerTest {

    private static StackTraceElement f(String cls, String method) {
        return new StackTraceElement(cls, method, "X.java", 10);
    }

    private static final StackTraceElement[] SAVE_TASK = {
        f("sun.nio.ch.FileChannelImpl", "write"),
        f("java.nio.file.Files", "write"),
        f("de.oliver.fancynpcs.NpcManager", "save"),
        f("de.oliver.fancynpcs.SaveTask", "run"),
        f("org.bukkit.craftbukkit.v1_20_R3.scheduler.CraftTask", "run"),
        f("org.bukkit.craftbukkit.v1_20_R3.scheduler.CraftScheduler", "mainThreadHeartbeat"),
        f("net.minecraft.server.MinecraftServer", "tickChildren"),
    };

    private static final StackTraceElement[] LISTENER = {
        f("com.foo.Listener", "onMove"),
        f("org.bukkit.plugin.RegisteredListener", "callEvent"),
        f("org.bukkit.plugin.SimplePluginManager", "callEvent"),
        f("net.minecraft.server.network.ServerGamePacketListenerImpl", "handleMovePlayer"),
    };

    private static final StackTraceElement[] CORE_ONLY = {
        f("net.minecraft.world.level.chunk.LevelChunk", "tick"),
        f("net.minecraft.server.MinecraftServer", "tickChildren"),
    };

    @Test
    void findsWhereTheCallStartedAndWhatItDid() {
        List<SpikeAnalyzer.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            samples.add(new SpikeAnalyzer.Sample(SAVE_TASK, "FancyNpcs", Thread.State.RUNNABLE, null, (20 + i * 5) * 1_000_000L));
        }
        samples.add(new SpikeAnalyzer.Sample(LISTENER, "Foo", Thread.State.RUNNABLE, null, 60_000_000L));
        SpikeAnalyzer.Result result = SpikeAnalyzer.analyze(samples, 0, 150_000_000L);

        assertEquals(7, result.samples());
        assertEquals(20, result.firstMs());
        assertEquals(90, result.gapMs());
        SpikeAnalyzer.PathInfo top = result.paths().get(0);
        assertEquals("FancyNpcs", top.owner());
        assertEquals("task", top.entry());
        assertEquals("disk", top.waitOn());
        assertEquals(6, top.count());
        assertEquals("SaveTask.run:10", top.frames().get(0));
        assertEquals("FileChannelImpl.write:10", top.frames().get(top.frames().size() - 1));
        assertEquals("FancyNpcs|task|SaveTask.run|FileChannelImpl.write", result.signature());
        assertEquals(6, result.waits().get("disk"));
        assertEquals("event", result.paths().get(1).entry());
    }

    @Test
    void signatureDoesNotDependOnLineNumbers() {
        List<SpikeAnalyzer.Sample> a = List.of(new SpikeAnalyzer.Sample(SAVE_TASK, "F", Thread.State.RUNNABLE, null, 1));
        StackTraceElement[] other = SAVE_TASK.clone();
        other[0] = new StackTraceElement("sun.nio.ch.FileChannelImpl", "write", "X.java", 999);
        List<SpikeAnalyzer.Sample> b = List.of(new SpikeAnalyzer.Sample(other, "F", Thread.State.RUNNABLE, null, 1));
        assertEquals(SpikeAnalyzer.analyze(a, 0, 2).signature(), SpikeAnalyzer.analyze(b, 0, 2).signature());
    }

    @Test
    void classifiesWaits() {
        assertEquals("lock", SpikeAnalyzer.waitKind(LISTENER, Thread.State.BLOCKED));
        assertNull(SpikeAnalyzer.waitKind(LISTENER, Thread.State.RUNNABLE));
        assertEquals("database", SpikeAnalyzer.waitKind(new StackTraceElement[] {
            f("java.net.SocketInputStream", "read"), f("com.foo.libs.hikari.pool.HikariPool", "getConnection")}, Thread.State.RUNNABLE));
        assertEquals("network", SpikeAnalyzer.waitKind(new StackTraceElement[] {f("sun.nio.ch.NioSocketImpl", "read")}, Thread.State.RUNNABLE));
        assertEquals("classload", SpikeAnalyzer.waitKind(new StackTraceElement[] {f("java.lang.ClassLoader", "defineClass1")}, Thread.State.RUNNABLE));
        assertEquals("sleep", SpikeAnalyzer.waitKind(new StackTraceElement[] {f("java.lang.Thread", "sleep0")}, Thread.State.TIMED_WAITING));
        assertEquals("thread", SpikeAnalyzer.waitKind(new StackTraceElement[] {f("java.util.concurrent.locks.LockSupport", "park")}, Thread.State.WAITING));
        assertEquals("serialize", SpikeAnalyzer.waitKind(new StackTraceElement[] {f("org.yaml.snakeyaml.Yaml", "dump")}, Thread.State.RUNNABLE));
    }

    @Test
    void coreOnlyStackUsesTheOwnerPart() {
        SpikeAnalyzer.Result r = SpikeAnalyzer.analyze(
                List.of(new SpikeAnalyzer.Sample(CORE_ONLY, FrameClassifier.CHUNKS, Thread.State.RUNNABLE, null, 5)), 0, 10);
        assertEquals("chunks", r.paths().get(0).entry());
    }

    @Test
    void noSamplesStillGivesAResultWithTheGap() {
        SpikeAnalyzer.Result r = SpikeAnalyzer.analyze(List.of(), 0, 200_000_000L);
        assertEquals(0, r.samples());
        assertEquals(200, r.gapMs());
        assertNull(r.signature());
        assertTrue(r.paths().isEmpty());
    }

    @Test
    void jsonIsBounded() {
        List<SpikeAnalyzer.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            samples.add(new SpikeAnalyzer.Sample(new StackTraceElement[] {f("com.foo.A" + i, "m")}, "P" + i, Thread.State.RUNNABLE, null, i));
        }
        StringBuilder sb = new StringBuilder();
        SpikeAnalyzer.appendJson(sb, SpikeAnalyzer.analyze(samples, 0, 40));
        assertEquals(5, sb.toString().split("\"o\":").length - 1);
    }

    /** Настоящий батч с новыми полями: scratch-скрипт проверяет его схемой панели. */
    @Test
    void writesASampleBatchForThePanelSchema() throws Exception {
        long now = System.currentTimeMillis();
        List<SpikeAnalyzer.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            samples.add(new SpikeAnalyzer.Sample(SAVE_TASK, "FancyNpcs", Thread.State.RUNNABLE, null, (20 + i * 5) * 1_000_000L));
        }
        samples.add(new SpikeAnalyzer.Sample(CORE_ONLY, FrameClassifier.CHUNKS, Thread.State.BLOCKED, "java.lang.Object < Craft Scheduler Thread - 1", 130_000_000L));
        String x = Profiler.insightJson(SpikeAnalyzer.analyze(samples, 0, 150_000_000L), 1024,
                List.of(new net.mcdiag.agent.insight.GcWatch.Pause(now, 60, "G1 Young Generation", "G1 Evacuation Pause", 900, 300)),
                List.of(new net.mcdiag.agent.insight.JfrInsight.VmStop(now, 12, "ThreadDump", "Worker")),
                List.of(new net.mcdiag.agent.insight.JfrInsight.Io(now, 40, "disk", "npcs.yml", "FancyNpcs")), t -> t);
        SpikeRecord spike = new SpikeRecord(now, 150, List.of(new SpikeRecord.Share("FancyNpcs", 0.9)),
                List.of(new SpikeRecord.Share("FileChannelImpl.write", 0.9)), List.of());
        String memory = new net.mcdiag.agent.insight.MemoryStats().snapshotJson(now / 60_000 * 60_000, name -> name.equals("main") ? "Owner" : null,
                new net.mcdiag.agent.insight.GcWatch.Minute(2, 90, 60),
                new net.mcdiag.agent.insight.JfrInsight.Allocations(java.util.Map.of("FancyNpcs", 5_000_000L), java.util.Map.of("byte[]", 4_000_000L)));
        String batch = "{\"batchId\":\"41a28438-1451-46a3-a829-7d8e98cb1b39\",\"sentAt\":" + now
                + ",\"plugin\":{\"version\":\"0.5.0\",\"core\":\"paper\",\"mcVersion\":\"1.21.4\",\"batchIntervalSec\":10},\"spikes\":["
                + spike.richJson(x) + "],\"events\":[],\"metrics\":[],\"memory\":[" + memory + "]}";
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/sample-insight-batch.json"), batch);
        assertTrue(batch.contains("\"x\":{\"n\":21"));
    }
}
