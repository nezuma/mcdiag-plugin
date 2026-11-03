package net.mcdiag.agent.collect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сущности, чанки и блоки-сущности по всем мирам — маленькими порциями. На Paper эти числа
 * считаются обходом всех сущностей и всех чанков в главном потоке, поэтому за один запуск
 * обходим один мир, а чанки и блоки-сущности меняются медленнее и обновляются реже.
 * Новый мир обходится сразу, чтобы сумма не проседала. Вызывается только в главном потоке.
 */
final class WorldCounter {

    /** Чанки и блоки-сущности: раз в столько обходов мира */
    static final int SLOW_EVERY = 6;

    /** То, что нужно от мира: так счётчик проверяется без сервера. */
    interface Probe {
        String name();

        int entities();

        int chunks();

        int tiles();
    }

    private static final class Counts {
        int entities;
        int chunks;
        int tiles;
        int visits;
    }

    private final Map<String, Counts> counts = new HashMap<>();
    private int cursor;
    private int entities;
    private int chunks;
    private int tiles;

    void update(List<? extends Probe> worlds) {
        if (worlds.isEmpty()) {
            counts.clear();
            entities = chunks = tiles = 0;
            return;
        }
        boolean visited = false;
        for (Probe world : worlds) {
            if (!counts.containsKey(world.name())) {
                visit(world);
                visited = true;
            }
        }
        if (!visited) {
            cursor = (cursor + 1) % worlds.size();
            visit(worlds.get(cursor));
        }
        if (counts.size() != worlds.size()) {
            List<String> loaded = new ArrayList<>(worlds.size());
            for (Probe world : worlds) {
                loaded.add(world.name());
            }
            counts.keySet().retainAll(loaded);
        }
        int e = 0;
        int c = 0;
        int t = 0;
        for (Counts value : counts.values()) {
            e += value.entities;
            c += value.chunks;
            t += value.tiles;
        }
        entities = e;
        chunks = c;
        tiles = t;
    }

    private void visit(Probe world) {
        Counts value = counts.computeIfAbsent(world.name(), key -> new Counts());
        value.entities = world.entities();
        if (value.visits++ % SLOW_EVERY == 0) {
            value.chunks = world.chunks();
            value.tiles = world.tiles();
        }
    }

    int entities() {
        return entities;
    }

    int chunks() {
        return chunks;
    }

    int tiles() {
        return tiles;
    }
}
