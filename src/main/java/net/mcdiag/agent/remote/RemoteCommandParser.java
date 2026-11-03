package net.mcdiag.agent.remote;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Достаёт команды из ответа бэкенда без JSON-библиотеки. Бэкенд сериализует каждую команду ровно
 * как {@code {"id":"<uuid>","type":"<type>","seconds":<n>}} (поле seconds — только у profile);
 * всё, что не совпадает с шаблоном, игнорируется.
 */
public final class RemoteCommandParser {

    /** Не больше команд за один ответ: защита от неожиданного ответа */
    static final int MAX_COMMANDS = 5;
    private static final Pattern COMMAND = Pattern.compile(
            "\\{\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\","
                    + "\"type\":\"([a-z_]{1,32})\"(?:,\"seconds\":(\\d{1,3}))?\\}");

    private RemoteCommandParser() {
    }

    public static List<RemoteCommand> parse(String body) {
        if (body == null) {
            return List.of();
        }
        int at = body.indexOf("\"commands\":[");
        if (at < 0) {
            return List.of();
        }
        int end = body.indexOf(']', at);
        String section = end < 0 ? body.substring(at) : body.substring(at, end);
        List<RemoteCommand> out = new ArrayList<>(2);
        Matcher m = COMMAND.matcher(section);
        while (m.find() && out.size() < MAX_COMMANDS) {
            out.add(new RemoteCommand(m.group(1), m.group(2), m.group(3) == null ? 0 : Integer.parseInt(m.group(3))));
        }
        return out;
    }
}
