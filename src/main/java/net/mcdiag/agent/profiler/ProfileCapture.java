package net.mcdiag.agent.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.mcdiag.agent.json.Json;

/**
 * Разовый подробный профиль по запросу поддержки: N секунд сэмплов главного потока каждые 5 мс.
 * Кроме долей плагинов собирает «горячие» методы — верхний кадр не из JDK. Работает в своём
 * потоке и использует отдельный кеш атрибуции, поэтому не мешает минутному профилировщику.
 */
public final class ProfileCapture {

    private static final long INTERVAL_MS = 5;
    private static final int TOP = 15;

    private ProfileCapture() {
    }

    /** @return поле {@code data} ответа на команду {@code profile} */
    public static String run(Thread mainThread, PluginLookup lookup, TickMonitor ticks, int seconds)
            throws InterruptedException {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        StackAttributor attributor = new StackAttributor(lookup);
        Map<String, Integer> owners = new HashMap<>();
        Map<String, Integer> hot = new HashMap<>();
        int busy = 0;
        int idle = 0;
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(INTERVAL_MS);
            ThreadInfo info = mx.getThreadInfo(mainThread.getId(), 128);
            if (info == null) {
                continue;
            }
            StackTraceElement[] stack = info.getStackTrace();
            boolean inTick = ticks != null && ticks.inTick();
            if (!inTick && FrameClassifier.isWaiting(info.getThreadState(), stack.length > 0 ? stack[0] : null)) {
                idle++;
                continue;
            }
            busy++;
            owners.merge(attributor.attribute(stack), 1, Integer::sum);
            String frame = hotFrame(stack);
            if (frame != null) {
                hot.merge(frame, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder(2048).append("{\"seconds\":").append(seconds)
                .append(",\"samples\":").append(busy + idle).append(",\"busy\":").append(busy).append(",\"owners\":");
        appendShares(sb, owners, busy, "n");
        sb.append(",\"hot\":");
        appendShares(sb, hot, busy, "f");
        return sb.append('}').toString();
    }

    /** Верхний кадр не из JDK: «Класс.метод» без пакета — коротко и без лишних подробностей. */
    static String hotFrame(StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            if (FrameClassifier.isNeutral(f.getClassName())) {
                continue;
            }
            String cls = f.getClassName();
            return cls.substring(cls.lastIndexOf('.') + 1) + "." + f.getMethodName();
        }
        return null;
    }

    private static void appendShares(StringBuilder sb, Map<String, Integer> counts, int total, String key) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        sb.append('[');
        for (int i = 0; i < Math.min(TOP, list.size()); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"").append(key).append("\":");
            Json.str(sb, list.get(i).getKey(), 120).append(",\"p\":");
            Json.decimal2(sb, total == 0 ? 0 : (double) list.get(i).getValue() / total).append('}');
        }
        sb.append(']');
    }
}
