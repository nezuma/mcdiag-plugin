package net.mcdiag.agent.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileWindowTest {

    @Test
    void aggregatesAndSerializes() {
        ProfileWindow w = new ProfileWindow(1_800_000_000_000L);
        for (int i = 0; i < 300; i++) {
            w.addBusy("WorldGuard", 20);
        }
        w.addBusy("Essentials", 20);
        w.addBusy(FrameClassifier.ENTITIES, 40);
        w.addBusy(FrameClassifier.OTHER, 20);
        w.addIdle(1000);
        w.addAsync("LuckPerms", 125.5);

        assertEquals(6080, w.busyMs(), 1e-9);
        assertEquals(7080, w.wallMs(), 1e-9);
        assertEquals(60, w.coreMs(), 1e-9);
        assertEquals("WorldGuard", w.top(1).get(0).name());

        SpikeRecord spike = SpikeRecord.of(1_800_000_001_000L, 380.5,
                List.of("WorldGuard", "WorldGuard", "WorldGuard", FrameClassifier.ENTITIES));
        assertEquals("WorldGuard", spike.top().get(0).name());
        assertEquals(0.75, spike.top().get(0).share(), 1e-9);

        String json = w.toJson(new TickMonitor.TickStats(1200, 15.25, 120), 7, "{\"cpu\":0.3}", List.of(spike));
        assertTrue(json.startsWith("{\"ts\":1800000000000,\"wall\":7080.00,\"busy\":6080.00,\"ticks\":1200,\"tickAvg\":15.25"), json);
        assertTrue(json.contains("{\"n\":\"WorldGuard\",\"ms\":6000.00,\"a\":0}"), json);
        assertTrue(json.contains("{\"n\":\"LuckPerms\",\"ms\":0,\"a\":125.50}"), json);
        assertTrue(json.contains("\"core\":{\"entities\":40.00,\"chunks\":0,\"blocks\":0,\"network\":0,\"saving\":0,\"other\":20.00}"), json);
        assertTrue(json.contains("\"spikes\":[{\"ts\":1800000001000,\"ms\":380.50,\"top\":[{\"n\":\"WorldGuard\",\"p\":0.75},{\"n\":\"~entities\",\"p\":0.25}]}]"), json);
    }

    @Test
    void limitsPluginCount() {
        ProfileWindow w = new ProfileWindow(0);
        for (int i = 0; i < 60; i++) {
            w.addBusy("P" + i, i + 1);
        }
        String json = w.toJson(new TickMonitor.TickStats(1, 1, 1), 0, null, List.of());
        assertEquals(ProfileWindow.MAX_PLUGINS, json.split("\"n\":").length - 1);
        assertTrue(json.contains("\"P59\""));
        assertFalse(json.contains("\"P0\""));
        assertFalse(json.contains("\"jvm\""));
        assertFalse(json.contains("\"spikes\""));
    }

    @Test
    void validatesNames() {
        assertTrue(ProfileWindow.validName("World Edit-7.2_x"));
        assertFalse(ProfileWindow.validName("Плагин"));
        assertFalse(ProfileWindow.validName("a/b"));
        assertFalse(ProfileWindow.validName("x".repeat(65)));
    }
}
