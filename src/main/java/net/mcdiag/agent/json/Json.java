package net.mcdiag.agent.json;

/**
 * Ручная сборка JSON без библиотек.
 *
 * <p>Всё, что не печатный ASCII, экранируется как {@code \\uXXXX}: длина строки в символах равна
 * длине тела в байтах, поэтому лимит бэкенда (64 KB) проверяется простым {@code length()}.
 */
public final class Json {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {
    }

    /** Строковое значение в кавычках, обрезанное до {@code maxLen} исходных символов. */
    public static StringBuilder str(StringBuilder sb, String s, int maxLen) {
        sb.append('"');
        int end = Math.min(s.length(), maxLen);
        if (end < s.length() && end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append("\\u")
                                .append(HEX[(c >> 12) & 0xf])
                                .append(HEX[(c >> 8) & 0xf])
                                .append(HEX[(c >> 4) & 0xf])
                                .append(HEX[c & 0xf]);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"');
    }

    /** Имя Bukkit-enum под шаблон бэкенда {@code ^[A-Z_]+$}; null, если ничего не осталось. */
    public static String enumName(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder out = null;
        int len = Math.min(name.length(), 64);
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || c == '_';
            if (!ok && out == null) {
                out = new StringBuilder(len).append(name, 0, i);
            }
            if (out != null && ok) {
                out.append(c);
            }
        }
        String result = out == null ? name.substring(0, len) : out.toString();
        return result.isEmpty() ? null : result;
    }

    /** Неотрицательное число с двумя знаками после точки без String.format. */
    public static StringBuilder decimal2(StringBuilder sb, double value) {
        if (!(value > 0)) {
            return sb.append('0');
        }
        long scaled = Math.round(value * 100);
        long cents = scaled % 100;
        sb.append(scaled / 100).append('.');
        if (cents < 10) {
            sb.append('0');
        }
        return sb.append(cents);
    }
}
