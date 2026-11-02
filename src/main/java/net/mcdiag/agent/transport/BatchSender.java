package net.mcdiag.agent.transport;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.buffer.TelemetryBuffer;
import net.mcdiag.agent.config.AgentConfig;

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

    private final McDiagPlugin plugin;
    private final HttpTransport transport;
    private final String pluginJson;
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
    private volatile String lastResult = "not sent yet";
    private volatile long sentBatches;

    public BatchSender(McDiagPlugin plugin, HttpTransport transport, String pluginJson) {
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

    public Status status() {
        return new Status(inFlight.get(), pendingItems, sentBatches, lastSuccessAt, lastResult);
    }

    private void tick() {
        AgentConfig cfg = plugin.cfg();
        long now = System.currentTimeMillis();
        if (!cfg.canSend() || now < nextAttemptAt) {
            return;
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
            report(status, "batch dropped " + status + code(res), true);
        } else {
            backoff(cfg, now);
            report(status, "server error " + status + ", will retry", true);
        }
    }

    /** @return false, если отправлять нечего */
    private boolean buildBatch(AgentConfig cfg, long now) {
        TelemetryBuffer buffer = plugin.buffer();
        if (buffer.isEmpty()) {
            return false;
        }
        int items = assemble(sb, buffer, cfg.maxBatchEvents(), UUID.randomUUID(), now, pluginJson);
        if (items == 0) {
            return false;
        }
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
     *
     * @return сколько событий и точек метрик попало в батч
     */
    static int assemble(StringBuilder sb, TelemetryBuffer buffer, int maxEvents, UUID batchId, long now,
                        String pluginJson) {
        sb.setLength(0);
        sb.append("{\"batchId\":\"").append(batchId)
                .append("\",\"sentAt\":").append(now)
                .append(",\"plugin\":").append(pluginJson)
                .append(",\"events\":[");
        int budget = MAX_BODY_CHARS - sb.length() - 32;
        int start = sb.length();
        int events = buffer.drainEvents(sb, maxEvents, budget);
        budget -= sb.length() - start;
        sb.append("],\"metrics\":[");
        int metrics = buffer.drainMetrics(sb, budget);
        sb.append("]}");
        return events + metrics;
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
