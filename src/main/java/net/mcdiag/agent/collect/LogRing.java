package net.mcdiag.agent.collect;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * Последние строки лога плагинов в памяти. Наружу они уходят только вместе с лаг-спайком:
 * постоянного сбора логов нет. Строки ядра, которые идут только через Log4j, сюда не попадают.
 */
public final class LogRing extends Handler {

    private static final int CAPACITY = 128;
    private static final int MAX_LINE = 180;

    private final TextMask mask;
    private final SimpleFormatter formatter = new SimpleFormatter();
    private final Line[] lines = new Line[CAPACITY];
    private int next;

    public LogRing(TextMask mask) {
        this.mask = mask;
        setLevel(Level.INFO);
    }

    public record Line(long ts, String level, String logger, String message) {
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getLevel().intValue() < Level.INFO.intValue()) {
            return;
        }
        try {
            String message = formatter.formatMessage(record);
            // Команды игроков идут в журнал с маскировкой: в этой строке могли быть пароли.
            if (message == null || message.isBlank() || message.contains(" issued server command: ")) {
                return;
            }
            String clean = mask.text(message);
            add(new Line(record.getMillis(), record.getLevel().getName(), record.getLoggerName(),
                    clean.length() > MAX_LINE ? clean.substring(0, MAX_LINE) : clean));
        } catch (RuntimeException ignored) {
            // Ошибка здесь не должна мешать чужому log().
        }
    }

    private synchronized void add(Line line) {
        lines[next] = line;
        next = (next + 1) % CAPACITY;
    }

    /** Строки не старше {@code sinceMs}, от старых к новым, не больше {@code limit} последних. */
    public synchronized List<Line> recent(long sinceMs, int limit) {
        List<Line> out = new ArrayList<>(limit);
        for (int i = 1; i <= CAPACITY && out.size() < limit; i++) {
            Line line = lines[(next - i + CAPACITY) % CAPACITY];
            if (line == null || line.ts() < sinceMs) {
                break;
            }
            out.add(0, line);
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
