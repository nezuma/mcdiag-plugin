package net.mcdiag.agent.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.mcdiag.agent.json.Json;

/**
 * Обращения плагинов во внешнюю сеть: (плагин, хост, порт) → сколько раз, из главного ли потока,
 * какими протоколами. Путь и параметры запроса не хранятся никогда: в них бывают токены.
 */
public final class NetAggregator {

    public static final int MAX_ENTRIES = 256;
    /** Один HTTP-запрос виден дважды: как https://host и как socket://host:443 того же потока. */
    private static final long DUPLICATE_MS = 2_000;

    static final int KIND_DISCORD_WEBHOOK = 1;
    static final int KIND_TELEGRAM_BOT = 2;

    private static final String[] SCHEMES = {"http", "https", "ws", "wss", "socket", "other"};

    private final Map<String, Entry> window = new HashMap<>();
    private long dropped;
    private long lastThread = -1;
    private String lastTarget;
    private long lastAt;

    public synchronized void record(long ts, String plugin, String scheme, String host, int port, boolean main, int kinds, long threadId) {
        String target = host + ':' + port;
        boolean socket = scheme.equals("socket");
        if (socket && threadId == lastThread && target.equals(lastTarget) && ts - lastAt <= DUPLICATE_MS) {
            return;
        }
        if (!socket) {
            lastThread = threadId;
            lastTarget = target;
            lastAt = ts;
        }
        String key = (plugin == null ? "" : plugin) + '|' + target;
        Entry e = window.get(key);
        if (e == null) {
            if (window.size() >= MAX_ENTRIES) {
                dropped++;
                return;
            }
            e = new Entry(plugin, host, port, ts);
            window.put(key, e);
        }
        e.count++;
        if (main) {
            e.main++;
        }
        e.last = ts;
        e.schemes |= 1 << schemeIndex(scheme);
        e.kinds |= kinds;
    }

    /** Записи окна для раздела {@code net}; окно очищается. */
    public synchronized List<String> drain() {
        List<String> out = new ArrayList<>(window.size());
        for (Entry e : window.values()) {
            out.add(e.toJson());
        }
        window.clear();
        return out;
    }

    public synchronized int pending() {
        return window.size();
    }

    public synchronized long dropped() {
        return dropped;
    }

    /** Признаки по пути считаются на лету; сам путь дальше не идёт. */
    static int kindsOf(String host, String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        String h = host.toLowerCase(Locale.ROOT);
        boolean discord = h.equals("discord.com") || h.equals("discordapp.com") || h.endsWith(".discord.com") || h.endsWith(".discordapp.com");
        if (discord && (path.startsWith("/api/webhooks/") || path.matches("^/api/v\\d+/webhooks/.*"))) {
            return KIND_DISCORD_WEBHOOK;
        }
        if (h.equals("api.telegram.org") && path.startsWith("/bot")) {
            return KIND_TELEGRAM_BOT;
        }
        return 0;
    }

    static int schemeIndex(String scheme) {
        for (int i = 0; i < SCHEMES.length - 1; i++) {
            if (SCHEMES[i].equals(scheme)) {
                return i;
            }
        }
        return SCHEMES.length - 1;
    }

    private static final class Entry {
        final String plugin;
        final String host;
        final int port;
        final long first;
        long last;
        int count;
        int main;
        int schemes;
        int kinds;

        Entry(String plugin, String host, int port, long ts) {
            this.plugin = plugin;
            this.host = host;
            this.port = port;
            this.first = ts;
            this.last = ts;
        }

        String toJson() {
            StringBuilder sb = new StringBuilder(160).append('{');
            if (plugin != null) {
                Json.str(sb.append("\"p\":"), plugin, 64).append(',');
            }
            Json.str(sb.append("\"h\":"), host, 253).append(",\"port\":").append(port).append(",\"s\":[");
            boolean firstScheme = true;
            for (int i = 0; i < SCHEMES.length; i++) {
                if ((schemes & (1 << i)) != 0) {
                    sb.append(firstScheme ? "" : ",").append('"').append(SCHEMES[i]).append('"');
                    firstScheme = false;
                }
            }
            sb.append("],\"c\":").append(count).append(",\"main\":").append(main)
                    .append(",\"f\":").append(first).append(",\"l\":").append(last).append(",\"k\":[");
            boolean firstKind = true;
            if ((kinds & KIND_DISCORD_WEBHOOK) != 0) {
                sb.append("\"discord_webhook\"");
                firstKind = false;
            }
            if ((kinds & KIND_TELEGRAM_BOT) != 0) {
                sb.append(firstKind ? "" : ",").append("\"telegram_bot\"");
            }
            return sb.append("]}").toString();
        }
    }
}
