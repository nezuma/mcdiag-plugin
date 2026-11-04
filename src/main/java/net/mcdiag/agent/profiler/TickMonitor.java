package net.mcdiag.agent.profiler;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import java.util.ArrayDeque;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Начало и конец каждого тика (события Paper). В главном потоке — только запись пары полей;
 * лаг-спайки складываются в маленькую очередь, которую разбирает поток профилировщика.
 */
public final class TickMonitor implements Listener {

    private static final int MAX_PENDING_SPIKES = 64;

    /** Номер текущего тика; профилировщик помечает им сэмплы */
    private volatile long tickSeq;
    private volatile boolean inTick;
    private volatile long tickStartNanos;
    private volatile double lastTickMs;
    private volatile double spikeThresholdMs;

    // Под монитором this: пишет главный поток раз за тик, читает профилировщик раз в минуту.
    private long ticks;
    private double sumMs;
    private double maxMs;
    private final ArrayDeque<Spike> spikes = new ArrayDeque<>();
    /** Профилировщик смотрит сюда на каждом сэмпле: монитор нужен, только когда спайк есть */
    private volatile boolean hasSpikes;

    public TickMonitor(double spikeThresholdMs) {
        this.spikeThresholdMs = spikeThresholdMs;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickStart(ServerTickStartEvent e) {
        tickStarted(e.getTickNumber());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent e) {
        tickEnded(e.getTickNumber(), e.getTickDuration());
    }

    void tickStarted(long tick) {
        tickStartNanos = System.nanoTime();
        tickSeq = tick;
        inTick = true;
    }

    void tickEnded(long tick, double ms) {
        inTick = false;
        lastTickMs = ms;
        synchronized (this) {
            ticks++;
            sumMs += ms;
            if (ms > maxMs) {
                maxMs = ms;
            }
            if (ms >= spikeThresholdMs && spikes.size() < MAX_PENDING_SPIKES) {
                spikes.addLast(new Spike(tick, System.currentTimeMillis() - (long) ms, ms, tickStartNanos));
                hasSpikes = true;
            }
        }
    }

    public double spikeThresholdMs() {
        return spikeThresholdMs;
    }

    public long tickStartNanos() {
        return tickStartNanos;
    }

    public double lastTickMs() {
        return lastTickMs;
    }

    public void setSpikeThreshold(double ms) {
        spikeThresholdMs = ms;
    }

    public long tickSeq() {
        return tickSeq;
    }

    public boolean inTick() {
        return inTick;
    }

    /** Забирает накопленные спайки (поток профилировщика). */
    public Spike pollSpike() {
        if (!hasSpikes) {
            return null;
        }
        synchronized (this) {
            Spike spike = spikes.pollFirst();
            hasSpikes = !spikes.isEmpty();
            return spike;
        }
    }

    /** Итог по тикам с прошлого вызова и обнуление счётчиков. */
    public synchronized TickStats drain() {
        TickStats s = new TickStats(ticks, ticks > 0 ? sumMs / ticks : 0, maxMs);
        ticks = 0;
        sumMs = 0;
        maxMs = 0;
        return s;
    }

    public record Spike(long tick, long startedAt, double ms, long startNanos) {
    }

    public record TickStats(long ticks, double avgMs, double maxMs) {
    }
}
