package net.mcdiag.agent.collect;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Что нельзя отправлять в панель даже при включённом журнале. Вызывается до записи в буфер:
 * пароли, личные сообщения, токены и IP не покидают сервер.
 */
public final class TextMask {

    public static final String HIDDEN = "***";

    /** Авторизация и личные сообщения: остаётся только имя команды. */
    private static final Set<String> DEFAULT_HIDDEN = Set.of(
            "login", "l", "log", "register", "reg", "unregister", "unreg", "changepassword", "changepass", "cp",
            "passwd", "password", "2fa", "totp", "authme", "email",
            "msg", "m", "tell", "t", "w", "whisper", "pm", "dm", "r", "reply", "mail", "message",
            "emsg", "etell", "ewhisper", "er", "ereply");

    private static final Pattern TOKEN = Pattern.compile("mcd_[0-9a-f]{24}_[0-9a-f-]{36}");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");

    private final Set<String> hidden;

    /** @param extra команды из {@code chat_log.hidden_commands} в дополнение к встроенным */
    public TextMask(List<String> extra) {
        this.hidden = Stream.concat(DEFAULT_HIDDEN.stream(), extra.stream().map(TextMask::normalize))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Строка команды как её ввели: с «/» у игрока, без него в консоли. */
    public String command(String line) {
        String trimmed = line.trim();
        int space = trimmed.indexOf(' ');
        if (space < 0) {
            return text(trimmed);
        }
        String head = trimmed.substring(0, space);
        String label = normalize(head);
        if (label.equals("diag")) {
            String[] parts = trimmed.split("\\s+", 3);
            return parts.length > 2 && parts[1].equalsIgnoreCase("token") ? head + " token " + HIDDEN : text(trimmed);
        }
        return hidden.contains(label) ? head + " " + HIDDEN : text(trimmed);
    }

    /** Токены и IP в обычном тексте: чат, строки лога. */
    public String text(String value) {
        return IPV4.matcher(TOKEN.matcher(value).replaceAll(HIDDEN)).replaceAll(HIDDEN);
    }

    /** «/Essentials:MSG» → «msg»: регистр, ведущий «/» и пространство имён не важны. */
    static String normalize(String head) {
        String label = head.toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        int colon = label.indexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }
}
