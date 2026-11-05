package net.mcdiag.agent.collect;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    /**
     * Ошибки плагинов ядро пишет своим логгером: «Could not pass event … to WorldGuard v7.0.9»,
     * «Plugin X v1.0 generated an exception while executing task». Имя плагина достаём из текста,
     * чтобы панель могла связать ошибку с плагином.
     */
    private static final Pattern PLUGIN_IN_MESSAGE = Pattern.compile(
            "(?: to | while (?:enabling|disabling|loading) |^Plugin |^Exception in plugin )([A-Za-z0-9_.-][A-Za-z0-9 _.-]{0,63}?) v\\d");

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
                || !plugin.cfg().collectErrors() || plugin.panel().free()) {
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
        String logger = loggerOf(record.getLoggerName(), message);
        if (logger != null && !logger.isEmpty()) {
            Json.str(sb.append(",\"logger\":"), logger, MAX_LOGGER);
        }
        Json.str(sb.append(",\"message\":"), message, MAX_MESSAGE);
        Json.str(sb.append(",\"trace\":"), StackTraceFormatter.format(thrown), StackTraceFormatter.MAX_CHARS);
        return sb.append('}').toString();
    }

    /** Логгер ядра («Minecraft», корневой) заменяем на плагин из текста сообщения, если он там есть. */
    static String loggerOf(String logger, String message) {
        boolean generic = logger == null || logger.isEmpty() || logger.equals("Minecraft")
                || logger.equals("global") || logger.startsWith("net.minecraft") || logger.startsWith("org.bukkit");
        if (!generic) {
            return logger;
        }
        Matcher m = PLUGIN_IN_MESSAGE.matcher(message);
        return m.find() ? m.group(1) : logger;
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
