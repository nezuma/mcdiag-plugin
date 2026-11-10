package net.mcdiag.api;

import java.util.Locale;

/** Серьёзность проблемы, как её показывает панель MCDiag. */
public enum Severity {
    /** Сервер или плагин не работает, данные под угрозой, возможный взлом. */
    CRITICAL,
    /** Функция сломана или заметно страдает производительность. */
    HIGH,
    /** Работает с ошибками или ограничениями. */
    MEDIUM,
    /** Стоит исправить при случае. */
    LOW,
    /** Для сведения. */
    INFO;

    /** Ключ в протоколе панели: {@code critical}, {@code high}, … */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
