package net.mcdiag.agent.collect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Компактный стектрейс без {@code printStackTrace}: первые {@link #HEAD} и последние {@link #TAIL}
 * строк, середина заменяется на {@code ... N lines omitted ...}. В голове — сама ошибка и место
 * броска, в хвосте — корневая причина ({@code Caused by}) и её самый глубокий вызов.
 *
 * <p>Сначала считается общее число строк, затем строятся только те, что попадут в результат, — на
 * трейсе из тысяч кадров не создаются тысячи строк.
 */
public final class StackTraceFormatter {

    static final int HEAD = 20;
    static final int TAIL = 15;
    static final int MAX_CHARS = 6000;
    private static final int MAX_LINE = 300;
    private static final int MAX_CAUSES = 8;

    private StackTraceFormatter() {
    }

    public static String format(Throwable root) {
        List<Section> sections = sections(root);
        int total = 0;
        for (Section s : sections) {
            total += s.lines();
        }

        StringBuilder sb = new StringBuilder(Math.min(MAX_CHARS, 64 + Math.min(total, HEAD + TAIL) * 80));
        int index = 0;
        boolean omittedWritten = false;
        for (Section s : sections) {
            for (int i = 0; i < s.lines(); i++, index++) {
                if (index >= HEAD && index < total - TAIL) {
                    if (!omittedWritten) {
                        line(sb, "\t... " + (total - HEAD - TAIL) + " lines omitted ...");
                        omittedWritten = true;
                    }
                    continue;
                }
                line(sb, s.line(i));
                if (sb.length() >= MAX_CHARS) {
                    sb.setLength(MAX_CHARS);
                    return sb.toString();
                }
            }
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String text) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(text, 0, Math.min(text.length(), MAX_LINE));
    }

    /** Ошибка и её цепочка причин; общие с обёрткой кадры схлопываются в "... N more", как в JDK. */
    private static List<Section> sections(Throwable root) {
        List<Section> out = new ArrayList<>(2);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        StackTraceElement[] enclosing = null;
        Throwable t = root;
        while (t != null && seen.add(t) && out.size() < MAX_CAUSES) {
            StackTraceElement[] frames = t.getStackTrace();
            int shown = frames.length;
            if (enclosing != null) {
                int e = enclosing.length - 1;
                while (shown > 0 && e >= 0 && frames[shown - 1].equals(enclosing[e])) {
                    shown--;
                    e--;
                }
            }
            out.add(new Section(out.isEmpty() ? "" : "Caused by: ", t.toString(), frames, shown));
            enclosing = frames;
            t = t.getCause();
        }
        return out;
    }

    private record Section(String prefix, String header, StackTraceElement[] frames, int shown) {

        int lines() {
            return 1 + shown + (shown < frames.length ? 1 : 0);
        }

        String line(int i) {
            if (i == 0) {
                return prefix + header;
            }
            if (i <= shown) {
                return "\tat " + frames[i - 1];
            }
            return "\t... " + (frames.length - shown) + " more";
        }
    }
}
