package net.mcdiag.agent.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Сэмплы главного потока. Пока сервер ждёт следующего тика, стек не читается вовсе. Обычный тик даёт
 * редкие сэмплы для минутной статистики; затянувшийся тик сразу получает частые, потому что данные
 * о лаг-спайке важнее всего и случаются такие тики редко.
 * Работает только в потоке профилировщика.
 */
final class MainThreadSampler {

    /** Обычный средний интервал: для минутной статистики этого хватает */
    static final long BASE_INTERVAL_MS = 50;
    private static final long TICK_MS = 50;
    /** Раньше этого сэмплы не учащаем: на здоровом сервере обычный тик короче */
    private static final double MIN_WATCH_MS = 15;
    private static final int RING = 2048;
    /** Стеки тяжелее остальных данных: их держим меньше, а долгий тик прореживаем */
    private static final int STACK_RING = 512;
    private static final int STACKS_PER_TICK = 256;
    private static final int STACK_EVERY_AFTER = 8;

    private final Thread main;
    private final TickMonitor ticks;
    private final StackAttributor attributor;
    private final StackReader reader;
    private final SamplePacer pacer;
    private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
    private final com.sun.management.ThreadMXBean allocMx;

    private final long[] ringTick = new long[RING];
    private final long[] ringNanos = new long[RING];
    private final String[] ringOwner = new String[RING];
    private final StackTraceElement[] ringFrame = new StackTraceElement[RING];
    private final Thread.State[] ringState = new Thread.State[RING];
    private final String[] ringLock = new String[RING];
    private final StackTraceElement[][] ringStack = new StackTraceElement[RING][];
    private final int[] stackSlots = new int[STACK_RING];
    private int stackSlot;
    private int ringPos;

    private long lastSampleNanos = System.nanoTime();
    private long nextSampleAt;
    private long lastTick = -1;
    private int tickSamples;
    private double avgTickMs = 10;
    private long allocAtTickStart = -1;
    private long allocTick = -1;
    private long allocLast;

    // Пишет только этот поток, читает /diag status.
    private volatile long samples;
    private volatile long reads;
    private volatile long readNanos;

    MainThreadSampler(Thread main, TickMonitor ticks, StackAttributor attributor, long intervalMs, long denseMs) {
        this(main, ticks, attributor, new StackReader(main),
                new SamplePacer(Math.max(BASE_INTERVAL_MS, intervalMs), denseMs, new SplittableRandom()));
    }

    MainThreadSampler(Thread main, TickMonitor ticks, StackAttributor attributor, StackReader reader, SamplePacer pacer) {
        this.main = main;
        this.ticks = ticks;
        this.attributor = attributor;
        this.reader = reader;
        this.pacer = pacer;
        this.allocMx = mx instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported() ? sun : null;
    }

    static boolean parked(Thread.State state) {
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    /**
     * Шаг цикла профилировщика. Просыпается примерно раз за тик только для проверки его длительности:
     * это чтение пары полей, главный поток об этом не знает. Стек снимается по расписанию или сразу
     * и часто, если тик затянулся.
     *
     * @return через сколько миллисекунд проснуться снова
     */
    long step(ProfileWindow window) {
        long now = System.nanoTime();
        boolean inTick = ticks.inTick();
        long start = ticks.tickStartNanos();
        double last = ticks.lastTickMs();
        if (last > 0) {
            avgTickMs += (Math.min(last, 100) - avgTickMs) / 64;
        }
        long watchNanos = watchNanos();
        boolean longTick = inTick && now - start >= watchNanos;
        if (longTick || now >= nextSampleAt) {
            double weight = Math.min((now - lastSampleNanos) / 1_000_000.0, pacer.maxWeightMs());
            lastSampleNanos = now;
            long delay = sample(window, weight);
            nextSampleAt = now + delay * 1_000_000;
            if (longTick) {
                return delay;
            }
        }
        long sampleIn = Math.max(1, (nextSampleAt - now) / 1_000_000);
        long watchAt = inTick ? start + watchNanos : Math.max(start + TICK_MS * 1_000_000, now) + watchNanos;
        return Math.max(1, Math.min(sampleIn, (watchAt - now) / 1_000_000 + 1));
    }

    private long watchNanos() {
        double watch = Math.max(MIN_WATCH_MS, avgTickMs * 1.5);
        return (long) (Math.min(watch, Math.max(MIN_WATCH_MS, ticks.spikeThresholdMs() / 2)) * 1_000_000);
    }

    /** @return через сколько миллисекунд снимать следующий сэмпл */
    long sample(ProfileWindow window, double weightMs) {
        samples++;
        long now = System.nanoTime();
        boolean inTick = ticks.inTick();
        long tick = ticks.tickSeq();
        boolean longTick = inTick && now - ticks.tickStartNanos() >= watchNanos();
        if (!inTick || tick != lastTick) {
            tickSamples = 0;
        }
        lastTick = inTick ? tick : -1;
        // Между тиками главный поток спит до следующего — это не нагрузка.
        Thread.State state = main.getState();
        if (!inTick && parked(state)) {
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
        // Пока читали стек, тик мог закончиться: состояние и стек читаются не атомарно.
        if (!inTick && FrameClassifier.isWaiting(main.getState(), stack[0])) {
            window.addIdle(weightMs);
            return pacer.next(false, cost);
        }
        String owner = attributor.attribute(stack);
        window.addBusy(owner, weightMs);
        if (inTick) {
            store(tick, now, owner, stack, state);
            trackAllocation(tick);
        }
        return pacer.next(longTick, cost);
    }

    private void store(long tick, long now, String owner, StackTraceElement[] stack, Thread.State state) {
        ringTick[ringPos] = tick;
        ringNanos[ringPos] = now;
        ringOwner[ringPos] = owner;
        ringFrame[ringPos] = ProfileCapture.hotElement(stack);
        ringState[ringPos] = state;
        ringLock[ringPos] = state == Thread.State.BLOCKED ? lockInfo() : null;
        boolean keepStack = tickSamples < STACKS_PER_TICK || tickSamples % STACK_EVERY_AFTER == 0;
        tickSamples++;
        ringStack[ringPos] = null;
        if (keepStack) {
            // Старые стеки освобождаем сразу: их общее число ограничено.
            ringStack[stackSlots[stackSlot]] = null;
            stackSlots[stackSlot] = ringPos;
            stackSlot = (stackSlot + 1) % STACK_RING;
            ringStack[ringPos] = stack;
        }
        ringPos = (ringPos + 1) % RING;
    }

    /** Кто держит блокировку: глубина 0, поэтому без остановки JVM. */
    private String lockInfo() {
        ThreadInfo info = mx.getThreadInfo(main.getId(), 0);
        if (info == null || info.getLockName() == null) {
            return null;
        }
        String name = info.getLockName();
        int at = name.indexOf('@');
        String lock = at > 0 ? name.substring(0, at) : name;
        return info.getLockOwnerName() == null ? lock : lock + " < " + info.getLockOwnerName();
    }

    private void trackAllocation(long tick) {
        if (allocMx == null) {
            return;
        }
        long bytes = allocMx.getThreadAllocatedBytes(main.getId());
        if (tick != allocTick) {
            allocTick = tick;
            allocAtTickStart = bytes;
        }
        allocLast = bytes;
    }

    /** Сколько памяти главный поток выделил между сэмплами этого тика; -1, если их мало. */
    long allocatedInTick(long tick) {
        return tick == allocTick && allocAtTickStart >= 0 ? Math.max(0, allocLast - allocAtTickStart) : -1;
    }

    double maxWeightMs() {
        return pacer.maxWeightMs();
    }

    /** Сэмплы тика от старых к новым; спайк только что закончился и лежит в самом конце. */
    void collect(long tick, List<String> owners, List<String> frames, List<SpikeAnalyzer.Sample> out) {
        boolean found = false;
        for (int i = 1; i <= RING; i++) {
            int at = (ringPos - i + RING) % RING;
            if (ringOwner[at] == null) {
                break;
            }
            if (ringTick[at] != tick) {
                if (found) {
                    break;
                }
                continue;
            }
            found = true;
            owners.add(ringOwner[at]);
            if (ringFrame[at] != null) {
                frames.add(ProfileCapture.frameName(ringFrame[at]));
            }
            out.add(0, new SpikeAnalyzer.Sample(ringStack[at], ringOwner[at], ringState[at], ringLock[at], ringNanos[at]));
        }
    }

    void collect(long tick, List<String> owners, List<String> frames) {
        collect(tick, owners, frames, new ArrayList<>());
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
