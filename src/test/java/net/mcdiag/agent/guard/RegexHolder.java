package net.mcdiag.agent.guard;

/** Строки-правила, как у плагинов защиты: не должны считаться адресами вебхуков. */
final class RegexHolder {
    static final String WEBHOOK_RULE = "(?i)discord(?:app)?\\.com/api/webhooks/|pastebin\\.com/raw";
    static final String MINER_RULE = "stratum\\+tcp://[\\w.]+";

    private RegexHolder() {
    }

    static String all() {
        return WEBHOOK_RULE + MINER_RULE;
    }
}
