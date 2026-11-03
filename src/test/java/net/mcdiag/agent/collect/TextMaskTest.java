package net.mcdiag.agent.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class TextMaskTest {

    private final TextMask mask = new TextMask(List.of("/Secret"));

    @Test
    void hidesPasswordsAndPrivateMessages() {
        assertEquals("/login ***", mask.command("/login hunter2"));
        assertEquals("/REGISTER ***", mask.command("/REGISTER hunter2 hunter2"));
        assertEquals("/msg ***", mask.command("/msg Steve привет, пароль 123"));
        assertEquals("/essentials:tell ***", mask.command("/essentials:tell Steve hi"));
        assertEquals("/secret ***", mask.command("/secret abc"), "команда из config.yml");
        assertEquals("/login", mask.command("/login"), "без аргументов скрывать нечего");
    }

    @Test
    void keepsWorldEditCommandsReadable() {
        assertEquals("//set stone", mask.command("//set stone"));
        assertEquals("//stack 100 up", mask.command("  //stack 100 up "));
        assertEquals("/say hello", mask.command("/say hello"));
    }

    @Test
    void hidesAgentTokenAndIps() {
        String token = "mcd_6ae95c2843b10e010f9e9ab5_c1e69bc1-0651-4df0-a42b-94df125380c0";
        assertEquals("diag token ***", mask.command("diag token " + token));
        assertEquals("/diag token ***", mask.command("/diag token " + token));
        assertEquals("/diag status", mask.command("/diag status"));
        assertEquals("токен ***", mask.text("токен " + token));
        assertEquals("зашёл с ***, порт", mask.text("зашёл с 10.20.30.40:25565, порт"));
        assertEquals("версия 1.19.4 ок", mask.text("версия 1.19.4 ок"), "версии — не IP");
    }

    @Test
    void chatLineIsSafeForPanelSchema() {
        assertEquals("Steve_1", ChatListener.safeNick("Steve_1"));
        assertEquals(".BedrockKid", ChatListener.safeNick(".BedrockKid"));
        assertEquals("unknown", ChatListener.safeNick("имя"));
        assertEquals(null, ChatListener.safeNick(null));
        assertEquals("{\"ts\":5,\"k\":\"cmd\",\"n\":\"Steve\",\"t\":\"//set stone\",\"p\":\"WorldEdit\",\"w\":\"world\"}",
                ChatListener.line(5, "cmd", "Steve", "world", "//set stone", "WorldEdit"));
        assertEquals("{\"ts\":5,\"k\":\"console\",\"t\":\"stop\"}", ChatListener.line(5, "console", null, null, "stop", null));
        assertEquals("{\"ts\":5,\"k\":\"chat\",\"n\":\"S\",\"t\":\"\\u043f\"}", ChatListener.line(5, "chat", "S", null, "п", "bad/name"));
    }

    @Test
    void logRingKeepsRecentLinesWithoutCommands() {
        LogRing ring = new LogRing(mask);
        long now = System.currentTimeMillis();
        LogRecord old = new LogRecord(Level.WARNING, "старое");
        old.setMillis(now - 120_000);
        ring.publish(old);
        ring.publish(new LogRecord(Level.FINE, "отладка"));
        ring.publish(new LogRecord(Level.INFO, "Steve issued server command: /login hunter2"));
        LogRecord line = new LogRecord(Level.WARNING, "ошибка с 1.2.3.4");
        line.setLoggerName("WorldEdit");
        ring.publish(line);

        List<LogRing.Line> recent = ring.recent(now - 60_000, 20);
        assertEquals(1, recent.size());
        assertEquals("ошибка с ***", recent.get(0).message());
        assertEquals("WorldEdit", recent.get(0).logger());
        for (int i = 0; i < 300; i++) {
            ring.publish(new LogRecord(Level.INFO, "x" + i));
        }
        List<LogRing.Line> capped = ring.recent(now - 60_000, 5);
        assertEquals(5, capped.size());
        assertEquals("x299", capped.get(4).message(), "от старых к новым");
        assertFalse(capped.stream().anyMatch(l -> l.message().contains("hunter2")));
        assertTrue(capped.stream().allMatch(l -> l.level().equals("INFO")));
    }
}
