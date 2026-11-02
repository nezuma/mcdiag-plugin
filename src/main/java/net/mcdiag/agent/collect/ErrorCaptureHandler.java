package net.mcdiag.agent.collect;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.json.Json;

/**
 * Хендлер на root JUL Logger: SEVERE с Throwable сразу превращается в JSON-строку, ссылка на
 * LogRecord/Throwable после {@link #publish} не сохраняется. Может вызываться из любого потока.
 */
public final class ErrorCaptureHandler extends Handler {

    private static final int MAX_MESSAGE = 512;
    private static final int MAX_LOGGER = 128;
    private static final long MINUTE_MS = 60_000;

    private final McDiagPlugin plugin;
    /** Только для formatMessage: подстановка {0}-параметров и resource bundle. */
    private final SimpleFormatter formatter = new SimpleFormatter();

    private long windowStart;
    private int windowCount;

    public ErrorCaptureHandler(McDiagPlugin plugin) {
        this.plugin = plugin;
        setLevel(Level.SEVERE);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getThrown() == null
                || record.getLevel().intValue() < Level.SEVERE.intValue()
                || !plugin.cfg().collectErrors()) {
            return;
        }
        if (!acquire(plugin.cfg().maxErrorsPerMinute())) {
            plugin.buffer().countDropped(1);
            return;
        }
        // Ошибка в собственном коде логирования не должна ни уронить чужой log(), ни зациклиться.
        try {
            plugin.buffer().addEvent(toJson(record, formatter, System.currentTimeMillis()));
        } catch (RuntimeException ignored) {
            plugin.buffer().countDropped(1);
        }
    }

    static String toJson(LogRecord record, SimpleFormatter formatter, long ts) {
        Throwable thrown = record.getThrown();
        String message = formatter.formatMessage(record);
        if (message == null || message.isBlank()) {
            message = thrown.toString();
        }

        StringBuilder sb = new StringBuilder(StackTraceFormatter.MAX_CHARS + 768);
        sb.append("{\"type\":\"error\",\"ts\":").append(ts);
        String logger = record.getLoggerName();
        if (logger != null && !logger.isEmpty()) {
            Json.str(sb.append(",\"logger\":"), logger, MAX_LOGGER);
        }
        Json.str(sb.append(",\"message\":"), message, MAX_MESSAGE);
        Json.str(sb.append(",\"trace\":"), StackTraceFormatter.format(thrown), StackTraceFormatter.MAX_CHARS);
        return sb.append('}').toString();
    }

    private synchronized boolean acquire(int perMinute) {
        long now = System.currentTimeMillis();
        if (now - windowStart >= MINUTE_MS) {
            windowStart = now;
            windowCount = 0;
        }
        return windowCount++ < perMinute;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
