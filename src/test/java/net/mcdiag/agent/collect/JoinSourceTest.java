package net.mcdiag.agent.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JoinSourceTest {

    @Test
    void hostIsNormalizedAndJunkDropped() {
        assertEquals("play.example.ru", PlayerListener.cleanHost("Play.Example.RU."));
        assertEquals("mc.example.ru", PlayerListener.cleanHost("mc.example.ru\0FML3\0"));
        assertEquals("mc.example.ru", PlayerListener.cleanHost("mc.example.ru///1.2.3.4:5555///123"));
        assertEquals("10.0.0.5", PlayerListener.cleanHost("10.0.0.5"));
        assertNull(PlayerListener.cleanHost("<script>"));
        assertNull(PlayerListener.cleanHost(""));
        assertNull(PlayerListener.cleanHost(null));
    }

    @Test
    void chatLineMarksBlockedMessages() {
        String line = CommandListener.line(1, "chat", "Steve", "world", "привет", null, true);
        assertEquals("{\"ts\":1,\"k\":\"chat\",\"n\":\"Steve\",\"t\":\"\\u043f\\u0440\\u0438\\u0432\\u0435\\u0442\",\"w\":\"world\",\"x\":true}", line);
        assertEquals("{\"ts\":1,\"k\":\"chat\",\"n\":\"Steve\",\"t\":\"hi\"}", CommandListener.line(1, "chat", "Steve", null, "hi", null));
    }

    @Test
    void chunkCounterReportsOnlyMinutesWithGeneration() {
        ChunkGenCounter counter = new ChunkGenCounter(null);
        assertNull(counter.drainMinute(60_000));
    }
}
