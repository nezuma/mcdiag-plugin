package net.mcdiag.agent.buffer;

import java.util.ArrayDeque;

/**
 * Ограниченный FIFO-буфер готовых JSON-фрагментов.
 *
 * <p>Пишут основной поток (события игроков), любые потоки (лог-хендлер) и async-таск метрик,
 * читает async-отправщик — поэтому все операции под одним монитором. Критические секции короткие:
 * offer/poll и копирование строк в StringBuilder.
 */
public final class TelemetryBuffer {

    /** Бэкенд принимает ≤ 60 точек на батч; 60 × 5 с = 5 минут без связи. */
    public static final int MAX_METRICS = 60;
    /** Минутные срезы профилировщика: бэкенд принимает ≤ 10 на батч, 10 минут без связи. */
    public static final int MAX_PROFILES = 10;
    /** Системные срезы (раз в минуту) и ответы на команды поддержки */
    public static final int MAX_SYSTEM = 10;
    public static final int MAX_RESULTS = 10;
    /** Защита от элемента, который никогда не влезет в батч: такой выбрасывается при чтении. */
    private static final int MAX_ELEMENT_CHARS = 32 * 1024;

    private final Object lock = new Object();
    private final ArrayDeque<String> events;
    private final ArrayDeque<String> metrics = new ArrayDeque<>(MAX_METRICS);
    private final ArrayDeque<String> profiles = new ArrayDeque<>(MAX_PROFILES);
    private final ArrayDeque<String> system = new ArrayDeque<>(MAX_SYSTEM);
    private final ArrayDeque<String> results = new ArrayDeque<>(MAX_RESULTS);
    /** Список плагинов сервера: важна только последняя версия */
    private String inventory;
    private int maxEvents;
    private long chars;
    private long dropped;

    public TelemetryBuffer(int maxEvents) {
        this.maxEvents = maxEvents;
        this.events = new ArrayDeque<>(maxEvents);
    }

    public void addEvent(String json) {
        synchronized (lock) {
            while (events.size() >= maxEvents) {
                evictOldestEvent();
            }
            events.addLast(json);
            chars += json.length();
        }
    }

    public void addMetric(String json) {
        synchronized (lock) {
            if (metrics.size() >= MAX_METRICS) {
                chars -= metrics.pollFirst().length();
                dropped++;
            }
            metrics.addLast(json);
            chars += json.length();
        }
    }

    public void addProfile(String json) {
        addBounded(profiles, MAX_PROFILES, json);
    }

    public void addSystem(String json) {
        addBounded(system, MAX_SYSTEM, json);
    }

    public void addResult(String json) {
        addBounded(results, MAX_RESULTS, json);
    }

    private void addBounded(ArrayDeque<String> queue, int max, String json) {
        synchronized (lock) {
            if (queue.size() >= max) {
                chars -= queue.pollFirst().length();
                dropped++;
            }
            queue.addLast(json);
            chars += json.length();
        }
    }

    public void setInventory(String json) {
        synchronized (lock) {
            if (inventory != null) {
                chars -= inventory.length();
            }
            inventory = json;
            chars += json.length();
        }
    }

    public void resize(int maxEvents) {
        synchronized (lock) {
            this.maxEvents = maxEvents;
            while (events.size() > maxEvents) {
                evictOldestEvent();
            }
        }
    }

    /**
     * Переносит события в {@code sb} через запятую, пока не наберётся {@code maxCount} штук или
     * следующее не вылезет за {@code maxChars}.
     *
     * @return сколько событий перенесено
     */
    public int drainEvents(StringBuilder sb, int maxCount, int maxChars) {
        synchronized (lock) {
            return drain(events, sb, maxCount, maxChars);
        }
    }

    public int drainMetrics(StringBuilder sb, int maxChars) {
        synchronized (lock) {
            return drain(metrics, sb, MAX_METRICS, maxChars);
        }
    }

    public int drainProfiles(StringBuilder sb, int maxChars) {
        synchronized (lock) {
            return drain(profiles, sb, MAX_PROFILES, maxChars);
        }
    }

    public int drainSystem(StringBuilder sb, int maxChars) {
        synchronized (lock) {
            return drain(system, sb, MAX_SYSTEM, maxChars);
        }
    }

    public int drainResults(StringBuilder sb, int maxChars) {
        synchronized (lock) {
            return drain(results, sb, MAX_RESULTS, maxChars);
        }
    }

    /** Забирает список плагинов, если он есть и помещается в {@code maxChars}; иначе null. */
    public String takeInventory(int maxChars) {
        synchronized (lock) {
            if (inventory == null || inventory.length() > maxChars) {
                return null;
            }
            String out = inventory;
            chars -= out.length();
            inventory = null;
            return out;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return events.isEmpty() && metrics.isEmpty() && profiles.isEmpty() && system.isEmpty()
                    && results.isEmpty() && inventory == null;
        }
    }

    public Stats stats() {
        synchronized (lock) {
            return new Stats(events.size(), maxEvents, metrics.size(), profiles.size(), chars, dropped);
        }
    }

    public void countDropped(int n) {
        synchronized (lock) {
            dropped += n;
        }
    }

    private int drain(ArrayDeque<String> queue, StringBuilder sb, int maxCount, int maxChars) {
        int n = 0;
        while (n < maxCount) {
            String next = queue.peekFirst();
            if (next == null) {
                break;
            }
            if (next.length() > MAX_ELEMENT_CHARS) {
                queue.pollFirst();
                chars -= next.length();
                dropped++;
                continue;
            }
            if (next.length() + 1 > maxChars) {
                break;
            }
            queue.pollFirst();
            chars -= next.length();
            if (n > 0) {
                sb.append(',');
            }
            sb.append(next);
            maxChars -= next.length() + 1;
            n++;
        }
        return n;
    }

    private void evictOldestEvent() {
        chars -= events.pollFirst().length();
        dropped++;
    }

    /** Снимок для /diag status. {@code chars} — суммарная длина строк (≈ байты, всё ASCII). */
    public record Stats(int events, int maxEvents, int metrics, int profiles, long chars, long dropped) {

        /** Грубая оценка: Latin-1 строки — байт на символ + ~56 байт заголовков String/byte[]. */
        public long approxBytes() {
            return chars + (long) (events + metrics + profiles) * 56;
        }
    }
}
