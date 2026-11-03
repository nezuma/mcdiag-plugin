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

    private static final int MAX_CACHE = 50_000;

    private final Function<String, String> pluginOf;
    private final Map<String, Owner> owners = new HashMap<>(4096);

    /** Всё, что нужно знать о классе; считается один раз, дальше — только поиск в HashMap. */
    private record Owner(String plugin, String category, boolean neutral) {
    }

    /** @param pluginOf имя класса → имя плагина или null; вызывается только для незнакомых классов */
    public StackAttributor(Function<String, String> pluginOf) {
        this.pluginOf = pluginOf;
    }

    /** @return имя плагина или ключ части ядра («~entities») */
    public String attribute(StackTraceElement[] stack) {
        String category = null;
        for (StackTraceElement frame : stack) {
            Owner owner = ownerOf(frame.getClassName());
            if (owner.plugin != null) {
                return owner.plugin;
            }
            if (category == null && !owner.neutral) {
                category = owner.category;
            }
        }
        return category != null ? category : FrameClassifier.OTHER;
    }

    /** Только плагин (для асинхронных потоков): null, если в стеке нет кода плагинов. */
    public String pluginIn(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String plugin = ownerOf(frame.getClassName()).plugin;
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

    private Owner ownerOf(String cls) {
        Owner cached = owners.get(cls);
        if (cached == null) {
            if (owners.size() >= MAX_CACHE) {
                owners.clear();
            }
            boolean neutral = FrameClassifier.isNeutral(cls);
            String plugin = neutral || FrameClassifier.isCore(cls) ? null : pluginOf.apply(cls);
            cached = new Owner(plugin, neutral ? null : FrameClassifier.category(cls), neutral);
            owners.put(cls, cached);
        }
        return cached;
    }
}
