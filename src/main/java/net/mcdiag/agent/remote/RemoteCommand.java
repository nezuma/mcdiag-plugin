package net.mcdiag.agent.remote;

/**
 * Команда диагностики от поддержки MCDiag. Приходит в ответе бэкенда на батч, только если
 * владелец сервера сам выдал поддержке доступ в обращении. Набор команд закрытый и только на чтение:
 * ничего на сервере они не меняют.
 *
 * @param seconds длительность для {@code profile}, иначе 0
 */
public record RemoteCommand(String id, String type, int seconds) {
}
