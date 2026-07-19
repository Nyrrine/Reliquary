package com.nyrrine.reliquary.core;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
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
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
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

    /**
     * Wielders whose relic is dealing damage of its own right now, counted by depth so nested moves unwind
     * cleanly. The framework's answer to a hook that re-enters itself.
     *
     * <p>{@link #onEntityDamageByEntity} cannot tell a swing from a relic's own follow-up by looking at the
     * damage: both arrive as a blow from a player holding the relic, which is all the event knows. So it
     * stops guessing and asks. Damage dealt while this is marked is the relic's own doing and is never handed
     * back to it.
     */
    private final Map<UUID, Integer> dealingDepth = new HashMap<>();

    /**
     * Marks a mob whose AI a relic has temporarily suspended, storing whether it had AI before (so a mob
     * spawned mindless is never woken). It lives on the mob's persistent data, not in a map, so it survives
     * a chunk unload/reload and even a crash: {@link #onEntitiesUnload} restores AI before the mob is written
     * to disk, {@link #onEntitiesLoad} catches any that slipped through, and the relic clears it when its own
     * hold ends. Without this, an {@code isValid()}-guarded restore silently skips a chunk-unloaded mob and
     * leaves it saved with {@code NoAI:1} — frozen for good.
     */
    private static final NamespacedKey AI_SUSPENDED_KEY = new NamespacedKey("reliquary", "ai_suspended");

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

        // Empty main hand: give relics a chance to react to a bare left-click (e.g. recalling a thrown weapon).
        if (main.getType() == Material.AIR) {
            for (Weapon w : weapons.values()) {
                if (w.onBareSwing(player)) {
                    lastSwing.put(id, now);
                    return;
                }
            }
        }
    }

    /**
     * Right-click: refuse the world interaction from <b>either</b> hand, but hand the ability to the
     * main-hand relic only.
     *
     * <p>Those are two questions, and one guard was answering both. A relic must never do what its fallback
     * item does, and a crossbow-model E.G.O is a real crossbow until this cancel says otherwise — so gating
     * the whole handler on the main hand meant the off-hand never got its cancel, and every crossbow-model
     * weapon carried in the off-hand was a plain vanilla crossbow firing live arrows, Multishot and all. The
     * cancel now keys off {@link PlayerInteractEvent#getItem()}, the stack in whichever hand actually fired,
     * the same way {@link #onBlockPlace} keys off the placing hand rather than a hand it assumed.
     *
     * <p>The dispatch below stays exactly where it was, deliberately. {@code onInteract} belongs to the
     * main-hand relic and to nothing else: a relic in the off-hand is being <i>carried</i>, not wielded, and
     * Lamp is built on that line — its lantern lights the world from the off-hand while its abilities stay
     * locked to the main hand. Refusing an off-hand relic's interaction must not wake it up.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();

        // Either hand: the relic doesn't interact with the world.
        if (fromItem(event.getItem()) != null) event.setCancelled(true);

        // Main hand only: the relic is wielded, so it gets its ability.
        if (event.getHand() != EquipmentSlot.HAND) return;
        Weapon weapon = fromItem(player.getInventory().getItemInMainHand());
        if (weapon == null) return;

        event.setCancelled(true);
        engage(weapon, player.getUniqueId());
        weapon.onInteract(player, player.isSneaking());
    }

    /**
     * A weapon is never a placeable block, in either hand. Most weapons wear non-block materials so this
     * never fires for them; the fast-path {@link #fromItem} makes those a cheap material check. It matters
     * for a weapon whose fallback material IS a block (e.g. Lamp, a LANTERN): the main-hand interact guard
     * cancels placement from the main hand, but an offhand placement fires with hand == OFF_HAND and slips
     * past it — {@code getItemInHand()} here reports whichever hand placed, so this closes both.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (fromItem(event.getItemInHand()) != null) event.setCancelled(true);
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

    /** True while {@code wielder}'s relic is dealing damage of its own — so this blow is not a swing. */
    public boolean isDealing(UUID wielder) {
        return dealingDepth.containsKey(wielder);
    }

    /**
     * Run {@code damage} marked as {@code wielder}'s relic dealing its own blow, so {@link Weapon#onHit} is
     * not handed it back.
     *
     * <p>A relic that damages from inside its own {@code onHit} needs nothing: the dispatch marks the hook
     * while it runs, so a splash or a cleave struck from in there is already covered. This is for the other
     * half — the follow-up two ticks later, the burst a scheduler drives, the shot a trigger fires — where
     * the hook has long since returned and the framework has no way to recognise the blow as yours. Wrap it
     * and it is recognised:
     *
     * <pre>{@code plugin.weapons().dealing(attacker.getUniqueId(), () -> victim.damage(5.0, attacker));}</pre>
     *
     * <p>Until a relic does that, it must keep its own guard, and a good few do. This does not unwire them.
     */
    public void dealing(UUID wielder, Runnable damage) {
        dealingDepth.merge(wielder, 1, Integer::sum);
        try {
            damage.run();
        } finally {
            dealingDepth.computeIfPresent(wielder, (id, depth) -> depth <= 1 ? null : depth - 1);
        }
    }

    /**
     * Suspend {@code mob}'s AI on a relic's behalf and mark it so the suspension can always be undone — on
     * the relic's own timer, on chunk unload, on reload, or on plugin disable. Idempotent: re-suspending an
     * already-marked mob just re-asserts {@code setAI(false)} without overwriting the remembered state, so a
     * relic that extends a hold never forgets the mob had AI to begin with.
     */
    public void suspendAi(Mob mob) {
        var pdc = mob.getPersistentDataContainer();
        if (!pdc.has(AI_SUSPENDED_KEY, PersistentDataType.BYTE)) {
            pdc.set(AI_SUSPENDED_KEY, PersistentDataType.BYTE, (byte) (mob.hasAI() ? 1 : 0));
        }
        mob.setAI(false);
    }

    /**
     * Undo a {@link #suspendAi}: restore the mob's AI to what it had before and clear the mark. A no-op on a
     * mob this manager never suspended, so a relic may call it unconditionally. Guarded on {@code isDead()}
     * only — never {@code isValid()}, which is also false for the chunk-unloaded mob this whole mechanism
     * exists to save. A dead mob is gone and never written, so it is the only case to skip.
     */
    public void restoreAi(Mob mob) {
        var pdc = mob.getPersistentDataContainer();
        Byte had = pdc.get(AI_SUSPENDED_KEY, PersistentDataType.BYTE);
        if (had == null) return;
        pdc.remove(AI_SUSPENDED_KEY);
        if (!mob.isDead()) mob.setAI(had != 0);
    }

    /**
     * Restore AI for every suspended mob in a chunk about to unload, before it is serialised — the fix for
     * the freeze-forever bug. A relic's own restore timer self-cancels the instant its target stops being
     * valid (which a chunk unload triggers), so without this the mob is written to disk still mindless.
     */
    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity e : event.getEntities()) {
            if (e instanceof Mob mob && mob.getPersistentDataContainer().has(AI_SUSPENDED_KEY, PersistentDataType.BYTE)) {
                restoreAi(mob);
            }
        }
    }

    /**
     * Belt-and-braces for a mob that still slipped through — one saved mindless by an older build or a crash
     * mid-hold. When its chunk loads again, wake it and clear the mark.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (e instanceof Mob mob && mob.getPersistentDataContainer().has(AI_SUSPENDED_KEY, PersistentDataType.BYTE)) {
                restoreAi(mob);
            }
        }
    }

    /**
     * A wielder's melee hit lands: dispatch an on-hit hook to whatever relic is in their main hand so it
     * can add a gimmick on top of the vanilla swing. Cheap: one instanceof + a map lookup per melee hit.
     *
     * <p><b>A relic is never handed its own damage.</b> The event carries nothing that separates a swing from
     * the relic's answer to one — both are a blow from a player holding it — so for a long time every relic
     * that struck twice off a swing got its own hook back and had to recognise itself, privately, on the way
     * in. Most did, each in its own way and under its own name. Crimson Scar did not: its Blood-drunk spray
     * cut the bodies around the target, each cut came back through here as a fresh hit, and the gimmick ran
     * again on its own spray — re-multiplying damage that was already multiplied, and re-spraying from every
     * body it sprayed. The author had reasoned the spray through carefully; nothing about it was careless.
     * The hook simply did not say it could be re-entered, so the trap was the framework's to close, not
     * theirs to keep dodging.
     *
     * <p>The hook is marked while it runs, so anything it damages is recognised as the relic's own doing and
     * stops here. That covers damage struck from inside the hook and nothing else: a follow-up a scheduler
     * delivers later arrives long after this returns, looking exactly like a swing again. A relic wanting
     * that covered too routes it through {@link #dealing}; a relic that hasn't yet keeps its own guard, which
     * is why the private ones are still there and still load-bearing.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        UUID id = player.getUniqueId();
        if (isDealing(id)) return; // the relic's own blow — never hand it back
        Weapon weapon = fromItem(player.getInventory().getItemInMainHand());
        if (weapon == null) return;
        engage(weapon, id);
        dealing(id, () -> weapon.onHit(player, victim, event));
    }

    /**
     * A wielder takes damage: dispatch an on-damaged hook to whatever relic is in their main hand, mirroring
     * {@link #onEntityDamageByEntity} for the receiving end. Listens on the base event so every cause counts
     * (fall, fire, drowning, a mob or player strike) — a relic that only cares about its attacker narrows the
     * event itself.
     *
     * <p>Monitor priority with ignoreCancelled: a relic here only ever sees damage that truly landed. Anything
     * cancelled — by a protection plugin, or by this manager's own {@link #onFallDamage} guard below — never
     * reaches it, so a counting relic can't tally a hit that never was. The tradeoff is that a relic cannot
     * change its incoming damage from this hook; that would need a separate, earlier one.
     *
     * <p>This fires for every damage event on the server, so the cost for a player holding no relic is one
     * instanceof plus the {@link #fromItem} material gate.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Weapon weapon = fromItem(player.getInventory().getItemInMainHand());
        if (weapon == null) return;
        engage(weapon, player.getUniqueId());
        weapon.onDamaged(player, event);
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
