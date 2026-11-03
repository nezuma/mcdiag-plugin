package net.mcdiag.agent.collect;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemStatsTest {

    @Test
    void reportsMemoryDiskAndUptime() {
        List<String> out = new ArrayList<>();
        SystemStats stats = new SystemStats(out::add);
        String first = stats.snapshotJson(1_800_000_000_000L);
        assertTrue(first.startsWith("{\"ts\":1800000000000,"), first);
        assertTrue(first.matches(".*\"heap\":\\d+.*\"thr\":\\d+.*\"up\":\\d+\\}"), first);
        assertTrue(first.contains("\"ramMax\":") && first.contains("\"diskMax\":"), first);
        // GC — дельта с прошлого раза, поэтому появляется со второго среза.
        assertTrue(!first.contains("\"gc\":") && stats.snapshotJson(1).contains("\"gc\":"));
    }
}
