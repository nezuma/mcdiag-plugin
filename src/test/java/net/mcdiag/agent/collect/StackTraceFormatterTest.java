package net.mcdiag.agent.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.Test;

class StackTraceFormatterTest {

    private static StackTraceElement[] frames(String cls, int n) {
        StackTraceElement[] out = new StackTraceElement[n];
        for (int i = 0; i < n; i++) {
            out[i] = new StackTraceElement(cls, "m" + i, cls + ".java", i + 1);
        }
        return out;
    }

    @Test
    void shortTraceIsKeptWhole() {
        Exception e = new IllegalStateException("boom");
        e.setStackTrace(frames("a.B", 5));
        String[] lines = StackTraceFormatter.format(e).split("\n");
        assertEquals(6, lines.length);
        assertEquals("java.lang.IllegalStateException: boom", lines[0]);
        assertEquals("\tat a.B.m4(a.B.java:5)", lines[5]);
    }

    @Test
    void longTraceKeepsHeadAndTailAndCutsMiddle() {
        Exception root = new NullPointerException("deep");
        root.setStackTrace(frames("deep.Root", 3));
        Exception top = new RuntimeException("wrapper", root);
        top.setStackTrace(frames("top.Wrapper", 500));

        String[] lines = StackTraceFormatter.format(top).split("\n");
        assertEquals(StackTraceFormatter.HEAD + 1 + StackTraceFormatter.TAIL, lines.length);
        assertTrue(lines[0].startsWith("java.lang.RuntimeException: wrapper"));
        assertTrue(lines[StackTraceFormatter.HEAD].matches("\t\\.\\.\\. \\d+ lines omitted \\.\\.\\."));
        // Корневая причина целиком в хвосте.
        assertEquals("Caused by: java.lang.NullPointerException: deep", lines[lines.length - 4]);
        assertEquals("\tat deep.Root.m2(deep.Root.java:3)", lines[lines.length - 1]);
    }

    @Test
    void collapsesFramesSharedWithEnclosingTrace() {
        StackTraceElement[] shared = frames("s.S", 4);
        Exception cause = new Exception("c");
        StackTraceElement[] causeFrames = new StackTraceElement[6];
        System.arraycopy(frames("c.C", 2), 0, causeFrames, 0, 2);
        System.arraycopy(shared, 0, causeFrames, 2, 4);
        cause.setStackTrace(causeFrames);
        Exception top = new Exception("t", cause);
        top.setStackTrace(shared);

        String trace = StackTraceFormatter.format(top);
        assertTrue(trace.endsWith("\tat c.C.m1(c.C.java:2)\n\t... 4 more"), trace);
    }

    @Test
    void survivesCauseCycles() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        assertTrue(StackTraceFormatter.format(a).contains("Caused by: java.lang.Exception: b"));
    }

    @Test
    void errorEventJsonIsBoundedAndEscaped() {
        Exception e = new RuntimeException("x".repeat(10_000));
        LogRecord record = new LogRecord(Level.SEVERE, "Could not pass event {0} to \"Plugin\"");
        record.setParameters(new Object[] {"PlayerJoinEvent"});
        record.setThrown(e);
        record.setLoggerName("Minecraft");
        String json = ErrorCaptureHandler.toJson(record, new SimpleFormatter(), 1L);
        assertTrue(json.startsWith("{\"type\":\"error\",\"ts\":1,\"logger\":\"Minecraft\","
                + "\"message\":\"Could not pass event PlayerJoinEvent to \\\"Plugin\\\"\",\"trace\":\""), json);
        assertTrue(json.length() < StackTraceFormatter.MAX_CHARS + 1024);
    }
}
