package net.mcdiag.agent.heatmap;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.mcdiag.agent.McDiagPlugin;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * Настройки модуля в config.yml и команды /diag: зона, POI, включение, снимок рельефа.
 * Сам сбор данных живёт в {@link HeatmapModule} и создаётся только когда нужен.
 */
public final class HeatmapControl {

    private static final String ROOT = "heatmap.";

    private final McDiagPlugin plugin;
    private HeatmapModule module;
    private TerrainScanner scanner;

    public HeatmapControl(McDiagPlugin plugin) {
        this.plugin = plugin;
    }

    /** Запускает или останавливает модуль по настройкам и ответу панели. */
    public void sync() {
        boolean want = enabled() && zone() != null && plugin.panel().heatmap() && !plugin.panel().free();
        if (!want) {
            stop();
        } else if (module == null) {
            module = new HeatmapModule(plugin, zone(), pois(), config().getBoolean(ROOT + "count_admins", false));
            module.start();
        }
    }

    public void stop() {
        if (module != null) {
            module.stop();
            module = null;
        }
        if (scanner != null) {
            scanner.cancel();
            scanner = null;
        }
    }

    private void restart() {
        stop();
        sync();
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    public boolean enabled() {
        return config().getBoolean(ROOT + "enabled", false);
    }

    /** Зона целиком, только если заданы обе точки в одном мире. */
    public HeatZone zone() {
        int[] a = point("pos1");
        int[] b = point("pos2");
        String world = config().getString(ROOT + "world");
        if (a == null || b == null || world == null || world.isEmpty()) {
            return null;
        }
        return HeatZone.of(world, a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    private int[] point(String key) {
        String raw = config().getString(ROOT + key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split(",");
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
        } catch (RuntimeException e) {
            return null;
        }
    }

    public List<Poi> pois() {
        List<Poi> out = new ArrayList<>();
        for (String line : config().getStringList(ROOT + "pois")) {
            Poi poi = Poi.decode(line);
            if (poi != null) {
                out.add(poi);
            }
        }
        return out;
    }

    /** /diag pos1 и /diag pos2 */
    public void setPos(CommandSender sender, String key) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[MCDiag] Run this in game.");
            return;
        }
        Location at = player.getLocation();
        String world = at.getWorld().getName();
        String previous = config().getString(ROOT + "world");
        if (previous != null && !previous.equals(world)) {
            config().set(ROOT + (key.equals("pos1") ? "pos2" : "pos1"), null);
        }
        config().set(ROOT + "world", world);
        config().set(ROOT + key, at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
        HeatZone zone = zone();
        sender.sendMessage("[MCDiag] " + key + " set to " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + ".");
        if (zone == null) {
            plugin.saveConfig();
            return;
        }
        if (zone.tooBig()) {
            config().set(ROOT + key, null);
            plugin.saveConfig();
            sender.sendMessage("[MCDiag] Zone is too large: each side must be at most " + HeatZone.MAX_SIDE + " blocks.");
            return;
        }
        dropForeignPois(zone);
        plugin.saveConfig();
        sender.sendMessage("[MCDiag] Zone: " + zone.width() + " x " + zone.depth() + " blocks."
                + (enabled() ? "" : " Turn it on: /diag heatmap on"));
        ZoneFrame.show(plugin, player, zone);
        restart();
        scan(sender);
    }

    private void dropForeignPois(HeatZone zone) {
        List<String> kept = new ArrayList<>();
        for (Poi poi : pois()) {
            if (zone.contains(poi.x(), poi.y(), poi.z())) {
                kept.add(poi.encode());
            }
        }
        config().set(ROOT + "pois", kept);
    }

    /** /diag poi name и /diag poi remove name */
    public void poi(CommandSender sender, String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            List<String> kept = new ArrayList<>();
            for (Poi poi : pois()) {
                if (!poi.name().equalsIgnoreCase(args[2])) {
                    kept.add(poi.encode());
                }
            }
            config().set(ROOT + "pois", kept);
            plugin.saveConfig();
            restart();
            sender.sendMessage("[MCDiag] POI removed.");
            return;
        }
        if (args.length == 1) {
            List<Poi> pois = pois();
            sender.sendMessage("[MCDiag] POI: " + (pois.isEmpty() ? "none" : ""));
            pois.forEach(p -> sender.sendMessage(" " + p.name() + " at " + p.x() + ", " + p.y() + ", " + p.z()));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[MCDiag] Run this in game.");
            return;
        }
        String name = args[1];
        if (!Poi.validName(name)) {
            sender.sendMessage("[MCDiag] Name: letters, digits, _ . - up to 32 characters.");
            return;
        }
        HeatZone zone = zone();
        Location at = player.getLocation();
        if (zone == null) {
            sender.sendMessage("[MCDiag] Set the zone first: /diag pos1 and /diag pos2");
            return;
        }
        if (!at.getWorld().getName().equals(zone.world()) || !zone.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
            sender.sendMessage("[MCDiag] Stand inside the zone to add a POI.");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Poi poi : pois()) {
            if (!poi.name().equalsIgnoreCase(name)) {
                lines.add(poi.encode());
            }
        }
        lines.add(new Poi(name, at.getBlockX(), at.getBlockY(), at.getBlockZ()).encode());
        config().set(ROOT + "pois", lines);
        plugin.saveConfig();
        restart();
        sender.sendMessage("[MCDiag] POI " + name + " saved at " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + ".");
    }

    /** /diag heatmap on|off|status|scan|clear */
    public void command(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "status";
        switch (sub) {
            case "on" -> {
                config().set(ROOT + "enabled", true);
                plugin.saveConfig();
                restart();
                sender.sendMessage("[MCDiag] Heatmap is on." + (zone() == null ? " Set the zone: /diag pos1 and /diag pos2" : ""));
            }
            case "off" -> {
                config().set(ROOT + "enabled", false);
                plugin.saveConfig();
                stop();
                sender.sendMessage("[MCDiag] Heatmap is off: no listeners and no tasks are running.");
            }
            case "scan" -> scan(sender);
            case "clear" -> {
                config().set(ROOT + "world", null);
                config().set(ROOT + "pos1", null);
                config().set(ROOT + "pos2", null);
                config().set(ROOT + "pois", null);
                plugin.saveConfig();
                stop();
                sender.sendMessage("[MCDiag] Zone and POI removed.");
            }
            default -> status(sender);
        }
    }

    private void status(CommandSender sender) {
        HeatZone zone = zone();
        sender.sendMessage("[MCDiag] Heatmap: " + (module != null ? "running" : enabled() ? "waiting" : "off"));
        sender.sendMessage(" Zone: " + (zone == null ? "not set" : zone.world() + " " + zone.width() + " x " + zone.depth()));
        sender.sendMessage(" POI: " + pois().size() + ", visits right now: " + (module == null ? 0 : module.activeVisits()));
        if (enabled() && module == null && zone != null) {
            sender.sendMessage(" The panel does not accept heatmap data: update it or check the plan.");
        }
    }

    /** Рельеф: один верхний блок на каждую точку зоны. */
    private void scan(CommandSender sender) {
        HeatZone zone = zone();
        if (zone == null) {
            sender.sendMessage("[MCDiag] Set the zone first: /diag pos1 and /diag pos2");
            return;
        }
        if (scanner != null) {
            scanner.cancel();
        }
        URI base = plugin.cfg().backendUrl();
        if (base == null || !plugin.cfg().canSend() || !plugin.panel().heatmap() || plugin.panel().free()) {
            sender.sendMessage("[MCDiag] Terrain is not sent: the panel does not accept heatmap data yet.");
            return;
        }
        scanner = new TerrainScanner(plugin, zone, body -> upload(base, body));
        sender.sendMessage("[MCDiag] Reading terrain: " + scanner.chunks() + " chunks, it will not slow the server down.");
        scanner.start();
    }

    private void upload(URI base, String body) {
        try {
            URI url = URI.create(base + "/terrain");
            var response = plugin.sender().transport().post(url, plugin.cfg().serverToken(), body);
            plugin.getLogger().info("Heatmap terrain sent: " + response.status());
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.INFO, "Heatmap terrain was not sent: " + e.getClass().getSimpleName());
        }
    }

    /** Имена подкоманд для автодополнения. */
    public static List<String> subcommands() {
        return List.of("on", "off", "status", "scan", "clear");
    }
}
