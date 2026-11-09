package net.mcdiag.agent.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PanelStateTest {

    @Test
    void startsInOldFormatUntilPanelAnnouncesFeatures() {
        PanelState panel = new PanelState();
        assertFalse(panel.richSpikes());
        assertFalse(panel.insight());

        // Панель до 0.4.0: ни caps, ни настроек.
        panel.update("{\"success\":true,\"data\":{\"duplicate\":false,\"events\":0,\"profiles\":0}}");
        assertFalse(panel.richSpikes());
        assertFalse(panel.commands());

        panel.update("{\"success\":true,\"data\":{\"caps\":[\"spikes\",\"chatlog\"],\"chatlog\":{\"chat\":false,\"cmd\":true}}}");
        assertTrue(panel.richSpikes());
        assertFalse(panel.insight());
        assertFalse(panel.memory());
        assertTrue(panel.commands());

        // Панель 0.5.0: чата больше нет, разбор спайков и карта памяти есть.
        panel.update("{\"data\":{\"caps\":[\"spikes\",\"chatlog\",\"insight\",\"memory\"],\"chatlog\":{\"cmd\":true}}}");
        assertTrue(panel.insight());
        assertTrue(panel.memory());
        assertTrue(panel.commands());

        // Владелец выключил журнал.
        panel.update("{\"data\":{\"caps\":[\"spikes\",\"chatlog\"],\"chatlog\":{\"chat\":false,\"cmd\":false}}}");
        assertFalse(panel.commands());
        assertTrue(panel.richSpikes());

        // Панель откатили на старую версию.
        panel.update("{\"data\":{\"duplicate\":false}}");
        assertFalse(panel.richSpikes());
    }

    @Test
    void rejectedBatchFallsBackToOldFormat() {
        PanelState panel = new PanelState();
        panel.update("{\"data\":{\"caps\":[\"spikes\",\"chatlog\",\"insight\"],\"chatlog\":{\"chat\":true,\"cmd\":true}}}");
        assertTrue(panel.insight());
        panel.reset();
        assertFalse(panel.insight());
        assertFalse(panel.richSpikes());
        panel.update(null);
        assertFalse(panel.commands());
    }

    @Test
    void freeModeFollowsThePanel() {
        PanelState panel = new PanelState();
        panel.update("{\"data\":{\"tier\":\"full\",\"caps\":[\"spikes\",\"heatmap\"]}}");
        assertFalse(panel.free());
        assertTrue(panel.heatmap());
        panel.update("{\"data\":{\"caps\":[],\"chatlog\":{\"chat\":false,\"cmd\":false},\"tier\":\"free\"}}");
        assertTrue(panel.free());
        assertFalse(panel.heatmap());
        panel.update("{\"data\":{\"tier\":\"frozen\"}}");
        assertTrue(panel.free());
        // Панель без поля tier — старая, агент работает как раньше.
        panel.update("{\"data\":{\"duplicate\":false}}");
        assertFalse(panel.free());
    }

    @Test
    void newModulesAndChatFollowThePanel() {
        PanelState panel = new PanelState();
        panel.update("{\"data\":{\"tier\":\"full\",\"caps\":[\"spikes\",\"chatlog\"],\"chatlog\":{\"chat\":true,\"cmd\":false}}}");
        assertTrue(panel.chat());
        assertFalse(panel.commands());
        assertFalse(panel.nicks());
        assertFalse(panel.economy());

        panel.update("{\"data\":{\"tier\":\"full\",\"caps\":[\"chatlog\",\"nicks\",\"joins\",\"chunkgen\",\"economy\"],\"chatlog\":{\"chat\":false,\"cmd\":true}}}");
        assertFalse(panel.chat());
        assertTrue(panel.commands());
        assertTrue(panel.nicks());
        assertTrue(panel.joins());
        assertTrue(panel.chunkgen());
        assertTrue(panel.economy());

        panel.reset();
        assertFalse(panel.economy());
        assertFalse(panel.joins());
    }
}
