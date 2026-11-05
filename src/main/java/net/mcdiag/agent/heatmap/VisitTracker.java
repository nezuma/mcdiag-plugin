package net.mcdiag.agent.heatmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Визиты на спавн: когда игрок вошёл в зону и как она для него закончилась. Это основа конверсии.
 * Чистая логика без Bukkit. Вызывается только в главном потоке, поэтому без синхронизации.
 */
final class VisitTracker {

    /** Ушёл в игру или вышел с сервера. */
    record Finished(long ts, String hash, boolean first, boolean fromJoin, long durSec, String end, String cause,
                    int x, int z, List<String> pois, List<String> clickedPois, int clicks) {
    }

    private static final class Visit {
        final String hash;
        final boolean first;
        final boolean fromJoin;
        final long startedAt;
        final Set<String> pois = new LinkedHashSet<>();
        final Set<String> clickedPois = new LinkedHashSet<>();
        int lastX;
        int lastZ;
        int clicks;
        long seenInSample;

        Visit(String hash, boolean first, boolean fromJoin, long startedAt, int x, int z) {
            this.hash = hash;
            this.first = first;
            this.fromJoin = fromJoin;
            this.startedAt = startedAt;
            this.lastX = x;
            this.lastZ = z;
        }
    }

    private final Map<UUID, Visit> visits = new HashMap<>();
    /** Зашли впервые: нужно и тем, кто дошёл до спавна не сразу */
    private final Set<UUID> newcomers = new HashSet<>();
    private final List<Poi> pois;
    private final List<Finished> finished = new ArrayList<>();
    private final int maxFinished;
    private long sampleSeq;

    VisitTracker(List<Poi> pois, int maxFinished) {
        this.pois = pois;
        this.maxFinished = maxFinished;
    }

    void onJoin(UUID id, String hash, boolean first, boolean inZone, int x, int z, long now) {
        if (first) {
            newcomers.add(id);
        }
        if (inZone) {
            visits.put(id, new Visit(hash, first, true, now, x, z));
            touchPois(visits.get(id), x, z);
        }
    }

    boolean visiting(UUID id) {
        return visits.containsKey(id);
    }

    boolean isNewcomer(UUID id) {
        return newcomers.contains(id);
    }

    void beginSample() {
        sampleSeq++;
    }

    /** Сэмпл игрока внутри зоны: начинает визит или обновляет его. */
    void sampleInside(UUID id, String hash, int x, int z, long now) {
        Visit visit = visits.get(id);
        if (visit == null) {
            visit = new Visit(hash, newcomers.contains(id), false, now, x, z);
            visits.put(id, visit);
        }
        visit.lastX = x;
        visit.lastZ = z;
        visit.seenInSample = sampleSeq;
        touchPois(visit, x, z);
    }

    /** Кого нет в зоне в этом сэмпле, тот ушёл пешком: последняя точка — место выхода. */
    void endSample(long now) {
        for (Iterator<Map.Entry<UUID, Visit>> it = visits.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Visit> entry = it.next();
            Visit visit = entry.getValue();
            // Только что начатый визит ещё не видел сэмпла: его оценит следующий.
            if (visit.seenInSample != sampleSeq && (visit.seenInSample != 0 || now > visit.startedAt)) {
                finish(visit, "walk", null, visit.lastX, visit.lastZ, now);
                it.remove();
            }
        }
    }

    /** @param portal портал, а не телепорт */
    void onLeaveByTeleport(UUID id, boolean portal, String cause, int fromX, int fromZ, long now) {
        Visit visit = visits.remove(id);
        if (visit != null) {
            finish(visit, portal ? "portal" : "tp", cause, fromX, fromZ, now);
        }
    }

    void onQuit(UUID id, int x, int z, long now) {
        newcomers.remove(id);
        Visit visit = visits.remove(id);
        if (visit != null) {
            finish(visit, "quit", null, x, z, now);
        }
    }

    void onDeath(UUID id, int x, int z, long now) {
        Visit visit = visits.remove(id);
        if (visit != null) {
            finish(visit, "death", null, x, z, now);
        }
    }

    void onClick(UUID id, String poi) {
        Visit visit = visits.get(id);
        if (visit == null) {
            return;
        }
        visit.clicks++;
        if (poi != null) {
            visit.clickedPois.add(poi);
            visit.pois.add(poi);
        }
    }

    /** Ближайший POI к точке клика в пределах радиуса. */
    String poiAt(int x, int z) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Poi poi : pois) {
            if (poi.near(x, z)) {
                int dist = (poi.x() - x) * (poi.x() - x) + (poi.z() - z) * (poi.z() - z);
                if (dist < bestDist) {
                    best = poi.name();
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    int active() {
        return visits.size();
    }

    List<Finished> drainFinished() {
        List<Finished> out = new ArrayList<>(finished);
        finished.clear();
        return out;
    }

    private void touchPois(Visit visit, int x, int z) {
        for (Poi poi : pois) {
            if (poi.near(x, z)) {
                visit.pois.add(poi.name());
            }
        }
    }

    private void finish(Visit v, String end, String cause, int x, int z, long now) {
        if (finished.size() >= maxFinished) {
            return;
        }
        finished.add(new Finished(now, v.hash, v.first, v.fromJoin, Math.max(0, (now - v.startedAt) / 1000), end, cause,
                x, z, List.copyOf(v.pois), List.copyOf(v.clickedPois), v.clicks));
    }
}
