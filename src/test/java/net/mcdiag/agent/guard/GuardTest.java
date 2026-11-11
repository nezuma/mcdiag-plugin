package net.mcdiag.agent.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardTest {

    @TempDir
    Path dir;

    private static byte[] classBytes(Class<?> type) throws Exception {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return in.readAllBytes();
        }
    }

    private Path jar(String name, Map<String, byte[]> entries) throws Exception {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(e.getKey()));
                jar.write(e.getValue());
                jar.closeEntry();
            }
        }
        return file;
    }

    @Test
    void readsCallsAndStringsFromClassFile() throws Exception {
        ClassInfo info = ClassInfo.parse(classBytes(EvilSample.class));
        assertEquals("net/mcdiag/agent/guard/EvilSample", info.name);
        assertTrue(info.methodRefs.contains("java/lang/Runtime.exec"), info.methodRefs.toString());
        assertTrue(info.methodRefs.contains("java/util/Base64$Decoder.decode"));
        assertTrue(info.strings.stream().anyMatch(s -> s.contains("discord.com/api/webhooks")));
    }

    @Test
    void flagsDangerousJarAndCombinations() throws Exception {
        Path file = jar("Evil.jar", Map.of(
                "plugin.yml", "name: FreeOp\nversion: '1.2'\nmain: x.Y\n".getBytes(StandardCharsets.UTF_8),
                "net/mcdiag/agent/guard/EvilSample.class", classBytes(EvilSample.class),
                "lib/miner.so", new byte[] {1, 2, 3}));
        JarScanner.Report r = JarScanner.scan(file, "plugins/Evil.jar");
        assertEquals("FreeOp", r.name);
        assertEquals("1.2", r.version);
        assertEquals(64, r.sha256.length());
        assertEquals("critical", r.risk());
        for (String k : List.of("exec", "define_class", "exfil_host", "raw_ip", "exit", "native_lib", "hidden_code", "exec_network")) {
            assertTrue(r.findings.containsKey(k), k + " in " + r.findings.keySet());
        }
        String json = r.toJson(1);
        assertTrue(json.startsWith("{\"t\":\"scan\",\"ts\":1,\"file\":\"plugins/Evil.jar\",\"name\":\"FreeOp\""), json);
        assertFalse(json.contains("AbCdEfGhIjKlMnOpQrStUvWxYz"), "webhook token must be masked: " + json);
    }

    @Test
    void cleanJarIsInfoAndSharedRootClassLooksLikeInfection() throws Exception {
        byte[] injected = classBytes(GuardTest.class);
        JarScanner.Report a = JarScanner.scan(jar("A.jar", Map.of("plugin.yml", "name: A\n".getBytes(), "Inj.class", injected)), "plugins/A.jar");
        JarScanner.Report b = JarScanner.scan(jar("B.jar", Map.of("plugin.yml", "name: B\n".getBytes(), "Inj.class", injected)), "plugins/B.jar");
        JarScanner.Report c = JarScanner.scan(jar("C.jar", Map.of("plugin.yml", "name: C\n".getBytes())), "plugins/C.jar");
        assertEquals("info", c.risk());
        List<String[]> shared = JarScanner.sharedRootClasses(List.of(a, b, c));
        assertEquals(1, shared.size());
        assertEquals("Inj.class", shared.get(0)[0]);
        assertEquals("plugins/A.jar, plugins/B.jar", shared.get(0)[1]);
    }

    private static ConfigAudit.Input input(boolean online, boolean bungee, String ip, boolean rcon, String pass, List<String> plugins, List<String> jvm) {
        return new ConfigAudit.Input(online, bungee, false, 0, ip, rcon, pass, false, false, 2, plugins, List.of(), jvm, "mc", "Paper");
    }

    private static String status(List<ConfigAudit.Check> checks, String id) {
        return checks.stream().filter(c -> c.id().equals(id)).findFirst().map(c -> c.status() + "/" + c.severity()).orElse("-");
    }

    @Test
    void auditsTypicalMisconfigurations() {
        List<ConfigAudit.Check> bad = ConfigAudit.run(input(false, false, "", true, "123456", List.of("Essentials"),
                List.of("-agentlib:jdwp=transport=dt_socket,server=y,address=*:5005")));
        assertEquals("risk/critical", status(bad, "offline_mode"));
        assertEquals("risk/high", status(bad, "rcon"));
        assertEquals("risk/critical", status(bad, "jvm_debug"));

        List<ConfigAudit.Check> proxied = ConfigAudit.run(input(false, true, "", false, "", List.of(), List.of()));
        assertEquals("ok/info", status(proxied, "offline_mode"));
        assertEquals("risk/high", status(proxied, "proxy_backend_open"));
        assertEquals("warn/medium", status(proxied, "proxy_forwarding"));

        List<ConfigAudit.Check> auth = ConfigAudit.run(input(false, false, "", true, "Very-Long-Rcon-Pass-42", List.of("AuthMe"), List.of()));
        assertEquals("warn/medium", status(auth, "offline_mode"));
        assertEquals("warn/low", status(auth, "rcon"));
        assertFalse(ConfigAudit.toJson(1, input(false, false, "", true, "Very-Long-Rcon-Pass-42", List.of(), List.of()), auth)
                .contains("Very-Long"), "password must not leak");
    }

    @Test
    void masksSecretsInCommandLines() {
        assertEquals("mysqldump --password=*** -u root db",
                GuardModule.maskCommand("mysqldump --password=hunter2 -u root db"));
        assertEquals("curl https://user:***@host/x", GuardModule.maskCommand("curl https://user:pw@host/x"));
    }

    @Test
    void masksSecretsInFindingExamples() {
        assertEquals("http://203.0.113.5:8080/ping?token=***&x=1",
                JarScanner.maskSecrets("http://203.0.113.5:8080/ping?token=SECRET&x=1"));
        assertEquals("https://discord.com/api/webhooks/1234567890/***",
                JarScanner.maskSecrets("https://discord.com/api/webhooks/1234567890/AbCdEfGhIjKlMnOp"));
        assertEquals("ftp://admin:***@10.0.0.1/", JarScanner.maskSecrets("ftp://admin:hunter2@10.0.0.1/"));
    }
}
