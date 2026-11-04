package net.mcdiag.agent.remote;

/**
 * Команда диагностики. Приходит в ответе бэкенда на батч, только если её запросил сам владелец
 * сервера или он выдал поддержке доступ в обращении. Набор команд закрытый и только на чтение:
 * ничего на сервере они не меняют.
 *
 * @param seconds длительность для {@code profile} и {@code leak_scan}, иначе 0
 */
public record RemoteCommand(String id, String type, int seconds) {
}
