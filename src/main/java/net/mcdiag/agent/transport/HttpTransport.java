package net.mcdiag.agent.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

/**
 * Один синхронный POST через {@link HttpURLConnection}: без собственного пула потоков и селектора,
 * в отличие от java.net.http.HttpClient. Соединение остаётся в keep-alive кеше JDK, поэтому TLS
 * рукопожатие не повторяется каждые 10 секунд. Вызывается только из async-таска.
 */
public final class HttpTransport {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_ERROR_BODY = 1024;
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]{1,64})\"");

    private final String userAgent;

    public HttpTransport(String userAgent) {
        this.userAgent = userAgent;
    }

    /** @param body только ASCII (см. Json) — байты совпадают с символами */
    public Response post(URI url, String token, String body) throws IOException {
        URLConnection raw = url.toURL().openConnection();
        if (!(raw instanceof HttpsURLConnection conn)) {
            throw new IOException("refusing non-HTTPS connection");
        }
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(false);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        // Иначе HttpURLConnection копирует всё тело во внутренний буфер.
        conn.setFixedLengthStreamingMode(bytes.length);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("User-Agent", userAgent);

        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int status = conn.getResponseCode();
        long retryAfterSec = parseRetryAfter(conn.getHeaderField("Retry-After"));
        String errorCode = null;
        // Тело дочитываем до конца — только так соединение вернётся в keep-alive кеш.
        try (InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            if (in != null) {
                String head = new String(in.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
                in.transferTo(OutputStream.nullOutputStream());
                if (status >= 400) {
                    Matcher m = ERROR_CODE.matcher(head);
                    errorCode = m.find() ? m.group(1) : null;
                }
            }
        }
        return new Response(status, retryAfterSec, errorCode);
    }

    private static long parseRetryAfter(String header) {
        if (header == null) {
            return -1;
        }
        try {
            return Math.max(0, Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** @param retryAfterSec -1, если заголовка нет; errorCode — {@code error.code} из конверта бэкенда */
    public record Response(int status, long retryAfterSec, String errorCode) {
    }
}
