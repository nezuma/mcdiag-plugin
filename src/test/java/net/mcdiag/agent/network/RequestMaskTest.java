package net.mcdiag.agent.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RequestMaskTest {

    @Test
    void hidesSecretParametersButKeepsTheRest() {
        String q = RequestMask.maskQuery("server=survival&api_key=AbC123xyz&token=deadbeef&players=12&flag", Set.of());
        assertEquals("server=survival&api_key=***(9)&token=***(8)&players=12&flag", q);
        assertEquals("license=***(4)", RequestMask.maskQuery("license=abcd", Set.of("license")));
        String longValue = "x".repeat(500);
        assertTrue(RequestMask.maskQuery("data=" + longValue, Set.of()).endsWith("…(500)"));
    }

    @Test
    void hidesWebhookAndBotTokensInPath() {
        assertEquals("/api/webhooks/1234567890/***(68)",
                RequestMask.maskPath("discord.com", "/api/webhooks/1234567890/" + "aB3".repeat(22) + "zz"));
        assertEquals("/bot***(44)/sendMessage",
                RequestMask.maskPath("api.telegram.org", "/bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/sendMessage"));
        assertEquals("/v1/***(40)/info", RequestMask.maskPath("x.dev", "/v1/" + "Ab12".repeat(10) + "/info"));
        // Обычные длинные слова — не секрет.
        assertEquals("/very-long-but-readable-path-segment-for-docs", RequestMask.maskPath("x.dev", "/very-long-but-readable-path-segment-for-docs"));
    }

    @Test
    void groupsIdsIntoPattern() {
        assertEquals("/api/player/{id}/stats", RequestMask.pattern("/api/player/3f1c2a7e-1b2c-4d5e-8f90-1234567890ab/stats"));
        assertEquals("/v2/servers/{id}/vote/{id}", RequestMask.pattern("/v2/servers/48213/vote/0a1b2c3d4e"));
        assertEquals("/ping?x=1", RequestMask.example("/ping", "x=1"));
    }

    @Test
    void aggregatesRequestsPerPathWithLastExampleAndCallSite() {
        NetAggregator agg = new NetAggregator();
        NetAggregator.Request a = new NetAggregator.Request("/api/player/{id}", "/api/player/1?token=***(8)", "me.shop.Net.call(Net.java:9)", "plugins/Shop.jar", "Server thread");
        NetAggregator.Request b = new NetAggregator.Request("/api/player/{id}", "/api/player/2?token=***(8)", "me.shop.Net.call(Net.java:9)", "plugins/Shop.jar", "Server thread");
        agg.record(1, "Shop", "https", "api.shop.dev", 443, true, 0, 1, a);
        agg.record(5, "Shop", "https", "api.shop.dev", 443, false, 0, 2, b);
        List<String> out = agg.drainRequests();
        assertEquals(1, out.size());
        String json = out.get(0);
        assertTrue(json.contains("\"pt\":\"/api/player/{id}\",\"ex\":\"/api/player/2?token=***(8)\",\"c\":2,\"main\":1,\"f\":1,\"l\":5"), json);
        assertTrue(json.contains("\"cs\":\"me.shop.Net.call(Net.java:9)\",\"file\":\"plugins/Shop.jar\",\"th\":\"Server thread\""), json);
        assertEquals(1, agg.drain().size());
    }

    @Test
    void capsPathsPerHost() {
        NetAggregator agg = new NetAggregator();
        for (int i = 0; i < NetAggregator.MAX_PATHS_PER_HOST + 10; i++) {
            agg.record(i, "Shop", "https", "a.dev", 443, false, 0, 1,
                    new NetAggregator.Request("/p" + (char) ('a' + i % 26) + (char) ('a' + i / 26), "/x", null, null, "t"));
        }
        assertEquals(NetAggregator.MAX_PATHS_PER_HOST, agg.drainRequests().size());
        assertFalse(agg.drain().isEmpty());
    }
}
