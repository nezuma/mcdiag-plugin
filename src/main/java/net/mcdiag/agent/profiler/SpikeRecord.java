package net.mcdiag.agent.profiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.mcdiag.agent.collect.LogRing;
import net.mcdiag.agent.json.Json;

/** Долгий тик и кто занимал главный поток во время него (доли сэмплов этого тика). */
public record SpikeRecord(long ts, double ms, List<Share> top, List<Share> hot, List<LogRing.Line> logs) {

    public static final int MAX_TOP = 3;
    public static final int MAX_HOT = 5;
    /** Лимит строк лога на спайк: батч не должен раздуваться из-за лога */
    private static final int MAX_LOG_CHARS = 8 * 1024;

    public SpikeRecord(long ts, double ms, List<Share> top) {
        this(ts, ms, top, List.of(), List.of());
    }

    /** Доли владельцев по сэмплам одного тика. */
    public static SpikeRecord of(long ts, double ms, List<String> owners) {
        return new SpikeRecord(ts, ms, shares(owners, MAX_TOP));
    }

    public static List<Share> shares(List<String> names, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (String o : names) {
            counts.merge(o, 1, Integer::sum);
        }
        List<Share> list = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            list.add(new Share(e.getKey(), (double) e.getValue() / names.size()));
        }
        list.sort((a, b) -> Double.compare(b.share(), a.share()));
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
    }

    /** Новый формат: отдельным элементом поля {@code spikes}, с горячими методами и логом. */
    public String richJson() {
        StringBuilder sb = new StringBuilder(512);
        appendJson(sb);
        sb.setLength(sb.length() - 1);
        sb.append(",\"hot\":[");
        for (int i = 0; i < hot.size(); i++) {
            Json.str(i > 0 ? sb.append(",{\"f\":") : sb.append("{\"f\":"), hot.get(i).name(), 120).append(",\"p\":");
            Json.decimal2(sb, Math.min(1, hot.get(i).share())).append('}');
        }
        sb.append("],\"logs\":[");
        int start = sb.length();
        boolean first = true;
        for (int i = logs.size() - 1; i >= 0 && sb.length() - start < MAX_LOG_CHARS; i--) {
            LogRing.Line line = logs.get(i);
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"ts\":").append(line.ts()).append(",\"l\":");
            Json.str(sb, line.level(), 16);
            if (line.logger() != null) {
                Json.str(sb.append(",\"g\":"), line.logger(), 64);
            }
            Json.str(sb.append(",\"m\":"), line.message(), 180).append('}');
        }
        return sb.append("]}").toString();
    }

    public void appendJson(StringBuilder sb) {
        sb.append("{\"ts\":").append(ts).append(",\"ms\":");
        Json.decimal2(sb, Math.min(ms, 600_000)).append(",\"top\":[");
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"n\":");
            Json.str(sb, top.get(i).name(), 65).append(",\"p\":");
            Json.decimal2(sb, Math.min(1, top.get(i).share())).append('}');
        }
        sb.append("]}");
    }

    public record Share(String name, double share) {
    }
}
