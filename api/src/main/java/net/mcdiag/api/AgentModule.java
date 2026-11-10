package net.mcdiag.api;

import java.util.Set;

/**
 * Модуль агента MCDiag. Жизненным циклом управляет агент: модуль включается, когда он разрешён в
 * {@code config.yml} агента ({@code modules.<id>.enabled}), сервер на полном тарифе и панель
 * объявила все {@link #requiredCaps()}; выключается при обратном или при выключении плагина-владельца.
 *
 * <p>{@link #enable} и {@link #disable} вызываются в главном потоке. Исключение из {@link #enable}
 * переводит модуль в состояние «сбой» — агент и другие модули продолжают работать.
 */
public interface AgentModule {

    /** Уникальный идентификатор: {@code [a-z0-9_-]{1,32}}. */
    String id();

    /** Одна строка для {@code /diag modules}. */
    default String description() {
        return "";
    }

    /** Возможности панели, без которых модулю нечего отправлять. Пусто — работает всегда. */
    default Set<String> requiredCaps() {
        return Set.of();
    }

    void enable(ModuleContext ctx) throws Exception;

    /** Освободить ресурсы. Задачи из {@link ModuleContext#repeat} агент отменяет сам. */
    void disable();

    /** Короткое состояние для {@code /diag modules}, например «12 записей в очереди». */
    default String status() {
        return "";
    }
}
