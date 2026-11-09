package net.mcdiag.agent.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static final String PLUGIN = "{\"version\":\"0.4.0\",\"core\":\"paper\",\"mcVersion\":\"1.19.4\",\"batchIntervalSec\":10}";

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

        long minute = now / 60_000 * 60_000 - 60_000;
        buffer.addProfile("{\"ts\":" + minute + ",\"wall\":60000,\"busy\":18000.5,\"ticks\":1200,\"tickAvg\":15.25,"
                + "\"tickMax\":180,\"online\":3,\"plugins\":[{\"n\":\"WorldGuard\",\"ms\":6000,\"a\":12.5}],"
                + "\"core\":{\"entities\":4000,\"chunks\":0,\"blocks\":0,\"network\":0,\"saving\":0,\"other\":8000},"
                + "\"jvm\":{\"cpu\":0.31,\"sys\":0.55,\"heap\":2048,\"heapMax\":4096,\"gc\":120,\"thr\":70},"
                + "\"spikes\":[{\"ts\":" + (minute + 5000) + ",\"ms\":380.5,\"top\":[{\"n\":\"WorldGuard\",\"p\":0.72},"
                + "{\"n\":\"~entities\",\"p\":0.2}]}]}");
        buffer.setInventory("[{\"n\":\"WorldGuard\",\"v\":\"7.0.9\",\"on\":true},{\"n\":\"MCDiag\",\"v\":\"0.2.0\",\"on\":true}]");

        buffer.addSystem("{\"ts\":" + minute + ",\"cpu\":0.31,\"sys\":0.55,\"ram\":11000,\"ramMax\":16000,"
                + "\"heap\":2048,\"heapMax\":4096,\"gc\":120,\"thr\":70,\"disk\":40000,\"diskMax\":100000,\"up\":3600}");
        buffer.addResult("{\"id\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"type\":\"ping\",\"ts\":" + now
                + ",\"ok\":true,\"data\":{\"agent\":\"0.3.0\",\"sentBatches\":5}}");
        buffer.addMetric("{\"ts\":" + (now / 5000 * 5000 - 5000) + ",\"tps\":20,\"mspt\":11.2,\"online\":3,\"entities\":2840,\"chunks\":900,\"tiles\":120}");

        buffer.addChat(net.mcdiag.agent.collect.CommandListenerAccess.line(now - 4000, "cmd", "Steve", "world", "//set stone", "WorldEdit"));
        buffer.addChat(net.mcdiag.agent.collect.CommandListenerAccess.line(now - 3500, "chat", "Steve", "world", "\u043f\u0440\u0438\u0432\u0435\u0442", null));
        buffer.addChat(net.mcdiag.agent.collect.CommandListenerAccess.line(now - 3000, "console", null, null, "say hi", null));
        buffer.addSpike(new net.mcdiag.agent.profiler.SpikeRecord(now - 2500, 380.5,
                net.mcdiag.agent.profiler.SpikeRecord.shares(java.util.List.of("WorldEdit", "~chunks"), 3),
                net.mcdiag.agent.profiler.SpikeRecord.shares(java.util.List.of("EditSession.flush"), 5),
                java.util.List.of(new net.mcdiag.agent.collect.LogRing.Line(now - 4000, "WARNING", "WorldEdit", "slow"))).richJson());

        StringBuilder sb = new StringBuilder();
        assertEquals(14, BatchSender.assemble(sb, buffer, 100, UUID.randomUUID(), now, PLUGIN));
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
        assertTrue(sb.toString().endsWith("]}"), "closed even without room for profiles");
    }

    @Test
    void emptyBufferGivesValidHeartbeat() {
        StringBuilder sb = new StringBuilder();
        assertEquals(0, BatchSender.assemble(sb, new TelemetryBuffer(10), 100, UUID.randomUUID(), 1, PLUGIN));
        assertTrue(sb.toString().endsWith(",\"events\":[],\"metrics\":[]}"), sb.toString());
    }

    @Test
    void chunkGenerationAndEconomyAreOptionalSections() {
        TelemetryBuffer buffer = new TelemetryBuffer(10);
        StringBuilder sb = new StringBuilder();
        BatchSender.assemble(sb, buffer, 100, UUID.randomUUID(), 1, PLUGIN);
        assertFalse(sb.toString().contains("chunkgen"));
        buffer.addChunkGen("{\"ts\":60000,\"w\":[{\"n\":\"world\",\"c\":3}]}");
        buffer.addEconomy("{\"ts\":300000,\"c\":\"vault\",\"name\":\"E\",\"b\":[]}");
        assertEquals(2, BatchSender.assemble(sb, buffer, 100, UUID.randomUUID(), 1, PLUGIN));
        assertTrue(sb.toString().contains(",\"chunkgen\":[{\"ts\":60000"));
        assertTrue(sb.toString().contains(",\"economy\":[{\"ts\":300000"));
        assertTrue(buffer.isEmpty());
    }
}
