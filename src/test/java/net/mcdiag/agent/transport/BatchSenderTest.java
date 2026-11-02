package net.mcdiag.agent.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import net.mcdiag.agent.buffer.TelemetryBuffer;
import org.junit.jupiter.api.Test;

class BatchSenderTest {

    private static final String PLUGIN = "{\"version\":\"0.1.0\",\"core\":\"leaf\",\"mcVersion\":\"1.21.4\"}";

    @Test
    void assemblesIngestBatchAndWritesSample() throws IOException {
        long now = System.currentTimeMillis();
        String hash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        TelemetryBuffer buffer = new TelemetryBuffer(500);
        buffer.addEvent("{\"type\":\"join\",\"ts\":" + (now - 3000) + ",\"playerHash\":\"" + hash
                + "\",\"firstJoin\":true,\"world\":\"world\"}");
        buffer.addEvent("{\"type\":\"death\",\"ts\":" + (now - 2000) + ",\"playerHash\":\"" + hash
                + "\",\"cause\":\"ENTITY_ATTACK\",\"killerType\":\"ZOMBIE\",\"world\":\"\\u043c\\u0438\\u0440\"}");
        buffer.addEvent("{\"type\":\"quit\",\"ts\":" + (now - 1000) + ",\"playerHash\":\"" + hash
                + "\",\"sessionSec\":2}");
        Exception e = new IllegalStateException("boom");
        LogRecord record = new LogRecord(Level.SEVERE, "Could not pass event");
        record.setThrown(e);
        record.setLoggerName("SomePlugin");
        buffer.addEvent(net.mcdiag.agent.collect.ErrorCaptureHandlerAccess.toJson(record, now - 500));
        buffer.addMetric("{\"ts\":" + (now / 5000 * 5000) + ",\"tps\":19.98,\"mspt\":12.4,\"online\":3,\"entities\":2841}");

        StringBuilder sb = new StringBuilder();
        assertEquals(5, BatchSender.assemble(sb, buffer, 100, UUID.randomUUID(), now, PLUGIN));
        assertTrue(buffer.isEmpty());
        String body = sb.toString();
        assertTrue(body.chars().allMatch(c -> c >= 0x20 && c < 0x7f), "body must be printable ASCII");
        Files.writeString(Path.of("build", "sample-batch.json"), body);
    }

    @Test
    void staysUnderBackendBodyLimit() {
        TelemetryBuffer buffer = new TelemetryBuffer(500);
        for (int i = 0; i < 500; i++) {
            buffer.addEvent("{\"type\":\"error\",\"ts\":1,\"message\":\"m\",\"trace\":\"" + "x".repeat(6000) + "\"}");
        }
        for (int i = 0; i < TelemetryBuffer.MAX_METRICS; i++) {
            buffer.addMetric("{\"ts\":1,\"tps\":20,\"mspt\":1,\"online\":1}");
        }
        StringBuilder sb = new StringBuilder();
        int items = BatchSender.assemble(sb, buffer, 500, UUID.randomUUID(), 1, PLUGIN);
        assertTrue(items > 0);
        assertTrue(sb.length() <= 64 * 1024, "body " + sb.length());
        assertEquals(500 - 10, buffer.stats().events(), "only what fits is drained");
    }
}
