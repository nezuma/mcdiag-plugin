package net.mcdiag.agent.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Ошибка внутри агента не должна попасть в консоль сервера как чужая проблема и не должна мешать тику. */
public final class Safe {

    private Safe() {
    }

    public static Runnable task(Logger log, String name, Runnable body) {
        return () -> run(log, name, body);
    }

    public static void run(Logger log, String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable e) {
            log.log(Level.FINE, "MCDiag " + name + " failed", e);
        }
    }
}
