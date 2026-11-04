package net.mcdiag.agent.profiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.mcdiag.agent.json.Json;

/**
 * Что делал главный поток во время долгого тика: откуда начался вызов, какой код работал и чего он
 * ждал. Работает только в потоке профилировщика и только для спайков. Чистая функция от сэмплов.
 */
final class SpikeAnalyzer {

    private static final int MAX_PATHS = 5;
    private static final int MAX_FRAMES = 10;
    private static final int WAIT_SCAN = 40;

    /** @param state состояние потока; lock — кто держит блокировку, если поток её ждёт */
    record Sample(StackTraceElement[] stack, String owner, Thread.State state, String lock, long atNanos) {
    }

    record PathInfo(String owner, String entry, String waitOn, List<String> frames, int count) {
    }

    /**
     * @param firstMs через сколько после начала тика снят первый сэмпл
     * @param gapMs   самый большой провал между сэмплами: в это время стоял и сам сэмплер
     */
    record Result(int samples, long firstMs, long gapMs, List<PathInfo> paths, Map<String, Integer> waits,
                  String lock, String signature) {
    }

    private SpikeAnalyzer() {
    }

    static Result analyze(List<Sample> samples, long tickStartNanos, long tickEndNanos) {
        Map<String, PathInfo> grouped = new HashMap<>();
        Map<String, Integer> waits = new HashMap<>();
        String lock = null;
        long first = -1;
        long gap = 0;
        long prev = tickStartNanos;
        for (Sample sample : samples) {
            if (first < 0) {
                first = (sample.atNanos() - tickStartNanos) / 1_000_000;
            }
            gap = Math.max(gap, sample.atNanos() - prev);
            prev = sample.atNanos();
            if (sample.lock() != null) {
                lock = sample.lock();
            }
            StackTraceElement[] stack = sample.stack();
            if (stack == null || stack.length == 0) {
                continue;
            }
            String wait = waitKind(stack, sample.state());
            if (wait != null) {
                waits.merge(wait, 1, Integer::sum);
            }
            Entry entry = entryOf(stack, sample.owner());
            String key = sample.owner() + "|" + entry.kind + "|" + entry.frame + "|" + hotMethod(stack) + "|" + wait;
            PathInfo old = grouped.get(key);
            grouped.put(key, new PathInfo(sample.owner(), entry.kind, wait,
                    old != null ? old.frames() : framesOf(stack, entry.index), old == null ? 1 : old.count() + 1));
        }
        gap = Math.max(gap, tickEndNanos - prev);
        List<PathInfo> paths = new ArrayList<>(grouped.values());
        paths.sort((a, b) -> b.count() - a.count());
        String signature = paths.isEmpty() ? null : signatureOf(paths.get(0));
        return new Result(samples.size(), Math.max(0, first), gap / 1_000_000,
                paths.size() > MAX_PATHS ? List.copyOf(paths.subList(0, MAX_PATHS)) : paths, waits, lock, signature);
    }

    /** Один и тот же путь вызова в разных спайках: по этому ключу панель находит повторы. */
    static String signatureOf(PathInfo path) {
        String entry = path.frames().isEmpty() ? "" : path.frames().get(0);
        String top = path.frames().isEmpty() ? "" : path.frames().get(path.frames().size() - 1);
        return path.owner() + "|" + path.entry() + "|" + withoutLine(entry) + "|" + withoutLine(top);
    }

    private static String withoutLine(String frame) {
        int colon = frame.lastIndexOf(':');
        return colon > 0 ? frame.substring(0, colon) : frame;
    }

    private record Entry(String kind, String frame, int index) {
    }

    /** Откуда начался вызов. Идём от вершины: ближайший к работающему коду вход важнее всего. */
    static Entry entryOf(StackTraceElement[] stack, String owner) {
        int lastPlugin = -1;
        for (int i = 0; i < stack.length; i++) {
            String cls = stack[i].getClassName();
            String kind = markerKind(cls, stack[i].getMethodName());
            if (kind != null) {
                int from = lastPlugin >= 0 ? lastPlugin : Math.max(0, i - 1);
                return new Entry(kind, shortName(stack[from]), from);
            }
            if (!FrameClassifier.isNeutral(cls) && !FrameClassifier.isCore(cls)) {
                lastPlugin = i;
            }
        }
        if (lastPlugin >= 0) {
            return new Entry("plugin", shortName(stack[lastPlugin]), lastPlugin);
        }
        return new Entry(coreEntry(owner), stack.length > 0 ? shortName(stack[0]) : "", Math.min(stack.length - 1, MAX_FRAMES - 1));
    }

    private static String markerKind(String cls, String method) {
        if (cls.endsWith(".scheduler.CraftTask") || cls.endsWith(".scheduler.CraftScheduler")) {
            return "task";
        }
        if (cls.equals("org.bukkit.plugin.RegisteredListener") || cls.equals("org.bukkit.plugin.SimplePluginManager")
                || cls.endsWith(".PaperEventManager") || cls.equals("org.bukkit.event.Event")) {
            return "event";
        }
        if (cls.equals("org.bukkit.command.SimpleCommandMap") || cls.equals("org.bukkit.command.PluginCommand")
                || cls.equals("net.minecraft.commands.Commands")) {
            return "command";
        }
        if (cls.equals("net.minecraft.network.protocol.PacketUtils") || cls.endsWith(".ServerGamePacketListenerImpl")
                || cls.endsWith(".PlayerConnection")) {
            return "packet";
        }
        return null;
    }

    private static String coreEntry(String owner) {
        return switch (owner) {
            case FrameClassifier.ENTITIES -> "entities";
            case FrameClassifier.CHUNKS -> "chunks";
            case FrameClassifier.BLOCKS -> "blocks";
            case FrameClassifier.NETWORK -> "network";
            case FrameClassifier.SAVING -> "saving";
            default -> "server";
        };
    }

    /** Чего ждал поток; null — просто считал. База данных проверяется первой: она тоже идёт через сокет. */
    static String waitKind(StackTraceElement[] stack, Thread.State state) {
        if (state == Thread.State.BLOCKED) {
            return "lock";
        }
        int scan = Math.min(stack.length, WAIT_SCAN);
        for (int i = 0; i < scan; i++) {
            String cls = stack[i].getClassName();
            if (cls.startsWith("java.sql.") || cls.contains(".mysql.") || cls.contains(".mariadb.") || cls.contains(".postgresql.")
                    || cls.contains(".sqlite.") || cls.contains(".h2.") || cls.contains(".hikari.") || cls.contains(".mongodb.")
                    || cls.contains(".jedis.") || cls.contains(".lettuce.")) {
                return "database";
            }
        }
        for (int i = 0; i < scan; i++) {
            String cls = stack[i].getClassName();
            String method = stack[i].getMethodName();
            if (cls.startsWith("java.net.") || cls.startsWith("sun.net.") || cls.equals("sun.nio.ch.NioSocketImpl")
                    || cls.equals("sun.nio.ch.Net") || cls.equals("sun.nio.ch.SocketDispatcher") || cls.startsWith("sun.security.ssl.")) {
                return "network";
            }
            if (cls.startsWith("java.io.File") || cls.equals("java.io.RandomAccessFile") || cls.equals("sun.nio.ch.FileChannelImpl")
                    || cls.startsWith("java.nio.file.") || cls.startsWith("sun.nio.fs.") || cls.endsWith("FileDispatcherImpl")) {
                return "disk";
            }
            if ((cls.equals("java.lang.ClassLoader") && (method.startsWith("loadClass") || method.startsWith("defineClass")))
                    || cls.startsWith("jdk.internal.loader.") || cls.endsWith(".PluginClassLoader")) {
                return "classload";
            }
            if (cls.equals("java.lang.Thread") && method.startsWith("sleep")) {
                return "sleep";
            }
            if (cls.contains("snakeyaml") || cls.startsWith("com.google.gson.") || cls.contains(".jackson.") || cls.startsWith("java.util.zip.")) {
                return "serialize";
            }
            if (MainThreadSampler.parked(state) && (cls.equals("java.util.concurrent.locks.LockSupport") || cls.equals("java.lang.Object")
                    || cls.equals("java.util.concurrent.CompletableFuture") || cls.equals("java.util.concurrent.FutureTask"))) {
                return "thread";
            }
        }
        return null;
    }

    private static String hotMethod(StackTraceElement[] stack) {
        StackTraceElement frame = ProfileCapture.hotElement(stack);
        return frame == null ? "" : ProfileCapture.frameName(frame);
    }

    /** Путь от входа к вершине: начало и конец важнее середины. */
    private static List<String> framesOf(StackTraceElement[] stack, int entryIndex) {
        List<String> frames = new ArrayList<>();
        int from = Math.min(entryIndex, stack.length - 1);
        for (int i = from; i >= 0; i--) {
            frames.add(shortName(stack[i]));
        }
        if (frames.size() <= MAX_FRAMES) {
            return frames;
        }
        List<String> cut = new ArrayList<>(frames.subList(0, MAX_FRAMES / 2));
        cut.addAll(frames.subList(frames.size() - MAX_FRAMES / 2, frames.size()));
        return cut;
    }

    static String shortName(StackTraceElement f) {
        String cls = f.getClassName();
        String name = cls.substring(cls.lastIndexOf('.') + 1) + "." + f.getMethodName();
        return f.getLineNumber() > 0 ? name + ":" + f.getLineNumber() : name;
    }

    /** Содержимое поля {@code x} спайка без внешних событий: их добавляет {@link Profiler}. */
    static void appendJson(StringBuilder sb, Result r) {
        sb.append("\"n\":").append(r.samples()).append(",\"first\":").append(r.firstMs()).append(",\"gap\":").append(r.gapMs());
        if (r.signature() != null) {
            Json.str(sb.append(",\"sig\":"), r.signature(), 240);
        }
        if (r.lock() != null) {
            Json.str(sb.append(",\"lock\":"), r.lock(), 160);
        }
        sb.append(",\"paths\":[");
        double total = Math.max(1, r.samples());
        for (int i = 0; i < r.paths().size(); i++) {
            PathInfo path = r.paths().get(i);
            Json.str(sb.append(i > 0 ? ",{\"o\":" : "{\"o\":"), path.owner(), 65);
            Json.str(sb.append(",\"e\":"), path.entry(), 16);
            Json.decimal2(sb.append(",\"s\":"), Math.min(1, path.count() / total));
            if (path.waitOn() != null) {
                Json.str(sb.append(",\"w\":"), path.waitOn(), 16);
            }
            sb.append(",\"f\":[");
            for (int f = 0; f < path.frames().size(); f++) {
                Json.str(f > 0 ? sb.append(',') : sb, path.frames().get(f), 160);
            }
            sb.append("]}");
        }
        sb.append("],\"waits\":[");
        int w = 0;
        for (Map.Entry<String, Integer> wait : r.waits().entrySet()) {
            Json.str(sb.append(w++ > 0 ? ",{\"k\":" : "{\"k\":"), wait.getKey(), 16);
            Json.decimal2(sb.append(",\"s\":"), Math.min(1, wait.getValue() / total)).append('}');
        }
        sb.append(']');
    }
}
