package net.mcdiag.agent.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.mcdiag.agent.buffer.TelemetryBuffer;
import net.mcdiag.agent.transport.BatchSenderAccess;
import net.mcdiag.agent.transport.PanelState;
import org.junit.jupiter.api.Test;

class ChannelTest {

    @Test
    void boundedQueueDropsOldestAndDrainsWithinBudget() {
        Channel c = new Channel("logs", "logs", 3, 10);
        for (int i = 0; i < 5; i++) {
            c.offer("{\"i\":" + i + "}");
        }
        assertEquals(2, c.dropped());
        StringBuilder sb = new StringBuilder();
        assertEquals(2, c.drain(sb, 16));
        assertEquals("{\"i\":2},{\"i\":3}", sb.toString());
        assertEquals(1, c.size());
    }

    @Test
    void channelsBecomeBatchSections() {
        Channel logs = new Channel("logs", "logs", 10, 10);
        logs.offer("{\"fp\":\"1\"}");
        Channel empty = new Channel("net", "net", 10, 10);
        StringBuilder sb = new StringBuilder();
        int items = BatchSenderAccess.assemble(sb, new TelemetryBuffer(10), UUID.randomUUID(), List.of(logs, empty));
        assertEquals(1, items);
        assertTrue(sb.toString().contains(",\"logs\":[{\"fp\":\"1\"}]"), sb.toString());
        assertTrue(!sb.toString().contains("\"net\""));
    }

    @Test
    void panelCapsAreParsedGenerically() {
        PanelState panel = new PanelState();
        assertTrue(!panel.answered());
        panel.update("{\"data\":{\"caps\":[\"spikes\",\"logs\",\"net\",\"audit\"]}}");
        assertTrue(panel.answered());
        assertTrue(panel.has("logs") && panel.has("net") && panel.has("audit"));
        panel.reset();
        assertTrue(!panel.has("logs"));
    }
}
