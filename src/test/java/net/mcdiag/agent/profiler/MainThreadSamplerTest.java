package net.mcdiag.agent.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MainThreadSamplerTest {

    private static MainThreadSampler sampler(Thread main, TickMonitor ticks) {
        StackAttributor attributor = new StackAttributor(cls -> cls.contains("MainThreadSamplerTest") ? "Owner" : null);
        return new MainThreadSampler(main, ticks, attributor, new StackReader(main),
                new SamplePacer(50, 20, new SplittableRandom(1)));
    }

    private static Thread parkedThread() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
                // конец теста
            }
        });
        t.setDaemon(true);
        t.start();
        started.await();
        while (t.getState() != Thread.State.TIMED_WAITING) {
            Thread.onSpinWait();
        }
        return t;
    }

    @Test
    void doesNotReadTheStackWhileTheServerWaitsForTheNextTick() throws Exception {
        Thread main = parkedThread();
        MainThreadSampler sampler = sampler(main, new TickMonitor(100));
        ProfileWindow window = new ProfileWindow(0);
        for (int i = 0; i < 100; i++) {
            sampler.sample(window, 50);
        }
        main.interrupt();
        assertEquals(0, sampler.stackReads());
        assertEquals(100, sampler.samples());
        assertEquals(5000, window.wallMs(), 0.001);
        assertEquals(0, window.busyMs(), 0.001);
    }

    @Test
    void attributesATickAndKeepsItsSamplesForTheSpike() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread main = new Thread(() -> {
            started.countDown();
            while (!stop.get()) {
                Thread.onSpinWait();
            }
        });
        main.start();
        started.await();
        TickMonitor ticks = new TickMonitor(100);
        MainThreadSampler sampler = sampler(main, ticks);
        ProfileWindow window = new ProfileWindow(0);
        try {
            ticks.tickStarted(7);
            sampler.sample(window, 50);
            sampler.sample(window, 20);
            ticks.tickEnded(7, 150);
            ticks.tickStarted(8);
            sampler.sample(window, 50);
            ticks.tickEnded(8, 10);
        } finally {
            stop.set(true);
            main.join();
        }
        assertEquals(3, sampler.stackReads());
        assertEquals("Owner", window.top(1).get(0).name());
        assertEquals(120, window.busyMs(), 0.001);

        List<String> owners = new ArrayList<>();
        List<String> frames = new ArrayList<>();
        sampler.collect(7, owners, frames);
        assertEquals(List.of("Owner", "Owner"), owners);
        assertEquals(2, frames.size());
        owners.clear();
        sampler.collect(99, owners, new ArrayList<>());
        assertTrue(owners.isEmpty());
    }

    @Test
    void readsStacksWithBothMethods() throws Exception {
        Thread main = parkedThread();
        assertTrue(new StackReader(main, true).read().length > 0);
        assertTrue(new StackReader(main, false).read().length > 0);
        main.interrupt();
    }

    @Test
    void spikeCheckDoesNotNeedTheMonitorWithoutSpikes() {
        TickMonitor ticks = new TickMonitor(100);
        assertEquals(null, ticks.pollSpike());
        ticks.tickStarted(1);
        ticks.tickEnded(1, 150);
        ticks.tickStarted(2);
        ticks.tickEnded(2, 300);
        assertEquals(1, ticks.pollSpike().tick());
        assertEquals(2, ticks.pollSpike().tick());
        assertEquals(null, ticks.pollSpike());
    }
}
