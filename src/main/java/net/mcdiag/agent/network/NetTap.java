package net.mcdiag.agent.network;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.mcdiag.agent.profiler.FrameClassifier;

/**
 * Обёртка над системным {@link ProxySelector}. К нему обращаются HttpURLConnection, OkHttp,
 * java.net.http.HttpClient и обычные Socket перед каждым соединением — так видно, какой плагин
 * куда ходит. Ответ всегда тот же, что у исходного селектора: поведение сети не меняется.
 *
 * <p>Ставится в {@code onLoad} агента, до включения других плагинов: HttpClient запоминает
 * селектор при создании.
 */
public final class NetTap extends ProxySelector {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final int MAX_STACK = 96;
    private static final int MAX_CACHE = 4096;
    private static final Path ROOT = Paths.get("").toAbsolutePath();
    private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);

    private final ProxySelector delegate;
    private final long mainThreadId;
    private volatile NetAggregator sink;
    private volatile Function<String, String> classOwner = c -> null;
    private volatile Function<String, String> threadOwner = t -> null;
    private volatile Function<Class<?>, String> loaderOwner = c -> null;
    private volatile boolean capturePaths = true;
    private volatile Set<String> extraSecret = Set.of();
    /** Класс → [плагин по загрузчику, файл]. Слабые ключи: не держим классы выгруженных плагинов. */
    private final Map<Class<?>, String[]> classInfo = Collections.synchronizedMap(new WeakHashMap<>());

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

    /** @param loaderOwner класс → плагин по его загрузчику ({@code JavaPlugin#getProvidingPlugin}) */
    public void setLoaderOwner(Function<Class<?>, String> loaderOwner) {
        this.loaderOwner = loaderOwner;
        classInfo.clear();
    }

    /** Пути и параметры запросов: {@code modules.network.paths} и свои секретные параметры. */
    public void setPathCapture(boolean capture, Set<String> extraSecret) {
        this.capturePaths = capture;
        this.extraSecret = extraSecret;
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
        Site site = site();
        if (site == AGENT) {
            return;
        }
        Thread thread = Thread.currentThread();
        String owner = site.plugin() != null ? site.plugin() : threadOwner.apply(thread.getName());
        host = host.toLowerCase(Locale.ROOT);
        String rawPath = uri.getRawPath();
        NetAggregator.Request request = null;
        // У socket:// пути нет; HTTP-запрос того же потока уже записан с путём.
        if (capturePaths && !scheme.equals("socket")) {
            String path = RequestMask.maskPath(host, rawPath);
            request = new NetAggregator.Request(RequestMask.pattern(path),
                    RequestMask.example(path, RequestMask.maskQuery(uri.getRawQuery(), extraSecret)),
                    site.callSite(), site.file(), clip(thread.getName(), 64));
        } else if (capturePaths && site.callSite() != null) {
            request = new NetAggregator.Request("", "", site.callSite(), site.file(), clip(thread.getName(), 64));
        }
        current.record(System.currentTimeMillis(), owner, scheme, host, port(uri, scheme),
                thread.getId() == mainThreadId, NetAggregator.kindsOf(host, rawPath), thread.getId(), request);
    }

    /**
     * Откуда вызов: плагин, место вызова «класс.метод(Файл:строка)» и jar, из которого загружен класс.
     *
     * @param plugin   null, если плагин не найден — тогда место вызова и файл первого кадра не из JDK и ядра
     */
    record Site(String plugin, String callSite, String file) {
    }

    /** Маркер «соединение открывает сам агент»: такие не учитываются. */
    private static final Site AGENT = new Site(null, null, null);
    private static final Site NONE = new Site(null, null, null);

    /** Плагин: по загрузчику класса, затем по индексу jar-ов; первый найденный кадр — место вызова. */
    private Site site() {
        Function<String, String> lookup = classOwner;
        Function<Class<?>, String> byLoader = loaderOwner;
        return WALKER.walk(frames -> {
            StackWalker.StackFrame firstForeign = null;
            java.util.Iterator<StackWalker.StackFrame> it = frames.limit(MAX_STACK).iterator();
            while (it.hasNext()) {
                StackWalker.StackFrame frame = it.next();
                String cls = frame.getClassName();
                if (cls.startsWith("net.mcdiag.agent.")) {
                    if (cls.startsWith("net.mcdiag.agent.network.")) {
                        continue;
                    }
                    return AGENT;
                }
                if (FrameClassifier.isNeutral(cls)) {
                    continue;
                }
                String[] info = info(frame.getDeclaringClass(), byLoader);
                String owner = info[0] != null ? info[0] : lookup.apply(cls);
                if (owner != null) {
                    return new Site(owner, describe(frame), info[1]);
                }
                if (firstForeign == null && !FrameClassifier.isCore(cls)) {
                    firstForeign = frame;
                }
            }
            if (firstForeign == null) {
                return NONE;
            }
            return new Site(null, describe(firstForeign), info(firstForeign.getDeclaringClass(), byLoader)[1]);
        });
    }

    private String[] info(Class<?> type, Function<Class<?>, String> byLoader) {
        String[] cached = classInfo.get(type);
        if (cached != null) {
            return cached;
        }
        String owner;
        try {
            owner = byLoader.apply(type);
        } catch (RuntimeException e) {
            owner = null;
        }
        String[] computed = {owner, fileOf(type)};
        if (classInfo.size() >= MAX_CACHE) {
            classInfo.clear();
        }
        classInfo.put(type, computed);
        return computed;
    }

    /** jar или папка, откуда загружен класс; путь относительно папки сервера, если он внутри неё. */
    static String fileOf(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null) {
                return null;
            }
            if (!"file".equals(location.getProtocol())) {
                return clip(location.toString(), 300);
            }
            Path path = Paths.get(location.toURI()).toAbsolutePath().normalize();
            String shown = (path.startsWith(ROOT) ? ROOT.relativize(path).toString() : path.toString()).replace('\\', '/');
            // Paper 1.20.5+ грузит переобработанную копию; владельцу понятнее исходный jar в plugins/.
            return clip(shown.replace("plugins/.paper-remapped/", "plugins/"), 300);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static String describe(StackWalker.StackFrame frame) {
        String file = frame.getFileName();
        int line = frame.getLineNumber();
        return clip(frame.getClassName() + "." + frame.getMethodName() + "("
                + (file == null ? "Unknown Source" : file + (line > 0 ? ":" + line : "")) + ")", 300);
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
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
