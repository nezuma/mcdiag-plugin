package net.mcdiag.agent.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;

class LogCaptureTest {

    private static LogCapture capture() {
        LogAggregator agg = new LogAggregator(cls -> cls.startsWith("me.shop.") ? "SomeShop" : null,
                Set.of("SomeShop")::contains, t -> t);
        return new LogCapture(agg, "MCDiag", 1L);
    }

    @Test
    void reassemblesStderrTraceIntoOneRecord() {
        LogCapture c = capture();
        c.accept(100, "WARN", "STDERR", 5, "java.lang.IllegalStateException: boom", null);
        c.accept(101, "WARN", "STDERR", 5, "\tat me.shop.Gui.open(Gui.java:9)", null);
        c.accept(102, "WARN", "STDERR", 5, "\tat org.bukkit.Foo.bar(Foo.java:1)", null);
        c.accept(103, "WARN", "STDERR", 5, "Caused by: java.lang.NullPointerException", null);
        c.accept(104, "WARN", "STDERR", 5, "\t... 3 more", null);
        List<String> out = c.drain(10_000);
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).contains("\"p\":\"SomeShop\""), out.get(0));
        assertTrue(out.get(0).contains("Caused by: java.lang.NullPointerException"));
    }

    @Test
    void mergesWatchdogDumpAndAttributesItToThePluginFrame() {
        LogCapture c = capture();
        long t = 1_000;
        c.accept(t, "ERROR", "Minecraft", 9, "The server has not responded for 10 seconds! Creating thread dump", null);
        c.accept(t + 1, "ERROR", "Minecraft", 9, "------------------------------", null);
        c.accept(t + 2, "ERROR", "Minecraft", 9, "Current Thread: Server thread", null);
        c.accept(t + 3, "ERROR", "Minecraft", 9, "\t\tjava.base@21.0.13/java.net.Socket$SocketInputStream.read(Socket.java:1099)", null);
        c.accept(t + 400, "ERROR", "Minecraft", 9, "\t\tMCDViolator.jar//me.shop.Net.call(Net.java:90)", null);
        c.accept(t + 900, "ERROR", "Minecraft", 9, "------------------------------", null);
        List<String> out = c.drain(t + 900 + LogCapture.DUMP_GAP_MS + 1);
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).contains("\"p\":\"SomeShop\""), out.get(0));
        assertTrue(out.get(0).contains("has not responded"));
    }

    @Test
    void mergesIndentedStderrWithoutPrefixAndBurstLines() {
        LogCapture c = capture();
        // Leaf и Paper печатают printStackTrace плагина без префикса и без логгера.
        c.accept(10, "WARN", null, 1, "java.lang.RuntimeException: printed to stderr", null);
        c.accept(11, "WARN", null, 1, "\tat me.shop.Main.onEnable(Main.java:34)", null);
        c.accept(20, "WARN", "net.minecraft.server.dedicated.DedicatedServer", 1, "**** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!", null);
        c.accept(21, "WARN", "net.minecraft.server.dedicated.DedicatedServer", 1, "The server will make no attempt to authenticate usernames. Beware.", null);
        c.accept(500, "WARN", "net.minecraft.server.dedicated.DedicatedServer", 1, "Unrelated warning much later", null);
        // Строки плагина подряд — разные сообщения, даже в одну миллисекунду.
        c.accept(600, "WARN", "SomeShop", 1, "Config option 'price' is missing", null);
        c.accept(601, "WARN", "SomeShop", 1, "SLF4J warning id=42", null);
        List<String> out = c.drain(10_000);
        assertEquals(5, out.size(), out.toString());
        assertTrue(String.join("", out).contains("\"p\":\"SomeShop\""));
    }

    @Test
    void resolvesPluginLaterWhenIndexWasNotReady() {
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        LogAggregator agg = new LogAggregator(cls -> ready.get() && cls.startsWith("me.shop.") ? "SomeShop" : null,
                Set.of("SomeShop")::contains, t -> t);
        Exception e = new IllegalStateException("x");
        e.setStackTrace(new StackTraceElement[] {new StackTraceElement("me.shop.Pay", "go", "Pay.java", 1)});
        agg.add(1, "ERROR", "ShopLib", true, "Payment provider failed", e);
        ready.set(true);
        assertTrue(agg.drain().get(0).contains("\"p\":\"SomeShop\""));
    }

    @Test
    void ignoresOwnLoggerAndWarningsInErrorsOnlyMode() {
        LogCapture c = capture();
        c.accept(1, "WARN", "MCDiag", 1, "Telemetry upload: network error", null);
        c.setErrorsOnly(true);
        c.accept(2, "WARN", "SomeShop", 1, "minor", null);
        c.accept(3, "ERROR", "SomeShop", 1, "major", null);
        // Строка без исключения ждёт возможного продолжения не дольше LINE_GAP_MS.
        assertEquals(0, c.drain(10).size());
        List<String> out = c.drain(3 + LogCapture.LINE_GAP_MS + 1);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("\"lv\":\"ERROR\""));
    }

    @Test
    void recognisesPaperStderrPrefix() {
        assertEquals("boom", LogCapture.stderrLine("SomeShop", "[SomeShop] [STDERR] boom"));
        assertEquals("SomeShop", LogCapture.stderrOwner("Minecraft", "[SomeShop] [STDERR] boom"));
        assertEquals(null, LogCapture.stderrLine("SomeShop", "regular [STDERR] text in the middle"));
    }

    @Test
    void log4jTapSeesSlf4jStyleLoggersAndDetaches() {
        Configurator.setRootLevel(Level.WARN);
        LogCapture c = capture();
        LogTaps.Tap tap = LogTaps.install(c, getClass().getClassLoader(), java.util.logging.Logger.getLogger("test"));
        assertEquals("log4j", tap.name());
        Logger logger = LogManager.getLogger("SomeShop");
        logger.warn("Shop {} is out of stock", 7);
        logger.error("Payment failed", new IllegalStateException("gateway"));
        logger.info("not captured");
        List<String> out = c.drain(System.currentTimeMillis());
        assertEquals(2, out.size(), out.toString());
        assertTrue(String.join("", out).contains("Shop 7 is out of stock"));
        tap.uninstall();
        logger.warn("after uninstall");
        assertEquals(0, c.drain(System.currentTimeMillis()).size());
    }
}
