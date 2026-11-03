package net.mcdiag.agent.collect;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import net.mcdiag.agent.json.Json;
import org.bukkit.Bukkit;

/**
 * Процессор, память JVM и машины, GC, диск и аптайм — раз в минуту из стандартных MXBean.
 * Всё читается без главного потока; JSON — элемент поля {@code system} батча.
 */
public final class SystemStats implements Runnable {

    private static final long MINUTE_MS = 60_000;

    private final BufferSink sink;
    private long lastGcMs = -1;

    /** Куда класть готовый JSON (буфер плагина; в тестах — список). */
    public interface BufferSink {
        void add(String json);
    }

    public SystemStats(BufferSink sink) {
        this.sink = sink;
    }

    @Override
    public void run() {
        sink.add(snapshotJson(System.currentTimeMillis() / MINUTE_MS * MINUTE_MS));
    }

    String snapshotJson(long ts) {
        StringBuilder sb = new StringBuilder(192).append("{\"ts\":").append(ts);
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double cpu = sun.getProcessCpuLoad();
            double sys = sun.getCpuLoad();
            if (cpu >= 0) {
                Json.decimal2(sb.append(",\"cpu\":"), Math.min(1, cpu));
            }
            if (sys >= 0) {
                Json.decimal2(sb.append(",\"sys\":"), Math.min(1, sys));
            }
            long total = sun.getTotalMemorySize();
            long free = sun.getFreeMemorySize();
            if (total > 0 && free >= 0) {
                sb.append(",\"ram\":").append((total - free) >> 20).append(",\"ramMax\":").append(total >> 20);
            }
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        sb.append(",\"heap\":").append(heap.getUsed() >> 20);
        if (heap.getMax() > 0) {
            sb.append(",\"heapMax\":").append(heap.getMax() >> 20);
        }
        long gc = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc += Math.max(0, bean.getCollectionTime());
        }
        if (lastGcMs >= 0) {
            sb.append(",\"gc\":").append(Math.min(3_600_000, Math.max(0, gc - lastGcMs)));
        }
        lastGcMs = gc;
        sb.append(",\"thr\":").append(ManagementFactory.getThreadMXBean().getThreadCount());
        File dir = Bukkit.getServer() == null ? new File(".") : Bukkit.getWorldContainer();
        long diskTotal = dir.getTotalSpace();
        if (diskTotal > 0) {
            sb.append(",\"disk\":").append((diskTotal - dir.getUsableSpace()) >> 20).append(",\"diskMax\":").append(diskTotal >> 20);
        }
        sb.append(",\"up\":").append(ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return sb.append('}').toString();
    }
}
