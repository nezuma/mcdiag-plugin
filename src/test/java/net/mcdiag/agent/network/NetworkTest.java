package net.mcdiag.agent.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetworkTest {

    @Test
    void aggregatesPerPluginHostAndSkipsSocketDuplicateOfHttp() {
        NetAggregator agg = new NetAggregator();
        agg.record(1, "SomeShop", "https", "api.shop.dev", 443, true, 0, 7);
        agg.record(2, "SomeShop", "socket", "api.shop.dev", 443, true, 0, 7);
        agg.record(3, "SomeShop", "https", "api.shop.dev", 443, false, 0, 8);
        agg.record(4, "SomeShop", "socket", "db.local", 3306, false, 0, 9);
        List<String> out = agg.drain();
        assertEquals(2, out.size());
        String api = out.stream().filter(s -> s.contains("api.shop.dev")).findFirst().orElseThrow();
        assertTrue(api.contains("\"c\":2,\"main\":1"), api);
        assertTrue(api.contains("\"s\":[\"https\"]"), api);
        assertTrue(String.join("", out).contains("\"port\":3306,\"s\":[\"socket\"]"));
    }

    @Test
    void detectsWebhooksWithoutKeepingThePath() {
        assertEquals(NetAggregator.KIND_DISCORD_WEBHOOK, NetAggregator.kindsOf("discord.com", "/api/webhooks/123/secret"));
        assertEquals(NetAggregator.KIND_DISCORD_WEBHOOK, NetAggregator.kindsOf("ptb.discord.com", "/api/v10/webhooks/1/x"));
        assertEquals(NetAggregator.KIND_TELEGRAM_BOT, NetAggregator.kindsOf("api.telegram.org", "/bot123:abc/sendMessage"));
        assertEquals(0, NetAggregator.kindsOf("example.com", "/api/webhooks/1"));
        NetAggregator agg = new NetAggregator();
        agg.record(1, "Evil", "https", "discord.com", 443, false, NetAggregator.kindsOf("discord.com", "/api/webhooks/1/secret"), 1);
        String json = agg.drain().get(0);
        assertTrue(json.contains("\"k\":[\"discord_webhook\"]"), json);
        assertFalse(json.contains("secret"));
    }

    @Test
    void tapPassesThroughAndRestoresDefaultSelector() {
        ProxySelector before = ProxySelector.getDefault();
        NetTap tap = NetTap.install(-1);
        try {
            NetAggregator agg = new NetAggregator();
            tap.setSink(agg);
            tap.setOwners(cls -> cls.startsWith("net.mcdiag.agent.network.NetworkTest") ? "Tester" : null, t -> null);
            assertEquals(before.select(URI.create("https://example.com/x?token=1")),
                    ProxySelector.getDefault().select(URI.create("https://example.com/x?token=1")));
            String json = agg.drain().get(0);
            assertTrue(json.contains("\"h\":\"example.com\",\"port\":443"), json);
            assertFalse(json.contains("token"));
        } finally {
            tap.uninstall();
        }
        assertSame(before, ProxySelector.getDefault());
        assertEquals(443, NetTap.port(URI.create("wss://a.b/"), "wss"));
        assertEquals(25565, NetTap.port(URI.create("socket://a.b:25565"), "socket"));
    }
}
