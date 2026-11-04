package net.mcdiag.agent.insight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import net.mcdiag.agent.json.Json;

/**
 * Поиск утечки по запросу владельца: какой код создал объекты, которые долго не освобождаются.
 * Снимка кучи здесь нет: он останавливает сервер. JFR отслеживает небольшую выборку объектов прямо
 * при выделении, а пути до GC roots отключены (cutoff 0), потому что только они требуют остановки.
 */
public final class LeakScan {

    private static final int MAX_SITES = 15;
    private static final int MAX_FRAMES = 6;

    /** Одно место создания объектов. */
    record Site(String plugin, String type, List<String> frames, int count, long oldestMs) {
    }

    private LeakScan() {
    }

    /** @return поле {@code data} ответа на команду {@code leak_scan} */
    public static String run(int seconds, Function<String, String> pluginOfClass) throws InterruptedException, IOException {
        Path file = Files.createTempFile("mcdiag-leak", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.OldObjectSample").with("cutoff", "0 ns").withStackTrace();
            recording.setMaxSize(8L * 1024 * 1024);
            recording.setMaxAge(Duration.ofSeconds(seconds + 60L));
            recording.start();
            Thread.sleep(seconds * 1000L);
            // События о долгоживущих объектах JVM записывает в момент остановки записи.
            recording.stop();
            recording.dump(file);
            return toJson(seconds, read(file, pluginOfClass));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static List<Site> read(Path file, Function<String, String> pluginOfClass) throws IOException {
        Map<String, Site> sites = new HashMap<>();
        try (RecordingFile events = new RecordingFile(file)) {
            while (events.hasMoreEvents()) {
                RecordedEvent event = events.readEvent();
                if (!event.getEventType().getName().equals("jdk.OldObjectSample") || event.getStackTrace() == null) {
                    continue;
                }
                List<String> frames = new ArrayList<>(MAX_FRAMES);
                String plugin = null;
                for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                    if (frame.getMethod() == null || frame.getMethod().getType() == null) {
                        continue;
                    }
                    String cls = frame.getMethod().getType().getName();
                    if (plugin == null) {
                        plugin = pluginOfClass.apply(cls);
                    }
                    if (frames.size() < MAX_FRAMES) {
                        frames.add(cls.substring(cls.lastIndexOf('.') + 1) + "." + frame.getMethod().getName());
                    }
                }
                Object typeName = event.getValue("object.type.name");
                String type = JfrInsight.readableClass(typeName == null ? null : typeName.toString());
                long age = event.getDuration("objectAge").toMillis();
                String key = plugin + "|" + type + "|" + String.join("<", frames);
                Site old = sites.get(key);
                sites.put(key, new Site(plugin, type, frames, old == null ? 1 : old.count() + 1,
                        old == null ? age : Math.max(old.oldestMs(), age)));
            }
        }
        List<Site> sorted = new ArrayList<>(sites.values());
        sorted.sort((a, b) -> b.count() - a.count());
        return sorted.size() > MAX_SITES ? sorted.subList(0, MAX_SITES) : sorted;
    }

    static String toJson(int seconds, List<Site> sites) {
        int total = 0;
        for (Site site : sites) {
            total += site.count();
        }
        StringBuilder sb = new StringBuilder(2048).append("{\"seconds\":").append(seconds)
                .append(",\"samples\":").append(total).append(",\"sites\":[");
        for (int i = 0; i < sites.size(); i++) {
            Site site = sites.get(i);
            sb.append(i > 0 ? ",{" : "{");
            if (site.plugin() != null) {
                Json.str(sb.append("\"n\":"), site.plugin(), 64).append(',');
            }
            Json.str(sb.append("\"c\":"), site.type(), 120).append(",\"k\":").append(site.count())
                    .append(",\"age\":").append(site.oldestMs() / 1000).append(",\"f\":[");
            for (int f = 0; f < site.frames().size(); f++) {
                Json.str(f > 0 ? sb.append(',') : sb, site.frames().get(f), 120);
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }
}
