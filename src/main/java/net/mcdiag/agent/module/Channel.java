package net.mcdiag.agent.module;

import java.util.ArrayDeque;

/**
 * Раздел батча, который наполняет модуль: ограниченная FIFO-очередь готовых JSON-объектов.
 * Раздел попадает в батч, только если панель объявила возможность {@link #cap()}: старая панель
 * отклонила бы незнакомое поле вместе со всем батчем.
 */
public final class Channel {

    private final String name;
    private final String cap;
    private final int capacity;
    private final int maxPerBatch;
    private final ArrayDeque<String> queue;
    private long dropped;

    public Channel(String name, String cap, int capacity, int maxPerBatch) {
        this.name = name;
        this.cap = cap;
        this.capacity = capacity;
        this.maxPerBatch = maxPerBatch;
        this.queue = new ArrayDeque<>(Math.min(capacity, 64));
    }

    public String name() {
        return name;
    }

    public String cap() {
        return cap;
    }

    public synchronized void offer(String json) {
        if (queue.size() >= capacity) {
            queue.pollFirst();
            dropped++;
        }
        queue.addLast(json);
    }

    /** Через запятую в {@code sb}, пока помещается в {@code maxChars}. @return сколько перенесено */
    public synchronized int drain(StringBuilder sb, int maxChars) {
        int n = 0;
        while (n < maxPerBatch) {
            String next = queue.peekFirst();
            if (next == null) {
                break;
            }
            if (next.length() + 1 > maxChars) {
                // Элемент, который не поместится даже в пустой батч, только блокировал бы очередь.
                if (n == 0 && next.length() > 32 * 1024) {
                    queue.pollFirst();
                    dropped++;
                    continue;
                }
                break;
            }
            queue.pollFirst();
            if (n > 0) {
                sb.append(',');
            }
            sb.append(next);
            maxChars -= next.length() + 1;
            n++;
        }
        return n;
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized void clear() {
        queue.clear();
    }
}
