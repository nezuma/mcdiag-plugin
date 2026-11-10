package net.mcdiag.agent.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.mcdiag.api.Severity;
import org.junit.jupiter.api.Test;

class LogAggregatorTest {

    private static final Set<String> PLUGINS = Set.of("WorldGuard", "SomeShop", "LuckPerms");

    private static LogAggregator aggregator() {
        return new LogAggregator(cls -> cls.startsWith("me.shop.") ? "SomeShop" : null, PLUGINS::contains,
                text -> text.replace("10.0.0.7", "***"));
    }

    private static Exception npe(String cls) {
        Exception e = new NullPointerException("value is null");
        e.setStackTrace(new StackTraceElement[] {
            new StackTraceElement(cls, "run", "Task.java", 42),
            new StackTraceElement("org.bukkit.craftbukkit.scheduler.CraftTask", "run", "CraftTask.java", 1),
        });
        return e;
    }

    @Test
    void groupsRepeatsWithVaryingNumbersIntoOneRecord() {
        LogAggregator agg = aggregator();
        for (int i = 0; i < 50; i++) {
            agg.add(1000 + i, "WARN", "Server thread", true, "Can't keep up! Is the server overloaded? Running " + (2000 + i) + "ms or " + i + " ticks behind", null);
        }
        List<String> out = agg.drain();
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("\"c\":50"), out.get(0));
        assertTrue(out.get(0).contains("\"th\":\"main\""));
        assertTrue(out.get(0).contains("\"f\":1000,\"l\":1049"));
    }

    @Test
    void attributesPluginByMessageFramesLoggerAndPrefix() {
        LogAggregator agg = aggregator();
        agg.add(1, "ERROR", "Minecraft", true, "Could not pass event PlayerJoinEvent to WorldGuard v7.0.9", npe("com.sk89q.Foo"));
        agg.add(2, "WARN", "Paper Async Task Handler Thread - 1", false, "Plugin task failed", npe("me.shop.Task"));
        agg.add(3, "WARN", "LuckPerms", false, "Slow storage", null);
        agg.add(4, "WARN", "Minecraft", false, "[SomeShop] Config value missing", null);
        String all = String.join("\n", agg.drain());
        assertTrue(all.contains("\"p\":\"WorldGuard\""), all);
        assertTrue(all.contains("\"p\":\"SomeShop\""), all);
        assertTrue(all.contains("\"p\":\"LuckPerms\""), all);
        assertEquals(4, all.split("\n").length);
        assertTrue(all.contains("\"x\":\"java.lang.NullPointerException\""));
        assertTrue(all.contains("\"t\":\"java.lang.NullPointerException: value is null\\n\\tat me.shop.Task.run"), all);
    }

    @Test
    void sendsTextOnlyOncePerHour() {
        LogAggregator agg = aggregator();
        agg.add(1_000, "ERROR", "SomeShop", false, "DB timeout after 30s from 10.0.0.7", npe("me.shop.Db"));
        String first = agg.drain().get(0);
        assertTrue(first.contains("\"m\":\"DB timeout after 30s from ***\""), first);
        agg.add(5_000, "ERROR", "SomeShop", false, "DB timeout after 31s from 10.0.0.7", npe("me.shop.Db"));
        String repeat = agg.drain().get(0);
        assertFalse(repeat.contains("\"m\""), repeat);
        assertFalse(repeat.contains("\"t\""), repeat);
        assertTrue(repeat.contains("\"p\":\"SomeShop\""), repeat);
        agg.add(1_000 + LogAggregator.RESEND_MS + 1, "ERROR", "SomeShop", false, "DB timeout after 32s", npe("me.shop.Db"));
        assertTrue(agg.drain().get(0).contains("\"m\":"));
    }

    @Test
    void capsDistinctProblemsPerWindow() {
        LogAggregator agg = aggregator();
        for (int i = 0; i < LogAggregator.MAX_ENTRIES + 20; i++) {
            agg.add(1, "WARN", "x", false, "unique message " + (char) ('a' + i % 26) + (char) ('a' + i / 26), null);
        }
        assertEquals(LogAggregator.MAX_ENTRIES, agg.drain().size());
        assertEquals(20, agg.dropped());
    }

    @Test
    void stderrLinesAttributeByFrames() {
        LogAggregator agg = aggregator();
        agg.addLines(1, "WARN", "STDERR", false, "java.lang.IllegalStateException: broken",
                List.of("\tat java.base/java.util.Objects.requireNonNull(Objects.java:1)", "\tat me.shop.Gui.open(Gui.java:9)"));
        String json = agg.drain().get(0);
        assertTrue(json.contains("\"p\":\"SomeShop\""), json);
        assertTrue(json.contains("\"x\":\"java.lang.IllegalStateException\""), json);
    }

    @Test
    void apiIssuesCarrySeverityAndCategory() {
        LogAggregator agg = aggregator();
        agg.addIssue(7, "SomeShop", Severity.HIGH, "payments.gateway", "Gateway down");
        String json = agg.drain().get(0);
        assertTrue(json.contains("\"src\":\"api\",\"sev\":\"high\",\"cat\":\"payments.gateway\""), json);
        assertTrue(json.contains("\"lv\":\"ERROR\""));
    }

    @Test
    void parsesFrameClassesAndExceptionHeaders() {
        assertEquals("me.shop.Gui", LogAggregator.frameClass("\tat me.shop.Gui.open(Gui.java:9)"));
        assertEquals("java.util.Objects", LogAggregator.frameClass("at java.base/java.util.Objects.requireNonNull(Objects.java:1)"));
        assertEquals("java.lang.RuntimeException", LogAggregator.exceptionClassOf("Exception in thread \"main\" java.lang.RuntimeException: x"));
        assertEquals(null, LogAggregator.exceptionClassOf("Hello world"));
        assertEquals(LogAggregator.normalize("uuid 3f1c2a7e-1b2c-4d5e-8f90-1234567890ab at 127.0.0.1:25565 took 15ms"),
                LogAggregator.normalize("uuid 00000000-1b2c-4d5e-8f90-1234567890ff at 10.1.1.1:1 took 7ms"));
    }
}
