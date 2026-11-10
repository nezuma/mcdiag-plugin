package net.mcdiag.testnet;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ProxySelector;
import java.net.URI;
import java.util.Set;
import net.mcdiag.agent.network.NetAggregator;
import net.mcdiag.agent.network.NetTap;
import org.junit.jupiter.api.Test;

/** Вне пакета агента: кадры теста должны считаться «чужим кодом», как код плагина. */
class CallSiteTest {

    @Test
    void recordsCallSiteFileAndMaskedPath() {
        NetTap tap = NetTap.install(-1);
        try {
            NetAggregator agg = new NetAggregator();
            tap.setSink(agg);
            tap.setPathCapture(true, Set.of());
            // Плагин не найден ни по загрузчику, ни по индексу: остаются место вызова и файл.
            tap.setOwners(cls -> null, t -> null);
            ProxySelector.getDefault().select(URI.create("https://Evil.Example/collect/42?token=abc&cfg=ops"));
            String json = String.join("", agg.drainRequests());
            assertTrue(json.contains("\"h\":\"evil.example\""), json);
            assertTrue(json.contains("\"pt\":\"/collect/{id}\",\"ex\":\"/collect/42?token=***(3)&cfg=ops\""), json);
            assertTrue(json.contains("\"cs\":\"net.mcdiag.testnet.CallSiteTest.recordsCallSiteFileAndMaskedPath(CallSiteTest.java:"), json);
            assertTrue(json.contains("\"file\":\"build/classes/java/test\"") || json.contains("\"file\":"), json);
            assertTrue(!json.contains("\"p\":"), json);

            tap.setOwners(cls -> cls.startsWith("net.mcdiag.testnet.") ? "Tester" : null, t -> null);
            ProxySelector.getDefault().select(URI.create("http://203.0.113.5:8080/ping"));
            assertTrue(String.join("", agg.drainRequests()).contains("\"p\":\"Tester\""));
        } finally {
            tap.uninstall();
        }
    }
}
