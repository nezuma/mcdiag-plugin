package net.mcdiag.agent.heatmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.mcdiag.agent.json.Json;

/**
 * Счётчики за 5 минут: позиции по клеткам и клики. Наружу уходит сводка, а не след каждого игрока.
 * Размер ограничен, поэтому батч не вырастет, сколько бы игроков ни стояло на спавне.
 */
final class HeatAggregator {

    static final int MAX_CELLS = 1200;
    static final int MAX_CLICKS = 256;
    static final int MAX_VISITS = 256;
    static final int MAX_DEATHS = 128;

    record Death(long ts, String hash, String victim, String killerType, String killer, String weapon, String cause,
                 String world, int x, int y, int z, boolean inZone) {
    }

    private Map<Long, int[]> cells = new HashMap<>();
    private Map<String, Click> clicks = new HashMap<>();
    private List<Death> deaths = new ArrayList<>();

    private static final class Click {
        final int x;
        final int y;
        final int z;
        final String kind;
        final String material;
        int count;

        Click(int x, int y, int z, String kind, String material) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.material = material;
        }
    }

    /** Снимок, из которого JSON собирается уже вне главного потока. */
    record Snapshot(Map<Long, int[]> cells, Map<String, ?> clicks, List<Death> deaths, List<VisitTracker.Finished> visits) {
    }

    void addPosition(int x, int z, boolean newcomer) {
        long key = (((long) Math.floorDiv(x, HeatZone.CELL)) << 32) ^ (Math.floorDiv(z, HeatZone.CELL) & 0xffffffffL);
        int[] counts = cells.get(key);
        if (counts == null) {
            if (cells.size() >= MAX_CELLS) {
                return;
            }
            counts = new int[2];
            cells.put(key, counts);
        }
        counts[0]++;
        if (newcomer) {
            counts[1]++;
        }
    }

    void addClick(int x, int y, int z, String kind, String material) {
        String key = x + ":" + y + ":" + z + ":" + kind;
        Click click = clicks.get(key);
        if (click == null) {
            if (clicks.size() >= MAX_CLICKS) {
                return;
            }
            click = new Click(x, y, z, kind, material);
            clicks.put(key, click);
        }
        click.count++;
    }

    void addDeath(Death death) {
        if (deaths.size() < MAX_DEATHS) {
            deaths.add(death);
        }
    }

    boolean isEmpty() {
        return cells.isEmpty() && clicks.isEmpty() && deaths.isEmpty();
    }

    /** Быстро: только подмена коллекций. */
    Snapshot drain(List<VisitTracker.Finished> visits) {
        Snapshot snapshot = new Snapshot(cells, clicks, deaths, visits);
        cells = new HashMap<>();
        clicks = new HashMap<>();
        deaths = new ArrayList<>();
        return snapshot;
    }

    /** Элемент поля {@code heat} батча. */
    static String toJson(Snapshot s, long ts, String zone, String defJson) {
        StringBuilder sb = new StringBuilder(2048).append("{\"ts\":").append(ts);
        Json.str(sb.append(",\"zone\":"), zone, 160);
        if (defJson != null) {
            sb.append(",\"def\":").append(defJson);
        }
        if (!s.cells().isEmpty()) {
            sb.append(",\"cells\":[");
            boolean first = true;
            for (Map.Entry<Long, int[]> cell : s.cells().entrySet()) {
                int cx = (int) (cell.getKey() >> 32);
                int cz = (int) (long) cell.getKey();
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(cx * HeatZone.CELL).append(',').append(cz * HeatZone.CELL).append(',')
                        .append(cell.getValue()[0]).append(',').append(cell.getValue()[1]);
            }
            sb.append(']');
        }
        if (!s.clicks().isEmpty()) {
            sb.append(",\"clicks\":[");
            boolean first = true;
            for (Object value : s.clicks().values()) {
                Click click = (Click) value;
                sb.append(first ? "{\"x\":" : ",{\"x\":").append(click.x).append(",\"y\":").append(click.y).append(",\"z\":").append(click.z);
                first = false;
                Json.str(sb.append(",\"k\":"), click.kind, 8);
                Json.str(sb.append(",\"m\":"), click.material, 64).append(",\"c\":").append(click.count).append('}');
            }
            sb.append(']');
        }
        if (!s.visits().isEmpty()) {
            sb.append(",\"visits\":[");
            for (int i = 0; i < s.visits().size(); i++) {
                VisitTracker.Finished v = s.visits().get(i);
                sb.append(i > 0 ? ",{\"ts\":" : "{\"ts\":").append(v.ts());
                Json.str(sb.append(",\"u\":"), v.hash(), 128);
                sb.append(",\"f\":").append(v.first()).append(",\"j\":").append(v.fromJoin()).append(",\"d\":").append(v.durSec());
                Json.str(sb.append(",\"e\":"), v.end(), 8);
                if (v.cause() != null) {
                    Json.str(sb.append(",\"c\":"), v.cause(), 64);
                }
                sb.append(",\"x\":").append(v.x()).append(",\"z\":").append(v.z());
                appendNames(sb, "p", v.pois());
                appendNames(sb, "pc", v.clickedPois());
                sb.append(",\"cl\":").append(v.clicks()).append('}');
            }
            sb.append(']');
        }
        if (!s.deaths().isEmpty()) {
            sb.append(",\"deaths\":[");
            for (int i = 0; i < s.deaths().size(); i++) {
                Death d = s.deaths().get(i);
                sb.append(i > 0 ? ",{\"ts\":" : "{\"ts\":").append(d.ts());
                Json.str(sb.append(",\"u\":"), d.hash(), 128);
                Json.str(sb.append(",\"v\":"), d.victim(), 32);
                Json.str(sb.append(",\"kt\":"), d.killerType(), 8);
                appendOptional(sb, "k", d.killer());
                appendOptional(sb, "w", d.weapon());
                appendOptional(sb, "c", d.cause());
                appendOptional(sb, "wd", d.world());
                sb.append(",\"x\":").append(d.x()).append(",\"y\":").append(d.y()).append(",\"z\":").append(d.z())
                        .append(",\"in\":").append(d.inZone()).append('}');
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    private static void appendOptional(StringBuilder sb, String key, String value) {
        if (value != null && !value.isEmpty()) {
            Json.str(sb.append(",\"").append(key).append("\":"), value, 64);
        }
    }

    private static void appendNames(StringBuilder sb, String key, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        sb.append(",\"").append(key).append("\":[");
        for (int i = 0; i < names.size(); i++) {
            Json.str(i > 0 ? sb.append(',') : sb, names.get(i), 32);
        }
        sb.append(']');
    }

    /** Описание зоны для панели. */
    static String defJson(HeatZone zone, List<Poi> pois) {
        StringBuilder sb = new StringBuilder(256).append("{\"world\":");
        Json.str(sb, zone.world(), 64).append(",\"x1\":").append(zone.minX()).append(",\"y1\":").append(zone.minY())
                .append(",\"z1\":").append(zone.minZ()).append(",\"x2\":").append(zone.maxX()).append(",\"y2\":").append(zone.maxY())
                .append(",\"z2\":").append(zone.maxZ()).append(",\"cell\":").append(HeatZone.CELL).append(",\"pois\":[");
        for (int i = 0; i < pois.size(); i++) {
            Poi poi = pois.get(i);
            Json.str(sb.append(i > 0 ? ",{\"n\":" : "{\"n\":"), poi.name(), 32)
                    .append(",\"x\":").append(poi.x()).append(",\"y\":").append(poi.y()).append(",\"z\":").append(poi.z()).append('}');
        }
        return sb.append("]}").toString();
    }
}
