package net.mcdiag.agent.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.mcdiag.agent.json.Json;

/**
 * Проверки настроек сервера, которые чаще всего приводят к взлому: вход под чужим ником без
 * авторизации, открытый бэкенд за прокси, RCON со слабым паролем, удалённая отладка JVM.
 * Чистая логика по уже прочитанным значениям: чтение файлов и Bukkit — в {@link GuardModule}.
 */
public final class ConfigAudit {

    /** Плагины авторизации: с ними offline-mode не означает «любой войдёт под любым ником». */
    public static final Set<String> AUTH_PLUGINS = Set.of(
            "authme", "authmereloaded", "nlogin", "librelogin", "jpremium", "fastlogin", "openlogin",
            "loginsecurity", "authmevelocity", "xauth", "premiumvanish-auth", "dynamicauth", "simpleauth");
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "", "password", "123456", "12345678", "admin", "minecraft", "rcon", "qwerty", "changeme", "server");

    /** Что известно о сервере. Секреты (пароль RCON, секрет Velocity) сюда приходят, но наружу — только их длина. */
    public record Input(
            boolean onlineMode,
            boolean bungeecord,
            boolean velocity,
            int velocitySecretLength,
            String serverIp,
            boolean rcon,
            String rconPassword,
            boolean query,
            boolean whitelist,
            int ops,
            List<String> plugins,
            List<String> defaultOpPermissions,
            List<String> jvmArgs,
            String osUser,
            String core) {
    }

    public record Check(String id, String status, String severity, String details) {
    }

    public static List<Check> run(Input in) {
        List<Check> out = new ArrayList<>();
        boolean proxy = in.bungeecord() || in.velocity();
        boolean auth = in.plugins().stream().anyMatch(p -> AUTH_PLUGINS.contains(p.toLowerCase(Locale.ROOT)));

        if (in.onlineMode()) {
            out.add(new Check("offline_mode", "ok", "info", null));
        } else if (proxy) {
            out.add(new Check("offline_mode", "ok", "info", "proxy"));
        } else if (auth) {
            out.add(new Check("offline_mode", "warn", "medium", "auth plugin"));
        } else {
            out.add(new Check("offline_mode", "risk", "critical", null));
        }

        if (proxy) {
            boolean open = in.serverIp() == null || in.serverIp().isBlank() || in.serverIp().equals("0.0.0.0");
            out.add(open
                    ? new Check("proxy_backend_open", "risk", "high", in.velocity() ? "velocity" : "bungeecord")
                    : new Check("proxy_backend_open", "ok", "info", null));
            if (in.bungeecord() && !in.velocity()) {
                out.add(new Check("proxy_forwarding", "warn", "medium", "bungeecord"));
            } else if (in.velocity()) {
                out.add(in.velocitySecretLength() < 12
                        ? new Check("proxy_forwarding", "risk", "high", "secret " + in.velocitySecretLength())
                        : new Check("proxy_forwarding", "ok", "info", "velocity"));
            }
        }

        if (in.rcon()) {
            String pass = in.rconPassword() == null ? "" : in.rconPassword();
            boolean weak = pass.length() < 12 || WEAK_PASSWORDS.contains(pass.toLowerCase(Locale.ROOT)) || pass.chars().allMatch(Character::isDigit);
            out.add(weak
                    ? new Check("rcon", "risk", "high", "password " + pass.length())
                    : new Check("rcon", "warn", "low", null));
        } else {
            out.add(new Check("rcon", "ok", "info", null));
        }
        out.add(in.query() ? new Check("query", "warn", "low", null) : new Check("query", "ok", "info", null));
        out.add(in.whitelist() ? new Check("whitelist", "ok", "info", null) : new Check("whitelist", "warn", "info", null));
        out.add(in.ops() > 5
                ? new Check("ops", "warn", "medium", String.valueOf(in.ops()))
                : new Check("ops", "ok", "info", String.valueOf(in.ops())));
        out.add(in.defaultOpPermissions().isEmpty()
                ? new Check("default_permissions", "ok", "info", null)
                : new Check("default_permissions", "risk", "high", String.join(", ", in.defaultOpPermissions())));

        String debug = in.jvmArgs().stream().filter(a -> a.startsWith("-agentlib:jdwp") || a.startsWith("-Xrunjdwp")).findFirst().orElse(null);
        if (debug != null) {
            boolean remote = !debug.contains("address=127.0.0.1") && !debug.contains("address=localhost");
            out.add(new Check("jvm_debug", remote ? "risk" : "warn", remote ? "critical" : "medium", null));
        } else {
            out.add(new Check("jvm_debug", "ok", "info", null));
        }
        boolean jmx = in.jvmArgs().stream().anyMatch(a -> a.startsWith("-Dcom.sun.management.jmxremote.port"));
        boolean jmxNoAuth = in.jvmArgs().stream().anyMatch(a -> a.equals("-Dcom.sun.management.jmxremote.authenticate=false"));
        if (jmx) {
            out.add(new Check("jmx", jmxNoAuth ? "risk" : "warn", jmxNoAuth ? "high" : "low", null));
        }
        out.add("root".equals(in.osUser())
                ? new Check("root_user", "warn", "medium", null)
                : new Check("root_user", "ok", "info", null));
        return out;
    }

    public static String toJson(long ts, Input in, List<Check> checks) {
        StringBuilder sb = new StringBuilder(1024).append("{\"t\":\"config\",\"ts\":").append(ts);
        if (in.core() != null) {
            Json.str(sb.append(",\"core\":"), in.core(), 120);
        }
        Json.str(sb.append(",\"java\":"), System.getProperty("java.version", "?"), 32);
        sb.append(",\"checks\":[");
        for (int i = 0; i < checks.size(); i++) {
            Check c = checks.get(i);
            sb.append(i > 0 ? "," : "").append("{\"id\":\"").append(c.id()).append("\",\"st\":\"").append(c.status())
                    .append("\",\"sev\":\"").append(c.severity()).append('"');
            if (c.details() != null) {
                Json.str(sb.append(",\"d\":"), c.details(), 200);
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }
}
