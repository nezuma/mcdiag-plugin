package net.mcdiag.agent.profiler;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/**
 * Имя класса → плагин, которому он принадлежит, по индексу пакетов из jar-файлов плагинов.
 *
 * <p>Классы не загружаются: поиск через загрузчик Bukkit пишет в консоль предупреждения
 * «Loaded class … which is not a depend», а индекс по оглавлению jar — нет. Пакет, который есть
 * в нескольких jar (незатенённая библиотека), не приписывается никому: тогда сэмпл достанется
 * плагину, который вызвал библиотеку, — его кадр ниже по стеку.
 */
final class PluginLookup implements Function<String, String> {

    /** Пакет есть в нескольких jar */
    private static final String AMBIGUOUS = "";

    private final Logger log;
    private volatile Map<String, String> packages = Map.of();
    private volatile List<Prefix> mainPackages = List.of();

    PluginLookup(Logger log) {
        this.log = log;
    }

    /** Строит индекс заново. Вызывается из потока профилировщика: читает только оглавления jar. */
    void rebuild(Plugin[] plugins) {
        Map<String, String> index = new HashMap<>(4096);
        List<Prefix> mains = new ArrayList<>();
        for (Plugin p : plugins) {
            String name = p.getName();
            if (!ProfileWindow.validName(name)) {
                continue;
            }
            String main = p.getClass().getName();
            int dot = main.lastIndexOf('.');
            if (dot > 0) {
                mains.add(new Prefix(main.substring(0, dot + 1), name));
            }
            File jar = jarOf(p);
            if (jar == null) {
                continue;
            }
            try (JarFile file = new JarFile(jar)) {
                Enumeration<JarEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) {
                        continue;
                    }
                    int slash = entry.lastIndexOf('/');
                    if (slash <= 0) {
                        continue;
                    }
                    String pkg = entry.substring(0, slash).replace('/', '.');
                    String prev = index.putIfAbsent(pkg, name);
                    if (prev != null && !prev.equals(name)) {
                        index.put(pkg, AMBIGUOUS);
                    }
                }
            } catch (IOException | SecurityException e) {
                log.fine("profiler: cannot index " + jar.getName() + ": " + e.getMessage());
            }
        }
        // Длинные пакеты первыми: com.example.addon. важнее com.example.
        mains.sort((a, b) -> b.pkg().length() - a.pkg().length());
        packages = index;
        mainPackages = mains;
    }

    @Override
    public String apply(String className) {
        int dot = className.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String owner = packages.get(className.substring(0, dot));
        if (owner != null) {
            return owner.isEmpty() ? null : owner;
        }
        for (Prefix p : mainPackages) {
            if (className.startsWith(p.pkg())) {
                return p.plugin();
            }
        }
        return null;
    }

    int indexedPackages() {
        return packages.size();
    }

    private static File jarOf(Plugin p) {
        try {
            CodeSource source = p.getClass().getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            File file = new File(source.getLocation().toURI());
            return file.isFile() ? file : null;
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            return null;
        }
    }

    private record Prefix(String pkg, String plugin) {
    }
}
