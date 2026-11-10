package net.mcdiag.agent.logs;

import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.Message;

/**
 * Appender на корневом логгере Log4j ядра. Через Log4j идёт всё: JUL-логгеры плагинов (ядро
 * пересылает их сюда), SLF4J и ComponentLogger, System.out/err и сообщения самого сервера.
 *
 * <p>Единственный класс агента, который ссылается на log4j-core: если его нет, загрузка этого класса
 * падает с LinkageError, и {@link LogTaps} переходит на JUL.
 */
final class Log4jTap implements LogTaps.Tap {

    static final String NAME = "MCDiagCapture";

    private final LoggerContext context;
    private final AbstractAppender appender;
    private final List<LoggerConfig> attached = new ArrayList<>();

    private Log4jTap(LoggerContext context, AbstractAppender appender) {
        this.context = context;
        this.appender = appender;
    }

    static Log4jTap install(LogCapture capture, ClassLoader serverLoader) {
        LoggerContext context = (LoggerContext) LogManager.getContext(serverLoader, false);
        AbstractAppender appender = new AbstractAppender(NAME, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                // Событие может быть переиспользуемым (async-логгеры): всё нужное берём сразу.
                Message message = event.getMessage();
                capture.accept(event.getTimeMillis(), levelOf(event.getLevel()), event.getLoggerName(),
                        event.getThreadId(), message == null ? null : message.getFormattedMessage(), event.getThrown());
            }
        };
        appender.start();
        Log4jTap tap = new Log4jTap(context, appender);
        tap.attach();
        return tap;
    }

    /** Корневой логгер и логгеры без наследования (additivity=false): их строки до корня не доходят. */
    private synchronized void attach() {
        Configuration config = context.getConfiguration();
        List<LoggerConfig> targets = new ArrayList<>();
        targets.add(config.getRootLogger());
        for (LoggerConfig lc : config.getLoggers().values()) {
            if (!lc.isAdditive() && lc != config.getRootLogger()) {
                targets.add(lc);
            }
        }
        for (LoggerConfig lc : targets) {
            if (!lc.getAppenders().containsKey(NAME)) {
                lc.addAppender(appender, Level.WARN, null);
                attached.add(lc);
            }
        }
        context.updateLoggers();
    }

    /** Конфигурацию Log4j могли перезагрузить, и наш appender пропал: подключаемся заново. */
    @Override
    public synchronized void ensure() {
        if (!context.getConfiguration().getRootLogger().getAppenders().containsKey(NAME)) {
            attached.clear();
            attach();
        }
    }

    @Override
    public synchronized void uninstall() {
        for (LoggerConfig lc : attached) {
            lc.removeAppender(NAME);
        }
        attached.clear();
        context.updateLoggers();
        appender.stop();
    }

    @Override
    public String name() {
        return "log4j";
    }

    private static String levelOf(Level level) {
        if (level.isMoreSpecificThan(Level.FATAL)) {
            return "FATAL";
        }
        return level.isMoreSpecificThan(Level.ERROR) ? "ERROR" : "WARN";
    }
}
