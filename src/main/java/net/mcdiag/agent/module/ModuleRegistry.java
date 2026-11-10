package net.mcdiag.agent.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.profiler.FrameClassifier;
import net.mcdiag.agent.profiler.PluginLookup;
import net.mcdiag.agent.transport.PanelState;
import net.mcdiag.api.AgentModule;
import net.mcdiag.api.McDiagApi;
import net.mcdiag.api.ModuleInfo;
import net.mcdiag.api.Severity;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Модули агента и их разделы батча. Включает модуль, когда его разрешает config.yml, тариф и
 * возможности панели; изолирует сбои — упавший модуль выключается, остальное работает.
 *
 * <p>{@link #sync()}, регистрация и снятие модулей — только в главном потоке.
 */
public final class ModuleRegistry implements McDiagApi, Listener {

    private static final Pattern ID = Pattern.compile("^[a-z0-9_-]{1,32}$");
    private static final Pattern CATEGORY = Pattern.compile("^[a-z0-9_.-]{1,48}$");
    private static final int MAX_MESSAGE = 512;
    private static final int MAX_STACK = 96;
    private static final StackWalker WALKER = StackWalker.getInstance();

    private final McDiagPlugin agent;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private volatile List<Channel> channelList = List.of();
    private volatile PluginLookup lookup;
    private volatile IssueSink issues;

    public ModuleRegistry(McDiagPlugin agent) {
        this.agent = agent;
    }

    // ── Каналы ──────────────────────────────────────────────────────────

    /** Раздел батча; повторный вызов с тем же именем возвращает существующий. */
    public synchronized Channel channel(String name, String cap, int capacity, int maxPerBatch) {
        Channel existing = channels.get(name);
        if (existing != null) {
            return existing;
        }
        Channel created = new Channel(name, cap, capacity, maxPerBatch);
        channels.put(name, created);
        channelList = List.copyOf(channels.values());
        return created;
    }

    /** Для сборки батча: читается из потока отправки. */
    public List<Channel> channels() {
        return channelList;
    }

    public boolean channelsEmpty() {
        for (Channel c : channelList) {
            if (!c.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Панель перестала принимать раздел (или отклонила батч): копить его незачем. */
    public void clearUnsupportedChannels() {
        PanelState panel = agent.panel();
        for (Channel c : channelList) {
            if (!panel.has(c.cap()) || panel.free()) {
                c.clear();
            }
        }
    }

    // ── Модули ──────────────────────────────────────────────────────────

    public void registerBuiltin(AgentModule module) {
        register(agent, module);
    }

    @Override
    public void registerModule(Plugin owner, AgentModule module) {
        if (owner == null || module == null) {
            throw new IllegalArgumentException("owner and module are required");
        }
        register(owner, module);
        if (Bukkit.isPrimaryThread()) {
            sync();
        } else {
            Bukkit.getScheduler().runTask(agent, this::sync);
        }
    }

    private synchronized void register(Plugin owner, AgentModule module) {
        String id = module.id();
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("module id must match [a-z0-9_-]{1,32}: " + id);
        }
        Entry existing = entries.get(id);
        if (existing != null && existing.owner != owner) {
            throw new IllegalStateException("module '" + id + "' is already registered by " + existing.owner.getName());
        }
        if (existing != null) {
            stop(existing, State.OFF);
        }
        entries.put(id, new Entry(owner, module));
    }

    @Override
    public void unregisterModules(Plugin owner) {
        List<Entry> removed = new ArrayList<>();
        synchronized (this) {
            entries.values().removeIf(e -> {
                if (e.owner == owner) {
                    removed.add(e);
                    return true;
                }
                return false;
            });
        }
        for (Entry e : removed) {
            stop(e, State.OFF);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != agent) {
            unregisterModules(event.getPlugin());
        }
    }

    /** Привести состояния модулей к настройкам, тарифу и возможностям панели. */
    public void sync() {
        PanelState panel = agent.panel();
        for (Entry e : snapshot()) {
            boolean configured = enabledInConfig(e.module.id());
            boolean allowed = configured && !panel.free() && e.owner.isEnabled()
                    && e.module.requiredCaps().stream().allMatch(panel::has);
            if (allowed && e.state != State.ACTIVE && e.state != State.FAILED) {
                start(e);
            } else if (!allowed && e.state == State.ACTIVE) {
                stop(e, configured ? State.WAITING : State.OFF);
            } else if (!allowed && e.state != State.FAILED) {
                e.state = configured ? State.WAITING : State.OFF;
            }
        }
        clearUnsupportedChannels();
    }

    /** {@code /diag reload}: модули со сбоем получают ещё одну попытку. */
    public void resetFailures() {
        for (Entry e : snapshot()) {
            if (e.state == State.FAILED) {
                e.state = State.OFF;
                e.failure = null;
            }
        }
    }

    public void stopAll() {
        for (Entry e : snapshot()) {
            stop(e, State.OFF);
        }
    }

    public boolean isActive(String id) {
        Entry e;
        synchronized (this) {
            e = entries.get(id);
        }
        return e != null && e.state == State.ACTIVE;
    }

    public boolean has(String id) {
        synchronized (this) {
            return entries.containsKey(id);
        }
    }

    public boolean enabledInConfig(String id) {
        return agent.getConfig().getBoolean("modules." + id + ".enabled", true);
    }

    private void start(Entry e) {
        AgentContext ctx = new AgentContext(this, e.module.id(), e.owner.getName());
        try {
            e.module.enable(ctx);
            e.ctx = ctx;
            e.state = State.ACTIVE;
            e.failure = null;
        } catch (Throwable t) {
            ctx.close();
            safeDisable(e);
            e.state = State.FAILED;
            e.failure = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            agent.getLogger().log(Level.WARNING, "Module '" + e.module.id() + "' failed to start and was turned off: " + e.failure);
            agent.getLogger().log(Level.FINE, "module start failure", t);
        }
    }

    private void stop(Entry e, State next) {
        if (e.state == State.ACTIVE) {
            AgentContext ctx = e.ctx;
            e.ctx = null;
            if (ctx != null) {
                ctx.close();
            }
            safeDisable(e);
        }
        e.state = next;
    }

    private void safeDisable(Entry e) {
        try {
            e.module.disable();
        } catch (Throwable t) {
            agent.getLogger().log(Level.FINE, "module disable failure", t);
        }
    }

    private synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    // ── McDiagApi ───────────────────────────────────────────────────────

    @Override
    public String agentVersion() {
        return agent.getDescription().getVersion();
    }

    @Override
    public List<ModuleInfo> modules() {
        List<ModuleInfo> out = new ArrayList<>();
        for (Entry e : snapshot()) {
            String status;
            try {
                status = e.state == State.FAILED ? e.failure : e.state == State.ACTIVE ? e.module.status() : "";
            } catch (RuntimeException ex) {
                status = "status failed: " + ex.getClass().getSimpleName();
            }
            out.add(new ModuleInfo(e.module.id(), e.owner.getName(), e.module.description(), e.state.key, status == null ? "" : status));
        }
        return out;
    }

    @Override
    public void report(Plugin source, Severity severity, String category, String message) {
        reportFrom(source == null ? null : source.getName(), severity, category, message);
    }

    void reportFrom(String plugin, Severity severity, String category, String message) {
        if (severity == null || category == null || !CATEGORY.matcher(category).matches()
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException("severity, category [a-z0-9_.-]{1,48} and message are required");
        }
        IssueSink sink = issues;
        if (sink != null) {
            sink.report(plugin, severity, category, message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message);
        }
    }

    public void setIssueSink(IssueSink sink) {
        this.issues = sink;
    }

    // ── Атрибуция плагинов ──────────────────────────────────────────────

    /**
     * Индекс «пакет → плагин» сразу, при включении агента: все плагины к этому моменту загружены, а
     * большинство ещё не включено — их ошибки и соединения при запуске уже будут подписаны.
     * Читаются только оглавления jar-ов.
     */
    public void buildLookupNow() {
        try {
            PluginLookup next = new PluginLookup(agent.getLogger());
            next.rebuild(Bukkit.getPluginManager().getPlugins());
            lookup = next;
        } catch (RuntimeException e) {
            agent.getLogger().log(Level.FINE, "plugin index failed", e);
        }
    }

    /** Обновление индекса в фоне: плагины могли догрузить после старта. */
    public void rebuildLookup() {
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        Bukkit.getScheduler().runTaskAsynchronously(agent, () -> {
            try {
                PluginLookup next = new PluginLookup(agent.getLogger());
                next.rebuild(plugins);
                lookup = next;
            } catch (RuntimeException e) {
                agent.getLogger().log(Level.FINE, "plugin index failed", e);
            }
        });
    }

    public String pluginOfClass(String className) {
        PluginLookup current = lookup;
        if (current == null || className == null || FrameClassifier.isNeutral(className) || FrameClassifier.isCore(className)) {
            return null;
        }
        String owner = current.apply(className);
        return owner == null || owner.equals(agent.getName()) ? null : owner;
    }

    /** Первый кадр стека, принадлежащий стороннему плагину. */
    public String callerPlugin() {
        PluginLookup current = lookup;
        if (current == null) {
            return null;
        }
        Optional<String> found = WALKER.walk(frames -> frames.limit(MAX_STACK)
                .map(StackWalker.StackFrame::getClassName)
                .map(this::pluginOfClass)
                .filter(p -> p != null)
                .findFirst());
        return found.orElse(null);
    }

    McDiagPlugin agent() {
        return agent;
    }

    PanelState panel() {
        return agent.panel();
    }

    public Collection<String> ids() {
        synchronized (this) {
            return List.copyOf(entries.keySet());
        }
    }

    private enum State {
        OFF("off"), WAITING("waiting"), ACTIVE("active"), FAILED("failed");

        final String key;

        State(String key) {
            this.key = key;
        }
    }

    private static final class Entry {
        final Plugin owner;
        final AgentModule module;
        volatile State state = State.OFF;
        volatile AgentContext ctx;
        volatile String failure;

        Entry(Plugin owner, AgentModule module) {
            this.owner = owner;
            this.module = module;
        }
    }
}
