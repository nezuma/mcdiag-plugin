package net.mcdiag.agent.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

/** SHA-256(соль + uuid) в hex. UUID наружу не уходит, бэкенд дополнительно прогоняет хеш через HMAC. */
public final class UuidHasher {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] salt;
    private final MessageDigest digest;

    public UuidHasher(String salt) {
        this.salt = salt.getBytes(StandardCharsets.UTF_8);
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }

    /** Вызывается из основного потока; synchronized — на случай событий из чужих потоков. */
    public synchronized String hash(UUID uuid) {
        digest.update(salt);
        digest.update(uuid.toString().getBytes(StandardCharsets.US_ASCII));
        byte[] bytes = digest.digest();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xf];
            out[i * 2 + 1] = HEX[bytes[i] & 0xf];
        }
        return new String(out);
    }

    public static String newSalt() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return sb.toString();
    }
}
