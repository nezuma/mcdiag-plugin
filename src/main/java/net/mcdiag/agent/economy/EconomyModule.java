package net.mcdiag.agent.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.mcdiag.agent.McDiagPlugin;
import net.mcdiag.agent.json.Json;
import net.mcdiag.agent.util.Safe;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Балансы игроков раз в 5 минут. Плагины валют читаются только из главного потока и порциями по
 * {@link #PER_TICK} игроков за тик, чтобы большой онлайн не дал спайка. Игроки офлайн по умолчанию
 * не читаются: у части плагинов это запрос к базе или диску.
 *
 * <p>Модуль не держит ссылок на игроков между тиками дольше одного прохода.
 */
public final class EconomyModule {

    static final long PERIOD_MS = 5 * 60_000;
    private static final long PERIOD_TICKS = PERIOD_MS / 50;
    /** Первый проход — через минуту после старта: плагины валют к этому времени уже загружены. */
    private static final long FIRST_DELAY_TICKS = 60 * 20;
    static final int PER_TICK = 50;
    static final int PER_ELEMENT = 100;
    private static final long OFFLINE_EVERY_MS = 6 * 3600_000L;
    private static final long OFFLINE_SEEN_MS = 30L * 24 * 3600_000;
    private static final int OFFLINE_MAX = 2000;
    static final double MAX_ABS = 1e15;

    private final McDiagPlugin plugin;
    private final boolean offline;
    private BukkitTask timer;
    private BukkitTask pass;
    private volatile List<EconomySource> sources = List.of();
    private long lastOfflineAt;

    public EconomyModule(McDiagPlugin plugin, boolean offline) {
        this.plugin = plugin;
        this.offline = offline;
    }

    public void start() {
        timer = Bukkit.getScheduler().runTaskTimer(plugin, Safe.task(plugin.getLogger(), "economy", this::beginPass),
                FIRST_DELAY_TICKS, PERIOD_TICKS);
    }

    public void stop() {
        if (pass != null) {
            pass.cancel();
            pass = null;
        }
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** Для /diag status: какие валюты найдены. */
    public String describe() {
        List<EconomySource> found = sources;
        if (found.isEmpty()) {
            return "no currency plugin found";
        }
        StringBuilder sb = new StringBuilder();
        for (EconomySource s : found) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s.name());
        }
        return sb.toString();
    }

    private void beginPass() {
        if (pass != null) {
            return;
        }
        // Провайдер мог появиться или смениться после старта: ищем каждый проход, это дёшево.
        List<EconomySource> found = new ArrayList<>(2);
        VaultSource vault = VaultSource.find();
        if (vault != null) {
            found.add(vault);
        }
        PlayerPointsSource points = PlayerPointsSource.find();
        if (points != null) {
            found.add(points);
        }
        sources = List.copyOf(found);
        if (found.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<OfflinePlayer> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (offline && now - lastOfflineAt >= OFFLINE_EVERY_MS) {
            lastOfflineAt = now;
            players.addAll(recentOffline(players, now));
        }
        if (players.isEmpty()) {
            return;
        }
        Pass next = new Pass(found, players, now / PERIOD_MS * PERIOD_MS, plugin.hasher()::hash);
        pass = Bukkit.getScheduler().runTaskTimer(plugin, Safe.task(plugin.getLogger(), "economy pass", () -> {
            if (next.step(PER_TICK, plugin.buffer()::addEconomy)) {
                BukkitTask current = pass;
                pass = null;
                if (current != null) {
                    current.cancel();
                }
            }
        }), 1, 1);
    }

    private static List<OfflinePlayer> recentOffline(List<OfflinePlayer> online, long now) {
        Set<UUID> skip = new HashSet<>();
        for (OfflinePlayer p : online) {
            skip.add(p.getUniqueId());
        }
        OfflinePlayer[] all = Bukkit.getOfflinePlayers();
        List<OfflinePlayer> recent = new ArrayList<>();
        for (OfflinePlayer p : all) {
            if (!skip.contains(p.getUniqueId()) && now - p.getLastPlayed() <= OFFLINE_SEEN_MS && p.getName() != null) {
                recent.add(p);
            }
        }
        recent.sort(Comparator.comparingLong(OfflinePlayer::getLastPlayed).reversed());
        return recent.size() > OFFLINE_MAX ? recent.subList(0, OFFLINE_MAX) : recent;
    }

    /** Один проход по игрокам. Отдельный класс без Bukkit-планировщика — его можно проверить тестом. */
    static final class Pass {
        private final List<EconomySource> sources;
        private final List<OfflinePlayer> players;
        private final long ts;
        private final Function<UUID, String> hasher;
        private final StringBuilder[] parts;
        private final int[] counts;
        private int index;

        Pass(List<EconomySource> sources, List<OfflinePlayer> players, long ts, Function<UUID, String> hasher) {
            this.sources = sources;
            this.players = players;
            this.ts = ts;
            this.hasher = hasher;
            this.parts = new StringBuilder[sources.size()];
            this.counts = new int[sources.size()];
        }

        /** @return true, когда все игроки пройдены и всё отдано в {@code out} */
        boolean step(int limit, java.util.function.Consumer<String> out) {
            int end = Math.min(players.size(), index + limit);
            for (; index < end; index++) {
                OfflinePlayer player = players.get(index);
                String nick = player instanceof Player online ? online.getName() : player.getName();
                if (nick == null) {
                    continue;
                }
                String hash = null;
                for (int s = 0; s < sources.size(); s++) {
                    Double value = sources.get(s).balance(player);
                    if (value == null || value.isNaN() || value.isInfinite()) {
                        continue;
                    }
                    if (hash == null) {
                        hash = hasher.apply(player.getUniqueId());
                    }
                    append(s, hash, nick, value);
                    if (counts[s] >= PER_ELEMENT) {
                        flush(s, out);
                    }
                }
            }
            if (index < players.size()) {
                return false;
            }
            for (int s = 0; s < sources.size(); s++) {
                flush(s, out);
            }
            players.clear();
            return true;
        }

        private void append(int s, String hash, String nick, double value) {
            StringBuilder sb = parts[s];
            if (sb == null) {
                EconomySource source = sources.get(s);
                sb = new StringBuilder(4096).append("{\"ts\":").append(ts).append(",\"c\":\"").append(source.id()).append('"');
                Json.str(sb.append(",\"name\":"), source.name(), 64);
                if (source.unit() != null) {
                    Json.str(sb.append(",\"unit\":"), source.unit(), 32);
                }
                sb.append(",\"b\":[");
                parts[s] = sb;
            } else {
                sb.append(',');
            }
            sb.append("{\"h\":\"").append(hash).append('"');
            Json.str(sb.append(",\"n\":"), net.mcdiag.agent.collect.Nicks.safe(nick), 32);
            money(sb.append(",\"v\":"), value).append('}');
            counts[s]++;
        }

        private void flush(int s, java.util.function.Consumer<String> out) {
            if (parts[s] == null) {
                return;
            }
            out.accept(parts[s].append("]}").toString());
            parts[s] = null;
            counts[s] = 0;
        }
    }

    /** Баланс с двумя знаками; бывает и отрицательным (долг). */
    static StringBuilder money(StringBuilder sb, double value) {
        double v = Math.max(-MAX_ABS, Math.min(MAX_ABS, value));
        if (v < 0) {
            sb.append('-');
            v = -v;
        }
        return Json.decimal2(sb, v);
    }
}
