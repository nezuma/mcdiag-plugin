package net.mcdiag.agent.profiler;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * Снимок стека одного потока самым дешёвым способом для этой JVM.
 *
 * <p>Начиная с Java 19 {@link Thread#getStackTrace()} работает через handshake: приостанавливается
 * только сам поток. {@link ThreadMXBean#getThreadInfo(long, int)} всегда останавливает всю JVM
 * (safepoint), поэтому на Java 17 он нужен только ради ограничения глубины стека.
 */
final class StackReader {

    /** С этой версии снимок чужого стека не останавливает остальные потоки */
    private static final int HANDSHAKE_SINCE_JAVA = 19;
    private static final int MAX_DEPTH = 128;
    private static final StackTraceElement[] NONE = new StackTraceElement[0];

    private final Thread thread;
    private final boolean handshake;
    private final ThreadMXBean mx;

    StackReader(Thread thread) {
        this(thread, Runtime.version().feature() >= HANDSHAKE_SINCE_JAVA);
    }

    StackReader(Thread thread, boolean handshake) {
        this.thread = thread;
        this.handshake = handshake;
        this.mx = handshake ? null : ManagementFactory.getThreadMXBean();
    }

    boolean usesHandshake() {
        return handshake;
    }

    /** @return стек от вершины; пустой, если поток уже завершился */
    StackTraceElement[] read() {
        if (handshake) {
            return thread.getStackTrace();
        }
        ThreadInfo info = mx.getThreadInfo(thread.getId(), MAX_DEPTH);
        return info == null ? NONE : info.getStackTrace();
    }
}
