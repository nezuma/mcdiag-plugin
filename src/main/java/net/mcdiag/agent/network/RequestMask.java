package net.mcdiag.agent.network;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Путь и параметры запроса для журнала: секреты скрыты, остальное видно как есть.
 *
 * <p>Скрываются значения параметров с «секретными» именами ({@code token}, {@code key}, …),
 * токены в путях вебхуков Discord и ботов Telegram и длинные «случайные» сегменты. Имя параметра
 * и длина значения остаются: видно, что именно передаётся, но не сам ключ. Чистые функции — для
 * unit-тестов.
 */
public final class RequestMask {

    public static final int MAX_EXAMPLE = 512;
    public static final int MAX_PATTERN = 200;
    private static final int MAX_VALUE = 200;
    private static final int SECRET_SEGMENT = 32;

    private static final String[] SECRET_NAMES = {
        "token", "key", "secret", "pass", "pwd", "auth", "sig", "session", "cookie", "bearer", "credential", "jwt",
    };
    private static final Pattern RANDOM = Pattern.compile("^[A-Za-z0-9_\\-.~%+=]{" + SECRET_SEGMENT + ",}$");
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern ID = Pattern.compile("^(\\d+|[0-9a-fA-F]{8,})$");

    private RequestMask() {
    }

    /** Путь со скрытыми токенами, ещё без замены идентификаторов. */
    static String maskPath(String host, String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        String[] segments = rawPath.split("/", -1);
        String h = host.toLowerCase(Locale.ROOT);
        boolean discord = h.equals("discord.com") || h.equals("discordapp.com") || h.endsWith(".discord.com") || h.endsWith(".discordapp.com");
        for (int i = 0; i < segments.length; i++) {
            String s = segments[i];
            if (s.isEmpty()) {
                continue;
            }
            // /api/webhooks/<id>/<token>: идентификатор оставляем, токен скрываем.
            if (discord && i >= 2 && "webhooks".equals(segments[i - 2])) {
                segments[i] = hidden(s);
            } else if (h.equals("api.telegram.org") && s.startsWith("bot") && s.indexOf(':') > 0) {
                segments[i] = "bot" + hidden(s.substring(3));
            } else if (RANDOM.matcher(s).matches() && !UUID.matcher(s).matches() && looksRandom(s)) {
                segments[i] = hidden(s);
            }
        }
        return String.join("/", segments);
    }

    /** Параметры: имя видно, значение секрета — нет; обычные значения обрезаются. */
    static String maskQuery(String rawQuery, Set<String> extraSecret) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(rawQuery.length(), MAX_EXAMPLE));
        for (String pair : rawQuery.split("&")) {
            if (out.length() > 0) {
                out.append('&');
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (eq < 0) {
                out.append(clip(name, 64));
                continue;
            }
            String value = pair.substring(eq + 1);
            out.append(clip(name, 64)).append('=');
            if (secretName(name, extraSecret) || (RANDOM.matcher(value).matches() && looksRandom(value))) {
                out.append(hidden(value));
            } else {
                out.append(value.length() > MAX_VALUE ? value.substring(0, MAX_VALUE) + "…(" + value.length() + ")" : value);
            }
        }
        return out.toString();
    }

    /** Шаблон для группировки: числа, UUID и hex → {id}. «/api/player/{id}/stats». */
    static String pattern(String maskedPath) {
        String[] segments = maskedPath.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String s = segments[i];
            if (UUID.matcher(s).matches() || ID.matcher(s).matches()) {
                segments[i] = "{id}";
            } else if (s.length() > 48) {
                segments[i] = "{…}";
            }
        }
        return clip(String.join("/", segments), MAX_PATTERN);
    }

    /** Полный пример: путь и параметры с маскировкой. */
    static String example(String maskedPath, String maskedQuery) {
        String full = maskedQuery.isEmpty() ? maskedPath : maskedPath + "?" + maskedQuery;
        return clip(full, MAX_EXAMPLE);
    }

    static boolean secretName(String name, Set<String> extra) {
        String n = name.toLowerCase(Locale.ROOT);
        if (extra.contains(n)) {
            return true;
        }
        for (String s : SECRET_NAMES) {
            if (n.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** Буквы и цифры вперемешку: так выглядят ключи и токены, а не слова и пути. */
    private static boolean looksRandom(String s) {
        int digits = 0;
        int upper = 0;
        int lower = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c >= 'A' && c <= 'Z') {
                upper++;
            } else if (c >= 'a' && c <= 'z') {
                lower++;
            }
        }
        int kinds = (digits > 0 ? 1 : 0) + (upper > 0 ? 1 : 0) + (lower > 0 ? 1 : 0);
        return kinds >= 2 && digits >= 3;
    }

    private static String hidden(String value) {
        return "***(" + value.length() + ")";
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
