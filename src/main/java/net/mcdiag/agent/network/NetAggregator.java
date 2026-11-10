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
    /** Групп «плагин × хост × шаблон пути» за окно и шаблонов на один хост */
    public static final int MAX_REQUESTS = 256;
    public static final int MAX_PATHS_PER_HOST = 50;
    /** Один HTTP-запрос виден дважды: как https://host и как socket://host:443 того же потока. */
    private static final long DUPLICATE_MS = 2_000;

    static final int KIND_DISCORD_WEBHOOK = 1;
    static final int KIND_TELEGRAM_BOT = 2;

    private static final String[] SCHEMES = {"http", "https", "ws", "wss", "socket", "other"};

    private final Map<String, Entry> window = new HashMap<>();
    private final Map<String, RequestEntry> requests = new HashMap<>();
    private long dropped;
    /** Последний HTTP-адрес каждого потока: его сокет — тот же запрос, а не новое соединение. */
    private final Map<Long, Object[]> lastHttp = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Object[]> eldest) {
            return size() > 64;
        }
    };

    /** Где и что: шаблон и пример пути (уже без секретов), место вызова и файл, поток. */
    public record Request(String pattern, String example, String callSite, String file, String thread) {
    }

    public synchronized void record(long ts, String plugin, String scheme, String host, int port, boolean main, int kinds, long threadId) {
        record(ts, plugin, scheme, host, port, main, kinds, threadId, null);
    }

    public synchronized void record(long ts, String plugin, String scheme, String host, int port, boolean main, int kinds,
                                    long threadId, Request request) {
        String target = host + ':' + port;
        boolean socket = scheme.equals("socket");
        if (socket) {
            Object[] last = lastHttp.get(threadId);
            if (last != null && target.equals(last[0]) && ts - (Long) last[1] <= DUPLICATE_MS) {
                return;
            }
        } else {
            lastHttp.put(threadId, new Object[] {target, ts});
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
        if (request != null) {
            recordRequest(ts, plugin, scheme, host, port, main, request, e);
        }
    }

    private void recordRequest(long ts, String plugin, String scheme, String host, int port, boolean main, Request request, Entry host0) {
        String key = (plugin == null ? "" : plugin) + '|' + host + ':' + port + '|' + request.pattern();
        RequestEntry r = requests.get(key);
        if (r == null) {
            if (requests.size() >= MAX_REQUESTS || host0.paths >= MAX_PATHS_PER_HOST) {
                // Остальные пути хоста — одним счётчиком: высокая кардинальность не должна съесть память.
                host0.otherPaths++;
                return;
            }
            host0.paths++;
            r = new RequestEntry(plugin, host, port, scheme, request.pattern(), ts);
            requests.put(key, r);
        }
        r.count++;
        if (main) {
            r.main++;
        }
        r.last = ts;
        // Последний пример и место вызова: по ним видно, что именно отправлялось в последний раз.
        r.example = request.example();
        if (request.callSite() != null) {
            r.callSite = request.callSite();
        }
        if (request.file() != null) {
            r.file = request.file();
        }
        r.thread = request.thread();
    }

    /** Группы запросов окна для раздела {@code netreq}; очищаются. */
    public synchronized List<String> drainRequests() {
        List<String> out = new ArrayList<>(requests.size());
        for (RequestEntry r : requests.values()) {
            out.add(r.toJson());
        }
        requests.clear();
        return out;
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

    /** Окно целиком: и хосты, и запросы (панель не принимает раздел или модуль выключен). */
    public synchronized void clear() {
        window.clear();
        requests.clear();
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
        int paths;
        int otherPaths;

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

    private static final class RequestEntry {
        final String plugin;
        final String host;
        final int port;
        final String scheme;
        final String pattern;
        final long first;
        long last;
        int count;
        int main;
        String example;
        String callSite;
        String file;
        String thread;

        RequestEntry(String plugin, String host, int port, String scheme, String pattern, long ts) {
            this.plugin = plugin;
            this.host = host;
            this.port = port;
            this.scheme = scheme;
            this.pattern = pattern;
            this.first = ts;
            this.last = ts;
        }

        String toJson() {
            StringBuilder sb = new StringBuilder(256).append('{');
            if (plugin != null) {
                Json.str(sb.append("\"p\":"), plugin, 64).append(',');
            }
            Json.str(sb.append("\"h\":"), host, 253).append(",\"port\":").append(port)
                    .append(",\"s\":\"").append(SCHEMES[schemeIndex(scheme)]).append('"');
            Json.str(sb.append(",\"pt\":"), pattern, RequestMask.MAX_PATTERN);
            Json.str(sb.append(",\"ex\":"), example == null ? pattern : example, RequestMask.MAX_EXAMPLE);
            sb.append(",\"c\":").append(count).append(",\"main\":").append(main)
                    .append(",\"f\":").append(first).append(",\"l\":").append(last);
            if (callSite != null) {
                Json.str(sb.append(",\"cs\":"), callSite, 300);
            }
            if (file != null) {
                Json.str(sb.append(",\"file\":"), file, 300);
            }
            if (thread != null) {
                Json.str(sb.append(",\"th\":"), thread, 64);
            }
            return sb.append('}').toString();
        }
    }
}
