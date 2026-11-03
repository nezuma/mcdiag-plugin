package net.mcdiag.agent.profiler;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Один сэмпл главного потока. Пока сервер ждёт следующего тика, стек не читается вовсе: состояние
 * потока берётся из обычного поля. Поэтому на здоровом сервере снимков стека в разы меньше сэмплов.
 * Работает только в потоке профилировщика.
 */
final class MainThreadSampler {

    /** Обычный средний интервал: основная экономия относительно прежних 20 мс */
    static final long BASE_INTERVAL_MS = 50;
    /** Тик идёт дольше этого — сэмплы становятся чаще; обычный тик на загруженном сервере короче */
    private static final long LONG_TICK_NANOS = BASE_INTERVAL_MS * 1_000_000;
    /** Сэмплы последних секунд — чтобы разобрать, кто занимал долгий тик */
    private static final int RING = 512;

    private final Thread main;
    private final TickMonitor ticks;
    private final StackAttributor attributor;
    private final StackReader reader;
    private final SamplePacer pacer;

    private final long[] ringTick = new long[RING];
    private final String[] ringOwner = new String[RING];
    private final StackTraceElement[] ringFrame = new StackTraceElement[RING];
    private int ringPos;
    private long lastTick = -1;
    private long tickSeenAt;

    // Пишет только этот поток, читает /diag status.
    private volatile long samples;
    private volatile long reads;
    private volatile long readNanos;

    MainThreadSampler(Thread main, TickMonitor ticks, StackAttributor attributor, long intervalMs) {
        this(main, ticks, attributor, new StackReader(main),
                new SamplePacer(Math.max(BASE_INTERVAL_MS, intervalMs), intervalMs, new SplittableRandom()));
    }

    MainThreadSampler(Thread main, TickMonitor ticks, StackAttributor attributor, StackReader reader, SamplePacer pacer) {
        this.main = main;
        this.ticks = ticks;
        this.attributor = attributor;
        this.reader = reader;
        this.pacer = pacer;
    }

    static boolean parked(Thread.State state) {
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    /** @return через сколько миллисекунд снимать следующий сэмпл */
    long sample(ProfileWindow window, double weightMs) {
        samples++;
        boolean inTick = ticks.inTick();
        long tick = ticks.tickSeq();
        long now = System.nanoTime();
        if (!inTick || tick != lastTick) {
            tickSeenAt = now;
        }
        boolean longTick = inTick && tick == lastTick && now - tickSeenAt >= LONG_TICK_NANOS;
        lastTick = inTick ? tick : -1;
        // Между тиками главный поток спит до следующего — это не нагрузка.
        if (!inTick && parked(main.getState())) {
            window.addIdle(weightMs);
            return pacer.next(false, 0);
        }
        StackTraceElement[] stack = reader.read();
        long cost = System.nanoTime() - now;
        reads++;
        readNanos += cost;
        if (stack.length == 0) {
            return pacer.next(longTick, cost);
        }
        // Пока читали стек, тик мог закончиться: состояние и стек теперь читаются не атомарно.
        if (!inTick && FrameClassifier.isWaiting(main.getState(), stack[0])) {
            window.addIdle(weightMs);
            return pacer.next(false, cost);
        }
        String owner = attributor.attribute(stack);
        window.addBusy(owner, weightMs);
        if (inTick) {
            ringTick[ringPos] = tick;
            ringOwner[ringPos] = owner;
            ringFrame[ringPos] = ProfileCapture.hotElement(stack);
            ringPos = (ringPos + 1) % RING;
        }
        return pacer.next(longTick, cost);
    }

    double maxWeightMs() {
        return pacer.maxWeightMs();
    }

    /** Кто занимал тик: идём от новых сэмплов, спайк только что закончился и лежит в самом конце. */
    void collect(long tick, List<String> owners, List<String> frames) {
        boolean found = false;
        for (int i = 1; i <= RING; i++) {
            int at = (ringPos - i + RING) % RING;
            if (ringOwner[at] == null) {
                return;
            }
            if (ringTick[at] != tick) {
                if (found) {
                    return;
                }
                continue;
            }
            found = true;
            owners.add(ringOwner[at]);
            if (ringFrame[at] != null) {
                frames.add(ProfileCapture.frameName(ringFrame[at]));
            }
        }
    }

    long samples() {
        return samples;
    }

    long stackReads() {
        return reads;
    }

    long stackReadNanos() {
        return readNanos;
    }

    boolean usesHandshake() {
        return reader.usesHandshake();
    }
}
