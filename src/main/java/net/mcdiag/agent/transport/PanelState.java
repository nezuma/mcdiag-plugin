package net.mcdiag.agent.transport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Что умеет панель и что разрешил владелец — из ответа на батч. Новые поля отправляются, только
 * если панель о них объявила: старая панель отклоняет незнакомые поля вместе со всем батчем.
 */
public final class PanelState {

    private static final Pattern CAPS = Pattern.compile("\"caps\":\\[([^\\]]*)\\]");
    private static final Pattern CHATLOG = Pattern.compile("\"chatlog\":\\{\"chat\":(true|false),\"cmd\":(true|false)\\}");

    private volatile boolean richSpikes;
    private volatile boolean chat;
    private volatile boolean commands;

    /** Успешный ответ панели. Нет {@code caps} — панель старая, работаем в прежнем формате. */
    public void update(String body) {
        if (body == null) {
            return;
        }
        Matcher caps = CAPS.matcher(body);
        String list = caps.find() ? caps.group(1) : "";
        richSpikes = list.contains("\"spikes\"");
        Matcher log = CHATLOG.matcher(body);
        boolean found = list.contains("\"chatlog\"") && log.find();
        chat = found && log.group(1).equals("true");
        commands = found && log.group(2).equals("true");
    }

    /** Панель отклонила батч (400): возвращаемся к прежнему формату до следующего ответа. */
    public void reset() {
        richSpikes = false;
        chat = false;
        commands = false;
    }

    public boolean richSpikes() {
        return richSpikes;
    }

    public boolean chat() {
        return chat;
    }

    public boolean commands() {
        return commands;
    }
}
