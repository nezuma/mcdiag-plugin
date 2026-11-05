package net.mcdiag.agent.heatmap;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.mcdiag.agent.McDiagPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

/**
 * Тепловая карта зоны спавна. Пока модуль выключен, зона не задана или панель не принимает данные,
 * объекта нет вовсе: ни слушателей, ни задач, ни памяти.
 *
 * <p>Движения игроков не отслеживаются ({@code PlayerMoveEvent} вызывается чаще всего на сервере):
 * позиции снимаются раз в 5 секунд, а телепорты и порталы приходят отдельными событиями.
 */
public final class HeatmapModule implements Listener {

    private static final long SAMPLE_TICKS = 100;
    private static final long FLUSH_TICKS = 5 * 60 * 20;
    /** Описание зоны повторяем раз в час: вдруг панель его потеряла */
    private static final long DEF_PERIOD_MS = 3_600_000;

    private final McDiagPlugin plugin;
    private final HeatZone zone;
    private final List<Poi> pois;
    private final VisitTracker tracker;
    private final HeatAggregator aggregator = new HeatAggregator();
    private final Map<UUID, Boolean> exempt = new HashMap<>();
    private final boolean countAdmins;
    private final Location reusable;
    private final String defJson;
    private long defSentAt;
    private BukkitTask sampleTask;
    private BukkitTask flushTask;

    public HeatmapModule(McDiagPlugin plugin, HeatZone zone, List<Poi> pois, boolean countAdmins) {
        this.plugin = plugin;
        this.zone = zone;
        this.pois = List.copyOf(pois);
        this.countAdmins = countAdmins;
        this.tracker = new VisitTracker(this.pois, HeatAggregator.MAX_VISITS);
        this.reusable = new Location(null, 0, 0, 0);
        this.defJson = HeatAggregator.defJson(zone, this.pois);
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerFancyNpcs();
        sampleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sampleSafely, SAMPLE_TICKS, SAMPLE_TICKS);
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushSafely, FLUSH_TICKS, FLUSH_TICKS);
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        if (sampleTask != null) {
            sampleTask.cancel();
        }
        if (flushTask != null) {
            flushTask.cancel();
        }
        flushSafely();
    }

    public int activeVisits() {
        return tracker.active();
    }

    private void sampleSafely() {
        try {
            sample();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "heatmap sample failed", e);
        }
    }

    private void sample() {
        World world = Bukkit.getWorld(zone.world());
        long now = System.currentTimeMillis();
        tracker.beginSample();
        if (world != null) {
            for (Player player : world.getPlayers()) {
                player.getLocation(reusable);
                int x = reusable.getBlockX();
                int y = reusable.getBlockY();
                int z = reusable.getBlockZ();
                if (!zone.contains(x, y, z) || isExempt(player)) {
                    continue;
                }
                UUID id = player.getUniqueId();
                aggregator.addPosition(x, z, tracker.isNewcomer(id));
                // Хеш считаем один раз за визит: это единственная заметная работа в цикле.
                tracker.sampleInside(id, tracker.visiting(id) ? null : plugin.hasher().hash(id), x, z, now);
            }
        }
        tracker.endSample(now);
    }

    private boolean isExempt(Player player) {
        if (countAdmins) {
            return false;
        }
        return exempt.computeIfAbsent(player.getUniqueId(), id -> player.hasPermission("mcdiag.admin"));
    }

    private void flushSafely() {
        try {
            flush();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "heatmap flush failed", e);
        }
    }

    private void flush() {
        long now = System.currentTimeMillis();
        List<VisitTracker.Finished> visits = tracker.drainFinished();
        boolean sendDef = now - defSentAt >= DEF_PERIOD_MS;
        if (!sendDef && visits.isEmpty() && aggregator.isEmpty()) {
            return;
        }
        if (sendDef) {
            defSentAt = now;
        }
        HeatAggregator.Snapshot snapshot = aggregator.drain(visits);
        String def = sendDef ? defJson : null;
        String zoneId = zone.id();
        // Сводка может быть большой: JSON собираем вне главного потока, если он ещё работает.
        Runnable build = () -> plugin.buffer().addHeat(HeatAggregator.toJson(snapshot, now, zoneId, def));
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, build);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        guard(() -> {
            Player player = event.getPlayer();
            if (isExempt(player)) {
                return;
            }
            Location at = player.getLocation();
            boolean inside = sameWorld(at) && zone.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            tracker.onJoin(player.getUniqueId(), plugin.hasher().hash(player.getUniqueId()), !player.hasPlayedBefore(),
                    inside, at.getBlockX(), at.getBlockZ(), System.currentTimeMillis());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        guard(() -> {
            Player player = event.getPlayer();
            Location at = player.getLocation();
            tracker.onQuit(player.getUniqueId(), at.getBlockX(), at.getBlockZ(), System.currentTimeMillis());
            exempt.remove(player.getUniqueId());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        guard(() -> leaveByTeleport(event, false));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        guard(() -> leaveByTeleport(event, true));
    }

    private void leaveByTeleport(PlayerTeleportEvent event, boolean portal) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!sameWorld(from) || !zone.contains(from.getBlockX(), from.getBlockY(), from.getBlockZ())) {
            return;
        }
        boolean staysInside = to != null && sameWorld(to) && zone.contains(to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (!staysInside) {
            tracker.onLeaveByTeleport(event.getPlayer().getUniqueId(), portal, event.getCause().name(),
                    from.getBlockX(), from.getBlockZ(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        // Самый частый обработчик модуля: сначала самые дешёвые проверки.
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        guard(() -> {
            Block block = event.getClickedBlock();
            if (block == null || !zone.world().equals(block.getWorld().getName())
                    || !zone.contains(block.getX(), block.getY(), block.getZ()) || isExempt(event.getPlayer())) {
                return;
            }
            Material type = block.getType();
            click(event.getPlayer().getUniqueId(), block.getX(), block.getY(), block.getZ(),
                    isContainer(type) ? "chest" : "block", type.name());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        guard(() -> {
            Entity entity = event.getRightClicked();
            if (!isNpc(entity) || isExempt(event.getPlayer())) {
                return;
            }
            Location at = entity.getLocation();
            if (sameWorld(at) && zone.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
                click(event.getPlayer().getUniqueId(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), "npc", entity.getType().name());
            }
        });
    }

    private void click(UUID player, int x, int y, int z, String kind, String material) {
        aggregator.addClick(x, y, z, kind, material);
        tracker.onClick(player, tracker.poiAt(x, z));
    }

    static boolean isContainer(Material type) {
        String name = type.name();
        return name.contains("CHEST") || name.equals("BARREL") || name.contains("SHULKER_BOX");
    }

    /** NPC Citizens — настоящие сущности с меткой, плюс обычные торговцы. */
    private static boolean isNpc(Entity entity) {
        return entity.hasMetadata("NPC") || entity instanceof Villager || entity instanceof WanderingTrader;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        guard(() -> {
            Player victim = event.getEntity();
            Location at = victim.getLocation();
            boolean inZone = sameWorld(at) && zone.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            EntityDamageEvent last = victim.getLastDamageCause();
            String cause = last == null ? null : last.getCause().name();
            String killerType = "env";
            String killer = null;
            String weapon = null;
            LivingEntity attacker = attackerOf(victim, last);
            if (attacker instanceof Player player) {
                killerType = "player";
                killer = player.getName();
                weapon = player.getInventory().getItemInMainHand().getType().name();
            } else if (attacker != null) {
                killerType = "mob";
                killer = attacker.getType().name();
            }
            long now = System.currentTimeMillis();
            aggregator.addDeath(new HeatAggregator.Death(now, plugin.hasher().hash(victim.getUniqueId()), victim.getName(),
                    killerType, killer, weapon, cause, at.getWorld() == null ? null : at.getWorld().getName(),
                    at.getBlockX(), at.getBlockY(), at.getBlockZ(), inZone));
            if (inZone) {
                tracker.onDeath(victim.getUniqueId(), at.getBlockX(), at.getBlockZ(), now);
            }
        });
    }

    private static LivingEntity attackerOf(Player victim, EntityDamageEvent last) {
        if (victim.getKiller() != null) {
            return victim.getKiller();
        }
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                return shooter instanceof LivingEntity living ? living : null;
            }
            return damager instanceof LivingEntity living ? living : null;
        }
        return null;
    }

    private boolean sameWorld(Location at) {
        World world = at.getWorld();
        return world != null && zone.world().equals(world.getName());
    }

    /** Исключение в обработчике Bukkit написал бы в консоль сервера как ошибку. */
    private void guard(Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "heatmap event failed", e);
        }
    }

    /**
     * NPC FancyNpcs существуют только в пакетах, обычное событие их не видит. Плагин не обязателен,
     * поэтому его событие подключается через отражение, и любая ошибка просто оставляет NPC без учёта.
     */
    @SuppressWarnings("unchecked")
    private void registerFancyNpcs() {
        Plugin fancy = Bukkit.getPluginManager().getPlugin("FancyNpcs");
        if (fancy == null) {
            return;
        }
        try {
            Class<?> type = fancy.getClass().getClassLoader().loadClass("de.oliver.fancynpcs.api.events.NpcInteractEvent");
            Method getPlayer = type.getMethod("getPlayer");
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) type, this, EventPriority.MONITOR, (listener, event) -> {
                if (!type.isInstance(event)) {
                    return;
                }
                guard(() -> {
                    try {
                        Player player = (Player) getPlayer.invoke(event);
                        Location at = player.getLocation();
                        if (sameWorld(at) && zone.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ()) && !isExempt(player)) {
                            click(player.getUniqueId(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), "npc", "FANCY_NPC");
                        }
                    } catch (ReflectiveOperationException e) {
                        plugin.getLogger().log(Level.FINE, "FancyNpcs event failed", e);
                    }
                });
            }, plugin, true);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "FancyNpcs events are unavailable", e);
        }
    }

}
