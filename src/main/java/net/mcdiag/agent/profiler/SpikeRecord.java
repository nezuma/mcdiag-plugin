package net.mcdiag.agent.profiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.mcdiag.agent.json.Json;

/** Долгий тик и кто занимал главный поток во время него (доли сэмплов этого тика). */
public record SpikeRecord(long ts, double ms, List<Share> top) {

    public static final int MAX_TOP = 3;

    /** Доли владельцев по сэмплам одного тика. */
    public static SpikeRecord of(long ts, double ms, List<String> owners) {
        Map<String, Integer> counts = new HashMap<>();
        for (String o : owners) {
            counts.merge(o, 1, Integer::sum);
        }
        List<Share> top = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            top.add(new Share(e.getKey(), (double) e.getValue() / owners.size()));
        }
        top.sort((a, b) -> Double.compare(b.share(), a.share()));
        return new SpikeRecord(ts, ms, top.size() > MAX_TOP ? List.copyOf(top.subList(0, MAX_TOP)) : top);
    }

    void appendJson(StringBuilder sb) {
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
