package net.mcdiag.agent.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

class EconomyModuleTest {

    private static OfflinePlayer player(String name, UUID id) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[] {OfflinePlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static EconomySource source(String id, Map<String, Double> balances) {
        return new EconomySource() {
            public String id() {
                return id;
            }

            public String name() {
                return "Test \"Eco\"";
            }

            public String unit() {
                return "coins";
            }

            public Double balance(OfflinePlayer p) {
                return balances.get(p.getName());
            }
        };
    }

    @Test
    void passSpreadsPlayersOverTicksAndSplitsLargeLists() {
        List<OfflinePlayer> players = new ArrayList<>();
        java.util.HashMap<String, Double> money = new java.util.HashMap<>();
        for (int i = 0; i < 130; i++) {
            players.add(player("p" + i, new UUID(0, i)));
            money.put("p" + i, (double) i);
        }
        money.put("p5", Double.NaN);
        EconomyModule.Pass pass = new EconomyModule.Pass(List.of(source("vault", money)), players, 300_000, u -> "h" + u.getLeastSignificantBits());
        List<String> out = new ArrayList<>();
        assertFalse(pass.step(50, out::add));
        assertFalse(pass.step(50, out::add));
        assertEquals(0, out.size(), "100 players fill exactly one part only at 101st");
        assertTrue(pass.step(50, out::add));
        assertEquals(2, out.size());
        assertTrue(out.get(0).startsWith("{\"ts\":300000,\"c\":\"vault\",\"name\":\"Test \\\"Eco\\\"\",\"unit\":\"coins\",\"b\":[{\"h\":\"h0\",\"n\":\"p0\",\"v\":0}"));
        assertFalse(out.get(0).contains("\"p5\""), "NaN balance is skipped");
        assertTrue(out.get(1).endsWith("{\"h\":\"h129\",\"n\":\"p129\",\"v\":129.00}]}"));
    }

    @Test
    void moneyKeepsSignAndClamps() {
        assertEquals("-12.50", EconomyModule.money(new StringBuilder(), -12.5).toString());
        assertEquals("1000000000000000.00", EconomyModule.money(new StringBuilder(), 1e30).toString());
        assertEquals("0", EconomyModule.money(new StringBuilder(), 0).toString());
    }
}
