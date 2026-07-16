package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Discord — the E.G.O of <b>Yin</b>, the half of the world that is death, as its twin is birth. A falchion
 * whose edge is shadowed by the world's discord: near-white steel trailing near-black. Yin is not a villain
 * and Discord is not a cruel weapon — birth and death are simply the two turns of one wheel, and this blade
 * only ever finishes the turn that was already coming.
 *
 * <ul>
 *   <li><b>Vestiges of A Whole Past</b> (passive) — while the falchion is in the main hand its wielder is
 *       shored up by the lives that came before: {@value #PASSIVE_ARMOR} more armour and
 *       {@value #PASSIVE_MAX_HEALTH} more maximum health (three hearts). See the note below on <i>how</i>
 *       this is applied — it is the one genuinely delicate piece of engineering in this class.</li>
 *   <li><b>The Devil's Pendant</b> — <b>left-click</b>, {@value #PENDANT_CD_MS} ms apart. A small lance of
 *       light that <b>mirrors off every wall it meets</b> and cuts whatever it passes through, capped at
 *       {@value #PENDANT_MAX_BOUNCES} bounces and {@value #PENDANT_MAX_TRAVEL} blocks of travel.</li>
 *   <li><b>Omnipresence</b> — <b>right-click</b>. Lays a mark on the enemy under the crosshair for
 *       {@value #MARK_LIFETIME_MS} ms. Every point of damage this weapon deals to a marked body — melee,
 *       beam, or Cycle — is amplified by 15%. Several foes may hold marks at once, and the <b>order</b>
 *       they were marked in is remembered.</li>
 *   <li><b>Cycle</b> — <b>sneak + right-click</b>, requires at least one live mark. The wielder appears
 *       behind each marked foe in marking order, cuts it once, and is gone again — one strike every
 *       {@value #CYCLE_STRIKE_DELAY_TICKS} ticks. Each mark is spent as its strike resolves. It should read
 *       as inevitable rather than flashy: you appear, they fall, you appear again.</li>
 * </ul>
 *
 * <p><b>The passive, and why it is built this way.</b> An E.G.O weapon is enchantable precisely because it
 * writes its {@link ItemMeta} once in {@link #createItem()} and never repaints it; a per-tick meta repaint
 * would silently wipe the wielder's vanilla enchants. So the passive must not be driven from
 * {@link #onTick}. Instead both halves are <b>item</b> {@link AttributeModifier}s bound to
 * {@link EquipmentSlotGroup#MAINHAND} and stamped onto the meta once, exactly the way
 * {@link EgoModels#stampWeapon} stamps this roster's attack damage and speed. Vanilla then applies them
 * when the falchion enters the main hand and strips them when it leaves — no ticking, no repaint, no
 * enchant loss, and nothing to clean up on quit or disable. It also means the passive shows in the item's
 * own vanilla "When in Main Hand" tooltip block for free.
 *
 * <p>Because {@link Attribute#MAX_HEALTH} is one of those modifiers, sheathing the falchion drops the
 * wielder's maximum health by {@value #PASSIVE_MAX_HEALTH}; if they were standing inside that top band
 * their current health is clamped down to the new maximum. That is correct vanilla behaviour and it is
 * exactly how "a <i>temporary</i> set of three extra hearts" should read — the borrowed vestiges are not
 * yours, and putting the blade away gives them back.
 *
 * <p><b>State and leaks.</b> Omnipresence marks are <b>victim-keyed</b>, and a mob never fires
 * {@link #onQuit}, so nothing external will ever clean them up. They are therefore pruned <b>inline on
 * every tick</b> ({@link #pruneMarks}) against expiry, death and validity; released wholesale the moment
 * the falchion leaves the main hand ({@link #releaseMarks}); and dropped again on death, on quit, and on
 * {@link #onEntityDeath}. This weapon spawns no entities at all — the beam, the marks and Cycle are pure
 * particles — so there is nothing to sweep for orphans.
 *
 * <p><b>Re-entrancy.</b> Both the beam and Cycle deal their damage through
 * {@link LivingEntity#damage(double, Entity)}, which re-fires {@link EntityDamageByEntityEvent} and
 * re-enters {@link #onHit}. The victim-keyed {@link #striking} fence makes those re-entrant calls a no-op,
 * so a blow this class already amplified is never amplified twice and never loops.
 */
public final class DiscordWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Discord. */
    private final NamespacedKey key;

    /** Keys for the two passive item modifiers. Keyed so they are identifiable and can never duplicate. */
    private final NamespacedKey armorKey;
    private final NamespacedKey healthKey;

    // ---- passive tuning: Vestiges of A Whole Past ---------------------------------

    /**
     * "A little bit more armour." The spec gives no number, so this is a chosen value: +2 armour is one
     * vanilla armour point over a full iron set's chest piece — a real but modest shoring-up, worth about
     * 8% damage reduction on its own, and nowhere near a free armour slot.
     */
    private static final double PASSIVE_ARMOR = 2.0;

    /** "A temporary set of three extra hearts" — 3 hearts = 6.0 health. */
    private static final double PASSIVE_MAX_HEALTH = 6.0;

    // ---- The Devil's Pendant tuning (left-click) ----------------------------------

    /** The beam rests this long between shots. */
    private static final long PENDANT_CD_MS = 4_000L;
    /** A small laser, not a cannon: a touch over a third of the falchion's own swing. */
    private static final double PENDANT_DAMAGE = 3.0;
    /** Blocks of travel per tick. The beam runs on a 1-tick timer. */
    private static final double PENDANT_SPEED = 1.6;
    /** Bounce cap. "Ricochets off every wall for a bit" — a beam that bounces forever is a real perf hazard. */
    private static final int PENDANT_MAX_BOUNCES = 8;
    /** Total path-length cap, in blocks. With PENDANT_SPEED this bounds the beam to ~30 ticks of life. */
    private static final double PENDANT_MAX_TRAVEL = 48.0;
    /** Belt-and-braces lifetime cap, in ticks — the beam can never outlive this even if the maths misbehaves. */
    private static final int PENDANT_MAX_TICKS = 40;
    /** How close to the beam's line a body must be to be cut. */
    private static final double PENDANT_HIT_RADIUS = 1.1;
    /** Distinct bodies one beam may cut. Each is cut at most once, however often the beam crosses it. */
    private static final int PENDANT_MAX_HITS = 8;
    /** Step off a struck face by this much after reflecting, so the beam can't re-hit the same wall. */
    private static final double PENDANT_WALL_NUDGE = 0.02;
    /** Spacing of the beam's drawn motes, in blocks. */
    private static final double PENDANT_DRAW_STEP = 0.35;

    // ---- Omnipresence tuning (right-click) ----------------------------------------

    /** How far the crosshair reaches when laying a mark. */
    private static final double MARK_RANGE = 20.0;
    /** A mark lasts this long. */
    private static final long MARK_LIFETIME_MS = 15_000L;
    /** Damage this weapon deals to a marked body is multiplied by this — +15%. */
    private static final double MARK_AMPLIFY = 1.15;
    /**
     * How many marks one wielder may hold at once. The spec sets no cap; this one exists so a wielder can't
     * brand a whole mob farm and turn Cycle into a hundred-teleport server stall. At the cap the OLDEST mark
     * is released to make room, which keeps marking-order semantics natural.
     */
    private static final int MARK_MAX = 6;
    /** A small gate on marking, purely so a held right-click can't spam the sigil FX. */
    private static final long MARK_CD_MS = 500L;

    // ---- Cycle tuning (sneak + right-click) ---------------------------------------

    /** Per strike, before the mark's own amplification. Spread one-per-foe, so this totals modestly. */
    private static final double CYCLE_DAMAGE = 5.0;
    /** "A little delay between each strike" — 6 ticks, ~0.3s. Inevitable, not frantic. */
    private static final long CYCLE_STRIKE_DELAY_TICKS = 6L;
    /** A marked foe further than this when its turn comes has outrun the cycle; its mark simply dissipates. */
    private static final double CYCLE_REACH = 32.0;
    /** How far behind the target's back the wielder appears. */
    private static final double CYCLE_BEHIND = 1.2;

    // ---- HUD / FX cadence ---------------------------------------------------------

    /** onTick fires every 2 server ticks; refresh the action bar every this many pulses. */
    private static final int HUD_INTERVAL = 3;
    /** Redraw the sigils over marked bodies every this many pulses (~0.5s). */
    private static final int MARK_FX_INTERVAL = 5;
    private static final int MARK_RING_POINTS = 8;
    private static final double MARK_RING_RADIUS = 0.35;

    // ---- state --------------------------------------------------------------------

    /**
     * Wielder -> their live Omnipresence marks, as victim UUID -> expiry epoch-millis. The map is a
     * {@link LinkedHashMap} because Cycle must consume marks in <b>marking order</b>; re-marking a foo that
     * already holds a mark removes and re-inserts it, so a fresh mark always goes to the back of the queue.
     *
     * <p>These inner keys are victim-keyed and mobs never fire {@link #onQuit} — see {@link #pruneMarks}.
     */
    private final Map<UUID, LinkedHashMap<UUID, Long>> marks = new HashMap<>();

    /** Wielder -> epoch-millis of their last Devil's Pendant shot. */
    private final Map<UUID, Long> lastPendant = new HashMap<>();

    /** Wielder -> epoch-millis of their last Omnipresence mark. */
    private final Map<UUID, Long> lastMark = new HashMap<>();

    /** Wielder -> their running Cycle sequence, so it can be cancelled on quit/death/disable. */
    private final Map<UUID, CycleRun> cycleRuns = new HashMap<>();

    /**
     * Victims currently taking a blow this class dealt itself (a beam cut or a Cycle strike). Those calls
     * re-enter {@link #onHit}; while a UUID sits here, onHit refuses to touch the damage — it has already
     * been amplified at source.
     */
    private final Set<UUID> striking = new HashSet<>();

    public DiscordWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "discord");
        this.armorKey = new NamespacedKey(plugin, "discord_vestige_armor");
        this.healthKey = new NamespacedKey(plugin, "discord_vestige_health");
    }

    @Override
    public String id() {
        return "discord";
    }

    // ---- item ---------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.DISCORD.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.DISCORD.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.DISCORD);
        stampVestiges(meta);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Vestiges of A Whole Past, stamped onto the item exactly once. Both halves ride
     * {@link EquipmentSlotGroup#MAINHAND}, so vanilla — not this plugin — applies them on draw and strips
     * them on sheathe. This is the whole reason the passive costs zero ticks and cannot eat an enchant: see
     * the class docs.
     */
    private void stampVestiges(ItemMeta meta) {
        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
                armorKey, PASSIVE_ARMOR, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.MAX_HEALTH, new AttributeModifier(
                healthKey, PASSIVE_MAX_HEALTH, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND));
    }

    // ---- the marks: bookkeeping ---------------------------------------------------

    /** True if this wielder currently holds a live mark on this body. */
    private boolean isMarked(UUID wielderId, UUID victimId) {
        LinkedHashMap<UUID, Long> m = marks.get(wielderId);
        if (m == null) return false;
        Long expiry = m.get(victimId);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    /** This wielder's live marks, in the order they were laid. */
    private List<UUID> markOrder(UUID wielderId) {
        LinkedHashMap<UUID, Long> m = marks.get(wielderId);
        return m == null ? List.of() : new ArrayList<>(m.keySet());
    }

    private int markCount(UUID wielderId) {
        LinkedHashMap<UUID, Long> m = marks.get(wielderId);
        return m == null ? 0 : m.size();
    }

    /**
     * Prune one wielder's marks in place. <b>This is the load-bearing leak guard of the whole class.</b>
     * The marks are keyed by <i>victim</i>, and a mob never fires {@link #onQuit} — so if this did not run
     * every tick, every mob the wielder ever marked would leave a key behind forever. Expired marks go
     * first (that also covers a victim in an unloaded chunk, which the server can't resolve to an entity),
     * then any body that has died or gone invalid. An emptied wielder drops out of the outer map too, so
     * that never accumulates either.
     */
    private void pruneMarks(UUID wielderId) {
        LinkedHashMap<UUID, Long> m = marks.get(wielderId);
        if (m == null) return;

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = m.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() <= now) { it.remove(); continue; }   // lapsed — the commonest exit
            Entity ent = plugin.getServer().getEntity(e.getKey());
            if (ent == null) continue;                            // unloaded/unresolvable — the TTL will take it
            if (!(ent instanceof LivingEntity le) || le.isDead() || !le.isValid()) it.remove();
        }
        if (m.isEmpty()) marks.remove(wielderId);
    }

    /** Spend one mark — Cycle consumes each as its strike resolves. */
    private void consumeMark(UUID wielderId, UUID victimId) {
        LinkedHashMap<UUID, Long> m = marks.get(wielderId);
        if (m == null) return;
        m.remove(victimId);
        if (m.isEmpty()) marks.remove(wielderId);
    }

    /** Let every one of this wielder's marks go at once — the bond runs through the falchion. */
    private void releaseMarks(UUID wielderId) {
        marks.remove(wielderId);
    }

    // ---- Omnipresence (right-click) / Cycle (sneak + right-click) -----------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (sneaking) cycle(player);
        else omnipresence(player);
    }

    /** Lay a mark on the body under the crosshair. It lasts {@value #MARK_LIFETIME_MS} ms. */
    private void omnipresence(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastMark.get(id);
        if (last != null && now - last < MARK_CD_MS) return;   // held right-click — quietly ignore

        LivingEntity target = lookedAt(player, MARK_RANGE);
        if (target == null) {
            player.sendActionBar(EgoHud.status("Omnipresence — no one there", FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.35f, 0.7f);
            return;
        }

        lastMark.put(id, now);
        pruneMarks(id);

        LinkedHashMap<UUID, Long> m = marks.computeIfAbsent(id, k -> new LinkedHashMap<>());
        // Remove-then-put: a re-mark is a fresh marking, so it belongs at the BACK of the cycle's queue.
        m.remove(target.getUniqueId());
        // At the cap, the oldest vestige lets go so the newest can be laid — see MARK_MAX.
        while (m.size() >= MARK_MAX) {
            Iterator<UUID> oldest = m.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        m.put(target.getUniqueId(), now + MARK_LIFETIME_MS);

        EgoDurability.wearMainHand(player);
        markFx(target);
        showHud(player);
    }

    /**
     * Cycle. Requires at least one live mark. Walks the wielder through every marked foe in marking order,
     * one strike every {@value #CYCLE_STRIKE_DELAY_TICKS} ticks, spending each mark as its strike resolves.
     * A wielder can only run one sequence at a time.
     */
    private void cycle(Player player) {
        UUID id = player.getUniqueId();
        if (cycleRuns.containsKey(id)) return;   // a cycle is already turning

        pruneMarks(id);
        List<UUID> order = markOrder(id);
        if (order.isEmpty()) {
            player.sendActionBar(EgoHud.status("Cycle — nothing is marked", FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.35f, 0.7f);
            return;
        }

        EgoDurability.wearMainHand(player);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.5f, 0.6f);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_CONDUIT_DEACTIVATE, 0.4f, 1.4f);

        CycleRun run = new CycleRun(id, order);
        cycleRuns.put(id, run);
        run.runTaskTimer(plugin, 0L, CYCLE_STRIKE_DELAY_TICKS);
    }

    /**
     * The turning of the wheel: one strike per firing. Each firing takes the next marked body in order,
     * appears behind it, cuts it, and spends its mark — whether or not the cut found anything, so a foe
     * that died, unloaded or outran {@value #CYCLE_REACH} simply passes without holding up the sequence.
     * It stops early if the wielder logs out, dies, or sheathes the falchion mid-cycle.
     */
    private final class CycleRun extends BukkitRunnable {
        private final UUID ownerId;
        private final List<UUID> order;
        private int index = 0;

        CycleRun(UUID ownerId, List<UUID> order) {
            this.ownerId = ownerId;
            this.order = order;
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline() || owner.isDead()) { finish(); return; }
            if (!matches(owner.getInventory().getItemInMainHand())) { finish(); return; }  // sheathed mid-cycle
            if (index >= order.size()) { finish(); return; }

            UUID vid = order.get(index++);
            try {
                Entity e = plugin.getServer().getEntity(vid);
                if (!(e instanceof LivingEntity victim) || victim.isDead() || !victim.isValid()) return;
                if (!isMarked(ownerId, vid)) return;                       // the mark lapsed mid-cycle
                if (victim.getWorld() != owner.getWorld()) return;
                if (victim.getLocation().distanceSquared(owner.getLocation()) > CYCLE_REACH * CYCLE_REACH) return;

                appearBehind(owner, victim);
                strike(owner, victim);
            } finally {
                consumeMark(ownerId, vid);   // "consumed upon use after each strike" — spent either way
            }
        }

        private void finish() {
            cancel();
            cycleRuns.remove(ownerId);
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner != null) showHud(owner);
        }
    }

    /**
     * Step out of the world and back in at the target's back. Wall-safe: the ideal spot behind it is tried
     * first, then progressively closer spots, and if the target is flush against geometry with nowhere to
     * stand the wielder simply doesn't move — the cut still lands from where they are, because the cycle
     * does not stop for architecture.
     */
    private void appearBehind(Player owner, LivingEntity victim) {
        Location vLoc = victim.getLocation();
        Vector facing = vLoc.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0e-6) {
            facing = vLoc.toVector().subtract(owner.getLocation().toVector()).setY(0);
        }
        if (facing.lengthSquared() < 1.0e-6) facing = new Vector(0, 0, 1);
        facing.normalize();

        Location dest = null;
        for (double d = CYCLE_BEHIND; d >= 0.0; d -= 0.4) {
            Location cand = vLoc.clone().subtract(facing.clone().multiply(d));
            if (canStand(cand)) { dest = cand; break; }
        }
        if (dest == null) return;                 // nowhere to stand — strike from where we are
        dest.setDirection(facing);                // facing the way it faces == standing at its back

        vanishFx(owner.getLocation());
        owner.teleport(dest);
        vanishFx(dest);
    }

    /** True if a player's body (feet + head) fits here without clipping into a solid block. */
    private static boolean canStand(Location feet) {
        return feet.getBlock().isPassable() && feet.clone().add(0, 1, 0).getBlock().isPassable();
    }

    /**
     * One cut. The mark is still live at this point, so the blow carries its amplification; the
     * {@link #striking} fence keeps the re-entrant {@link #onHit} from amplifying it a second time, and
     * clearing the victim's i-frames first stops a strike landing inside an earlier blow's immunity window
     * from being silently swallowed.
     */
    private void strike(Player owner, LivingEntity victim) {
        UUID vid = victim.getUniqueId();
        if (striking.contains(vid)) return;

        double damage = CYCLE_DAMAGE * (isMarked(owner.getUniqueId(), vid) ? MARK_AMPLIFY : 1.0);
        striking.add(vid);
        try {
            victim.setNoDamageTicks(0);
            victim.damage(damage, owner);
        } finally {
            striking.remove(vid);
        }
        cutFx(owner, victim);
    }

    // ---- The Devil's Pendant (left-click) -----------------------------------------

    /**
     * Left-click looses the beam. Note that a melee attack is also an arm-swing, so a wielder mid-fight
     * fires the pendant on the same input as the swing — that is the platform's grammar, and the
     * {@value #PENDANT_CD_MS} ms gate keeps it to one beam every four seconds regardless. The gate is
     * silent on purpose: buzzing on every gated swing would drone through an entire melee.
     */
    @Override
    public void onSwing(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastPendant.get(id);
        if (last != null && now - last < PENDANT_CD_MS) { showHud(player); return; }

        lastPendant.put(id, now);
        EgoDurability.wearMainHand(player);

        World w = player.getWorld();
        Location eye = player.getEyeLocation();
        w.playSound(eye, Sound.ENTITY_ENDER_EYE_LAUNCH, 0.6f, 0.7f);
        w.playSound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.5f, 1.5f);

        new DevilsPendantBeam(player).runTaskTimer(plugin, 0L, 1L);
        showHud(player);
    }

    /**
     * A small lance of light that mirrors off every wall it meets. Each tick it spends a fixed travel
     * budget across one or more straight legs, using a proper {@link World#rayTraceBlocks} (with the
     * 5-argument overload, so grass and flowers can't clip the shot) to find each wall and reflecting off
     * the struck face's normal.
     *
     * <p><b>Termination is provable.</b> Every pass of the inner loop either consumes the whole remaining
     * budget (no wall in the leg) or increments the bounce count (a wall), so a tick can never run more
     * than {@value #PENDANT_MAX_BOUNCES}+1 passes even if a leg measures zero — which is what saves the
     * beam if it is ever born inside a block. Across ticks it is bounded three ways over:
     * {@value #PENDANT_MAX_BOUNCES} bounces, {@value #PENDANT_MAX_TRAVEL} blocks of path, and
     * {@value #PENDANT_MAX_TICKS} ticks. This is a ~100-player server; a beam that bounces forever is not
     * a cosmetic problem.
     *
     * <p>Entity lookup is <b>one query per tick</b>, not one per leg: every point the beam can reach in a
     * tick lies within {@link #PENDANT_SPEED} of where the tick began, so a single sphere at the tick's
     * start covers every leg it will walk. Each body is cut at most once per beam, however many times the
     * ricochet crosses it.
     */
    private final class DevilsPendantBeam extends BukkitRunnable {
        private final UUID ownerId;
        private final World world;
        private Location pos;
        private Vector dir;
        private double travelled = 0.0;
        private int bounces = 0;
        private int ticks = 0;
        private int spin = 0;
        private final Set<UUID> cut = new HashSet<>();

        DevilsPendantBeam(Player owner) {
            this.ownerId = owner.getUniqueId();
            this.world = owner.getWorld();
            Location eye = owner.getEyeLocation();
            this.dir = eye.getDirection().normalize();
            this.pos = eye.clone().add(dir.clone().multiply(0.5));
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline() || owner.getWorld() != world) { end(); return; }
            if (++ticks > PENDANT_MAX_TICKS) { end(); return; }

            List<LivingEntity> candidates = sweepCandidates();

            double budget = PENDANT_SPEED;
            while (budget > 1.0e-4) {
                if (travelled >= PENDANT_MAX_TRAVEL) { end(); return; }
                double leg = Math.min(budget, PENDANT_MAX_TRAVEL - travelled);

                // The 5-arg overload: FluidCollisionMode.NEVER + ignorePassableBlocks, so only real walls
                // turn the beam — grass, flowers and fluids let it through.
                RayTraceResult hit = world.rayTraceBlocks(pos, dir, leg, FluidCollisionMode.NEVER, true);
                double segment = leg;
                if (hit != null && hit.getHitPosition() != null) {
                    segment = Math.max(0.0, pos.toVector().distance(hit.getHitPosition()));
                }

                Location from = pos.clone();
                drawLeg(from, segment);
                cutAlong(owner, candidates, from, segment);

                pos = from.clone().add(dir.clone().multiply(segment));
                travelled += segment;
                budget -= segment;

                if (hit == null) continue;                       // clear leg — the budget is spent
                if (++bounces > PENDANT_MAX_BOUNCES) { end(); return; }

                BlockFace face = hit.getHitBlockFace();
                if (face == null) { end(); return; }
                Vector normal = face.getDirection();             // unit, pointing out of the struck face
                dir = dir.subtract(normal.clone().multiply(2.0 * dir.dot(normal))).normalize(); // mirror
                pos.add(normal.clone().multiply(PENDANT_WALL_NUDGE));  // step off, so we can't re-hit it
                bounceFx(pos);
            }
        }

        /**
         * The tick's single entity query. Radius is the tick's whole reach plus the beam's own thickness,
         * so no body the beam could touch this tick is missed no matter how the path bends.
         */
        private List<LivingEntity> sweepCandidates() {
            double r = PENDANT_SPEED + PENDANT_HIT_RADIUS;
            List<LivingEntity> out = new ArrayList<>();
            if (cut.size() >= PENDANT_MAX_HITS) return out;
            for (Entity e : world.getNearbyEntities(pos, r, r, r)) {
                if (e.getUniqueId().equals(ownerId)) continue;
                if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
                if (cut.contains(e.getUniqueId())) continue;
                out.add(le);
            }
            return out;
        }

        /** Cut every candidate whose body lies within {@link #PENDANT_HIT_RADIUS} of this leg. */
        private void cutAlong(Player owner, List<LivingEntity> candidates, Location from, double len) {
            if (candidates.isEmpty() || len <= 0.0) return;
            for (LivingEntity le : candidates) {
                if (cut.size() >= PENDANT_MAX_HITS) return;
                UUID vid = le.getUniqueId();
                if (cut.contains(vid)) continue;

                // Distance from the body's centre to the leg, clamped to the leg's ends.
                Vector v = center(le).subtract(from.toVector());
                double along = Math.max(0.0, Math.min(len, v.dot(dir)));
                if (v.clone().subtract(dir.clone().multiply(along)).length() > PENDANT_HIT_RADIUS) continue;

                cut.add(vid);
                pierce(owner, le);
            }
        }

        /** Paint one leg. Forced, because the beam travels well past the ~32-block cull and must still read. */
        private void drawLeg(Location from, double len) {
            for (double t = 0.0; t <= len; t += PENDANT_DRAW_STEP) {
                Location p = from.clone().add(dir.clone().multiply(t));
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0.03, 0.03, 0.03, 0.0, BEAM_CORE, true);
                if ((spin++ & 3) == 0) {
                    world.spawnParticle(Particle.DUST, p, 1, 0.07, 0.07, 0.07, 0.0, DUST_SHADE, true);
                }
            }
        }

        /** A soft chime and a small scatter where the light turns on a wall. */
        private void bounceFx(Location at) {
            world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.6f);
            world.spawnParticle(Particle.DUST, at, 5, 0.12, 0.12, 0.12, 0.0, DUST_PALE, true);
            world.spawnParticle(Particle.ELECTRIC_SPARK, at, 3, 0.1, 0.1, 0.1, 0.0, null, true);
        }

        private void end() {
            world.spawnParticle(Particle.DUST, pos, 6, 0.15, 0.15, 0.15, 0.0, DUST_SHADE, true);
            world.spawnParticle(Particle.SMOKE, pos, 3, 0.1, 0.1, 0.1, 0.0, null, true);
            world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.35f, 1.2f);
            cancel();
        }
    }

    /**
     * The beam's cut. Amplified at source if the body carries this wielder's mark — the beam is damage done
     * with Yin like any other — and fenced so the re-entrant {@link #onHit} leaves it alone. The victim's
     * i-frames are cleared first so a beam arriving just after a swing isn't swallowed by it.
     */
    private void pierce(Player owner, LivingEntity victim) {
        UUID vid = victim.getUniqueId();
        if (striking.contains(vid)) return;

        double damage = PENDANT_DAMAGE * (isMarked(owner.getUniqueId(), vid) ? MARK_AMPLIFY : 1.0);
        striking.add(vid);
        try {
            victim.setNoDamageTicks(0);
            victim.damage(damage, owner);
        } finally {
            striking.remove(vid);
        }

        World w = victim.getWorld();
        Location c = center(victim).toLocation(w);
        w.spawnParticle(Particle.DUST, c, 6, 0.25, 0.35, 0.25, 0.0, DUST_PALE, true);
        w.spawnParticle(Particle.CRIT, c, 4, 0.2, 0.3, 0.2, 0.05, null, true);
        w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.5f, 1.5f);
    }

    // ---- melee: the mark's amplification -------------------------------------------

    /**
     * A landed melee hit. Vanilla's swing damage is left to resolve as normal and simply scaled by the
     * mark, through {@code event.setDamage} — never a second {@code victim.damage()} call, which would
     * re-enter this dispatch. A blow this class dealt itself (beam or Cycle) arrives here fenced and is
     * left alone: it was already amplified at source.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (striking.contains(victim.getUniqueId())) return;   // our own blow re-entering

        UUID aid = attacker.getUniqueId();
        pruneMarks(aid);
        if (!isMarked(aid, victim.getUniqueId())) return;

        event.setDamage(event.getDamage() * MARK_AMPLIFY);
        cutFx(attacker, victim);
    }

    // ---- tick: prune, HUD, sigils ---------------------------------------------------

    /**
     * Ticked only while this player is an engaged wielder. It bails the instant the falchion is not in the
     * main hand — anything else would tick this player forever — and lets their marks go on the way out.
     * While it is held: prune the marks inline (the one thing standing between this weapon and a leak on
     * every mob it ever marks), then refresh the action bar and the sigils on their own slower cadences.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();
        if (!matches(player.getInventory().getItemInMainHand())) {
            releaseMarks(id);      // the vestiges run through the blade; sheathe it and the marks let go
            return false;
        }

        pruneMarks(id);
        if (tick % HUD_INTERVAL == 0) showHud(player);
        if (tick % MARK_FX_INTERVAL == 0) drawSigils(id);
        return true;
    }

    /** The pendant's cooldown and the marks currently held, in the shared E.G.O grammar. */
    private void showHud(Player player) {
        UUID id = player.getUniqueId();
        long rem = remaining(lastPendant.get(id), PENDANT_CD_MS);
        Component cd = rem > 0 ? EgoHud.cooldown("Devil's Pendant", rem, PALE)
                               : EgoHud.ready("Devil's Pendant", PALE);
        player.sendActionBar(cd
                .append(EgoHud.status("   ", FAINT))
                .append(EgoHud.pips("Marks", PALE, markCount(id), MARK_MAX)));
    }

    /** Milliseconds left on a cooldown clock, or 0 if it is ready. Whole seconds are EgoHud's problem. */
    private static long remaining(Long last, long cd) {
        if (last == null) return 0L;
        return Math.max(0L, cd - (System.currentTimeMillis() - last));
    }

    /** A slow sigil turning over each marked body — shadow bleeding into light. */
    private void drawSigils(UUID wielderId) {
        LinkedHashMap<UUID, Long> m = marks.get(wielderId);
        if (m == null || m.isEmpty()) return;
        for (UUID vid : m.keySet()) {
            Entity e = plugin.getServer().getEntity(vid);
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            sigil(le);
        }
    }

    private void sigil(LivingEntity victim) {
        World w = victim.getWorld();
        Location crown = victim.getLocation().add(0, victim.getHeight() + MARK_RING_RADIUS, 0);
        for (int i = 0; i < MARK_RING_POINTS; i++) {
            double a = (Math.PI * 2 * i) / MARK_RING_POINTS;
            Location p = crown.clone().add(Math.cos(a) * MARK_RING_RADIUS, 0.0, Math.sin(a) * MARK_RING_RADIUS);
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0.0, 0.0, 0.0, 0.0, MARK_SIGIL);
        }
    }

    // ---- SFX / VFX -------------------------------------------------------------------

    /** The mark settling on a body: a quiet ring, a low charge, nothing triumphant. */
    private void markFx(LivingEntity target) {
        World w = target.getWorld();
        Location crown = target.getLocation().add(0, target.getHeight() + 0.4, 0);
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, crown, 10, 0.28, 0.16, 0.28, 0.0, MARK_SIGIL);
        w.spawnParticle(Particle.SCULK_SOUL, crown, 2, 0.1, 0.05, 0.1, 0.0);
        w.playSound(target.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.4f, 1.5f);
        w.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.35f, 0.6f);
    }

    /** Appearing / departing. Deliberately understated — Yin does not announce itself. */
    private void vanishFx(Location at) {
        World w = at.getWorld();
        Location body = at.clone().add(0, 1.0, 0);
        w.spawnParticle(Particle.DUST, body, 8, 0.25, 0.5, 0.25, 0.0, DUST_SHADE);
        w.spawnParticle(Particle.DUST, body, 5, 0.25, 0.5, 0.25, 0.0, DUST_PALE);
        w.spawnParticle(Particle.REVERSE_PORTAL, body, 6, 0.2, 0.4, 0.2, 0.02);
        w.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.45f, 0.6f);
    }

    /** A single pale line drawn across the body, edge-on to the striker, and a short dark spray. */
    private void cutFx(Player attacker, LivingEntity victim) {
        World w = victim.getWorld();
        Location chest = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);

        Vector facing = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (facing.lengthSquared() < 1.0e-6) facing = attacker.getEyeLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1.0e-6) facing = new Vector(1, 0, 0);
        facing.normalize();
        Vector right = facing.crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();

        for (int i = -3; i <= 3; i++) {
            Location p = chest.clone().add(right.clone().multiply(i * 0.17)).add(0, i * 0.05, 0);
            w.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, DUST_PALE);
        }
        w.spawnParticle(Particle.DUST, chest, 6, 0.2, 0.25, 0.2, 0.0, DUST_SHADE);
        w.playSound(chest, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.3f);
    }

    private static Vector center(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0).toVector();
    }

    /**
     * The living body under the crosshair within {@code range}. The block raytrace runs first with the
     * 5-argument overload so a wall between the wielder and a foe genuinely blocks the mark, and so grass
     * or a flower in the way does not.
     */
    private LivingEntity lookedAt(Player player, double range) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        double reach = range;
        RayTraceResult wall = world.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        if (wall != null && wall.getHitPosition() != null) {
            reach = eye.toVector().distance(wall.getHitPosition());
        }

        RayTraceResult hit = world.rayTraceEntities(eye, dir, reach, 0.6,
                e -> e instanceof LivingEntity && !e.getUniqueId().equals(player.getUniqueId()));
        if (hit != null && hit.getHitEntity() instanceof LivingEntity le && !le.isDead() && le.isValid()) {
            return le;
        }
        return null;
    }

    // ---- lifecycle -------------------------------------------------------------------

    /**
     * A body that dies is out of the cycle: drop its mark from every wielder holding one. This is only a
     * fast path — {@link #pruneMarks} is the actual guarantee — but it is nearly free (the map is empty for
     * all but a live Discord wielder) and it keeps the HUD honest the instant a marked foe drops.
     */
    @Override
    public void onEntityDeath(EntityDeathEvent event) {
        UUID vid = event.getEntity().getUniqueId();
        striking.remove(vid);
        if (marks.isEmpty()) return;
        marks.values().removeIf(m -> { m.remove(vid); return m.isEmpty(); });
    }

    @Override
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        stopCycle(id);
        releaseMarks(id);
        marks.values().removeIf(m -> { m.remove(id); return m.isEmpty(); });  // they may have been marked
        striking.remove(id);
    }

    @Override
    public void onQuit(UUID id) {
        stopCycle(id);
        releaseMarks(id);
        lastPendant.remove(id);
        lastMark.remove(id);
        striking.remove(id);
        // A quitting PLAYER may also have been someone else's marked foe — mobs can't, which is exactly
        // why pruneMarks has to carry the load.
        marks.values().removeIf(m -> { m.remove(id); return m.isEmpty(); });
    }

    @Override
    public void onDisable() {
        for (CycleRun run : new ArrayList<>(cycleRuns.values())) run.cancel();
        cycleRuns.clear();
        marks.clear();
        lastPendant.clear();
        lastMark.clear();
        striking.clear();
        // No entities to sweep: the beam, the sigils and Cycle are all particles. Any in-flight beam task
        // dies with the scheduler on disable and holds no world state.
    }

    private void stopCycle(UUID id) {
        CycleRun run = cycleRuns.remove(id);
        if (run != null) run.cancel();
    }

    @Override
    public List<String> outstandingReport() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, LinkedHashMap<UUID, Long>> e : marks.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            String who = p != null ? p.getName() : e.getKey().toString().substring(0, 8);
            out.add("discord  " + e.getValue().size() + "/" + MARK_MAX + " Omnipresence marks  (" + who + ")");
        }
        return out;
    }

    // ---- palette & lore ---------------------------------------------------------------

    /** Primary — near-white. The half of the wheel that is birth. */
    private static final TextColor PALE = TextColor.color(0xD8D4E0);

    /**
     * Secondary — the half that is death, and the Abnormality title line. Specified as #15131A, which is
     * so nearly black that a tooltip's background eats it; lifted to the violet-grey the action bar
     * already reads in, which is the same shadow with light let into it. It still sits far below
     * {@link #PALE}, so the two halves of the wheel read exactly as they should — the pale one is still
     * the living one.
     */
    private static final TextColor SHADE = TextColor.color(0x7A7684);

    /** HUD conditions. */
    private static final TextColor FAINT = TextColor.color(0x7A7684);

    private static final Color C_PALE = Color.fromRGB(0xD8, 0xD4, 0xE0);
    private static final Color C_SHADE = Color.fromRGB(0x15, 0x13, 0x1A);

    private static final Particle.DustOptions DUST_PALE = new Particle.DustOptions(C_PALE, 0.8f);
    private static final Particle.DustOptions DUST_SHADE = new Particle.DustOptions(C_SHADE, 0.9f);
    /** The beam's core: light turning to shadow on a single mote — DUST_COLOR_TRANSITION wants a DustTransition. */
    private static final Particle.DustTransition BEAM_CORE = new Particle.DustTransition(C_PALE, C_SHADE, 1.0f);
    /** The mark's sigil: the same turn, run the other way — shadow returning to light. */
    private static final Particle.DustTransition MARK_SIGIL = new Particle.DustTransition(C_SHADE, C_PALE, 0.9f);

    /**
     * Built once. The display name is the weapon (Discord), the title line is the Abnormality (Yin) — the
     * rule of the house, and neither ever repeats the other.
     */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Discord",
            "Yin",
            PALE,
            SHADE,
            List.of(
                    "A falchion edge shadowed by the",
                    "world's discord. Every life is",
                    "trapped in the cycle of",
                    "reincarnation. The power of birth",
                    "and the power of death. Those",
                    "powers are neither good nor evil;",
                    "they are just the way life is."),
            List.of(
                    new EgoLore.Ability("[Passive] Vestiges of A Whole Past",
                            "While the falchion is in hand, gain",
                            "a little more armour and three",
                            "extra hearts. They fade when you",
                            "sheathe it."),
                    new EgoLore.Ability("[Left Click] The Devil's Pendant",
                            "A small laser beam that ricochets",
                            "off every wall for a bit.",
                            "4s cooldown."),
                    new EgoLore.Ability("[Right Click] Omnipresence",
                            "Mark the enemy you are looking at",
                            "for 15s. Damage you deal to it with",
                            "Yin is amplified by 15%."),
                    new EgoLore.Ability("[Shift + Right Click] Cycle",
                            "Appear behind every marked foe in",
                            "turn and cut it down, in the order",
                            "they were marked. Each strike",
                            "spends its mark.")));
}
