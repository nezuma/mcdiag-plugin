package net.mcdiag.agent.config;

import java.net.URI;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Неизменяемый снимок config.yml. Новый снимок создаётся на старте и при /diag reload;
 * потоки читают его через volatile-ссылку в плагине.
 *
 * @param problem причина, по которой отправка невозможна (null — всё в порядке); сам токен в
 *                текст не попадает
 */
public record AgentConfig(
        URI backendUrl,
        String serverToken,
        String uuidSalt,
        int batchIntervalSec,
        int maxBufferSize,
        int maxBatchEvents,
        int metricsIntervalSec,
        int maxErrorsPerMinute,
        boolean collectJoins,
        boolean collectQuits,
        boolean collectDeaths,
        boolean collectErrors,
        boolean collectMetrics,
        String problem) {

    /** Формат токена из openapi: {@code mcd_<serverId>_<uuid>}. */
    public static final Pattern TOKEN = Pattern.compile("^mcd_[0-9a-f]{24}_[0-9a-f-]{36}$");

    public static AgentConfig from(FileConfiguration c) {
        String token = c.getString("server_token", "").trim();
        String problem = null;

        URI url = null;
        String rawUrl = c.getString("backend_url", "").trim();
        try {
            url = URI.create(rawUrl);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null) {
                problem = "backend_url must be an https:// URL";
                url = null;
            }
        } catch (IllegalArgumentException e) {
            problem = "backend_url is not a valid URL";
        }
        if (problem == null && token.isEmpty()) {
            problem = "server_token is not set (console: diag token <value>)";
        } else if (problem == null && !TOKEN.matcher(token).matches()) {
            problem = "server_token has invalid format";
        }

        ConfigurationSection collect = c.getConfigurationSection("collect");
        return new AgentConfig(
                url,
                token,
                c.getString("uuid_salt", ""),
                clamp(c.getInt("batch_interval_sec", 10), 5, 3600),
                clamp(c.getInt("max_buffer_size", 500), 10, 5000),
                clamp(c.getInt("max_batch_events", 100), 1, 500),
                clamp(c.getInt("metrics_interval_sec", 5), 5, 60),
                clamp(c.getInt("max_errors_per_minute", 30), 0, 1000),
                flag(collect, "joins"),
                flag(collect, "quits"),
                flag(collect, "deaths"),
                flag(collect, "errors"),
                flag(collect, "metrics"),
                problem);
    }

    public boolean canSend() {
        return problem == null;
    }

    /** Токен для вывода: {@code mcd_****abcd}. */
    public String maskedToken() {
        if (serverToken.isEmpty()) {
            return "<not set>";
        }
        int tail = Math.min(4, serverToken.length() / 4);
        return "mcd_****" + serverToken.substring(serverToken.length() - tail);
    }

    /** Не даём токену утечь через случайный toString() в логе. */
    @Override
    public String toString() {
        return "AgentConfig[backendUrl=" + backendUrl + ", token=" + maskedToken() + "]";
    }

    private static boolean flag(ConfigurationSection section, String key) {
        return section == null || section.getBoolean(key, true);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
