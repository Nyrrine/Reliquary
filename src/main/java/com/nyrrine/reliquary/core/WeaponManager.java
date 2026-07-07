package com.nyrrine.reliquary.core;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The vault's registry and central event/tick router. Weapons register here; the
 * manager owns the shared listeners and the per-player tick loop and dispatches to
 * whichever relic a player is holding.
 */
public final class WeaponManager implements Listener {

    private final Reliquary plugin;
    private final Map<String, Weapon> weapons = new LinkedHashMap<>();

    // Shared swing guard: ignore duplicate swing events from a single click.
    private final Map<UUID, Long> lastSwing = new HashMap<>();
    private static final long SLASH_GUARD_MS = 120L;

    private long tick = 0;

    public WeaponManager(Reliquary plugin) {
        this.plugin = plugin;
    }

    public void register(Weapon weapon) {
        weapons.put(weapon.id(), weapon);
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
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    for (Weapon w : weapons.values()) {
                        w.onTick(player, tick);
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
        weapon.onInteract(player, player.isSneaking());
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
}
