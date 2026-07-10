package com.nyrrine.reliquary.core;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.EnumSet;
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

    /**
     * Players confirmed to have the server resource pack loaded. Used so purely-cosmetic display entities
     * (e.g. Solemn Lament's flying image "particles") can be hidden from clients without the pack — they'd
     * otherwise see the plain fallback item. A player counts as having the pack only once their client
     * reports SUCCESSFULLY_LOADED, so this assumes the pack is server-sent (server.properties resource-pack).
     */
    private final Set<UUID> packLoaded = new HashSet<>();

    // Shared swing guard: ignore duplicate swing events from a single click.
    private final Map<UUID, Long> lastSwing = new HashMap<>();
    private static final long SLASH_GUARD_MS = 120L;

    private long tick = 0;

    /**
     * Every item Material any registered weapon can wear, collected once at register time. {@link #fromItem}
     * uses it as an O(1) gate: on a busy server the vast majority of held items (blocks, food, ordinary
     * tools) share no material with any weapon, so we skip the full per-weapon matches() scan for them —
     * important because ARM_SWING fires continuously while a player mines or attacks.
     */
    private final Set<Material> weaponMaterials = EnumSet.noneOf(Material.class);

    /** Handle to the central 2-tick task so {@link #disable()} can cancel it explicitly. */
    private BukkitTask tickTask;

    public WeaponManager(Reliquary plugin) {
        this.plugin = plugin;
    }

    public void register(Weapon weapon) {
        weapons.put(weapon.id(), weapon);
        active.put(weapon, new HashSet<>());
        // Record every material this weapon's item(s) can be, for the fromItem fast path. Alternate forms
        // (e.g. a pistol form) reuse a material already covered by another weapon, so createItem() +
        // adminVariant() cover the roster in practice.
        try {
            ItemStack sample = weapon.createItem();
            if (sample != null) weaponMaterials.add(sample.getType());
            ItemStack admin = weapon.adminVariant();
            if (admin != null) weaponMaterials.add(admin.getType());
        } catch (Throwable ignored) {
            // A weapon that can't build a sample item at register simply skips the fast-path hint.
        }
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
        // Fast path: if no registered weapon even uses this material, it can't be one of ours. Skips the
        // full per-weapon matches() scan for the common case (empty hand, blocks, food, ordinary tools) —
        // this runs on every arm-swing, which fires continuously while a player mines or attacks.
        if (item == null || !weaponMaterials.contains(item.getType())) return null;
        for (Weapon w : weapons.values()) {
            if (w.matches(item)) return w;
        }
        return null;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.tickTask = new BukkitRunnable() {
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
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastSwing.get(id);
        if (last != null && now - last < SLASH_GUARD_MS) return; // shared swing guard

        ItemStack main = player.getInventory().getItemInMainHand();
        Weapon weapon = fromItem(main);
        if (weapon != null) {
            lastSwing.put(id, now);
            engage(weapon, id);
            weapon.onSwing(player);
            return;
        }

        // Empty main hand: give relics a chance to react to a bare left-click (e.g. Gungnir recall).
        if (main.getType() == Material.AIR) {
            for (Weapon w : weapons.values()) {
                if (w.onBareSwing(player)) {
                    lastSwing.put(id, now);
                    return;
                }
            }
        }
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
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        // Dispatched to every relic — the presser's hand may be empty (its item is out in the world).
        for (Weapon w : weapons.values()) {
            w.onSwapHands(event.getPlayer(), event);
        }
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
        for (Weapon w : weapons.values()) {
            w.onJoin(event.getPlayer());
        }
    }

    /** Called from the plugin's onDisable so relics can return anything they have out in the world. */
    public void disable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Weapon w : weapons.values()) {
            w.onDisable();
        }
    }

    /**
     * A wielder's melee hit lands: dispatch an on-hit hook to whatever relic is in their main hand so it
     * can add a gimmick on top of the vanilla swing. Cheap: one instanceof + a map lookup per melee hit.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        Weapon weapon = fromItem(player.getInventory().getItemInMainHand());
        if (weapon == null) return;
        engage(weapon, player.getUniqueId());
        weapon.onHit(player, victim, event);
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

    /** Track resource-pack load status so cosmetic displays can be hidden from clients without the pack. */
    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> packLoaded.add(id);
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, DISCARDED, INVALID_URL -> packLoaded.remove(id);
            default -> { /* ACCEPTED / DOWNLOADED — wait for the terminal status */ }
        }
    }

    /** True if this player's client has the server resource pack loaded (so cosmetic pack models render). */
    public boolean hasPack(UUID id) {
        return packLoaded.contains(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        packLoaded.remove(id);
        lastSwing.remove(id);
        for (Set<UUID> set : active.values()) set.remove(id);
        for (Weapon w : weapons.values()) {
            w.onQuit(id);
        }
    }
}
