package com.nyrrine.reliquary.core;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The vault's registry and central event/tick router. Weapons register here; the
 * manager owns the shared listeners and the per-player tick loop and dispatches to
 * whichever relic a player is holding.
 */
public final class WeaponManager implements Listener {

    private final Reliquary plugin;
    private final Map<String, Weapon> weapons = new LinkedHashMap<>();

    /**
     * Active wielders per weapon. Only these players are ticked — a player is
     * engaged when they draw/swing/use a relic and disengages once its onTick
     * returns false (fully idle). This keeps the tick cost O(wielders), not
     * O(online players), so 1 Arayashiki on a 99-player server costs ~1 player.
     */
    private final Map<Weapon, Set<UUID>> active = new HashMap<>();

    // Shared swing guard: ignore duplicate swing events from a single click.
    private final Map<UUID, Long> lastSwing = new HashMap<>();
    private static final long SLASH_GUARD_MS = 120L;

    private long tick = 0;

    public WeaponManager(Reliquary plugin) {
        this.plugin = plugin;
    }

    public void register(Weapon weapon) {
        weapons.put(weapon.id(), weapon);
        active.put(weapon, new HashSet<>());
    }

    /** Mark a player as an active wielder of this weapon (starts ticking them). */
    public void engage(Weapon weapon, UUID id) {
        Set<UUID> set = active.get(weapon);
        if (set != null) set.add(id);
    }

    /** Engage a player for whatever relic they're currently holding, if any. */
    public void engageHeld(Player player) {
        Weapon w = fromItem(player.getInventory().getItemInMainHand());
        if (w != null) engage(w, player.getUniqueId());
    }

    public Weapon get(String id) {
        return weapons.get(id);
    }

    public Collection<Weapon> all() {
        return weapons.values();
    }

    /** The first registered weapon whose matches() accepts this stack, else null. */
    public Weapon fromItem(ItemStack item) {
        for (Weapon w : weapons.values()) {
            if (w.matches(item)) return w;
        }
        return null;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        new BukkitRunnable() {
            @Override
            public void run() {
                tick++;
                // Only iterate active wielders, not every online player.
                for (Map.Entry<Weapon, Set<UUID>> entry : active.entrySet()) {
                    Weapon w = entry.getKey();
                    Set<UUID> set = entry.getValue();
                    if (set.isEmpty()) continue;
                    Iterator<UUID> it = set.iterator();
                    while (it.hasNext()) {
                        Player p = plugin.getServer().getPlayer(it.next());
                        if (p == null) { it.remove(); continue; }   // offline
                        if (!w.onTick(p, tick)) it.remove();        // fully idle -> stop ticking
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 2L);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        Player player = event.getPlayer();
        Weapon weapon = fromItem(player.getInventory().getItemInMainHand());
        if (weapon == null) return;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastSwing.get(id);
        if (last != null && now - last < SLASH_GUARD_MS) return;
        lastSwing.put(id, now);

        engage(weapon, id);
        weapon.onSwing(player);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        Weapon weapon = fromItem(player.getInventory().getItemInMainHand());
        if (weapon == null) return;

        event.setCancelled(true); // the relic doesn't interact with the world
        engage(weapon, player.getUniqueId());
        weapon.onInteract(player, player.isSneaking());
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Weapon w = fromItem(player.getInventory().getItem(event.getNewSlot()));
        if (w != null) engage(w, player.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        engageHeld(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        for (Weapon w : weapons.values()) {
            if (w.cancelsFallDamage(id)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        for (Weapon w : weapons.values()) {
            w.onEntityDeath(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        for (Weapon w : weapons.values()) {
            w.onPlayerDeath(event);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastSwing.remove(id);
        for (Set<UUID> set : active.values()) set.remove(id);
        for (Weapon w : weapons.values()) {
            w.onQuit(id);
        }
    }
}
