package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Blink;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Boss;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Life for a Daredevil — the abnormality <b>Crumbling Armor</b>. A Lobotomy Corp E.G.O Equipment: an
 * ancient sword whose archetype "desired" that it be useless in the hands of the frightened.
 *
 * <p>The blade is a plain NETHERITE_SWORD swing — vanilla damage, uncancelled — with a single, brutal
 * gimmick in {@link #onHit}: a <b>decapitation execute</b>. When a landed blow finds a foe already at the
 * end of its rope (a normal mob under 25% HP, a player under 10%, a boss under 5%) and that foe is within
 * {@link #EXECUTE_RANGE} blocks, the wielder blinks to <em>directly behind</em> the target and takes the
 * head, wrapped in a clean slice, a burst of blood, and a wet crunch.
 *
 * <p>The sword reads as one idea in two beats: <b>you close, and it is over.</b> {@link #onInteract}'s
 * right-click {@link #dash} is the closing — a committed lunge the way you look, no damage of its own,
 * purely the distance between you and a faltering foe. The execute is the ending. They share a voice
 * deliberately: the dash speaks in a high, quick {@code ITEM_TRIDENT_RETURN} and the execute answers with
 * the same instrument dropped to its lowest register, played close to the wielder's ear alone.
 *
 * <h2>How the head comes off</h2>
 * The finisher is a real, armour-bypassing blow: a {@link DamageSource} built on
 * {@link DamageType#GENERIC_KILL} and credited to the wielder, sized by {@link #executeDamage} to be
 * lethal through the worst mitigation the target can legally stack.
 *
 * <p>{@code GENERIC_KILL} is the type vanilla's own {@code kill()} builds, and of the types the server
 * ships it is the one that actually delivers an execute: it is tagged {@code bypasses_armor},
 * {@code bypasses_resistance} and {@code bypasses_invulnerability} (and, because {@code bypasses_shield}
 * includes the whole {@code bypasses_armor} tag, a raised shield does not stop it either). {@code MAGIC}
 * bypasses armour but is still cut by Resistance, so it cannot promise a kill.
 *
 * <p>The armour bypass is also what keeps the hit honest. The server charges armour durability
 * (~damage/4 <em>per piece</em>) inside a branch it skips outright when the source is tagged
 * {@code bypasses_armor} — so the size of this blow costs the victim's gear nothing. An earlier build
 * dealt a plain 10,000-point <em>attack</em> instead, which carries no such tag; that stripped ~2,500
 * durability off every piece the victim wore, in one hit. Sizing the number down was a workaround for
 * the wrong problem: the damage type was the bug, not the magnitude.
 *
 * <p>That finishing blow is dealt with {@link LivingEntity#damage(double, DamageSource)}, which fires its
 * own {@code EntityDamageByEntityEvent} and re-enters {@link #onHit}. A per-attacker re-entrancy fence
 * ({@link #executing}) makes the re-entrant call a no-op, so one swing lands exactly one execute, never a
 * loop. Going through {@code damage()} rather than straight to a kill is deliberate: it is what marks the
 * wielder as the last attacker, which is what the server later reads to award XP and player-kill drops.
 *
 * <p><b>The execute is lethal by construction and carries no tunable number.</b> {@link #executeDamage} is
 * derived from what the victim has left to spend, not chosen; there is no "execute damage" constant to
 * raise or lower, and the thresholds are the tier ladder rather than a balance dial — Daredevil's
 * {@code 25/10/5} is exactly half Mimicry's {@code 50/25/10} because Daredevil is HE and Mimicry is ALEPH.
 * A complaint that the execute is not <em>felt</em> is therefore always a complaint about its staging, and
 * is answered in {@link #decapFx} and {@link #blinkFx} — never here.
 *
 * <h2>The dash</h2>
 * Right-click lunges the wielder the way they are looking: one charge on a {@value #DASH_COOLDOWN_MS}ms
 * cooldown, no damage, no resource, no entity. It is a velocity impulse rather than a teleport, so the
 * server's own collision keeps the wielder out of geometry; a raytrace ahead additionally scales the
 * impulse down to the room actually available, and refuses the dash outright — without spending the
 * cooldown — when there is nowhere to go. Landing is waived by {@link #cancelsFallDamage} for a short
 * grace, so closing from above never costs the wielder their own legs.
 *
 * <p><b>Drawback.</b> While the sword is held the wielder is diminished: a {@code -4.0} (two-heart)
 * {@link Attribute#MAX_HEALTH} modifier, keyed by {@link #healthKey}, is applied in {@link #onTick} and
 * stripped the moment the blade leaves the hand (the tick then returns {@code false} to disengage). The
 * keyed modifier is remove-before-add and can exist at most once per player; it is also cleared on quit,
 * on death, on join (defensively), and on plugin disable, so it can never stack or leak.
 */
public final class LifeForADaredevilWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Life for a Daredevil. */
    private final NamespacedKey key;

    /** Key for the two-heart MAX_HEALTH burden applied while the blade is held. */
    private final NamespacedKey healthKey;

    /** How far a target may be and still be blinked-to and executed. */
    private static final double EXECUTE_RANGE = 9.0;
    private static final double EXECUTE_RANGE_SQ = EXECUTE_RANGE * EXECUTE_RANGE;

    /**
     * Fraction of a struck body's armour the base swing ignores (NOT full — the execute is the only
     * armour-bypass) — the edge that pays for the wielder's two-heart burden. PLACEHOLDER for the balance wave.
     */
    private static final double DAREDEVIL_ARMOR_IGNORE = 0.45;

    /** How far past the victim's back the wielder arrives — a step, not a stride. */
    private static final double BEHIND_DISTANCE = 1.2;

    /** HP-fraction thresholds below which a struck target qualifies for the decapitation. */
    private static final double THRESH_MOB    = 0.25; // a normal mob under a quarter
    private static final double THRESH_PLAYER = 0.10; // a player under a tenth
    private static final double THRESH_BOSS   = 0.05; // a boss under a twentieth

    /**
     * Protection's ceiling. The server clamps the enchantment's protection factor to 20 and scales damage
     * by {@code 1 - factor/25}, so no legal armour set can shave off more than 80% — five times what is
     * needed to kill is therefore lethal through any of it. {@code GENERIC_KILL} is not tagged
     * {@code bypasses_enchantments}, so this is the one reduction the execute still has to out-muscle.
     */
    private static final double PROTECTION_HEADROOM = 5.0;

    /**
     * Slack folded into the finisher before the headroom multiplies it. Covers the target's absorption
     * hearts and the damage cooldown: a blow landing inside the victim's invulnerability window has the
     * previous hit's damage subtracted from it, and the previous hit here is the sword swing that
     * triggered the execute in the first place.
     */
    private static final double EXECUTE_MARGIN = 40.0;

    /** The two-heart burden borne while the blade is held. */
    private static final double HEALTH_PENALTY = -4.0;

    /** Bosses by type. An entity that {@code instanceof Boss} (carries a boss bar) also counts. */
    private static final Set<EntityType> BOSS_TYPES = EnumSet.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN, EntityType.ELDER_GUARDIAN);

    /** Attackers currently inside their own execute damage() call — the fence against re-entrant onHit. */
    private final Set<UUID> executing = new HashSet<>();

    /** Players currently carrying the two-heart burden, so disable/cleanup is precise. */
    private final Set<UUID> burdened = new HashSet<>();

    // ---- dash state ------------------------------------------------------------------

    /** Wielder -> epoch-millis at which the dash comes back. Absent = ready. */
    private final Map<UUID, Long> dashReadyAt = new HashMap<>();
    /** Wielder -> epoch-millis until which post-dash fall damage is waived. */
    private final Map<UUID, Long> fallGraceUntil = new HashMap<>();

    public LifeForADaredevilWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "life_for_a_daredevil");
        this.healthKey = new NamespacedKey(plugin, "life_for_a_daredevil_burden");
    }

    @Override
    public String id() {
        return "life_for_a_daredevil";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.LIFE_FOR_A_DAREDEVIL.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.LIFE_FOR_A_DAREDEVIL.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.LIFE_FOR_A_DAREDEVIL);

        item.setItemMeta(meta);
        return item;
    }

    // ---- the closing: a right-click dash ---------------------------------------------

    /** One charge, and the wait between charges. */
    private static final long DASH_COOLDOWN_MS = 7_000L;

    /**
     * The impulse itself, quoted from the dash this one was asked to feel like: the same forward power and
     * the same touch of lift, so a level dash still leaves the floor for a moment instead of scraping it.
     */
    private static final double DASH_POWER = 1.9;
    private static final double DASH_LIFT  = 0.1;

    /** Dashing breaks the fall you were in, and the one you are about to take. */
    private static final long DASH_FALL_GRACE_MS = 4_000L;

    /**
     * Wall-safety. The impulse is physics, not a teleport, so the server already refuses to put the wielder
     * inside a block — but being fired face-first into stone is its own kind of bad. So we look
     * {@link #DASH_PROBE} blocks ahead, stop {@link #DASH_WALL_MARGIN} short of the first solid face, and
     * scale the impulse to the room that leaves. Under {@link #DASH_MIN_ROOM} there is no dash worth having:
     * refuse it and keep the charge.
     */
    private static final double DASH_PROBE       = 5.0;
    private static final double DASH_WALL_MARGIN = 0.6;
    private static final double DASH_MIN_ROOM    = 1.2;

    /**
     * Afterimage length. Perf ceiling: {@value #DASH_TRAIL_TICKS} ticks of {@value #DASH_TRAIL_DUST} red dust +
     * {@value #DASH_TRAIL_DARK} black dust = 8 particles a tick, 48 for the whole dash, once per wielder per
     * seven seconds. Nothing here scales with the number of players nearby.
     */
    private static final int DASH_TRAIL_TICKS = 6;
    private static final int DASH_TRAIL_DUST  = 6;
    private static final int DASH_TRAIL_DARK  = 2;

    /**
     * Right-click: dash. Sneaking is not a separate move — a wielder mid-fight is often crouched, and the
     * blade has one active, so both spellings of right-click close the distance.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        dash(player);
    }

    /**
     * The lunge. A velocity impulse the way the wielder looks, clamped to the room actually ahead of them,
     * on one charge and a {@value #DASH_COOLDOWN_MS}ms cooldown.
     *
     * <p>It carries <b>no damage</b>. The blade is {@code 7.0 atk / 1.6 spd} and its whole identity is the
     * execute; a dash that also cut would be a second damage number to defend, and the mobility is the point.
     * It also means the dash can never land a blow on a body the swing is already hitting, so there is no
     * invulnerability window here to force open.
     */
    private void dash(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long ready = dashReadyAt.get(id);
        if (ready != null && now < ready) {
            player.sendActionBar(EgoHud.cooldown("Dash", ready - now, FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 1.3f);
            return;
        }

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // Look ahead: how much room is there, really?
        double room = DASH_PROBE;
        RayTraceResult rt = world.rayTraceBlocks(eye, dir, DASH_PROBE, FluidCollisionMode.NEVER, true);
        if (rt != null) room = eye.toVector().distance(rt.getHitPosition()) - DASH_WALL_MARGIN;
        room = Math.min(DASH_PROBE, Math.max(0.0, room));

        if (room < DASH_MIN_ROOM) {
            // Nose to the wall. Don't fire into it, and don't burn the charge for nothing.
            player.sendActionBar(EgoHud.status("No room to dash…", FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 1.5f);
            return;
        }

        // Scale the impulse to the room: a short gap gives a short lunge rather than a face-plant.
        Vector v = dir.clone().multiply(DASH_POWER * (room / DASH_PROBE));
        v.setY(v.getY() + DASH_LIFT);
        player.setVelocity(v);
        player.setFallDistance(0f); // the dash breaks the fall you were already in

        dashReadyAt.put(id, now + DASH_COOLDOWN_MS);
        fallGraceUntil.put(id, now + DASH_FALL_GRACE_MS);

        // A dash is a non-vanilla use of the blade, so it wears where a swing would not.
        EgoDurability.wearMainHand(player);

        // The blade's voice, high and quick. The execute answers with the same instrument, dropped low.
        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.7f);
        world.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.7f, 1.9f);

        dashTrail(player, world);
        player.sendActionBar(EgoHud.cooldown("Dash", DASH_COOLDOWN_MS, FAINT));
    }

    /**
     * The afterimage: a short steel smear left behind the lunging body. Lifetime-capped and self-cancelling,
     * and it drops out the moment the wielder leaves or changes world. It spawns no entities.
     */
    private void dashTrail(Player player, World world) {
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t++ >= DASH_TRAIL_TICKS || !player.isOnline() || player.getWorld() != world) {
                    cancel();
                    return;
                }
                Location c = player.getLocation().add(0, 1.0, 0);
                world.spawnParticle(Particle.DUST, c, DASH_TRAIL_DUST, 0.3, 0.5, 0.3, 0, DASH_RED);
                world.spawnParticle(Particle.DUST, c, DASH_TRAIL_DARK, 0.2, 0.3, 0.2, 0, DASH_BLACK);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** The dash waives the landing it bought. Polled for every wielder, held blade or not. */
    @Override
    public boolean cancelsFallDamage(UUID id) {
        Long until = fallGraceUntil.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    // ---- gimmick: a decapitation execute on a faltering foe -------------------------

    /**
     * Melee hit landed. The base swing is re-dealt through {@code pierceDamage} so it ignores part of the
     * target's armour ({@link #DAREDEVIL_ARMOR_IGNORE}). If the struck target was already below its HP
     * threshold before the swing and within {@link #EXECUTE_RANGE}, the wielder then blinks behind it and
     * takes the head with an armour-bypassing finishing blow. Both re-enter this method (each fires its own
     * damage event); the {@link #executing} fence and pierceDamage's own fence make those calls no-ops.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID aid = attacker.getUniqueId();
        if (executing.contains(aid)) return;                 // our own execute re-entering — never recurse

        if (victim.isDead() || !victim.isValid()) return;
        if (victim.getWorld() != attacker.getWorld()) return;

        // Whether this blow finishes the target is read on its health BEFORE the swing lands — unchanged: the
        // execute answers a foe already faltering, not one this swing itself brought low.
        boolean faltering = false;
        if (attacker.getLocation().distanceSquared(victim.getLocation()) <= EXECUTE_RANGE_SQ) {
            AttributeInstance maxAttr = victim.getAttribute(Attribute.MAX_HEALTH);
            double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
            faltering = maxHp > 0.0 && victim.getHealth() / maxHp < thresholdFor(victim);
        }

        // The base swing bites through ~45% of the target's armour — the reach the wielder's own two-heart
        // burden is paid for. pierceDamage re-deals the blow fenced (never recursing) with the armour ignored.
        // NOTE it re-deals rather than editing the event, so the swing's vanilla knockback, sweep, on-hit
        // enchant procs and durability wear do not carry — flagged for review.
        double swingDmg = event.getDamage();
        event.setCancelled(true);
        plugin.weapons().pierceDamage(victim, swingDmg, DAREDEVIL_ARMOR_IGNORE, attacker);

        if (faltering) decapitate(attacker, victim);          // already low before the swing — take the head
    }

    /**
     * The execute's damage source: {@link DamageType#GENERIC_KILL}, attributed to the wielder.
     *
     * <p>Both the causing and the direct entity are the wielder — they are stood at the target's back
     * swinging the blade themselves, with nothing in between. That attribution is what names them in the
     * death message and hands them the drops.
     */
    private static DamageSource executeSource(Player attacker) {
        return DamageSource.builder(DamageType.GENERIC_KILL)
                .withCausingEntity(attacker)
                .withDirectEntity(attacker)
                .build();
    }

    /**
     * What it takes to finish this target for certain: everything it has left to spend — health plus
     * absorption plus {@link #EXECUTE_MARGIN} of slack — multiplied by {@link #PROTECTION_HEADROOM}.
     *
     * <p>Derived rather than a fixed overkill number, so it stays lethal against a target with far more
     * health than vanilla grants without ever being an arbitrary magic constant. It costs the victim's
     * armour nothing however large it gets — see the class notes on {@code bypasses_armor}.
     */
    private static double executeDamage(LivingEntity victim) {
        return (victim.getHealth() + victim.getAbsorptionAmount() + EXECUTE_MARGIN) * PROTECTION_HEADROOM;
    }

    /** The HP fraction below which this target may be executed. */
    private double thresholdFor(LivingEntity victim) {
        if (isBoss(victim)) return THRESH_BOSS;
        if (victim instanceof Player) return THRESH_PLAYER;
        return THRESH_MOB;
    }

    /** Boss = one of a small set of boss types, or any entity carrying a boss bar. */
    private boolean isBoss(LivingEntity victim) {
        return BOSS_TYPES.contains(victim.getType()) || victim instanceof Boss;
    }

    /**
     * Blink the wielder to directly behind the target and take the head: FX, a wet crunch, then the
     * armour-bypassing finishing blow fenced against re-entrancy, and a point of non-vanilla wear on the
     * blade.
     */
    private void decapitate(Player attacker, LivingEntity victim) {
        UUID aid = attacker.getUniqueId();

        // Step behind the target — one step past its back, turned to look at the nape.
        //
        // This used to teleport blind, and it was the only player-moving blink in the vault that did: it
        // would happily bury its wielder in a wall if the victim had their back to one, which is exactly
        // where a cornered thing puts its back. Blink.behind answers whether a body fits there and shuffles
        // if it nearly does. When there is genuinely nowhere, the execute still lands — the blade reaches
        // the nape from where it stands. Refusing to kill because the scenery is inconvenient would be a
        // worse bug than the one being fixed, and this is a weapon whose whole identity is that the killing
        // blow arrives.
        Location from = attacker.getLocation().clone();       // remembered so the blink has a departure to read
        Location behind = Blink.behind(victim.getLocation(), BEHIND_DISTANCE);
        if (behind != null) {
            attacker.teleport(behind);
        } else {
            behind = from;                                    // nowhere to stand; take its head from here
        }

        blinkFx(attacker.getWorld(), from, behind);
        decapFx(attacker, victim);

        // The finishing blow: armour-bypassing, credited to the wielder, and sized to be lethal outright.
        // Guard re-entrancy — this damage() re-fires onHit for the same attacker.
        DamageSource source = executeSource(attacker);
        executing.add(aid);
        try {
            victim.damage(executeDamage(victim), source);
        } finally {
            executing.remove(aid);
        }
        // Belt and braces. The blow above is lethal on its own against anything the game can legally put in
        // its way, so reaching here means something outside this weapon intervened — another plugin editing
        // or cancelling the damage event, most plausibly. An execute is defined as a guaranteed kill below
        // the threshold, so honour that. kill() is setHealth(0) plus a death with our own source, which is
        // why it reads better than a bare setHealth(0): that dies to a *generic* source and credits nobody.
        if (!victim.isDead() && victim.isValid() && victim.getHealth() > 0.0) {
            victim.kill(source);
        }

        // Teleport + kill is a non-vanilla action, so wear the blade a point beyond the vanilla swing.
        EgoDurability.wearMainHand(attacker);
    }

    // ---- drawback: two hearts, while borne ------------------------------------------

    /**
     * Ticked only while this player is an engaged wielder. Apply the two-heart burden when the blade is in
     * the main hand; strip it and disengage ({@code return false}) the moment it is not.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (matches(player.getInventory().getItemInMainHand())) {
            applyBurden(player);
            return true;                                      // still held — keep ticking
        }
        clearBurden(player);
        return false;                                         // blade is away — drop the burden, stop ticking
    }

    /** Apply the keyed two-heart MAX_HEALTH modifier if it is not already present (remove-before-add safe). */
    private void applyBurden(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        for (AttributeModifier m : inst.getModifiers()) {
            if (healthKey.equals(m.getKey())) { burdened.add(player.getUniqueId()); return; } // already borne
        }
        inst.addModifier(new AttributeModifier(healthKey, HEALTH_PENALTY, AttributeModifier.Operation.ADD_NUMBER));
        burdened.add(player.getUniqueId());
    }

    /** Strip the keyed burden from a live player, if present. */
    private void clearBurden(Player player) {
        burdened.remove(player.getUniqueId());
        AttributeInstance inst = player.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        for (AttributeModifier m : inst.getModifiers()) {
            if (healthKey.equals(m.getKey())) inst.removeModifier(m);
        }
    }

    @Override
    public void onJoin(Player player) {
        clearBurden(player);                                  // defensive: never inherit a stale burden on login
    }

    @Override
    public void onPlayerDeath(PlayerDeathEvent event) {
        clearBurden(event.getEntity());                       // don't carry the burden through respawn
    }

    @Override
    public void onQuit(UUID id) {
        executing.remove(id);
        burdened.remove(id);
        dashReadyAt.remove(id);
        fallGraceUntil.remove(id);
        Player p = plugin.getServer().getPlayer(id);
        if (p != null) clearBurden(p);                        // still online at quit-time — strip it clean
    }

    @Override
    public void onDisable() {
        for (UUID id : new HashSet<>(burdened)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) clearBurden(p);
        }
        burdened.clear();
        dashReadyAt.clear();
        fallGraceUntil.clear();
    }

    // ---- SFX / VFX ------------------------------------------------------------------

    /**
     * The staging budget. Every count here is fixed — nothing multiplies by nearby players, nothing repeats
     * per tick beyond its own capped lifetime, and none of it is forced (all of it happens at the wielder or
     * within {@link #EXECUTE_RANGE} of them, so every eye that matters is inside the default ~32-block
     * render distance and the server has no reason to push it further).
     *
     * <p>Ceilings for one execute: {@value #BLINK_DUST} + {@value #BLINK_STARS} at each end of the blink
     * (24), the cut at {@value #SLICE_POINTS} points living at most {@value #SLICE_FADE} ticks each (~39 dust
     * + ~4 stars, peak ~13 in a tick), one sweep plate, and {@value #BLOOD_GOUT} + {@value #BLOOD_MIST} of
     * blood (30). ~98 particles total, peak ~55 in the loudest tick, spread over 7 — and it fires once per
     * kill, not per tick. The drama is bought with size, spread and dwell, not with counts.
     */
    private static final int SLICE_POINTS = 12;  // points along the cut; 1 dust each
    private static final int SLICE_REVEAL = 3;   // ticks for the blade to travel the arc
    private static final int SLICE_FADE   = 3;   // ticks each segment lingers behind the edge
    private static final double SLICE_RADIUS    = 0.65; // the neck it wraps
    private static final double SLICE_SWEEP_DEG = 200.0; // most of the way around it
    private static final float  SLICE_SIZE      = 1.25f;
    private static final int BLINK_DUST  = 8;
    private static final int BLINK_STARS = 4;
    private static final int BLOOD_GOUT = 20;    // the fat burst at the cut
    private static final int BLOOD_MIST = 10;    // the fine spray driven up out of it

    /**
     * The blink reads. Previously it did not: the wielder simply teleported, with nothing at either end to
     * say that they had moved, which is most of why an execute that always kills could still fail to land as
     * an event. A steel smear where they left and a steel smear where they arrived, in the dash's own colour,
     * so the closing and the ending are visibly the same blade.
     */
    private void blinkFx(World world, Location from, Location to) {
        Location a = from.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.DUST, a, BLINK_DUST, 0.25, 0.5, 0.25, 0, TRAIL);
        world.spawnParticle(Particle.END_ROD, a, BLINK_STARS, 0.2, 0.4, 0.2, 0.02);
        world.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.6f);

        Location b = to.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.DUST, b, BLINK_DUST, 0.25, 0.5, 0.25, 0, TRAIL);
        world.spawnParticle(Particle.END_ROD, b, BLINK_STARS, 0.2, 0.4, 0.2, 0.02);
    }

    /**
     * The decapitation, staged as a moment rather than a puff.
     *
     * <p>The old version drew a seven-dot line across the nape and a single burst of blood, all in one tick:
     * correct, instant, and almost impossible to notice — which is exactly what "I barely feel it" describes.
     * Nothing about the blow changed here; only its telling. The cut is now animated — the edge travels 200°
     * around the neck over {@value #SLICE_REVEAL} ticks with a {@value #SLICE_FADE}-tick trail behind it, the
     * way the blade in this roster always draws itself — and it lands on a vanilla sweep plate, a fat gout of
     * blood, and a fine mist driven up out of the wound.
     *
     * <p>The sound is layered so the beat has an order to it: the sweep is the edge arriving, the crit is it
     * biting, the bone break is the neck, and the anvil is what is left hitting the floor. The wielder alone
     * also gets the blade's own note, dropped to its lowest register and played at their ear — the same
     * {@code ITEM_TRIDENT_RETURN} their dash sings high. Everyone nearby hears an execution; the executioner
     * hears their sword.
     */
    private void decapFx(Player attacker, LivingEntity victim) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = victim.getWorld();
        Location neck = victim.getLocation().add(0, victim.getHeight() * 0.85, 0);

        // Order the beat: edge, bite, neck, and the weight of it landing.
        world.playSound(neck, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.9f + (rng.nextFloat() - 0.5f) * 0.1f);
        world.playSound(neck, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.7f + (rng.nextFloat() - 0.5f) * 0.1f);
        world.playSound(neck, Sound.BLOCK_BONE_BLOCK_BREAK, 0.9f, 0.6f + (rng.nextFloat() - 0.5f) * 0.1f);
        world.playSound(neck, Sound.BLOCK_ANVIL_LAND, 0.35f, 1.4f + (rng.nextFloat() - 0.5f) * 0.1f);
        // Close-mic'd, for the one who swung: the dash's note, an octave down and final.
        attacker.playSound(attacker.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.8f, 0.7f);

        // The frame the cut is drawn in — flat at the nape, so the edge travels horizontally through it.
        Vector dir = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 1.0e-6) dir = attacker.getEyeLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0e-6) dir = new Vector(1, 0, 0);
        dir.normalize();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();

        sliceArc(world, neck, dir, right);

        // The vanilla slash plate — the read every player already knows means "a cut landed".
        world.spawnParticle(Particle.SWEEP_ATTACK, neck, 1, 0.0, 0.0, 0.0, 0.0);

        // Blood: a fat gout opening at the cut, and a fine mist driven up out of it.
        world.spawnParticle(Particle.DUST, neck, BLOOD_GOUT, 0.30, 0.20, 0.30, 0.0, BLOOD_FAT);
        world.spawnParticle(Particle.DUST, neck.clone().add(0, 0.15, 0), BLOOD_MIST, 0.06, 0.02, 0.06, 0.22, BLOOD_FINE);
    }

    /**
     * The cut itself: an edge travelling {@value #SLICE_SWEEP_DEG}° around the nape, revealed point by point
     * with a fading trail behind it. One dust per point per tick, lifetime-capped, no entities.
     */
    private void sliceArc(World world, Location neck, Vector dir, Vector right) {
        final int n = SLICE_POINTS;
        final Location[] pts = new Location[n + 1];
        final int[] birth = new int[n + 1];
        final double sweep = Math.toRadians(SLICE_SWEEP_DEG);
        for (int i = 0; i <= n; i++) {
            double f = (double) i / n;
            double a = -sweep / 2.0 + sweep * f;
            Vector radial = dir.clone().multiply(Math.cos(a) * SLICE_RADIUS)
                    .add(right.clone().multiply(Math.sin(a) * SLICE_RADIUS));
            pts[i] = neck.clone().add(radial);
            birth[i] = Math.round((float) SLICE_REVEAL * i / n);
        }
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t > SLICE_REVEAL + SLICE_FADE) { cancel(); return; }
                for (int i = 0; i <= n; i++) {
                    int age = t - birth[i];
                    if (age < 0 || age >= SLICE_FADE) continue;
                    float sz = SLICE_SIZE * (1.0f - 0.55f * age / SLICE_FADE);
                    world.spawnParticle(Particle.DUST, pts[i], 1, 0, 0, 0, 0,
                            new Particle.DustOptions(SLICE, sz));
                    if (age == 0 && i % 4 == 0) {             // the glowing leading edge
                        world.spawnParticle(Particle.END_ROD, pts[i], 1, 0, 0, 0, 0);
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---- lore -----------------------------------------------------------------------

    /** Primary — the blade's pale gray-blue. Display name, "How to use:", ability headers. */
    private static final TextColor NAME  = TextColor.color(0x8FA6BE);
    /** Secondary — the dimmer steel the conditions were always read in. The Abnormality title line. */
    private static final TextColor FAINT = TextColor.color(0x7C8794);

    // Particle colours, kept apart from the lore palette so tuning one never disturbs the other.
    private static final Color     SLICE = Color.fromRGB(0xED, 0xEF, 0xF2); // the clean slice — near-white
    private static final Color     BLOOD = Color.fromRGB(0x8A, 0x0F, 0x12); // decapitation blood — dark red
    private static final Color     STEEL = Color.fromRGB(0x8F, 0xA6, 0xBE); // the blink smear — the blade

    private static final Particle.DustOptions TRAIL      = new Particle.DustOptions(STEEL, 1.0f);
    private static final Particle.DustOptions BLOOD_FAT  = new Particle.DustOptions(BLOOD, 1.6f);
    private static final Particle.DustOptions BLOOD_FINE = new Particle.DustOptions(BLOOD, 1.0f);

    // The dash wears its own colours — black and red (playtest §3.5), kept off TRAIL so the execute blink stays steel.
    private static final Particle.DustOptions DASH_RED   = new Particle.DustOptions(Color.fromRGB(0xC4, 0x12, 0x12), 1.0f);
    private static final Particle.DustOptions DASH_BLACK = new Particle.DustOptions(Color.fromRGB(0x0A, 0x0A, 0x0A), 1.0f);

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Life for a Daredevil",   // display name — always the weapon
            "Crumbling Armor",        // title line — always the Abnormality
            NAME,
            FAINT,
            List.of(
                    "An ancient sword.",
                    "Just as its archetype desired, it",
                    "will be useless in the hands of",
                    "the frightened"
            ),
            List.of(
                    new EgoLore.Ability("[Left Click] Blink Execute",
                            "Blink behind a faltering foe within",
                            "9 blocks and take its head. The",
                            "finishing blow ignores armour,",
                            "shields and Resistance, and costs",
                            "the victim's gear no durability.",
                            "Executes mob <25%, player <10%,",
                            "boss <5% HP."),
                    new EgoLore.Ability("[Right Click] Dash",
                            "Lunge the way you look. It stops",
                            "short of walls rather than driving",
                            "into them, and is not spent if there",
                            "is no room. Fall damage is waived",
                            "briefly on landing. Deals no damage.",
                            "One charge, 7 second cooldown."),
                    new EgoLore.Ability("[Passive] Max Health Penalty",
                            "Holding it costs you 2 hearts.")
            ));
}
