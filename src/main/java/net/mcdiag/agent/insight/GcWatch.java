package net.mcdiag.agent.insight;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Паузы сборщика мусора. JVM сама сообщает о каждой сборке, опроса нет. Пока идёт пауза, стоят все
 * потоки, поэтому сэмплы её не видят: без этих данных время GC приписывается случайному плагину.
 */
public final class GcWatch implements NotificationListener {

    private static final int RING = 64;
    private static final long MB = 1024 * 1024;

    /** @param startMs начало по часам сервера */
    public record Pause(long startMs, long ms, String name, String cause, long beforeMb, long afterMb) {
    }

    /** Итог за минуту. */
    public record Minute(int count, long totalMs, long maxMs) {
    }

    private final ArrayDeque<Pause> recent = new ArrayDeque<>(RING);
    private final List<NotificationEmitter> emitters = new ArrayList<>();
    private final long jvmStartMs = ManagementFactory.getRuntimeMXBean().getStartTime();
    private int count;
    private long totalMs;
    private long maxMs;

    public void start() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(this, null, null);
                emitters.add(emitter);
            }
        }
    }

    public void stop() {
        for (NotificationEmitter emitter : emitters) {
            try {
                emitter.removeNotificationListener(this);
            } catch (Exception ignored) {
                // Слушателя уже нет.
            }
        }
        emitters.clear();
    }

    /** ZGC, Shenandoah и G1 сообщают и о фоновых циклах: сервер в это время не стоит. */
    static boolean isPause(String gcName) {
        return !gcName.contains("Cycles") && !gcName.contains("Concurrent");
    }

    @Override
    public void handleNotification(Notification notification, Object handback) {
        try {
            if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                return;
            }
            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            if (!isPause(info.getGcName())) {
                return;
            }
            GcInfo gc = info.getGcInfo();
            record(new Pause(jvmStartMs + gc.getStartTime(), gc.getDuration(), info.getGcName(), info.getGcCause(),
                    sum(gc.getMemoryUsageBeforeGc()), sum(gc.getMemoryUsageAfterGc())));
        } catch (RuntimeException ignored) {
            // Диагностика не должна мешать работе JVM.
        }
    }

    private static long sum(Map<String, MemoryUsage> pools) {
        long used = 0;
        for (Map.Entry<String, MemoryUsage> pool : pools.entrySet()) {
            String name = pool.getKey();
            if (name.contains("Eden") || name.contains("Survivor") || name.contains("Old") || name.contains("Tenured")
                    || name.startsWith("ZHeap") || name.startsWith("ZGC") || name.equals("Shenandoah")) {
                used += pool.getValue().getUsed();
            }
        }
        return used / MB;
    }

    synchronized void record(Pause pause) {
        if (recent.size() >= RING) {
            recent.pollFirst();
        }
        recent.addLast(pause);
        count++;
        totalMs += pause.ms();
        maxMs = Math.max(maxMs, pause.ms());
    }

    /** Паузы, пересекающие промежуток. */
    public synchronized List<Pause> overlapping(long fromMs, long toMs) {
        List<Pause> out = new ArrayList<>(2);
        for (Pause pause : recent) {
            if (pause.startMs() <= toMs && pause.startMs() + pause.ms() >= fromMs) {
                out.add(pause);
            }
        }
        return out;
    }

    public synchronized Minute drainMinute() {
        Minute minute = new Minute(count, totalMs, maxMs);
        count = 0;
        totalMs = 0;
        maxMs = 0;
        return minute;
    }
}
