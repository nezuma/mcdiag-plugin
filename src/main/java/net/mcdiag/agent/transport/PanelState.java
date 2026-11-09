package net.mcdiag.agent.transport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Что умеет панель и что разрешил владелец — из ответа на батч. Новые поля отправляются, только
 * если панель о них объявила: старая панель отклоняет незнакомые поля вместе со всем батчем.
 */
public final class PanelState {

    private static final Pattern CAPS = Pattern.compile("\"caps\":\\[([^\\]]*)\\]");
    private static final Pattern CHATLOG = Pattern.compile("\"chatlog\":\\{(?:\"chat\":(true|false),)?\"cmd\":(true|false)\\}");

    private static final Pattern TIER = Pattern.compile("\"tier\":\"(full|free|frozen)\"");

    private volatile boolean richSpikes;
    private volatile boolean insight;
    private volatile boolean memory;
    private volatile boolean heatmap;
    private volatile boolean free;
    private volatile boolean commands;
    private volatile boolean chat;
    private volatile boolean nicks;
    private volatile boolean joins;
    private volatile boolean chunkgen;
    private volatile boolean economy;

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
        heatmap = list.contains("\"heatmap\"");
        nicks = list.contains("\"nicks\"");
        joins = list.contains("\"joins\"");
        chunkgen = list.contains("\"chunkgen\"");
        economy = list.contains("\"economy\"");
        Matcher tier = TIER.matcher(body);
        free = tier.find() && !tier.group(1).equals("full");
        commands = found && log.group(2).equals("true");
        // Чат принимает только панель 0.7.0+: прежние присылают chat:false.
        chat = found && "true".equals(log.group(1));
    }

    /** Панель отклонила батч (400): возвращаемся к прежнему формату до следующего ответа. */
    public void reset() {
        richSpikes = false;
        insight = false;
        memory = false;
        heatmap = false;
        free = false;
        commands = false;
        chat = false;
        nicks = false;
        joins = false;
        chunkgen = false;
        economy = false;
    }

    /** Панель принимает тепловую карту. */
    public boolean heatmap() {
        return heatmap;
    }

    /** Тариф закончился или сервер заморожен: агент почти ничего не делает и шлёт одну точку в 5 минут. */
    public boolean free() {
        return free;
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

    /** Владелец включил на сайте запись чата. */
    public boolean chat() {
        return chat;
    }

    /** Панель принимает ник игрока в событиях входа, выхода и смерти. */
    public boolean nicks() {
        return nicks;
    }

    /** Панель принимает IP и адрес подключения при входе. */
    public boolean joins() {
        return joins;
    }

    /** Панель принимает счётчик новых чанков (поле {@code chunkgen}). */
    public boolean chunkgen() {
        return chunkgen;
    }

    /** Панель принимает балансы игроков (поле {@code economy}). */
    public boolean economy() {
        return economy;
    }
}
