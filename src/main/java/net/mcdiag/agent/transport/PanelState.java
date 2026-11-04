package net.mcdiag.agent.transport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Что умеет панель и что разрешил владелец — из ответа на батч. Новые поля отправляются, только
 * если панель о них объявила: старая панель отклоняет незнакомые поля вместе со всем батчем.
 */
public final class PanelState {

    private static final Pattern CAPS = Pattern.compile("\"caps\":\\[([^\\]]*)\\]");
    private static final Pattern CHATLOG = Pattern.compile("\"chatlog\":\\{(?:\"chat\":(?:true|false),)?\"cmd\":(true|false)\\}");

    private volatile boolean richSpikes;
    private volatile boolean insight;
    private volatile boolean memory;
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
        insight = list.contains("\"insight\"");
        memory = list.contains("\"memory\"");
        commands = found && log.group(1).equals("true");
    }

    /** Панель отклонила батч (400): возвращаемся к прежнему формату до следующего ответа. */
    public void reset() {
        richSpikes = false;
        insight = false;
        memory = false;
        commands = false;
    }

    public boolean richSpikes() {
        return richSpikes;
    }

    /** Панель понимает подробный разбор спайка (поле {@code x}). */
    public boolean insight() {
        return insight;
    }

    /** Панель понимает карту памяти (поле {@code memory} батча). */
    public boolean memory() {
        return memory;
    }

    public boolean commands() {
        return commands;
    }
}
