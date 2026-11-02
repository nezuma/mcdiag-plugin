package net.mcdiag.agent.collect;

import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/** Доступ к package-private форматированию для тестов из других пакетов. */
public final class ErrorCaptureHandlerAccess {

    private ErrorCaptureHandlerAccess() {
    }

    public static String toJson(LogRecord record, long ts) {
        return ErrorCaptureHandler.toJson(record, new SimpleFormatter(), ts);
    }
}
