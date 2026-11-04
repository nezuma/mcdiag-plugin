package net.mcdiag.agent.collect;

/** Доступ к package-private сборке строки журнала для тестов из других пакетов. */
public final class CommandListenerAccess {

    private CommandListenerAccess() {
    }

    public static String line(long ts, String kind, String nick, String world, String text, String owner) {
        return CommandListener.line(ts, kind, nick, world, text, owner);
    }

    public static String safeNick(String nick) {
        return CommandListener.safeNick(nick);
    }
}
