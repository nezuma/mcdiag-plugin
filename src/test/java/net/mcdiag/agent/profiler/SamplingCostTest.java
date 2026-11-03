package net.mcdiag.agent.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Сколько процессора стоит один сэмпл: снять стек занятого потока глубиной ~100 кадров и найти
 * владельца. Это вся работа профилировщика на сэмпл; при интервале 20 мс их 50 в секунду.
 * Меряем CPU-время потока профилировщика: ожидание safepoint процессор не занимает.
 */
class SamplingCostTest {

    private static volatile long sink;

    private static void deep(int depth, AtomicBoolean stop) {
        if (depth > 0) {
            deep(depth - 1, stop);
            return;
        }
        while (!stop.get()) {
            sink += System.nanoTime() % 7;
        }
    }

    @Test
    void sampleIsCheap() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Thread busy = new Thread(() -> deep(100, stop), "busy-main");
        busy.start();
        Thread.sleep(100);
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        StackAttributor attributor = new StackAttributor(cls -> cls.startsWith("net.mcdiag.") ? "MCDiag" : null);
        ProfileWindow window = new ProfileWindow(0);
        try {
            for (int i = 0; i < 200; i++) {
                window.addBusy(attributor.attribute(mx.getThreadInfo(busy.getId(), 128).getStackTrace()), 20);
            }
            int samples = 2000;
            long cpu0 = mx.getCurrentThreadCpuTime();
            for (int i = 0; i < samples; i++) {
                ThreadInfo info = mx.getThreadInfo(busy.getId(), 128);
                window.addBusy(attributor.attribute(info.getStackTrace()), 20);
            }
            double cpuUs = (mx.getCurrentThreadCpuTime() - cpu0) / 1000.0 / samples;
            System.out.printf("profiler CPU per sample: %.1f us = %.3f%% of one core at 50 samples/s%n",
                    cpuUs, cpuUs * 50 / 10_000);
            assertEquals("MCDiag", window.top(1).get(0).name());
            assertTrue(cpuUs < 200, "sample took " + cpuUs + " us of CPU");
        } finally {
            stop.set(true);
            busy.join();
        }
    }
}
