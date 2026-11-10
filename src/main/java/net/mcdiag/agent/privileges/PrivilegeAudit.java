package net.mcdiag.agent.privileges;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.mcdiag.agent.collect.Nicks;
import net.mcdiag.agent.json.Json;
import net.mcdiag.api.Severity;

/**
 * Журнал выдачи прав: сопоставляет изменения списка OP и прав с командами, которые их объясняют.
 * OP, появившийся без команды {@code /op}, — признак бэкдора в плагине. Без Bukkit: события
 * приходят из модуля, записи уходят в {@code out} как JSON раздела {@code audit}.
 */
public final class PrivilegeAudit {

    /** За сколько до изменения должна быть команда, чтобы считаться его причиной. */
    static final long COMMAND_WINDOW_MS = 30_000;
    /** Изменение в LuckPerms без записи в его журнале за это время — выдано через API. */
    static final long LP_LOG_WINDOW_MS = 5_000;
    private static final int MAX_RECENT = 64;
    private static final int MAX_SEEN = 1024;
    private static final int MAX_TEXT = 256;

    private final Consumer<String> out;
    private final Set<String> extraDangerous;
    private final ArrayDeque<OpCommand> opCommands = new ArrayDeque<>();
    private final ArrayDeque<LogMark> lpLogs = new ArrayDeque<>();
    private final ArrayDeque<PendingNode> lpPending = new ArrayDeque<>();
    private final Set<String> seenAttachments = new HashSet<>();
    private Map<UUID, String> ops;

    public PrivilegeAudit(Consumer<String> out, Set<String> extraDangerous) {
        this.out = out;
        this.extraDangerous = extraDangerous;
    }

    /** Отправитель команды. */
    public enum Actor {
        PLAYER("player"), CONSOLE("console"), RCON("rcon"), COMMAND_BLOCK("command_block"), OTHER("other"), NONE("none");

        final String key;

        Actor(String key) {
            this.key = key;
        }
    }

    /** Любая команда управления правами: пишется в журнал и запоминается для сопоставления с OP. */
    public synchronized void command(long ts, Actor actor, String actorName, String line, String masked, String ownerPlugin) {
        String label = AdminCommands.label(line);
        if (!AdminCommands.isAdmin(label)) {
            return;
        }
        String target = AdminCommands.opTarget(line);
        if (target != null) {
            opCommands.addLast(new OpCommand(ts, label, target.toLowerCase(Locale.ROOT), actor, actorName));
            trim(opCommands);
        }
        boolean dangerous = AdminCommands.dangerousCommand(line, extraDangerous);
        StringBuilder sb = start(ts, "cmd", dangerous ? Severity.MEDIUM : Severity.LOW);
        actor(sb, actor, actorName);
        if (ownerPlugin != null) {
            Json.str(sb.append(",\"p\":"), ownerPlugin, 64);
        }
        Json.str(sb.append(",\"t\":"), masked, MAX_TEXT);
        emit(sb);
    }

    /**
     * Текущий список OP. Первый вызов только запоминает его и отправляет снимок; дальше каждое
     * изменение сопоставляется с командами {@code /op} и {@code /deop}.
     */
    public synchronized void ops(long ts, Map<UUID, String> current) {
        Map<UUID, String> previous = ops;
        ops = new LinkedHashMap<>(current);
        if (previous == null) {
            snapshot(ts, current);
            return;
        }
        boolean changed = false;
        for (Map.Entry<UUID, String> e : current.entrySet()) {
            if (!previous.containsKey(e.getKey())) {
                opChange(ts, true, e.getValue());
                changed = true;
            }
        }
        for (Map.Entry<UUID, String> e : previous.entrySet()) {
            if (!current.containsKey(e.getKey())) {
                opChange(ts, false, e.getValue());
                changed = true;
            }
        }
        if (changed) {
            snapshot(ts, current);
        }
    }

    /** Плагин выдал игроку опасное право через PermissionAttachment: виден сам плагин. */
    public synchronized void attachment(long ts, UUID player, String nick, String permission, String plugin) {
        String key = player + "|" + permission + "|" + plugin;
        if (!seenAttachments.add(key)) {
            return;
        }
        if (seenAttachments.size() > MAX_SEEN) {
            seenAttachments.clear();
            seenAttachments.add(key);
        }
        StringBuilder sb = start(ts, "attachment", Severity.CRITICAL);
        target(sb, nick);
        Json.str(sb.append(",\"perm\":"), permission, 128);
        Json.str(sb.append(",\"p\":"), plugin, 64);
        emit(sb);
    }

    /** Запись журнала LuckPerms: команда или редактор. */
    public synchronized void luckPermsLog(long ts, String actorName, String targetName, String targetType, String description) {
        lpLogs.addLast(new LogMark(ts, lower(targetName)));
        trim(lpLogs);
        lpPending.removeIf(p -> p.target.equals(lower(targetName)));
        boolean dangerous = AdminCommands.dangerousLuckPermsAction(description, extraDangerous);
        StringBuilder sb = start(ts, "lp", dangerous ? Severity.MEDIUM : Severity.LOW);
        boolean console = actorName == null || actorName.equalsIgnoreCase("console") || actorName.equalsIgnoreCase("Console@");
        actor(sb, console ? Actor.CONSOLE : Actor.PLAYER, console ? null : actorName);
        target(sb, targetName);
        if (targetType != null) {
            sb.append(",\"tt\":\"").append(targetType).append('"');
        }
        Json.str(sb.append(",\"t\":"), description == null ? "" : description, MAX_TEXT);
        emit(sb);
    }

    /** Право добавлено в LuckPerms. Опасное без записи в журнале LP — выдано плагином через API. */
    public synchronized void luckPermsNode(long ts, String targetName, String targetType, String key, boolean value, boolean transientData) {
        if (!value || !AdminCommands.dangerousPermission(key, extraDangerous)) {
            return;
        }
        String target = lower(targetName);
        for (LogMark mark : lpLogs) {
            if (mark.target.equals(target) && Math.abs(ts - mark.ts) <= LP_LOG_WINDOW_MS) {
                return;
            }
        }
        lpPending.addLast(new PendingNode(ts, target, targetName, targetType, key, transientData));
        trim(lpPending);
    }

    /** Раз в пару секунд: изменения LP, так и не получившие запись в журнале. */
    public synchronized void sweep(long now) {
        for (Iterator<PendingNode> it = lpPending.iterator(); it.hasNext(); ) {
            PendingNode p = it.next();
            if (now - p.ts < LP_LOG_WINDOW_MS) {
                continue;
            }
            it.remove();
            StringBuilder sb = start(p.ts, "lp_api", Severity.HIGH);
            target(sb, p.displayName);
            if (p.targetType != null) {
                sb.append(",\"tt\":\"").append(p.targetType).append('"');
            }
            Json.str(sb.append(",\"perm\":"), p.key, 128);
            if (p.transientData) {
                sb.append(",\"tr\":true");
            }
            emit(sb);
        }
        long cutoff = now - COMMAND_WINDOW_MS * 2;
        opCommands.removeIf(c -> c.ts < cutoff);
        lpLogs.removeIf(m -> m.ts < cutoff);
    }

    // ── Внутреннее ──────────────────────────────────────────────────────

    private void opChange(long ts, boolean grant, String nick) {
        OpCommand cause = null;
        String target = lower(nick);
        for (Iterator<OpCommand> it = opCommands.descendingIterator(); it.hasNext(); ) {
            OpCommand c = it.next();
            if (c.label.equals(grant ? "op" : "deop") && c.target.equals(target) && ts - c.ts <= COMMAND_WINDOW_MS && c.ts <= ts) {
                cause = c;
                break;
            }
        }
        Severity severity;
        if (!grant) {
            severity = Severity.INFO;
        } else if (cause == null) {
            severity = Severity.CRITICAL;
        } else {
            severity = switch (cause.actor) {
                case CONSOLE, RCON -> Severity.LOW;
                case PLAYER -> Severity.MEDIUM;
                default -> Severity.HIGH;
            };
        }
        StringBuilder sb = start(ts, grant ? "op_grant" : "op_revoke", severity);
        target(sb, nick);
        if (cause != null) {
            actor(sb, cause.actor, cause.actorName);
        } else {
            sb.append(",\"as\":\"none\"");
        }
        emit(sb);
    }

    private void snapshot(long ts, Map<UUID, String> current) {
        StringBuilder sb = start(ts, "ops", Severity.INFO).append(",\"n\":[");
        List<String> names = new ArrayList<>(current.values());
        int written = 0;
        for (String name : names) {
            String safe = Nicks.safe(name);
            if (safe == null || written >= 100) {
                continue;
            }
            Json.str(sb.append(written++ == 0 ? "" : ","), safe, 32);
        }
        emit(sb.append(']'));
    }

    private StringBuilder start(long ts, String kind, Severity severity) {
        return new StringBuilder(160).append("{\"ts\":").append(ts).append(",\"k\":\"").append(kind)
                .append("\",\"sev\":\"").append(severity.key()).append('"');
    }

    private static void actor(StringBuilder sb, Actor actor, String name) {
        sb.append(",\"as\":\"").append(actor.key).append('"');
        String safe = name == null ? null : Nicks.safe(name);
        if (safe != null) {
            Json.str(sb.append(",\"a\":"), safe, 32);
        }
    }

    private static void target(StringBuilder sb, String name) {
        if (name != null && !name.isEmpty()) {
            Json.str(sb.append(",\"tg\":"), name, 64);
        }
    }

    private void emit(StringBuilder sb) {
        out.accept(sb.append('}').toString());
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static <T> void trim(ArrayDeque<T> deque) {
        while (deque.size() > MAX_RECENT) {
            deque.pollFirst();
        }
    }

    private record OpCommand(long ts, String label, String target, Actor actor, String actorName) {
    }

    private record LogMark(long ts, String target) {
    }

    private record PendingNode(long ts, String target, String displayName, String targetType, String key, boolean transientData) {
    }
}
