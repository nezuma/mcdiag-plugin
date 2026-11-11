package net.mcdiag.agent.guard;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.mcdiag.agent.json.Json;
import net.mcdiag.agent.module.Channel;
import net.mcdiag.agent.module.ModuleRegistry;
import net.mcdiag.agent.privileges.AdminCommands;
import net.mcdiag.api.AgentModule;
import net.mcdiag.api.ModuleContext;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Защита сервера: проверка jar-файлов плагинов и их целостности, слежение за папкой plugins,
 * дочерние процессы сервера и аудит настроек. Всё в фоне, на одном потоке с низким приоритетом;
 * в главном потоке — только снимок списка OP и плагинов для аудита.
 *
 * <p>Наблюдение начинается при включении агента ({@link #startEarly}): процесс, запущенный плагином
 * при старте сервера, иначе прошёл бы незамеченным. До включения модуля записи копятся в очереди.
 */
public final class GuardModule implements AgentModule {

    public static final String ID = "guard";
    private static final long SCAN_DELAY_SEC = 30;
    private static final long CONFIG_DELAY_TICKS = 60 * 20;
    private static final long CONFIG_PERIOD_TICKS = 60 * 60 * 20;
    private static final long PROCESS_TICKS = 10 * 20;
    private static final long WATCH_DEBOUNCE_SEC = 3;
    private static final long CONFIG_RESEND_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_EARLY = 300;
    private static final int MAX_SEEN_PIDS = 4096;
    private static final Pattern SUSPICIOUS_CMD = Pattern.compile(
            "(?i)(?:^|[\\s/])(?:curl|wget|bash|sh|zsh|nc|ncat|netcat|socat|python\\d?|perl|php|powershell|cmd\\.exe|xmrig|minerd|cpuminer|base64)(?:\\s|$)|/tmp/|/dev/shm");
    private static final Pattern JVM_HELPER = Pattern.compile("(?:^|/)lib/jspawnhelper(?:\\s|$)");
    private static final Pattern SECRET_ARG = Pattern.compile(
            "(?i)((?:password|passwd|pass|token|secret|key|auth)[=:]\\s*)\\S+|(://[^/:@\\s]+:)[^@\\s]+@");

    private final ModuleRegistry registry;
    private final Logger log;
    private final Path pluginsDir = Paths.get("plugins");
    private final Path ownJar;
    private final ArrayDeque<String> early = new ArrayDeque<>();
    private volatile Channel channel;
    private volatile boolean running;
    /** До первой полной проверки события папки откладываются: иначе ранний файл сломает признак первого запуска. */
    private volatile boolean initialScanDone;
    private ScheduledExecutorService worker;
    private WatchService watcher;
    private Thread watchThread;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Set<Long> seenPids = new HashSet<>();
    private final Map<String, ScheduledFuture<?>> pending = new LinkedHashMap<>();
    private String lastConfig;
    private long lastConfigAt;

    public GuardModule(ModuleRegistry registry, Logger log, Path ownJar) {
        this.registry = registry;
        this.log = log;
        this.ownJar = ownJar;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "проверка плагинов и их целостности, дочерние процессы, аудит настроек сервера";
    }

    @Override
    public Set<String> requiredCaps() {
        return Set.of("guard");
    }

    /** Главный поток, при включении агента. */
    public synchronized void startEarly(Plugin agent, ConfigurationSection cfg) {
        if (running) {
            return;
        }
        running = true;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MCDiag Guard");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        if (cfg.getBoolean("processes", true)) {
            scanProcesses(true);
            tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(agent, () -> safe("process scan", () -> scanProcesses(false)),
                    PROCESS_TICKS, PROCESS_TICKS));
        }
        if (cfg.getBoolean("scan_plugins", true)) {
            worker.schedule(() -> safe("plugin scan", () -> {
                try {
                    scanAll();
                } finally {
                    initialScanDone = true;
                }
            }), SCAN_DELAY_SEC, TimeUnit.SECONDS);
        } else {
            initialScanDone = true;
        }
        if (cfg.getBoolean("watch_plugins", true)) {
            startWatcher();
        }
        if (cfg.getBoolean("config_audit", true)) {
            tasks.add(Bukkit.getScheduler().runTaskTimer(agent, () -> safe("config audit", this::auditConfig),
                    CONFIG_DELAY_TICKS, CONFIG_PERIOD_TICKS));
        }
    }

    public synchronized void stop() {
        running = false;
        for (BukkitTask t : tasks) {
            t.cancel();
        }
        tasks.clear();
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException ignored) {
                // Поток наблюдения завершится сам.
            }
            watcher = null;
        }
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        synchronized (early) {
            early.clear();
        }
    }

    public boolean running() {
        return running;
    }

    @Override
    public void enable(ModuleContext ctx) {
        Channel ch = registry.channel("guard", "guard", 300, 60);
        if (!running) {
            startEarly(ctx.agent(), ctx.config());
        }
        synchronized (early) {
            for (String json : early) {
                ch.offer(json);
            }
            early.clear();
            channel = ch;
        }
    }

    @Override
    public void disable() {
        channel = null;
        stop();
    }

    @Override
    public String status() {
        Channel ch = channel;
        return (running ? "watching" : "stopped") + (ch == null ? "" : ", " + ch.size() + " queued");
    }

    // ── Плагины ─────────────────────────────────────────────────────────

    private void scanAll() throws IOException {
        if (!Files.isDirectory(pluginsDir)) {
            return;
        }
        IntegrityStore store = IntegrityStore.load(integrityFile());
        boolean firstRun = store.isEmpty();
        Map<String, String> now = new LinkedHashMap<>();
        List<JarScanner.Report> reports = new ArrayList<>();
        long ts = System.currentTimeMillis();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path jar : jars) {
                if (!running) {
                    return;
                }
                if (isOwn(jar)) {
                    continue;
                }
                JarScanner.Report r = JarScanner.scan(jar, "plugins/" + jar.getFileName());
                reports.add(r);
                now.put(r.file, r.sha256);
                emit(r.toJson(ts));
                String prev = store.get(r.file);
                if (!firstRun && prev == null) {
                    emit(event(ts, "jar_new", severityForNew(r), r, null, false));
                } else if (firstRun && JarScanner.rank(r.risk()) >= 3) {
                    // Первый запуск: всё считается исходным, но опасный плагин нельзя молча принять за норму.
                    emit(event(ts, "jar_risky", severityForNew(r), r, null, false));
                } else if (prev != null && !prev.equals(r.sha256)) {
                    emit(event(ts, "jar_changed", "high", r, prev, false));
                }
            }
        }
        if (!firstRun) {
            for (String file : store.files()) {
                if (!now.containsKey(file)) {
                    emit(removed(ts, file));
                }
            }
        }
        for (String[] shared : JarScanner.sharedRootClasses(reports)) {
            StringBuilder sb = new StringBuilder(256).append("{\"t\":\"event\",\"ts\":").append(ts)
                    .append(",\"k\":\"jar_infected\",\"sev\":\"critical\"");
            Json.str(sb.append(",\"d\":"), shared[0] + " → " + shared[1], 300);
            emit(sb.append('}').toString());
        }
        IntegrityStore.save(integrityFile(), now);
    }

    /** Файл появился или изменился, пока сервер работает. */
    private void rescan(Path jar) {
        if (!initialScanDone) {
            debounce(jar);
            return;
        }
        // Файл дописан только сейчас: при первом событии плагин мог быть ещё неполным.
        if (isOwn(jar)) {
            return;
        }
        String shown = "plugins/" + jar.getFileName();
        long ts = System.currentTimeMillis();
        try {
            IntegrityStore store = IntegrityStore.load(integrityFile());
            String prev = store.get(shown);
            if (!Files.exists(jar)) {
                if (prev != null) {
                    emit(removed(ts, shown));
                    store.remove(shown);
                    IntegrityStore.save(integrityFile(), store.map());
                }
                return;
            }
            JarScanner.Report r = JarScanner.scan(jar, shown);
            if (r.sha256.equals(prev)) {
                return;
            }
            emit(r.toJson(ts));
            emit(prev == null
                    ? event(ts, "jar_new", severityForNew(r), r, null, true)
                    : event(ts, "jar_changed", JarScanner.rank(r.risk()) >= 4 ? "critical" : "high", r, prev, true));
            store.put(shown, r.sha256);
            IntegrityStore.save(integrityFile(), store.map());
        } catch (IOException | RuntimeException e) {
            log.log(Level.FINE, "guard rescan failed", e);
        }
    }

    private void startWatcher() {
        if (!Files.isDirectory(pluginsDir)) {
            return;
        }
        try {
            watcher = FileSystems.getDefault().newWatchService();
            pluginsDir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            log.log(Level.FINE, "plugins folder watch is unavailable", e);
            return;
        }
        WatchService ws = watcher;
        watchThread = new Thread(() -> {
            try {
                while (running) {
                    WatchKey key = ws.take();
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        if (ev.context() instanceof Path name && name.toString().endsWith(".jar")) {
                            debounce(pluginsDir.resolve(name));
                        }
                    }
                    if (!key.reset()) {
                        return;
                    }
                }
            } catch (InterruptedException | ClosedWatchServiceException ignored) {
                // Остановка агента.
            }
        }, "MCDiag Guard Watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    /** Копирование jar — серия событий; проверяем, когда файл затих. */
    private synchronized void debounce(Path jar) {
        if (worker == null || isOwn(jar)) {
            return;
        }
        ScheduledFuture<?> old = pending.remove(jar.toString());
        if (old != null) {
            old.cancel(false);
        }
        pending.put(jar.toString(), worker.schedule(() -> {
            synchronized (this) {
                pending.remove(jar.toString());
            }
            rescan(jar);
        }, WATCH_DEBOUNCE_SEC, TimeUnit.SECONDS));
    }

    private static String severityForNew(JarScanner.Report r) {
        int risk = JarScanner.rank(r.risk());
        return risk >= 4 ? "critical" : risk >= 3 ? "high" : "medium";
    }

    private static String event(long ts, String kind, String severity, JarScanner.Report r, String prev, boolean runtime) {
        StringBuilder sb = new StringBuilder(256).append("{\"t\":\"event\",\"ts\":").append(ts)
                .append(",\"k\":\"").append(kind).append("\",\"sev\":\"").append(severity).append('"');
        Json.str(sb.append(",\"file\":"), r.file, 256);
        if (r.name != null) {
            Json.str(sb.append(",\"name\":"), r.name, 64);
        }
        sb.append(",\"sha\":\"").append(r.sha256).append('"');
        if (prev != null) {
            sb.append(",\"prev\":\"").append(prev).append('"');
        }
        if (runtime) {
            sb.append(",\"rt\":true");
        }
        Json.str(sb.append(",\"d\":"), r.risk(), 16);
        return sb.append('}').toString();
    }

    private static String removed(long ts, String file) {
        StringBuilder sb = new StringBuilder(160).append("{\"t\":\"event\",\"ts\":").append(ts)
                .append(",\"k\":\"jar_removed\",\"sev\":\"info\"");
        return Json.str(sb.append(",\"file\":"), file, 256).append('}').toString();
    }

    /**
     * Свой jar агента. Сравнение по файлу не годится: при обновлении на работающем сервере старый файл
     * уже удалён, а на Paper/Leaf загружена перемапленная копия из plugins/.paper-remapped. Поэтому — путь,
     * имя файла, а для обновления под другим именем — главный класс агента в plugin.yml.
     */
    private boolean isOwn(Path jar) {
        if (ownJar == null) {
            return false;
        }
        if (jar.toAbsolutePath().normalize().equals(ownJar.toAbsolutePath().normalize())
                || jar.getFileName().equals(ownJar.getFileName())) {
            return true;
        }
        try {
            if (Files.exists(jar) && Files.exists(ownJar) && Files.isSameFile(jar, ownJar)) {
                return true;
            }
        } catch (IOException ignored) {
            // Файл удалён или ещё пишется — решит проверка содержимого.
        }
        return isAgentJar(jar);
    }

    private static final java.util.regex.Pattern AGENT_MAIN = java.util.regex.Pattern.compile(
            "(?m)^main:\\s*['\"]?net\\.mcdiag\\.agent\\.McDiagPlugin['\"]?\\s*$");

    static boolean isAgentJar(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry yml = zip.getEntry("plugin.yml");
            if (yml == null || zip.getEntry("net/mcdiag/agent/McDiagPlugin.class") == null) {
                return false;
            }
            try (java.io.InputStream in = zip.getInputStream(yml)) {
                String text = new String(in.readNBytes(16 * 1024), java.nio.charset.StandardCharsets.UTF_8);
                return AGENT_MAIN.matcher(text).find();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Path integrityFile() {
        return pluginsDir.resolve("MCDiag").resolve("integrity.txt");
    }

    // ── Процессы ────────────────────────────────────────────────────────

    /** Новые дочерние процессы сервера: майнеры и обратные оболочки запускаются именно так. */
    private void scanProcesses(boolean first) {
        long ts = System.currentTimeMillis();
        List<ProcessHandle> children = ProcessHandle.current().descendants().toList();
        synchronized (seenPids) {
            for (ProcessHandle p : children) {
                if (!seenPids.add(p.pid())) {
                    continue;
                }
                String cmd = p.info().commandLine().orElseGet(() -> p.info().command().orElse("?"));
                // Помощник самой JVM для запуска процессов: настоящий дочерний процесс покажется отдельно.
                if (JVM_HELPER.matcher(cmd).find()) {
                    continue;
                }
                String masked = maskCommand(cmd);
                String severity = SUSPICIOUS_CMD.matcher(cmd).find() ? "critical" : "high";
                StringBuilder sb = new StringBuilder(320).append("{\"t\":\"event\",\"ts\":").append(ts)
                        .append(",\"k\":\"process\",\"sev\":\"").append(severity).append("\",\"pid\":").append(p.pid());
                Json.str(sb.append(",\"cmd\":"), masked, 256);
                if (first) {
                    sb.append(",\"d\":\"before agent\"");
                }
                emit(sb.append('}').toString());
            }
            if (seenPids.size() > MAX_SEEN_PIDS) {
                seenPids.clear();
                children.forEach(p -> seenPids.add(p.pid()));
            }
        }
    }

    static String maskCommand(String cmd) {
        String masked = SECRET_ARG.matcher(cmd).replaceAll(m -> m.group(1) != null
                ? java.util.regex.Matcher.quoteReplacement(m.group(1)) + "***"
                : java.util.regex.Matcher.quoteReplacement(m.group(2)) + "***@");
        return masked.length() > 256 ? masked.substring(0, 256) : masked;
    }

    // ── Настройки ───────────────────────────────────────────────────────

    /** Главный поток: снимок OP и плагинов; файлы читаются тут же — они маленькие. */
    private void auditConfig() throws IOException {
        Properties props = new Properties();
        Path propsFile = Paths.get("server.properties");
        if (Files.exists(propsFile)) {
            try (InputStream in = Files.newInputStream(propsFile)) {
                props.load(in);
            }
        }
        YamlConfiguration spigot = yaml("spigot.yml");
        YamlConfiguration paper = yaml("config/paper-global.yml");
        YamlConfiguration legacyPaper = yaml("paper.yml");
        boolean velocity = paper.getBoolean("proxies.velocity.enabled", legacyPaper.getBoolean("settings.velocity-support.enabled", false));
        String secret = paper.getString("proxies.velocity.secret", legacyPaper.getString("settings.velocity-support.secret", ""));
        List<String> plugins = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            plugins.add(p.getName());
        }
        ConfigAudit.Input input = new ConfigAudit.Input(
                Bukkit.getOnlineMode(),
                spigot.getBoolean("settings.bungeecord", false),
                velocity,
                secret == null ? 0 : secret.length(),
                props.getProperty("server-ip", ""),
                Boolean.parseBoolean(props.getProperty("enable-rcon", "false")),
                props.getProperty("rcon.password", ""),
                Boolean.parseBoolean(props.getProperty("enable-query", "false")),
                Bukkit.hasWhitelist(),
                Bukkit.getOperators().size(),
                plugins,
                defaultOpPermissions(),
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                System.getProperty("user.name", ""),
                Bukkit.getVersion());
        List<ConfigAudit.Check> checks = ConfigAudit.run(input);
        long ts = System.currentTimeMillis();
        String json = ConfigAudit.toJson(ts, input, checks);
        String body = json.substring(json.indexOf(",\"core\""));
        if (!body.equals(lastConfig) || ts - lastConfigAt > CONFIG_RESEND_MS) {
            lastConfig = body;
            lastConfigAt = ts;
            emit(json);
        }
    }

    /** Опасные права, которые permissions.yml выдаёт всем ({@code default: true}). */
    private static List<String> defaultOpPermissions() {
        YamlConfiguration bukkit = yaml("bukkit.yml");
        YamlConfiguration perms = yaml(bukkit.getString("settings.permissions-file", "permissions.yml"));
        List<String> out = new ArrayList<>();
        for (String key : perms.getKeys(false)) {
            ConfigurationSection section = perms.getConfigurationSection(key);
            String def = section == null ? null : section.getString("default");
            if ("true".equalsIgnoreCase(def) && AdminCommands.dangerousPermission(key, Set.of())) {
                out.add(key);
            }
        }
        return out;
    }

    private static YamlConfiguration yaml(String path) {
        File file = new File(path);
        return file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    // ── Вывод ───────────────────────────────────────────────────────────

    private void emit(String json) {
        Channel ch = channel;
        if (ch != null) {
            ch.offer(json);
            return;
        }
        synchronized (early) {
            Channel now = channel;
            if (now != null) {
                now.offer(json);
                return;
            }
            if (early.size() >= MAX_EARLY) {
                early.pollFirst();
            }
            early.addLast(json);
        }
    }

    private void safe(String what, ThrowingRunnable body) {
        try {
            body.run();
        } catch (Exception | LinkageError e) {
            log.log(Level.FINE, "MCDiag guard " + what + " failed", e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** SHA-256 каждого jar с прошлой проверки: «файл → хеш», по строке на файл. */
    static final class IntegrityStore {
        private final Map<String, String> map;

        private IntegrityStore(Map<String, String> map) {
            this.map = map;
        }

        static IntegrityStore load(Path file) throws IOException {
            Map<String, String> map = new LinkedHashMap<>();
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int sp = line.indexOf("  ");
                    if (sp == 64) {
                        map.put(line.substring(66), line.substring(0, 64));
                    }
                }
            }
            return new IntegrityStore(map);
        }

        static void save(Path file, Map<String, String> map) throws IOException {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            map.forEach((f, sha) -> sb.append(sha).append("  ").append(f).append('\n'));
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        }

        boolean isEmpty() {
            return map.isEmpty();
        }

        String get(String file) {
            return map.get(file);
        }

        void put(String file, String sha) {
            map.put(file, sha);
        }

        void remove(String file) {
            map.remove(file);
        }

        Set<String> files() {
            return new HashSet<>(map.keySet());
        }

        Map<String, String> map() {
            return map;
        }
    }
}
