package net.mcdiag.agent.network;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Обёртка над системным {@link ProxySelector}. К нему обращаются HttpURLConnection, OkHttp,
 * java.net.http.HttpClient и обычные Socket перед каждым соединением — так видно, какой плагин
 * куда ходит. Ответ всегда тот же, что у исходного селектора: поведение сети не меняется.
 *
 * <p>Ставится в {@code onLoad} агента, до включения других плагинов: HttpClient запоминает
 * селектор при создании.
 */
public final class NetTap extends ProxySelector {

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final int MAX_STACK = 96;
    private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);

    private final ProxySelector delegate;
    private final long mainThreadId;
    private volatile NetAggregator sink;
    private volatile Function<String, String> classOwner = c -> null;
    private volatile Function<String, String> threadOwner = t -> null;

    private NetTap(ProxySelector delegate, long mainThreadId) {
        this.delegate = delegate;
        this.mainThreadId = mainThreadId;
    }

    public static NetTap install(long mainThreadId) {
        NetTap tap = new NetTap(ProxySelector.getDefault(), mainThreadId);
        ProxySelector.setDefault(tap);
        return tap;
    }

    /** Возвращаем исходный селектор, если после нас его никто не заменил. */
    public void uninstall() {
        sink = null;
        if (ProxySelector.getDefault() == this) {
            ProxySelector.setDefault(delegate);
        }
    }

    public void setSink(NetAggregator sink) {
        this.sink = sink;
    }

    /** @param classOwner класс → плагин; @param threadOwner имя потока → плагин (пулы без кода плагина в стеке) */
    public void setOwners(Function<String, String> classOwner, Function<String, String> threadOwner) {
        this.classOwner = classOwner;
        this.threadOwner = threadOwner;
    }

    @Override
    public List<Proxy> select(URI uri) {
        List<Proxy> result = delegate != null ? delegate.select(uri) : DIRECT;
        NetAggregator current = sink;
        if (current != null && uri != null) {
            try {
                observe(current, uri);
            } catch (RuntimeException ignored) {
                // Учёт не должен ломать чужое соединение.
            }
        }
        return result;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        if (delegate != null) {
            delegate.connectFailed(uri, sa, ioe);
        }
    }

    private void observe(NetAggregator current, URI uri) {
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return;
        }
        String scheme = uri.getScheme() == null ? "other" : uri.getScheme().toLowerCase(Locale.ROOT);
        String owner = owner();
        if (owner == AGENT) {
            return;
        }
        Thread thread = Thread.currentThread();
        if (owner == null) {
            owner = threadOwner.apply(thread.getName());
        }
        host = host.toLowerCase(Locale.ROOT);
        current.record(System.currentTimeMillis(), owner, scheme, host, port(uri, scheme),
                thread.getId() == mainThreadId, NetAggregator.kindsOf(host, uri.getRawPath()), thread.getId());
    }

    /** Маркер «соединение открывает сам агент»: такие не учитываются. */
    private static final String AGENT = new String("agent");

    private String owner() {
        Function<String, String> lookup = classOwner;
        return WALKER.walk(frames -> {
            java.util.Iterator<StackWalker.StackFrame> it = frames.limit(MAX_STACK).iterator();
            while (it.hasNext()) {
                String cls = it.next().getClassName();
                if (cls.startsWith("net.mcdiag.agent.")) {
                    if (cls.startsWith("net.mcdiag.agent.network.")) {
                        continue;
                    }
                    return AGENT;
                }
                String owner = lookup.apply(cls);
                if (owner != null) {
                    return owner;
                }
            }
            return null;
        });
    }

    static int port(URI uri, String scheme) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return switch (scheme) {
            case "https", "wss" -> 443;
            case "http", "ws" -> 80;
            default -> 0;
        };
    }
}
