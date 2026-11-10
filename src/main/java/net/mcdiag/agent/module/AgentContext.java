package net.mcdiag.agent.module;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.mcdiag.agent.util.Safe;
import net.mcdiag.api.ModuleContext;
import net.mcdiag.api.Severity;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Контекст одного включения модуля. Задачи отменяются в {@link #close()}. */
final class AgentContext implements ModuleContext {

    private final ModuleRegistry registry;
    private final String moduleId;
    private final String owner;
    private final List<BukkitTask> tasks = new ArrayList<>();

    AgentContext(ModuleRegistry registry, String moduleId, String owner) {
        this.registry = registry;
        this.moduleId = moduleId;
        this.owner = owner;
    }

    @Override
    public Plugin agent() {
        return registry.agent();
    }

    @Override
    public Logger logger() {
        return registry.agent().getLogger();
    }

    @Override
    public ConfigurationSection config() {
        ConfigurationSection section = registry.agent().getConfig().getConfigurationSection("modules." + moduleId);
        return section != null ? section : new MemoryConfiguration();
    }

    @Override
    public boolean panelSupports(String cap) {
        return registry.panel().has(cap);
    }

    @Override
    public String pluginOfClass(String className) {
        return registry.pluginOfClass(className);
    }

    @Override
    public String callerPlugin() {
        return registry.callerPlugin();
    }

    @Override
    public boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public synchronized BukkitTask repeat(Runnable task, long delayTicks, long periodTicks, boolean async) {
        Runnable safe = Safe.task(logger(), moduleId, task);
        BukkitTask scheduled = async
                ? Bukkit.getScheduler().runTaskTimerAsynchronously(registry.agent(), safe, delayTicks, periodTicks)
                : Bukkit.getScheduler().runTaskTimer(registry.agent(), safe, delayTicks, periodTicks);
        tasks.add(scheduled);
        return scheduled;
    }

    @Override
    public void report(Severity severity, String category, String message) {
        registry.reportFrom(owner, severity, category, message);
    }

    synchronized void close() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }
}
