package net.mcdiag.agent.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.buffer.TelemetryBuffer;
import net.mcdiag.agent.config.AgentConfig;
import net.mcdiag.agent.module.Channel;
import net.mcdiag.agent.module.ModuleRegistry;
import net.mcdiag.agent.remote.RemoteCommandParser;

/**
 * Async-таск отправки. Пока предыдущий запрос не завершён, новый не создаётся ({@link #inFlight}).
 *
 * <p>Неотправленный батч хранится как готовая строка и повторяется с тем же {@code batchId}:
 * бэкенд по нему отбрасывает дубли, так что таймаут после успешной записи не удвоит данные.
 * Буфер в это время продолжает копить новые события в пределах своего лимита.
 */
public final class BatchSender implements Runnable {

    /** Бэкенд режет тело > 64 KB; запас под заголовок батча. */
    private static final int MAX_BODY_CHARS = 60 * 1024;
    /** Бэкенд молча отбрасывает события старше 24 ч — дольше повторять незачем. */
    private static final long PENDING_TTL_MS = 23L * 3600 * 1000;
    private static final long MAX_BACKOFF_MS = 5 * 60 * 1000;
    private static final long AUTH_BACKOFF_MS = 60 * 1000;
    /**
     * Пустой батч-«пульс», если отправлять нечего: панель видит, что сервер на связи, и может
     * передать команды поддержки, даже если метрики выключены.
     */
    private static final long HEARTBEAT_MS = 30 * 1000;
    private static final long FREE_INTERVAL_MS = 5 * 60 * 1000;

    private final McDiagPlugin plugin;
    private final HttpTransport transport;
    private final Supplier<String> pluginJson;
    private final Logger log;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    /** Переиспользуется между батчами — не аллоцируем 64 KB каждые 10 секунд. */
    private final StringBuilder sb = new StringBuilder(8 * 1024);

    private String pendingBody;
    private long pendingCreatedAt;
    private volatile int failures;
    private volatile long nextAttemptAt;
    private int lastLoggedStatus;

    private volatile int pendingItems;
    private volatile long lastSuccessAt;
    private long lastAttemptAt;
    private volatile String lastResult = "not sent yet";
    private volatile long sentBatches;

    public BatchSender(McDiagPlugin plugin, HttpTransport transport, Supplier<String> pluginJson) {
        this.plugin = plugin;
        this.transport = transport;
        this.pluginJson = pluginJson;
        this.log = plugin.getLogger();
    }

    @Override
    public void run() {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            tick();
        } finally {
            inFlight.set(false);
        }
    }

    /** /diag reload или новый токен: сразу пробуем снова, без накопленной паузы. */
    public void resetBackoff() {
        nextAttemptAt = 0;
        failures = 0;
    }

    /** Рельеф тепловой карты уходит отдельным запросом через тот же транспорт. */
    public HttpTransport transport() {
        return transport;
    }

    public Status status() {
        return new Status(inFlight.get(), pendingItems, sentBatches, lastSuccessAt, lastResult);
    }

    private void tick() {
        AgentConfig cfg = plugin.cfg();
        long now = System.currentTimeMillis();
        if (!cfg.canSend() || now < nextAttemptAt) {
            return;
        }
        // Бесплатный режим: одна точка в 5 минут, остальное не отправляем и не копим.
        if (plugin.panel().free()) {
            plugin.buffer().clearAllButMetrics();
            if (now - lastAttemptAt < FREE_INTERVAL_MS) {
                return;
            }
        }
        if (pendingBody != null && now - pendingCreatedAt > PENDING_TTL_MS) {
            plugin.buffer().countDropped(pendingItems);
            clearPending();
        }
        if (pendingBody == null && !buildBatch(cfg, now)) {
            return;
        }

        HttpTransport.Response res;
        try {
            res = transport.post(cfg.backendUrl(), cfg.serverToken(), pendingBody);
        } catch (IOException e) {
            backoff(cfg, now);
            report(-1, "network error: " + e.getClass().getSimpleName(), true);
            return;
        }

        int status = res.status();
        if (status >= 200 && status < 300) {
            clearPending();
            PanelState panel = plugin.panel();
            boolean wasFree = panel.free();
            boolean hadHeatmap = panel.heatmap();
            boolean hadEconomy = panel.economy();
            Set<String> hadCaps = panel.caps();
            boolean answered = panel.answered();
            panel.update(res.body());
            ModuleRegistry modules = plugin.modules();
            if (modules != null) {
                modules.clearUnsupportedChannels();
            }
            // Команды и чат лежат в одной очереди: чистим, только если выключено и то и другое.
            plugin.buffer().clearOptional(!panel.commands() && !panel.chat(), !panel.richSpikes());
            plugin.buffer().clearModules(!panel.chunkgen(), !panel.economy());
            if (!panel.heatmap()) {
                plugin.buffer().clearHeat();
            }
            if (wasFree != panel.free() || hadHeatmap != panel.heatmap() || hadEconomy != panel.economy()
                    || !answered || !hadCaps.equals(panel.caps())) {
                plugin.onPanelChanged();
            }
            plugin.remote().accept(RemoteCommandParser.parse(res.body()));
            failures = 0;
            sentBatches++;
            lastSuccessAt = now;
            report(status, "OK " + status, false);
        } else if (status == 429) {
            nextAttemptAt = now + (res.retryAfterSec() >= 0 ? res.retryAfterSec() * 1000 : 5_000);
            report(status, "rate limited (429)", false);
        } else if (status == 401 || status == 403) {
            // Неверный/отозванный токен или неактивная подписка: повтор не поможет, данные отбрасываем.
            plugin.buffer().countDropped(pendingItems);
            clearPending();
            nextAttemptAt = now + AUTH_BACKOFF_MS;
            report(status, "rejected " + status + code(res), true);
        } else if (status >= 400 && status < 500) {
            plugin.buffer().countDropped(pendingItems);
            clearPending();
            // Панель не приняла формат — откатываемся к старому, чтобы не терять и остальные данные.
            plugin.panel().reset();
            plugin.buffer().clearOptional(true, true);
            plugin.buffer().clearModules(true, true);
            ModuleRegistry modules = plugin.modules();
            if (modules != null) {
                modules.clearUnsupportedChannels();
            }
            plugin.onPanelChanged();
            report(status, "batch dropped " + status + code(res), true);
        } else {
            backoff(cfg, now);
            report(status, "server error " + status + ", will retry", true);
        }
    }

    /** @return false, если отправлять нечего */
    private boolean buildBatch(AgentConfig cfg, long now) {
        TelemetryBuffer buffer = plugin.buffer();
        boolean heartbeat = now - lastAttemptAt >= HEARTBEAT_MS;
        List<Channel> channels = activeChannels();
        if (buffer.isEmpty() && channelsEmpty(channels) && !heartbeat) {
            return false;
        }
        int items = assemble(sb, buffer, cfg.maxBatchEvents(), UUID.randomUUID(), now, pluginJson.get(), channels);
        if (items == 0 && !heartbeat) {
            return false;
        }
        lastAttemptAt = now;
        pendingBody = sb.toString();
        pendingCreatedAt = now;
        pendingItems = items;
        // Пиковый батч не держим в памяти между отправками.
        if (sb.capacity() > 16 * 1024) {
            sb.setLength(0);
            sb.trimToSize();
        }
        return true;
    }

    /**
     * Собирает тело IngestBatch в {@code sb}, забирая элементы из буфера в пределах лимитов бэкенда.
     * Ответы на команды поддержки идут первыми: они маленькие и их ждёт человек.
     *
     * @return сколько элементов попало в батч (0 — пустой «пульс»)
     */
    static int assemble(StringBuilder sb, TelemetryBuffer buffer, int maxEvents, UUID batchId, long now,
                        String pluginJson) {
        return assemble(sb, buffer, maxEvents, batchId, now, pluginJson, List.of());
    }

    /** Разделы модулей, которые панель сейчас принимает. */
    private List<Channel> activeChannels() {
        ModuleRegistry modules = plugin.modules();
        if (modules == null || plugin.panel().free()) {
            return List.of();
        }
        List<Channel> out = new ArrayList<>();
        for (Channel c : modules.channels()) {
            if (plugin.panel().has(c.cap())) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean channelsEmpty(List<Channel> channels) {
        for (Channel c : channels) {
            if (!c.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** @param channels разделы модулей; идут после основных данных, порядок — порядок регистрации */
    static int assemble(StringBuilder sb, TelemetryBuffer buffer, int maxEvents, UUID batchId, long now,
                        String pluginJson, List<Channel> channels) {
        sb.setLength(0);
        sb.append("{\"batchId\":\"").append(batchId)
                .append("\",\"sentAt\":").append(now)
                .append(",\"plugin\":").append(pluginJson);
        int[] budget = {MAX_BODY_CHARS - sb.length() - 64};
        int items = optional(sb, "results", budget, buffer::drainResults);
        // Спайки и команды — первыми: по ним панель сразу предупреждает об опасных действиях.
        items += optional(sb, "spikes", budget, buffer::drainSpikes);
        items += optional(sb, "chatlog", budget, buffer::drainChat);
        items += section(sb, "events", budget, (out, max) -> buffer.drainEvents(out, maxEvents, max));
        items += section(sb, "metrics", budget, buffer::drainMetrics);
        items += optional(sb, "profile", budget, buffer::drainProfiles);
        items += optional(sb, "system", budget, buffer::drainSystem);
        items += optional(sb, "memory", budget, buffer::drainMemory);
        items += optional(sb, "heat", budget, buffer::drainHeat);
        items += optional(sb, "chunkgen", budget, buffer::drainChunkGen);
        items += optional(sb, "economy", budget, buffer::drainEconomy);
        for (Channel channel : channels) {
            items += optional(sb, channel.name(), budget, channel::drain);
        }
        String inventory = budget[0] > 512 ? buffer.takeInventory(budget[0] - 32) : null;
        if (inventory != null) {
            sb.append(",\"inventory\":").append(inventory);
            items++;
        }
        sb.append('}');
        return items;
    }

    private interface Drainer {
        int drain(StringBuilder out, int maxChars);
    }

    /** Обязательный массив: пишется всегда, даже пустой. */
    private static int section(StringBuilder sb, String name, int[] budget, Drainer drainer) {
        sb.append(",\"").append(name).append("\":[");
        int start = sb.length();
        int n = drainer.drain(sb, Math.max(0, budget[0]));
        budget[0] -= sb.length() - start;
        sb.append(']');
        return n;
    }

    /** Необязательный массив: не пишется, если пуст или места мало (старый бэкенд о нём не знает). */
    private static int optional(StringBuilder sb, String name, int[] budget, Drainer drainer) {
        if (budget[0] <= 512) {
            return 0;
        }
        int mark = sb.length();
        sb.append(",\"").append(name).append("\":[");
        int start = sb.length();
        int n = drainer.drain(sb, budget[0] - 32);
        if (n == 0) {
            sb.setLength(mark);
            return 0;
        }
        budget[0] -= sb.length() - start;
        sb.append(']');
        return n;
    }

    private void clearPending() {
        pendingBody = null;
        pendingItems = 0;
    }

    private void backoff(AgentConfig cfg, long now) {
        failures = Math.min(failures + 1, 10);
        long delay = Math.min(MAX_BACKOFF_MS, cfg.batchIntervalSec() * 1000L << (failures - 1));
        nextAttemptAt = now + delay;
    }

    /** В лог — только смена состояния, чтобы недоступный бэкенд не засыпал консоль. Токен не пишется. */
    private void report(int status, String result, boolean warn) {
        lastResult = result;
        if (status != lastLoggedStatus) {
            lastLoggedStatus = status;
            if (warn) {
                log.warning("Telemetry upload: " + result);
            } else if (status >= 200 && status < 300) {
                log.info("Telemetry upload: OK");
            }
        }
    }

    private static String code(HttpTransport.Response res) {
        return res.errorCode() == null ? "" : " (" + res.errorCode() + ")";
    }

    public record Status(boolean inFlight, int pendingItems, long sentBatches, long lastSuccessAt,
                         String lastResult) {
    }
}
