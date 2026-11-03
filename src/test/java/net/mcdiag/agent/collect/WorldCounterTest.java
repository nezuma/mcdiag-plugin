package net.mcdiag.agent.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldCounterTest {

    private static final class FakeWorld implements WorldCounter.Probe {
        final String name;
        int entities;
        int chunks;
        int tiles;
        int entityReads;
        int chunkReads;

        FakeWorld(String name, int entities, int chunks, int tiles) {
            this.name = name;
            this.entities = entities;
            this.chunks = chunks;
            this.tiles = tiles;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int entities() {
            entityReads++;
            return entities;
        }

        @Override
        public int chunks() {
            chunkReads++;
            return chunks;
        }

        @Override
        public int tiles() {
            return tiles;
        }
    }

    @Test
    void firstRunCountsEveryWorldSoTheTotalIsNotLow() {
        WorldCounter counter = new WorldCounter();
        FakeWorld a = new FakeWorld("a", 10, 100, 5);
        FakeWorld b = new FakeWorld("b", 20, 200, 6);
        counter.update(List.of(a, b));
        assertEquals(30, counter.entities());
        assertEquals(300, counter.chunks());
        assertEquals(11, counter.tiles());
    }

    @Test
    void laterRunsVisitOneWorldAtATime() {
        WorldCounter counter = new WorldCounter();
        FakeWorld a = new FakeWorld("a", 10, 100, 5);
        FakeWorld b = new FakeWorld("b", 20, 200, 6);
        counter.update(List.of(a, b));
        a.entityReads = 0;
        b.entityReads = 0;
        for (int i = 0; i < 10; i++) {
            int before = a.entityReads + b.entityReads;
            counter.update(List.of(a, b));
            assertEquals(before + 1, a.entityReads + b.entityReads);
        }
        assertEquals(5, a.entityReads);
        assertEquals(5, b.entityReads);
    }

    @Test
    void chunksAreReadLessOftenThanEntities() {
        WorldCounter counter = new WorldCounter();
        FakeWorld a = new FakeWorld("a", 10, 100, 5);
        for (int i = 0; i < WorldCounter.SLOW_EVERY * 4; i++) {
            counter.update(List.of(a));
        }
        assertEquals(WorldCounter.SLOW_EVERY * 4, a.entityReads);
        assertEquals(4, a.chunkReads);
        a.entities = 77;
        counter.update(List.of(a));
        assertEquals(77, counter.entities());
    }

    @Test
    void newAndUnloadedWorldsChangeTheTotalImmediately() {
        WorldCounter counter = new WorldCounter();
        FakeWorld a = new FakeWorld("a", 10, 100, 5);
        FakeWorld b = new FakeWorld("b", 20, 200, 6);
        counter.update(List.of(a));
        counter.update(List.of(a, b));
        assertEquals(30, counter.entities());
        counter.update(List.of(b));
        assertEquals(20, counter.entities());
        counter.update(List.of());
        assertEquals(0, counter.entities());
    }
}
