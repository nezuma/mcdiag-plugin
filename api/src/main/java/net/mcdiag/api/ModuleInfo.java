package net.mcdiag.api;

/**
 * Состояние модуля для {@code /diag modules} и {@link McDiagApi#modules()}.
 *
 * @param owner плагин, зарегистрировавший модуль
 * @param state {@code active}, {@code waiting} (ждёт панель или тариф), {@code off} или {@code failed}
 */
public record ModuleInfo(String id, String owner, String description, String state, String status) {
}
