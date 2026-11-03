package net.mcdiag.agent.profiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.mcdiag.agent.json.Json;

/**
 * Итоги профилировщика за одну минуту: сколько миллисекунд главного потока ушло в каждый плагин
 * и в части ядра. Пишет только поток профилировщика.
 */
public final class ProfileWindow {

    /** Бэкенд принимает ≤ 40 плагинов на срез; самые лёгкие сверх лимита не передаются. */
    public static final int MAX_PLUGINS = 40;
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9 _.-]{1,64}$");
    private static final String[] CORE_KEYS = {
        FrameClassifier.ENTITIES, FrameClassifier.CHUNKS, FrameClassifier.BLOCKS,
        FrameClassifier.NETWORK, FrameClassifier.SAVING, FrameClassifier.OTHER,
    };

    private final long startMs;
    private double wallMs;
    private double busyMs;
    /** [0] — главный поток, мс; [1] — CPU асинхронных потоков, мс */
    private final Map<String, double[]> plugins = new HashMap<>();
    private final double[] core = new double[CORE_KEYS.length];

    public ProfileWindow(long startMs) {
        this.startMs = startMs;
    }

    public long startMs() {
        return startMs;
    }

    /** Сэмпл, когда главный поток работал: владелец — плагин или ключ части ядра. */
    public void addBusy(String owner, double ms) {
        wallMs += ms;
        busyMs += ms;
        if (FrameClassifier.isCoreKey(owner)) {
            core[coreIndex(owner)] += ms;
        } else {
            slot(owner)[0] += ms;
        }
    }

    /** Главный поток ждал следующего тика. */
    public void addIdle(double ms) {
        wallMs += ms;
    }

    public void addAsync(String plugin, double ms) {
        slot(plugin)[1] += ms;
    }

    public double wallMs() {
        return wallMs;
    }

    public double busyMs() {
        return busyMs;
    }

    /** Плагины по убыванию времени главного потока — для /diag plugins. */
    public List<Entry> top(int limit) {
        List<Entry> list = new ArrayList<>(plugins.size());
        for (Map.Entry<String, double[]> e : plugins.entrySet()) {
            list.add(new Entry(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        list.sort((a, b) -> Double.compare(b.mainMs() + b.asyncMs() / 1000, a.mainMs() + a.asyncMs() / 1000));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public double coreMs() {
        double sum = 0;
        for (double v : core) {
            sum += v;
        }
        return sum;
    }

    /** Срез в формате IngestProfile. */
    public String toJson(TickMonitor.TickStats ticks, int online, String jvmJson, List<SpikeRecord> spikes) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"ts\":").append(startMs).append(",\"wall\":");
        Json.decimal2(sb, wallMs).append(",\"busy\":");
        Json.decimal2(sb, busyMs).append(",\"ticks\":").append(Math.min(ticks.ticks(), 100_000)).append(",\"tickAvg\":");
        Json.decimal2(sb, Math.min(ticks.avgMs(), 600_000)).append(",\"tickMax\":");
        Json.decimal2(sb, Math.min(ticks.maxMs(), 600_000)).append(",\"online\":").append(online).append(",\"plugins\":[");
        int n = 0;
        for (Entry e : top(MAX_PLUGINS)) {
            if (e.mainMs() < 0.005 && e.asyncMs() < 0.005) {
                continue;
            }
            if (n++ > 0) {
                sb.append(',');
            }
            sb.append("{\"n\":");
            Json.str(sb, e.name(), 64).append(",\"ms\":");
            Json.decimal2(sb, e.mainMs()).append(",\"a\":");
            Json.decimal2(sb, e.asyncMs()).append('}');
        }
        sb.append("],\"core\":{");
        for (int i = 0; i < CORE_KEYS.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(CORE_KEYS[i], 1, CORE_KEYS[i].length()).append("\":");
            Json.decimal2(sb, core[i]);
        }
        sb.append('}');
        if (jvmJson != null) {
            sb.append(",\"jvm\":").append(jvmJson);
        }
        if (!spikes.isEmpty()) {
            sb.append(",\"spikes\":[");
            for (int i = 0; i < spikes.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                spikes.get(i).appendJson(sb);
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    /** Имя, которое бэкенд примет; плагины с «экзотическими» именами не попадают в срез. */
    public static boolean validName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    private double[] slot(String plugin) {
        return plugins.computeIfAbsent(plugin, k -> new double[2]);
    }

    private static int coreIndex(String key) {
        for (int i = 0; i < CORE_KEYS.length - 1; i++) {
            if (CORE_KEYS[i].equals(key)) {
                return i;
            }
        }
        return CORE_KEYS.length - 1;
    }

    public record Entry(String name, double mainMs, double asyncMs) {
    }
}
