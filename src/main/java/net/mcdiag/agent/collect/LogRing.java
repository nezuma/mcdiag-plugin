package net.mcdiag.agent.collect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * Последние предупреждения и ошибки плагинов в памяти. Наружу они уходят только вместе с лаг-спайком:
 * постоянного сбора логов нет. Строки ядра, которые идут только через Log4j, сюда не попадают.
 *
 * <p>{@link #publish} вызывается в потоке того, кто пишет в лог, в том числе в главном, поэтому
 * здесь только запись ссылки. Маскировка и фильтры выполняются в {@link #recent}: большинство строк
 * вытесняются раньше, чем понадобятся.
 */
public final class LogRing extends Handler {

    private static final int CAPACITY = 128;
    private static final int MAX_LINE = 180;
    /** Огромная строка не должна жить в памяти до вытеснения */
    private static final int MAX_STORED = 1024;

    private final SimpleFormatter formatter = new SimpleFormatter();
    private final Line[] lines = new Line[CAPACITY];
    private final BooleanSupplier active;
    private volatile TextMask mask;
    private int next;

    public LogRing(TextMask mask) {
        this(mask, () -> true);
    }

    /** @param active пока false, строки не запоминаются: старая панель их не примет */
    public LogRing(TextMask mask, BooleanSupplier active) {
        this.mask = mask;
        this.active = active;
        setLevel(Level.WARNING);
    }

    /** {@code /diag reload} мог изменить список скрытых команд. */
    public void setMask(TextMask mask) {
        this.mask = mask;
    }

    public record Line(long ts, String level, String logger, String message) {
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getLevel().intValue() < Level.WARNING.intValue() || !active.getAsBoolean()) {
            return;
        }
        try {
            Object[] params = record.getParameters();
            boolean plain = (params == null || params.length == 0) && record.getResourceBundle() == null;
            String message = plain ? record.getMessage() : formatter.formatMessage(record);
            if (message == null) {
                return;
            }
            add(new Line(record.getMillis(), record.getLevel().getName(), record.getLoggerName(),
                    message.length() > MAX_STORED ? message.substring(0, MAX_STORED) : message));
        } catch (RuntimeException ignored) {
            // Ошибка здесь не должна мешать чужому log().
        }
    }

    /** Строка из перехвата Log4j: тот же формат, что у JUL-записей. */
    public void add(long ts, String level, String logger, String message) {
        if (message == null || !active.getAsBoolean()) {
            return;
        }
        add(new Line(ts, level, logger, message.length() > MAX_STORED ? message.substring(0, MAX_STORED) : message));
    }

    private synchronized void add(Line line) {
        lines[next] = line;
        next = (next + 1) % CAPACITY;
    }

    /** Строки не старше {@code sinceMs}, от старых к новым, не больше {@code limit} последних. */
    public List<Line> recent(long sinceMs, int limit) {
        Line[] copy = new Line[CAPACITY];
        int from;
        synchronized (this) {
            System.arraycopy(lines, 0, copy, 0, CAPACITY);
            from = next;
        }
        TextMask current = mask;
        List<Line> out = new ArrayList<>(limit);
        for (int i = 1; i <= CAPACITY && out.size() < limit; i++) {
            Line line = copy[(from - i + CAPACITY) % CAPACITY];
            if (line == null || line.ts() < sinceMs) {
                break;
            }
            String message = line.message();
            // Команды игроков идут в журнал с маскировкой: в этой строке могли быть пароли.
            if (message.isBlank() || message.contains(" issued server command: ")) {
                continue;
            }
            String clean = current.text(message);
            out.add(0, new Line(line.ts(), line.level(), line.logger(),
                    clean.length() > MAX_LINE ? clean.substring(0, MAX_LINE) : clean));
        }
        return out;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
