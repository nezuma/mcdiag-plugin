package net.mcdiag.agent.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TelemetryBufferTest {

    @Test
    void evictsOldestWhenFull() {
        TelemetryBuffer buffer = new TelemetryBuffer(3);
        for (int i = 0; i < 5; i++) {
            buffer.addEvent("{\"n\":" + i + "}");
        }
        StringBuilder sb = new StringBuilder();
        assertEquals(3, buffer.drainEvents(sb, 100, 10_000));
        assertEquals("{\"n\":2},{\"n\":3},{\"n\":4}", sb.toString());
        assertEquals(2, buffer.stats().dropped());
        assertEquals(0, buffer.stats().chars());
    }

    @Test
    void drainRespectsCountAndCharBudget() {
        TelemetryBuffer buffer = new TelemetryBuffer(10);
        for (int i = 0; i < 10; i++) {
            buffer.addEvent("x".repeat(9));
        }
        StringBuilder sb = new StringBuilder();
        assertEquals(4, buffer.drainEvents(sb, 4, 10_000));
        sb.setLength(0);
        assertEquals(3, buffer.drainEvents(sb, 100, 30));
        assertEquals(3, buffer.stats().events());
    }

    @Test
    void metricsAreCappedAndResizeTrims() {
        TelemetryBuffer buffer = new TelemetryBuffer(10);
        for (int i = 0; i < TelemetryBuffer.MAX_METRICS + 5; i++) {
            buffer.addMetric("{}");
        }
        for (int i = 0; i < 10; i++) {
            buffer.addEvent("{}");
        }
        buffer.resize(4);
        TelemetryBuffer.Stats stats = buffer.stats();
        assertEquals(TelemetryBuffer.MAX_METRICS, stats.metrics());
        assertEquals(4, stats.events());
        assertEquals(5 + 6, stats.dropped());
    }
}
