package net.mcdiag.agent.profiler;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Чей это сэмпл: идём по стеку от вершины и берём первый кадр кода плагина. Если плагина в стеке
 * нет — первая узнаваемая часть ядра, иначе {@link FrameClassifier#OTHER}.
 *
 * <p>Владелец класса вычисляется один раз и кешируется. Работает только в потоке профилировщика,
 * поэтому обычная HashMap без синхронизации.
 */
public final class StackAttributor {

    /** Значение кеша «класс ядра или JDK, не плагин» */
    private static final String NOT_PLUGIN = "";
    private static final int MAX_CACHE = 50_000;

    private final Function<String, String> pluginOf;
    private final Map<String, String> owners = new HashMap<>(4096);

    /** @param pluginOf имя класса → имя плагина или null; вызывается только для незнакомых классов */
    public StackAttributor(Function<String, String> pluginOf) {
        this.pluginOf = pluginOf;
    }

    /** @return имя плагина или ключ части ядра («~entities») */
    public String attribute(StackTraceElement[] stack) {
        String category = null;
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            String plugin = pluginFor(cls);
            if (plugin != null) {
                return plugin;
            }
            if (category == null && !FrameClassifier.isNeutral(cls)) {
                category = FrameClassifier.category(cls);
            }
        }
        return category != null ? category : FrameClassifier.OTHER;
    }

    /** Только плагин (для асинхронных потоков): null, если в стеке нет кода плагинов. */
    public String pluginIn(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String plugin = pluginFor(frame.getClassName());
            if (plugin != null) {
                return plugin;
            }
        }
        return null;
    }

    /** После загрузки или выгрузки плагинов старые ответы могут быть неверны. */
    public void reset() {
        owners.clear();
    }

    public int cacheSize() {
        return owners.size();
    }

    private String pluginFor(String cls) {
        String cached = owners.get(cls);
        if (cached == null) {
            if (owners.size() >= MAX_CACHE) {
                owners.clear();
            }
            String resolved = FrameClassifier.isNeutral(cls) || FrameClassifier.isCore(cls) ? null : pluginOf.apply(cls);
            cached = resolved == null ? NOT_PLUGIN : resolved;
            owners.put(cls, cached);
        }
        return cached.isEmpty() ? null : cached;
    }
}
