package net.mcdiag.agent.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class InsightTest {

    @Test
    void onlyStopTheWorldCollectionsCountAsPauses() {
        assertTrue(GcWatch.isPause("G1 Young Generation"));
        assertTrue(GcWatch.isPause("ZGC Pauses"));
        assertFalse(GcWatch.isPause("ZGC Cycles"));
        assertFalse(GcWatch.isPause("G1 Concurrent GC"));
        assertFalse(GcWatch.isPause("Shenandoah Cycles"));
    }

    @Test
    void findsPausesInsideASpikeAndSumsTheMinute() {
        GcWatch gc = new GcWatch();
        gc.record(new GcWatch.Pause(1000, 150, "G1 Young Generation", "Allocation", 900, 300));
        gc.record(new GcWatch.Pause(5000, 20, "G1 Young Generation", "Allocation", 500, 300));
        assertEquals(1, gc.overlapping(1100, 1300).size());
        assertEquals(0, gc.overlapping(2000, 3000).size());
        GcWatch.Minute minute = gc.drainMinute();
        assertEquals(2, minute.count());
        assertEquals(170, minute.totalMs());
        assertEquals(150, minute.maxMs());
        assertEquals(0, gc.drainMinute().count());
    }

    @Test
    void readableClassNames() {
        assertEquals("byte[]", JfrInsight.readableClass("[B"));
        assertEquals("java.lang.String[][]", JfrInsight.readableClass("[[Ljava.lang.String;"));
        assertEquals("com.foo.Bar", JfrInsight.readableClass("com.foo.Bar"));
        assertEquals("file.yml", JfrInsight.fileName("/srv/mc/plugins/Foo/file.yml"));
        assertEquals("file.yml", JfrInsight.fileName("C:\\mc\\file.yml"));
    }

    @Test
    void gcOperationsAreNotReportedTwice() {
        assertTrue(JfrInsight.isGcOperation("G1CollectForAllocation"));
        assertTrue(JfrInsight.isGcOperation("ZMarkStart"));
        assertFalse(JfrInsight.isGcOperation("ThreadDump"));
    }

    @Test
    void memorySnapshotIsCompleteWithoutOptionalData() {
        String json = new MemoryStats().snapshotJson(60_000, name -> name.equals("main") ? "Owner" : null, null, null);
        assertTrue(json.startsWith("{\"ts\":60000"));
        for (String key : List.of("\"heap\":", "\"live\":", "\"meta\":", "\"code\":", "\"classes\":", "\"threads\":")) {
            assertTrue(json.contains(key), key);
        }
        assertFalse(json.contains("\"alloc\""));
    }

    @Test
    void parsesProcAndNativeMemoryReports() {
        StringBuilder proc = new StringBuilder();
        MemoryStats.appendProcText(proc, "Name:\tjava\nVmRSS:\t 2048000 kB\nRssAnon:\t 1024000 kB\nRssFile:\t 2048 kB\n");
        assertEquals(",\"rss\":2000,\"rssAnon\":1000,\"rssFile\":2", proc.toString());
        StringBuilder nmt = new StringBuilder();
        MemoryStats.appendNmtText(nmt, "Total: reserved=9MB, committed=5MB\n-                 Java Heap (reserved=4096MB, committed=2048MB)\n-                     Class (reserved=1024MB, committed=64MB)\n");
        assertEquals(",\"nmt\":[{\"n\":\"Java Heap\",\"mb\":2048},{\"n\":\"Class\",\"mb\":64}]", nmt.toString());
    }

    @Test
    void jfrSeesAllocationsAndLeaksFindsTheirSource() throws Exception {
        Thread current = Thread.currentThread();
        JfrInsight jfr = new JfrInsight(Logger.getLogger("test"), current.getId(),
                cls -> cls.contains("InsightTest") ? "TestPlugin" : null, (name, names) -> null);
        jfr.start();
        assertTrue(jfr.active());
        byte[][] keep = new byte[256][];
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            for (int i = 0; i < keep.length; i++) {
                keep[i] = new byte[32 * 1024];
            }
        }
        JfrInsight.Allocations alloc = jfr.drainAllocations();
        jfr.stop();
        assertFalse(jfr.active());
        assertNotNull(keep);
        assertTrue(alloc.plugins().getOrDefault("TestPlugin", 0L) > 0, alloc.plugins().toString());
        assertTrue(alloc.classes().containsKey("byte[]"), alloc.classes().toString());
    }

    @Test
    void leakScanReportsWhereLongLivedObjectsCameFrom() throws Exception {
        java.util.List<byte[]> hold = new java.util.ArrayList<>();
        Thread load = new Thread(() -> {
            try {
                while (true) {
                    for (int i = 0; i < 40; i++) {
                        hold.add(new byte[64 * 1024]);
                    }
                    if (hold.size() > 2000) {
                        hold.subList(0, 500).clear();
                    }
                    // Выборка отсеивает объекты сборкой мусора: без неё итог будет пустым.
                    System.gc();
                    Thread.sleep(100);
                }
            } catch (InterruptedException ignored) {
                // Конец теста.
            }
        });
        load.setDaemon(true);
        load.start();
        String json = LeakScan.run(5, cls -> cls.contains("InsightTest") ? "TestPlugin" : null);
        load.interrupt();
        assertTrue(json.startsWith("{\"seconds\":5"), json);
        assertTrue(json.contains("\"n\":\"TestPlugin\""), json);
        assertTrue(json.contains("byte[]"), json);
    }
}
