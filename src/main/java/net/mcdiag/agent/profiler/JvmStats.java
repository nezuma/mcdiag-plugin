package net.mcdiag.agent.profiler;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import net.mcdiag.agent.json.Json;

/** Процессор, память и сборщик мусора JVM — раз в минуту, из стандартных MXBean. */
final class JvmStats {

    private long lastGcMs = -1;

    String snapshotJson() {
        StringBuilder sb = new StringBuilder(128).append('{');
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        boolean first = true;
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double cpu = sun.getProcessCpuLoad();
            double sys = sun.getCpuLoad();
            if (cpu >= 0) {
                Json.decimal2(sb.append("\"cpu\":"), Math.min(1, cpu));
                first = false;
            }
            if (sys >= 0) {
                Json.decimal2(sb.append(first ? "" : ",").append("\"sys\":"), Math.min(1, sys));
                first = false;
            }
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        sb.append(first ? "" : ",").append("\"heap\":").append(heap.getUsed() >> 20);
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
        return sb.append('}').toString();
    }
}
