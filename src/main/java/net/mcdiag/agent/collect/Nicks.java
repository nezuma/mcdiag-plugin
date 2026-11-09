package net.mcdiag.agent.collect;

/** Ник под шаблон панели {@code [A-Za-z0-9_.*-]{1,32}}: Bedrock-игроки Geyser приходят с «.» или «*». */
public final class Nicks {

    private Nicks() {
    }

    /** Ник под шаблон панели: непонятный символ не должен стоить всего батча. */
    public static String safe(String nick) {
        if (nick == null) {
            return null;
        }
        StringBuilder clean = null;
        for (int i = 0; i < nick.length(); i++) {
            char c = nick.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_' || c == '.' || c == '*' || c == '-';
            if (!ok && clean == null) {
                clean = new StringBuilder(nick.length()).append(nick, 0, i);
            } else if (ok && clean != null) {
                clean.append(c);
            }
        }
        if (clean == null) {
            return nick.isEmpty() ? "unknown" : nick;
        }
        return clean.length() == 0 ? "unknown" : clean.toString();
    }
}
