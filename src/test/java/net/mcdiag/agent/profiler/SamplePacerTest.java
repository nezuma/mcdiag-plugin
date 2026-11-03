package net.mcdiag.agent.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SamplePacerTest {

    private static SamplePacer pacer() {
        return new SamplePacer(50, 20, new SplittableRandom(1));
    }

    @Test
    void averagesTheBaseIntervalWithJitter() {
        SamplePacer pacer = pacer();
        Set<Long> seen = new HashSet<>();
        long sum = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            long delay = pacer.next(false, 0);
            assertTrue(delay >= 25 && delay < 75, "delay " + delay);
            seen.add(delay);
            sum += delay;
        }
        assertEquals(50, (double) sum / n, 1);
        assertTrue(seen.size() > 40, "the period must not stay in phase with 50 ms ticks");
    }

    @Test
    void samplesFasterWhileATickRunsLong() {
        SamplePacer pacer = pacer();
        for (int i = 0; i < 1000; i++) {
            long delay = pacer.next(true, 0);
            assertTrue(delay >= 10 && delay < 30, "delay " + delay);
        }
    }

    @Test
    void backsOffWhenStackReadsGetExpensive() {
        SamplePacer pacer = pacer();
        // 2 мс на снимок при бюджете 1/200 — не чаще раза в 400 мс.
        for (int i = 0; i < 100; i++) {
            pacer.next(false, 2_000_000);
        }
        assertEquals(400, pacer.nominalMs());
        // Снимки снова дешёвые — интервал возвращается.
        for (int i = 0; i < 100; i++) {
            pacer.next(false, 10_000);
        }
        assertEquals(50, pacer.nominalMs());
    }

    @Test
    void aLongTickKeepsDetailWithALargerBudget() {
        SamplePacer pacer = pacer();
        // Снимок 250 мкс (обычно на Java 17): в обычное время 50 мс, в долгом тике всё те же 20 мс.
        for (int i = 0; i < 100; i++) {
            pacer.next(true, 250_000);
        }
        assertEquals(20, pacer.nominalMs());
        // Но и там бюджет есть: 2 мс на снимок при 1/50 — не чаще раза в 100 мс.
        for (int i = 0; i < 100; i++) {
            pacer.next(true, 2_000_000);
        }
        assertEquals(100, pacer.nominalMs(), 1);
    }

    @Test
    void keepsSamplingEvenWhenReadsAreVeryExpensive() {
        SamplePacer pacer = pacer();
        for (int i = 0; i < 100; i++) {
            pacer.next(false, 1_000_000_000);
        }
        assertEquals(500, pacer.nominalMs());
    }
}
