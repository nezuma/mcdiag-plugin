package net.mcdiag.agent.logs;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Подключение перехвата лога: Log4j, если ядро его даёт, иначе корневой JUL Logger. */
public final class LogTaps {

    public interface Tap {
        void ensure();

        void uninstall();

        String name();
    }

    private LogTaps() {
    }

    public static Tap install(LogCapture capture, ClassLoader serverLoader, Logger log) {
        try {
            Tap tap = Log4jTap.install(capture, serverLoader);
            capture.setSource(tap.name());
            return tap;
        } catch (LinkageError | RuntimeException e) {
            log.log(Level.FINE, "Log4j capture is unavailable, falling back to java.util.logging", e);
        }
        Tap tap = new JulTap(capture);
        capture.setSource(tap.name());
        return tap;
    }

    /** Запасной путь: видит только JUL-логгеры (логгеры плагинов Bukkit и сообщения ядра через них). */
    private static final class JulTap extends Handler implements Tap {

        private final LogCapture capture;
        private final SimpleFormatter formatter = new SimpleFormatter();

        JulTap(LogCapture capture) {
            this.capture = capture;
            setLevel(Level.WARNING);
            Logger.getLogger("").addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || record.getLevel().intValue() < Level.WARNING.intValue()) {
                return;
            }
            try {
                String level = record.getLevel().intValue() >= Level.SEVERE.intValue() ? "ERROR" : "WARN";
                capture.accept(record.getMillis(), level, record.getLoggerName(), record.getLongThreadID(),
                        formatter.formatMessage(record), record.getThrown());
            } catch (RuntimeException ignored) {
                // Сбой здесь не должен мешать чужому log().
            }
        }

        @Override
        public void ensure() {
        }

        @Override
        public void uninstall() {
            Logger.getLogger("").removeHandler(this);
        }

        @Override
        public String name() {
            return "jul";
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
