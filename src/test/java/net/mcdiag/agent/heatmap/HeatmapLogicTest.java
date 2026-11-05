package net.mcdiag.agent.heatmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HeatmapLogicTest {

    private static final UUID A = UUID.randomUUID();
    private static final List<Poi> POIS = List.of(new Poi("kit", 10, 64, 10), new Poi("portal", 40, 64, 40));

    @Test
    void zoneNormalizesCornersAndChecksBounds() {
        HeatZone zone = HeatZone.of("world", 10, 70, -5, -10, 60, 5);
        assertEquals(-10, zone.minX());
        assertEquals(21, zone.width());
        assertTrue(zone.contains(-10, 60, -5));
        assertTrue(zone.contains(10, 70, 5));
        assertFalse(zone.contains(11, 65, 0));
        assertFalse(zone.contains(0, 71, 0));
        assertFalse(HeatZone.of("w", 0, 0, 0, 511, 10, 511).tooBig());
        assertTrue(HeatZone.of("w", 0, 0, 0, 512, 10, 0).tooBig());
    }

    @Test
    void poiSurvivesConfigAndRejectsBadNames() {
        Poi poi = new Poi("start_chest", 1, 2, -3);
        assertEquals(poi, Poi.decode(poi.encode()));
        assertNull(Poi.decode("bad name:1:2:3"));
        assertNull(Poi.decode("x:1:2"));
        assertNull(Poi.decode("x:a:2:3"));
        assertFalse(Poi.validName("a b"));
        assertTrue(poi.near(5, -3));
        assertFalse(poi.near(8, -3));
    }

    @Test
    void newcomerWhoWalksIntoTheGame() {
        VisitTracker t = new VisitTracker(POIS, 10);
        t.onJoin(A, "h", true, true, 0, 0, 1_000);
        t.beginSample();
        t.sampleInside(A, "h", 10, 12, 6_000);
        t.onClick(A, t.poiAt(10, 10));
        t.endSample(6_000);
        assertEquals(1, t.active());
        t.beginSample();
        t.sampleInside(A, "h", 30, 30, 11_000);
        t.endSample(11_000);
        t.beginSample();
        t.endSample(16_000);

        VisitTracker.Finished v = t.drainFinished().get(0);
        assertEquals("walk", v.end());
        assertTrue(v.first());
        assertTrue(v.fromJoin());
        assertEquals(15, v.durSec());
        assertEquals(30, v.x());
        assertEquals(List.of("kit"), v.pois());
        assertEquals(List.of("kit"), v.clickedPois());
        assertEquals(1, v.clicks());
        assertEquals(0, t.active());
    }

    @Test
    void quitOnSpawnIsAnOutflowPoint() {
        VisitTracker t = new VisitTracker(POIS, 10);
        t.onJoin(A, "h", true, true, 5, 5, 0);
        t.onQuit(A, 7, 8, 12_000);
        VisitTracker.Finished v = t.drainFinished().get(0);
        assertEquals("quit", v.end());
        assertEquals(7, v.x());
        assertEquals(8, v.z());
        assertEquals(12, v.durSec());
    }

    @Test
    void teleportAndPortalKeepTheCause() {
        VisitTracker t = new VisitTracker(POIS, 10);
        t.onJoin(A, "h", false, true, 5, 5, 0);
        t.onLeaveByTeleport(A, false, "COMMAND", 6, 6, 3_000);
        t.onJoin(A, "h", false, true, 5, 5, 4_000);
        t.onLeaveByTeleport(A, true, "NETHER_PORTAL", 9, 9, 5_000);
        List<VisitTracker.Finished> done = t.drainFinished();
        assertEquals("tp", done.get(0).end());
        assertEquals("COMMAND", done.get(0).cause());
        assertEquals("portal", done.get(1).end());
    }

    @Test
    void returningToSpawnIsAVisitButNotFromJoinAndNewcomersAreRemembered() {
        VisitTracker t = new VisitTracker(POIS, 10);
        t.onJoin(A, "h", true, false, 500, 500, 0);
        assertTrue(t.isNewcomer(A));
        assertEquals(0, t.active());
        t.beginSample();
        t.sampleInside(A, "h", 0, 0, 1_000);
        t.endSample(1_000);
        t.onDeath(A, 1, 1, 2_000);
        VisitTracker.Finished v = t.drainFinished().get(0);
        assertFalse(v.fromJoin());
        assertTrue(v.first());
        assertEquals("death", v.end());
        t.onQuit(A, 0, 0, 3_000);
        assertFalse(t.isNewcomer(A));
    }

    @Test
    void visitsAreBounded() {
        VisitTracker t = new VisitTracker(POIS, 2);
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            t.onJoin(id, "h", false, true, 0, 0, 0);
            t.onQuit(id, 0, 0, 1);
        }
        assertEquals(2, t.drainFinished().size());
    }

    @Test
    void aggregatorIsBoundedAndWritesCompactJson() {
        HeatAggregator a = new HeatAggregator();
        a.addPosition(10, 20, true);
        a.addPosition(11, 21, false);
        a.addPosition(-3, -1, false);
        for (int i = 0; i < HeatAggregator.MAX_CELLS + 100; i++) {
            a.addPosition(i * 4, 0, false);
        }
        a.addClick(1, 2, 3, "chest", "CHEST");
        a.addClick(1, 2, 3, "chest", "CHEST");
        a.addDeath(new HeatAggregator.Death(5, "h", "Notch", "player", "Dark", "DIAMOND_SWORD", "ENTITY_ATTACK", "world", 1, 2, 3, false));
        HeatAggregator.Snapshot s = a.drain(List.of(new VisitTracker.Finished(1, "h", true, true, 3, "tp", "COMMAND", 1, 2, List.of("kit"), List.of(), 0)));
        assertTrue(a.isEmpty());
        assertEquals(HeatAggregator.MAX_CELLS, s.cells().size());
        String json = HeatAggregator.toJson(s, 7, "world:0", HeatAggregator.defJson(HeatZone.of("world", 0, 0, 0, 9, 9, 9), POIS));
        assertTrue(json.contains("10,20,2,1"), "две позиции в одной клетке 2×2");
        assertTrue(json.contains("-4,-2,1,0"), "отрицательные координаты округляются вниз");
        assertTrue(json.contains("\"c\":2}"));
        assertTrue(json.contains("\"k\":\"Dark\""));
        assertTrue(json.contains("\"def\":{\"world\":\"world\""));
    }

    @Test
    void containersAreRecognized() {
        assertTrue(HeatmapModule.isContainer(org.bukkit.Material.CHEST));
        assertTrue(HeatmapModule.isContainer(org.bukkit.Material.ENDER_CHEST));
        assertTrue(HeatmapModule.isContainer(org.bukkit.Material.BARREL));
        assertFalse(HeatmapModule.isContainer(org.bukkit.Material.STONE_BUTTON));
    }

    @Test
    void terrainColorsAndCompression() {
        assertEquals(0x7fb238, TerrainPalette.colorOf("GRASS_BLOCK"));
        assertEquals(0x3f76e4, TerrainPalette.colorOf("WATER"));
        assertEquals(0x808080, TerrainPalette.colorOf("UNKNOWN_THING"));
        int color = 0x646464;
        assertEquals(color, TerrainPalette.shade(color, 70, 69));
        assertTrue((TerrainPalette.shade(color, 69, 70) & 0xff) < (TerrainPalette.shade(color, 70, 70) & 0xff));
        String body = TerrainPalette.encode("z", 2, 2, new int[] {5, 5, 5, 9});
        assertEquals("{\"zone\":\"z\",\"w\":2,\"h\":2,\"palette\":[5,9],\"runs\":[0,3,1,1]}", body);
    }

    /** Настоящие строки агента: scratch-скрипт проверяет их схемой панели. */
    @Test
    void writesASampleForThePanelSchema() throws Exception {
        long now = System.currentTimeMillis();
        HeatAggregator a = new HeatAggregator();
        a.addPosition(10, 20, true);
        a.addClick(1, 64, 3, "chest", "CHEST");
        a.addClick(2, 64, 3, "npc", "VILLAGER");
        a.addDeath(new HeatAggregator.Death(now, "a".repeat(64), "Notch", "player", "Dark", "DIAMOND_SWORD", "ENTITY_ATTACK", "world", 1, 2, 3, false));
        a.addDeath(new HeatAggregator.Death(now, "b".repeat(64), "Steve", "env", null, null, "FALL", "world", 1, 2, 3, true));
        HeatAggregator.Snapshot s = a.drain(List.of(new VisitTracker.Finished(now, "a".repeat(64), true, true, 3, "tp", "COMMAND", 1, 2, List.of("kit"), List.of("kit"), 1)));
        HeatZone zone = HeatZone.of("world", 0, 60, 0, 99, 90, 99);
        String heat = HeatAggregator.toJson(s, now, zone.id(), HeatAggregator.defJson(zone, POIS));
        String batch = "{\"batchId\":\"41a28438-1451-46a3-a829-7d8e98cb1b39\",\"sentAt\":" + now
                + ",\"plugin\":{\"version\":\"0.6.0\",\"core\":\"paper\",\"mcVersion\":\"1.21.4\",\"batchIntervalSec\":10},\"events\":[],\"metrics\":[],\"heat\":[" + heat + "]}";
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/sample-heat-batch.json"), batch);
        Files.writeString(Path.of("build/sample-terrain.json"), TerrainPalette.encode(zone.id(), 2, 2, new int[] {5, 5, 5, 9}));
    }
}
