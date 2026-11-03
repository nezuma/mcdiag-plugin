package net.mcdiag.agent.profiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
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
        StackReader reader = new StackReader(mainThread);
        // Разовый профиль подробнее обычного, но и его снимки стека ограничены бюджетом.
        SamplePacer pacer = new SamplePacer(INTERVAL_MS, INTERVAL_MS, new SplittableRandom());
        StackAttributor attributor = new StackAttributor(lookup);
        Map<String, Integer> owners = new HashMap<>();
        Map<String, Integer> hot = new HashMap<>();
        int busy = 0;
        int idle = 0;
        long delay = INTERVAL_MS;
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(delay);
            boolean inTick = ticks != null && ticks.inTick();
            if (!inTick && MainThreadSampler.parked(mainThread.getState())) {
                idle++;
                delay = pacer.next(false, 0);
                continue;
            }
            long started = System.nanoTime();
            StackTraceElement[] stack = reader.read();
            delay = pacer.next(true, System.nanoTime() - started);
            if (stack.length == 0) {
                continue;
            }
            if (!inTick && FrameClassifier.isWaiting(mainThread.getState(), stack[0])) {
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
        StackTraceElement f = hotElement(stack);
        return f == null ? null : frameName(f);
    }

    /** Верхний кадр не из JDK; ссылка на кадр дешевле строки, строку соберём только для спайка. */
    static StackTraceElement hotElement(StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            if (!FrameClassifier.isNeutral(f.getClassName())) {
                return f;
            }
        }
        return null;
    }

    static String frameName(StackTraceElement f) {
        String cls = f.getClassName();
        return cls.substring(cls.lastIndexOf('.') + 1) + "." + f.getMethodName();
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
