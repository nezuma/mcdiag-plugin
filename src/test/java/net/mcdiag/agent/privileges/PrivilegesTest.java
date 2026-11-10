package net.mcdiag.agent.privileges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrivilegesTest {

    private final List<String> out = new ArrayList<>();
    private final PrivilegeAudit audit = new PrivilegeAudit(out::add, Set.of("myplugin.admin"));
    private final UUID steve = UUID.randomUUID();

    private Map<UUID, String> ops(UUID... ids) {
        Map<UUID, String> m = new LinkedHashMap<>();
        for (UUID id : ids) {
            m.put(id, id.equals(steve) ? "Steve" : "Alex");
        }
        return m;
    }

    @Test
    void parsesAdminCommands() {
        assertEquals("op", AdminCommands.label("/minecraft:OP Steve"));
        assertEquals("Steve", AdminCommands.opTarget("op Steve"));
        assertNull(AdminCommands.opTarget("op @a"));
        assertTrue(AdminCommands.dangerousCommand("/lp user Steve permission set * true", Set.of()));
        assertFalse(AdminCommands.dangerousCommand("/lp user Steve permission set * false", Set.of()));
        assertTrue(AdminCommands.dangerousCommand("/lp user Steve parent add Admin", Set.of()));
        assertFalse(AdminCommands.dangerousCommand("/lp user Steve parent add vip", Set.of()));
        assertTrue(AdminCommands.dangerousPermission("essentials.*", Set.of()));
        assertTrue(AdminCommands.dangerousPermission("group.owner", Set.of()));
        assertTrue(AdminCommands.dangerousPermission("myplugin.admin", Set.of("myplugin.admin")));
        assertFalse(AdminCommands.dangerousPermission("essentials.home", Set.of()));
        assertTrue(AdminCommands.dangerousLuckPermsAction("permission set luckperms.* true", Set.of()));
    }

    @Test
    void opWithoutCommandIsCritical() {
        audit.ops(1_000, ops());
        assertTrue(out.get(0).contains("\"k\":\"ops\""));
        audit.ops(11_000, ops(steve));
        String grant = out.stream().filter(s -> s.contains("op_grant")).findFirst().orElseThrow();
        assertTrue(grant.contains("\"sev\":\"critical\""), grant);
        assertTrue(grant.contains("\"tg\":\"Steve\",\"as\":\"none\""), grant);
    }

    @Test
    void opFromConsoleCommandIsLowAndRevokeIsInfo() {
        audit.ops(1_000, ops());
        audit.command(5_000, PrivilegeAudit.Actor.CONSOLE, null, "op Steve", "op Steve", null);
        audit.ops(11_000, ops(steve));
        String grant = out.stream().filter(s -> s.contains("op_grant")).findFirst().orElseThrow();
        assertTrue(grant.contains("\"sev\":\"low\""), grant);
        assertTrue(grant.contains("\"as\":\"console\""), grant);
        String cmd = out.stream().filter(s -> s.contains("\"k\":\"cmd\"")).findFirst().orElseThrow();
        assertTrue(cmd.contains("\"sev\":\"medium\""), cmd);
        audit.ops(21_000, ops());
        assertTrue(out.stream().anyMatch(s -> s.contains("op_revoke") && s.contains("\"sev\":\"info\"")));
    }

    @Test
    void staleCommandDoesNotExplainGrant() {
        audit.ops(1_000, ops());
        audit.command(1_000, PrivilegeAudit.Actor.PLAYER, "Admin", "/op Steve", "/op Steve", null);
        audit.ops(1_000 + PrivilegeAudit.COMMAND_WINDOW_MS + 5_000, ops(steve));
        assertTrue(out.stream().anyMatch(s -> s.contains("op_grant") && s.contains("critical")));
    }

    @Test
    void luckPermsChangeWithoutLogEntryIsApiGrant() {
        audit.luckPermsNode(1_000, "Steve", "user", "*", true, false);
        audit.luckPermsNode(1_000, "Alex", "user", "group.admin", true, false);
        audit.luckPermsLog(2_000, "Owner", "Alex", "user", "parent add admin");
        audit.luckPermsNode(1_000, "Bob", "user", "essentials.home", true, false);
        audit.sweep(1_000 + PrivilegeAudit.LP_LOG_WINDOW_MS + 1);
        List<String> api = out.stream().filter(s -> s.contains("lp_api")).toList();
        assertEquals(1, api.size(), out.toString());
        assertTrue(api.get(0).contains("\"tg\":\"Steve\""));
        assertTrue(api.get(0).contains("\"sev\":\"high\""));
        assertTrue(out.stream().anyMatch(s -> s.contains("\"k\":\"lp\"") && s.contains("\"sev\":\"medium\"")));
    }

    @Test
    void attachmentIsReportedOncePerPluginAndPermission() {
        audit.attachment(1, steve, "Steve", "*", "FreeOp");
        audit.attachment(2, steve, "Steve", "*", "FreeOp");
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("\"k\":\"attachment\",\"sev\":\"critical\",\"tg\":\"Steve\",\"perm\":\"*\",\"p\":\"FreeOp\""), out.get(0));
    }
}
