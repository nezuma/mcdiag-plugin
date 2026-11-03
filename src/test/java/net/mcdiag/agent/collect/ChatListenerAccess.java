package net.mcdiag.agent.collect;

/** Доступ к package-private сборке строки журнала для тестов из других пакетов. */
public final class ChatListenerAccess {

    private ChatListenerAccess() {
    }

    public static String line(long ts, String kind, String nick, String world, String text, String owner) {
        return ChatListener.line(ts, kind, nick, world, text, owner);
    }
}
