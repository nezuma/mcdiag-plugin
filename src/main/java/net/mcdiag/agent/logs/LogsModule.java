package net.mcdiag.agent.logs;

import java.util.Locale;
import java.util.Set;
import net.mcdiag.agent.module.Channel;
import net.mcdiag.agent.module.ModuleRegistry;
import net.mcdiag.api.AgentModule;
import net.mcdiag.api.ModuleContext;

/**
 * Журнал предупреждений и ошибок сервера. Перехват ставится ещё в {@code onLoad} агента, чтобы
 * увидеть ошибки запуска других плагинов; модуль только раз в 15 секунд переносит сгруппированные
 * записи в раздел {@code logs} батча. Серьёзность и влияние определяет панель по своим правилам.
 */
public final class LogsModule implements AgentModule {

    public static final String ID = "logs";
    private static final long DRAIN_TICKS = 15 * 20;
    private static final long ENSURE_TICKS = 60 * 20;

    private final LogCapture capture;
    private final LogTaps.Tap tap;
    private final ModuleRegistry registry;
    private volatile Channel channel;

    public LogsModule(LogCapture capture, LogTaps.Tap tap, ModuleRegistry registry) {
        this.capture = capture;
        this.tap = tap;
        this.registry = registry;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "все WARN и ERROR сервера и плагинов, сгруппированные по проблеме";
    }

    @Override
    public Set<String> requiredCaps() {
        return Set.of("logs");
    }

    @Override
    public void enable(ModuleContext ctx) {
        channel = registry.channel("logs", "logs", 400, 200);
        capture.setErrorsOnly("ERROR".equals(ctx.config().getString("min_level", "WARN").toUpperCase(Locale.ROOT)));
        capture.setAccepting(true);
        ctx.repeat(this::drain, DRAIN_TICKS, DRAIN_TICKS, true);
        ctx.repeat(tap::ensure, ENSURE_TICKS, ENSURE_TICKS, true);
    }

    @Override
    public void disable() {
        capture.setAccepting(false);
        Channel current = channel;
        if (current != null) {
            current.clear();
        }
    }

    @Override
    public String status() {
        Channel current = channel;
        return "source " + tap.name() + ", " + capture.aggregator().pending() + " groups this window"
                + (current == null ? "" : ", " + current.size() + " queued")
                + ", dropped " + capture.aggregator().dropped();
    }

    void drain() {
        Channel current = channel;
        if (current == null) {
            return;
        }
        for (String json : capture.drain(System.currentTimeMillis())) {
            current.offer(json);
        }
    }
}
