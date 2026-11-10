package net.mcdiag.api;

import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Что агент даёт модулю. Экземпляр живёт от {@link AgentModule#enable} до {@link AgentModule#disable}. */
public interface ModuleContext {

    /** Плагин агента MCDiag. */
    Plugin agent();

    Logger logger();

    /** Раздел {@code modules.<id>} из config.yml агента; пустой, если его нет. */
    ConfigurationSection config();

    /** Объявила ли панель возможность {@code cap} в последнем ответе. */
    boolean panelSupports(String cap);

    /** Какому плагину принадлежит класс (по оглавлению jar-ов), или null. */
    String pluginOfClass(String className);

    /** Ближайший по стеку вызова плагин, кроме самого агента, или null. */
    String callerPlugin();

    boolean isMainThread();

    /**
     * Повторяющаяся задача от имени агента. Исключение в ней не попадает в консоль сервера.
     * Отменяется автоматически при выключении модуля.
     */
    BukkitTask repeat(Runnable task, long delayTicks, long periodTicks, boolean async);

    /** То же, что {@link McDiagApi#report}, от имени модуля. */
    void report(Severity severity, String category, String message);
}
