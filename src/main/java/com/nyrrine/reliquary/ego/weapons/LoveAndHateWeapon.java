package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoEnchants;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In the Name of Love and Hate — "The Queen of Hatred" (Lobotomy Corp E.G.O, WAW).
 *
 * <p>A magical-girl's wand that lives in two hearts at once. Left-click <b>cycles the wand's form</b>
 * between <b>Love</b> (soft pink-and-white) and <b>Hate</b> (a harsh red-violet); the current form rides
 * the action bar, and every ability changes shape with it.
 *
 * <ul>
 *   <li><b>Love · right-click</b> — loose a <b>mending mote</b> that passes through everything it meets,
 *       healing and casting Regeneration on the living. It deals no damage.</li>
 *   <li><b>Love · sneak + right-click — Minor Arcana Slave</b>: a bright beam that <b>ricochets off walls</b>,
 *       showering Regeneration III on anyone its light touches. 2-minute cooldown.</li>
 *   <li><b>Hate · right-click</b> — call up a fan of <b>homing bolts from behind you</b> that chase enemies
 *       down and detonate for damage.</li>
 *   <li><b>Hate · sneak + right-click — Reverse Arcana Slave</b>: a long, dramatic chant draws converging
 *       magic circles inward, then fires a <b>massive laser that follows your aim for ten seconds</b>,
 *       dealing tick damage (no knockback) and temporarily carving a tunnel through the blocks in front of
 *       you. 5-minute cooldown.</li>
 * </ul>
 *
 * <p>All state is a handful of in-memory UUID maps, dropped on quit. Every runnable is lifetime-capped and
 * self-cancels the moment its caster goes offline, so nothing leaks and no work runs for non-wielders. Any
 * block the Reverse Arcana laser carves is guaranteed to return — on a per-block timer during play, and
 * force-restored on quit or plugin shutdown.
 */
public final class LoveAndHateWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;
    private final NamespacedKey key;

    // ---- per-wielder state --------------------------------------------------------
    /** Wielder -> true if the wand is in HATE form (default false = LOVE). */
    private final Map<UUID, Boolean> hateForm = new HashMap<>();
    /** Wielder -> epoch-millis of their last mending mote (Love right-click). */
    private final Map<UUID, Long> lastMend = new HashMap<>();
    /** Wielder -> epoch-millis of their last homing salvo (Hate right-click). */
    private final Map<UUID, Long> lastSalvo = new HashMap<>();
    /** Wielder -> epoch-millis of their last Minor Arcana Slave (Love ult). */
    private final Map<UUID, Long> lastMinor = new HashMap<>();
    /** Wielder -> epoch-millis of their last Reverse Arcana Slave (Hate ult). */
    private final Map<UUID, Long> lastReverse = new HashMap<>();

    /** Every temp-carve batch out in the world right now, so we can force-restore on quit/disable. */
    private final Set<CarveBatch> batches = new HashSet<>();

    /** Re-entrancy fence: true while we are re-dealing a bolt's damage (after clearing the victim's i-frames). */
    private boolean reDealingBolt = false;

    // ---- tuning -------------------------------------------------------------------
    private static final long MEND_CD_MS    = 900L;      // Love right-click gate
    private static final long SALVO_CD_MS   = 3500L;     // Hate right-click gate — 3.5s so the 4-hit salvo isn't spammable
    private static final long MINOR_CD_MS   = 120_000L;  // Minor Arcana Slave — 2 minutes
    private static final long REVERSE_CD_MS = 300_000L;  // Reverse Arcana Slave — 5 minutes

    /**
     * ENCHANT — Arcana Focus (custom id {@code "arcana_focus"}): each level shaves this off the current
     * form's ult cooldown, capped at {@link #ARCANA_FOCUS_CAP} and never below {@link #ARCANA_FOCUS_MIN_MS}.
     * At max it trims 45s — Minor 120s→75s, Reverse 300s→255s. Pure cadence; the ult's effect is unchanged,
     * so it can't push the Aleph's burst out of band. PLACEHOLDER (balance wave). */
    private static final long ARCANA_FOCUS_REDUCTION_MS = 15_000L;
    private static final int  ARCANA_FOCUS_CAP          = 3;
    private static final long ARCANA_FOCUS_MIN_MS       = 60_000L;

    private static final double MEND_HEAL    = 3.0;      // 1.5 hearts to each ally the mote passes
    private static final int    MEND_REGEN_TICKS = 100;  // Regeneration I on a mended ally
    private static final int    MEND_REGEN_AMP   = 0;

    private static final double SALVO_DAMAGE = 4.0;      // per homing bolt
    private static final int    SALVO_COUNT  = 4;        // bolts per cast

    private static final int    MINOR_REGEN_TICKS = 120; // Regeneration III on a beam-struck body
    private static final int    MINOR_REGEN_AMP   = 2;   // amplifier 2 == level III

    // Reverse Arcana Slave.
    private static final int    CHARGE_TICKS   = 100;    // ~5s dramatic wind-up (was ~1s)
    private static final int    LASER_TICKS    = 200;    // the laser channels for 10s
    private static final double LASER_RANGE    = 40.0;   // how far the beam reaches / carves
    private static final double LASER_RADIUS   = 1.8;    // how close to the line counts as struck

    /**
     * ENCHANT — Heart's Reach (custom id {@code "hearts_reach"}): each level lengthens the Reverse Arcana
     * beam by this many blocks, capped at {@link #HEARTS_REACH_CAP} — max +8, a 40→48 lance. Pure reach:
     * the per-pulse damage ({@link #LASER_DAMAGE}), its cadence, radius and the {@link #CARVE_MAX} carve
     * budget are all unchanged, so a longer beam costs nothing but its own length. Read once at cast so the
     * drawn beam and the scanned line are always the same length. PLACEHOLDER (balance wave). */
    private static final double HEARTS_REACH_PER_LEVEL = 4.0;
    private static final int    HEARTS_REACH_CAP       = 2;
    private static final int    LASER_DMG_PERIOD = 8;    // tick-damage cadence (respects i-frames)
    private static final double LASER_DAMAGE   = 4.0;    // per pulse, no knockback
    private static final int    LASER_MAX_TARGETS = 12;
    private static final int    CARVE_MAX      = 600;    // cap on concurrently-open temp-carved blocks; restores free slots as they expire
    private static final long   CARVE_TTL_MS   = 3000L;  // a carved block pops back ~3s after the beam passes

    // ---- palette ------------------------------------------------------------------
    private static final TextColor NAME  = TextColor.color(0xFF5FB0); // rose pink (display name, "How to use:", ability headers)
    private static final TextColor LOVE_TEXT = TextColor.color(0xFFC4E4); // soft pink/white (Love HUD)
    private static final TextColor HATE_TEXT = TextColor.color(0xFF3355); // harsh red-violet (Hate HUD, Abnormality title line)

    // Love sparkle — pink / white / blush.
    private static final Color C_PINK  = Color.fromRGB(0xFF, 0x7F, 0xC4);
    private static final Color C_WHITE = Color.fromRGB(0xFF, 0xF0, 0xF8);
    private static final Color C_BLUSH = Color.fromRGB(0xFF, 0xC4, 0xE4);
    // Hate sparkle — red / violet.
    private static final Color C_RED    = Color.fromRGB(0xFF, 0x2A, 0x4D);
    private static final Color C_VIOLET = Color.fromRGB(0x9A, 0x3D, 0xFF);

    private static final Particle.DustOptions DUST_PINK   = new Particle.DustOptions(C_PINK, 0.8f);
    private static final Particle.DustOptions DUST_WHITE  = new Particle.DustOptions(C_WHITE, 0.6f);
    private static final Particle.DustOptions DUST_BLUSH  = new Particle.DustOptions(C_BLUSH, 0.7f);
    private static final Particle.DustOptions DUST_RED    = new Particle.DustOptions(C_RED, 0.9f);
    private static final Particle.DustOptions DUST_VIOLET = new Particle.DustOptions(C_VIOLET, 0.9f);
    // The Reverse Arcana laser runs larger — it's the ultimate.
    private static final Particle.DustOptions LASER_CORE = new Particle.DustOptions(C_VIOLET, 1.4f);
    private static final Particle.DustOptions LASER_EDGE = new Particle.DustOptions(C_RED, 1.1f);

    public LoveAndHateWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "love_and_hate");
    }

    @Override
    public String id() {
        return "love_and_hate";
    }

    private boolean isHate(UUID id) {
        return hateForm.getOrDefault(id, Boolean.FALSE);
    }

    // ---- form cycle (left-click) --------------------------------------------------

    @Override
    public void onSwing(Player player) {
        UUID id = player.getUniqueId();
        boolean nowHate = !isHate(id);
        hateForm.put(id, nowHate);
        World w = player.getWorld();
        Location c = player.getEyeLocation();
        if (nowHate) {
            w.playSound(c, Sound.ENTITY_WITHER_SHOOT, 0.5f, 1.6f);
            w.playSound(c, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.7f, 0.9f);
            ringPuff(w, c, true);
        } else {
            w.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.7f);
            w.playSound(c, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.5f);
            ringPuff(w, c, false);
        }
        showHud(player);
    }

    /** A quick sparkle ring at the wand when the form flips. */
    private void ringPuff(World w, Location c, boolean hate) {
        Vector[] b = perp(c.getDirection());
        for (int i = 0; i < 12; i++) {
            double a = (i / 12.0) * Math.PI * 2;
            Location p = c.clone()
                    .add(b[0].clone().multiply(Math.cos(a) * 0.7))
                    .add(b[1].clone().multiply(Math.sin(a) * 0.7));
            twinkle(w, p, hate, i);
        }
    }

    // ---- HUD ----------------------------------------------------------------------

    /**
     * The always-on composed readout: the current form, that form's ult (its cooldown or ready), and — in
     * Hate — the Salvo cooldown while it runs, all on ONE line via {@link EgoHud#row}. Every form-flip and
     * every gated cast sends this, so a form indicator and a cooldown never flash in over one another.
     */
    private void showHud(Player player) {
        UUID id = player.getUniqueId();
        boolean hate = isHate(id);
        long now = System.currentTimeMillis();
        player.sendActionBar(EgoHud.row(
                formReadout(hate),
                ultReadout(player, hate, now),
                salvoReadout(id, hate, now)));
    }

    /** The form indicator — the heart the wand is speaking through right now. */
    private Component formReadout(boolean hate) {
        return hate ? EgoHud.status("✖ Hate", HATE_TEXT) : EgoHud.status("♥ Love", LOVE_TEXT);
    }

    /** The current form's ultimate: its cooldown while recharging, else ready. */
    private Component ultReadout(Player player, boolean hate, long now) {
        UUID id = player.getUniqueId();
        if (hate) {
            long rem = cooldownRemaining(lastReverse.get(id), ultCooldown(player, REVERSE_CD_MS), now);
            return rem > 0 ? EgoHud.cooldown("Reverse Arcana", rem, HATE_TEXT)
                           : EgoHud.ready("Reverse Arcana", HATE_TEXT);
        }
        long rem = cooldownRemaining(lastMinor.get(id), ultCooldown(player, MINOR_CD_MS), now);
        return rem > 0 ? EgoHud.cooldown("Minor Arcana", rem, LOVE_TEXT)
                       : EgoHud.ready("Minor Arcana", LOVE_TEXT);
    }

    /**
     * ENCHANT — Arcana Focus (custom id {@code "arcana_focus"}): the current form's ult cooldown after
     * Arcana Focus trims it (capped, floored). Both the gate and the HUD read through here so the buzz and
     * the shown timer always agree. Cadence only — verdict/ult effect is untouched.
     */
    private long ultCooldown(Player player, long baseCd) {
        int level = Math.min(ARCANA_FOCUS_CAP,
                EgoEnchants.level(player.getInventory().getItemInMainHand(), "arcana_focus"));
        long cd = baseCd - Math.max(0, level) * ARCANA_FOCUS_REDUCTION_MS;
        return Math.max(ARCANA_FOCUS_MIN_MS, cd);
    }

    /** The Hate-form Salvo's cooldown while it runs; dropped entirely in Love or once the salvo is ready. */
    private Component salvoReadout(UUID id, boolean hate, long now) {
        if (!hate) return null;
        long rem = cooldownRemaining(lastSalvo.get(id), SALVO_CD_MS, now);
        return rem > 0 ? EgoHud.cooldown("Salvo", rem, HATE_TEXT) : null;
    }

    private static long cooldownRemaining(Long last, long cd, long now) {
        if (last == null) return 0L;
        long rem = cd - (now - last);
        return Math.max(0L, rem);
    }

    // ---- cast dispatch (right-click) ----------------------------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (isHate(player.getUniqueId())) {
            if (sneaking) castReverseArcana(player);
            else fireHomingSalvo(player);
        } else {
            if (sneaking) castMinorArcana(player);
            else fireMendingMote(player);
        }
    }

    /** True (and buzzes) if this ability is still on cooldown; drives the HUD to show the remaining time. */
    private boolean gated(Player player, Map<UUID, Long> map, long cd, boolean ult) {
        UUID id = player.getUniqueId();
        long rem = cooldownRemaining(map.get(id), cd, System.currentTimeMillis());
        if (rem <= 0) return false;
        if (ult) {
            showHud(player);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f,
                    isHate(id) ? 0.9f : 1.6f);
        }
        return true;
    }

    // ---- Love · mending mote ------------------------------------------------------

    private void fireMendingMote(Player player) {
        if (gated(player, lastMend, MEND_CD_MS, false)) return;
        lastMend.put(player.getUniqueId(), System.currentTimeMillis());
        EgoDurability.wearMainHand(player); // no-op on the durability-free rod; called for consistency

        World w = player.getWorld();
        Location muzzle = player.getEyeLocation();
        w.playSound(muzzle, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.8f);
        w.playSound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.5f, 1.7f);
        new MendingMote(player).runTaskTimer(plugin, 0L, 1L);
    }

    /** Restore a little health to a living body and bless it with Regeneration. Never overheals. */
    private void mend(LivingEntity le) {
        AttributeInstance maxAttr = le.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        le.setHealth(Math.min(maxHp, le.getHealth() + MEND_HEAL));
        le.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, MEND_REGEN_TICKS, MEND_REGEN_AMP));
        Location c = le.getLocation().add(0, le.getHeight() * 0.6, 0);
        World w = le.getWorld();
        w.spawnParticle(Particle.GLOW, c, 4, 0.25, 0.4, 0.25, 0);
        w.spawnParticle(Particle.DUST, c, 4, 0.25, 0.4, 0.25, 0, DUST_BLUSH);
        w.playSound(c, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 2.0f);
    }

    /**
     * A mote of holy light. It marches cheap sub-steps so it can't tunnel, mends each living body it
     * overlaps (once) and flies straight on through them — it deals no damage. It ends on a wall or when
     * its brief lifetime runs out.
     */
    private final class MendingMote extends BukkitRunnable {
        private static final double SPEED = 0.9;
        private static final double STEP  = 0.4;
        private static final double HIT   = 1.3;
        private static final int    MAX_TICKS = 60;

        private final UUID ownerId;
        private final World world;
        private final Location pos;
        private final Vector dir;
        private int ticks = 0;
        private int spin = 0;
        private final Set<UUID> mended = new HashSet<>();

        MendingMote(Player owner) {
            this.ownerId = owner.getUniqueId();
            this.world = owner.getWorld();
            this.pos = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.6));
            this.dir = owner.getEyeLocation().getDirection().normalize();
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) { cancel(); return; }
            if (++ticks > MAX_TICKS) { fizzle(); return; }

            double moved = 0.0;
            while (moved < SPEED) {
                double step = Math.min(STEP, SPEED - moved);
                Location next = pos.clone().add(dir.clone().multiply(step));
                if (next.getBlock().getType().isSolid()) { fizzle(); return; }
                pos.add(dir.clone().multiply(step));
                twinkle(world, pos, false, spin++);
                for (Entity e : world.getNearbyEntities(pos, HIT, HIT, HIT)) {
                    if (!(e instanceof LivingEntity le) || le.isDead()) continue;
                    if (mended.contains(e.getUniqueId())) continue;
                    mended.add(e.getUniqueId());
                    mend(le);
                }
                moved += step;
            }
        }

        private void fizzle() {
            world.spawnParticle(Particle.GLOW, pos, 5, 0.15, 0.15, 0.15, 0.01);
            world.spawnParticle(Particle.DUST, pos, 4, 0.15, 0.15, 0.15, 0, DUST_BLUSH);
            world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.9f);
            cancel();
        }
    }

    // ---- Love · Minor Arcana Slave (ricochet beam) --------------------------------

    private void castMinorArcana(Player player) {
        if (gated(player, lastMinor, ultCooldown(player, MINOR_CD_MS), true)) return;
        lastMinor.put(player.getUniqueId(), System.currentTimeMillis());
        EgoDurability.wearMainHand(player);

        World w = player.getWorld();
        w.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 0.9f, 1.6f);
        w.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.7f, 1.4f);
        new RicochetBeam(player).runTaskTimer(plugin, 0L, 1L);
        showHud(player);
    }

    /**
     * A bright mote of light that bounces off block faces, gilding everything living near its path with
     * Regeneration III. Lifetime-capped and bounce-capped; cancels the instant its caster leaves.
     */
    private final class RicochetBeam extends BukkitRunnable {
        private static final double SPEED = 0.85;
        private static final double STEP  = 0.4;
        private static final double HIT   = 1.6;
        private static final int    MAX_TICKS   = 130;
        private static final int    MAX_BOUNCES = 30;

        private final UUID ownerId;
        private final World world;
        private final Location pos;
        private final Vector dir;
        private int ticks = 0;
        private int spin = 0;
        private int bounces = 0;
        private final Set<UUID> touched = new HashSet<>();

        RicochetBeam(Player owner) {
            this.ownerId = owner.getUniqueId();
            this.world = owner.getWorld();
            this.pos = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.6));
            this.dir = owner.getEyeLocation().getDirection().normalize();
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) { cancel(); return; }
            if (++ticks > MAX_TICKS) { end(); return; }

            double moved = 0.0;
            while (moved < SPEED) {
                double step = Math.min(STEP, SPEED - moved);
                Location next = pos.clone().add(dir.clone().multiply(step));
                if (next.getBlock().getType().isSolid()) {
                    if (++bounces > MAX_BOUNCES) { end(); return; }
                    reflect(pos, dir, step);
                    world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f);
                    world.spawnParticle(Particle.FIREWORK, pos, 6, 0.15, 0.15, 0.15, 0.02);
                    Location retry = pos.clone().add(dir.clone().multiply(step));
                    if (retry.getBlock().getType().isSolid()) { end(); return; } // enclosed
                    moved += step;
                    continue;
                }
                pos.add(dir.clone().multiply(step));
                twinkle(world, pos, false, spin++);
                if (spin % 3 == 0) blessNearby();
                moved += step;
            }
        }

        /** Shower Regeneration III on the living things near the beam's current point. */
        private void blessNearby() {
            for (Entity e : world.getNearbyEntities(pos, HIT, HIT, HIT)) {
                if (!(e instanceof LivingEntity le) || le.isDead()) continue;
                le.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, MINOR_REGEN_TICKS, MINOR_REGEN_AMP));
                if (touched.add(e.getUniqueId())) {
                    Location c = le.getLocation().add(0, le.getHeight() * 0.6, 0);
                    world.spawnParticle(Particle.GLOW, c, 5, 0.25, 0.4, 0.25, 0);
                    world.spawnParticle(Particle.DUST, c, 4, 0.25, 0.4, 0.25, 0, DUST_BLUSH);
                    world.playSound(c, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 2.0f);
                }
            }
        }

        private void end() {
            world.spawnParticle(Particle.GLOW, pos, 8, 0.2, 0.2, 0.2, 0.02);
            world.spawnParticle(Particle.DUST, pos, 6, 0.2, 0.2, 0.2, 0, DUST_PINK);
            world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.7f);
            cancel();
        }
    }

    /** Flip the direction's blocked components so the beam reflects off the face(s) it met. */
    private static void reflect(Location pos, Vector dir, double step) {
        boolean bx = solidAt(pos, dir.getX() >= 0 ? step : -step, 0, 0);
        boolean by = solidAt(pos, 0, dir.getY() >= 0 ? step : -step, 0);
        boolean bz = solidAt(pos, 0, 0, dir.getZ() >= 0 ? step : -step);
        boolean any = false;
        if (bx) { dir.setX(-dir.getX()); any = true; }
        if (by) { dir.setY(-dir.getY()); any = true; }
        if (bz) { dir.setZ(-dir.getZ()); any = true; }
        if (!any) { dir.multiply(-1); } // corner hit: bounce straight back
    }

    private static boolean solidAt(Location base, double dx, double dy, double dz) {
        return base.clone().add(dx, dy, dz).getBlock().getType().isSolid();
    }

    // ---- Hate · homing salvo ------------------------------------------------------

    private void fireHomingSalvo(Player player) {
        if (gated(player, lastSalvo, SALVO_CD_MS, false)) { showHud(player); return; }
        lastSalvo.put(player.getUniqueId(), System.currentTimeMillis());
        EgoDurability.wearMainHand(player);

        World w = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector fwd = eye.getDirection().normalize();
        Vector[] b = perp(fwd);
        w.playSound(eye, Sound.ENTITY_WITHER_SHOOT, 0.7f, 1.5f);
        w.playSound(eye, Sound.ENTITY_VEX_CHARGE, 0.8f, 0.7f);

        for (int i = 0; i < SALVO_COUNT; i++) {
            double a = (i / (double) SALVO_COUNT) * Math.PI * 2;
            Location start = eye.clone()
                    .subtract(fwd.clone().multiply(1.7))       // spawned behind the wielder
                    .add(b[0].clone().multiply(Math.cos(a) * 0.9))
                    .add(b[1].clone().multiply(Math.sin(a) * 0.9))
                    .add(0, 0.2, 0);
            new HateBolt(player, start, fwd.clone()).runTaskTimer(plugin, 0L, 1L);
        }
    }

    /**
     * Deal one homing bolt's damage as its own distinct hit. The victim's hurt-immunity (i-frames) is
     * cleared first so a follow-up bolt landing inside another bolt's immunity window isn't swallowed —
     * all four bolts register. A re-entrancy fence stops a nested damage event from recursing here.
     */
    private void dealBoltDamage(Player owner, LivingEntity victim) {
        if (reDealingBolt) return;              // fence: never re-enter mid-hit
        reDealingBolt = true;
        try {
            victim.setNoDamageTicks(0);         // clear i-frames so this bolt lands as a separate instance
            victim.damage(SALVO_DAMAGE, owner); // re-enters onHit dispatch; this weapon doesn't override it
        } finally {
            reDealingBolt = false;
        }
    }

    /**
     * A spiteful bolt that curves onto the nearest enemy ahead, sub-stepping so it can't tunnel, and
     * detonates on the first non-player body for {@link #SALVO_DAMAGE}. Ends on a wall or lifetime.
     */
    private final class HateBolt extends BukkitRunnable {
        private static final double SPEED = 0.9;
        private static final double STEP  = 0.4;
        private static final double HIT   = 1.2;
        private static final double SEEK  = 10.0;
        private static final double SEEK_DOT = 0.1;
        private static final double HOMING = 0.22;
        private static final int    MAX_TICKS = 70;
        private static final int    RESEEK_TICKS = 4; // full AABB re-acquire cadence; steer toward cache in between

        private final UUID ownerId;
        private final World world;
        private final Location pos;
        private Vector dir;
        private int ticks = 0;
        private int spin = 0;
        private LivingEntity target = null; // cached homing target between re-seeks

        HateBolt(Player owner, Location start, Vector startDir) {
            this.ownerId = owner.getUniqueId();
            this.world = owner.getWorld();
            this.pos = start;
            this.dir = startDir.normalize();
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) { cancel(); return; }
            if (++ticks > MAX_TICKS) { fizzle(); return; }

            homeTowardEnemy();

            double moved = 0.0;
            while (moved < SPEED) {
                double step = Math.min(STEP, SPEED - moved);
                Location next = pos.clone().add(dir.clone().multiply(step));
                if (next.getBlock().getType().isSolid()) { fizzle(); return; }
                pos.add(dir.clone().multiply(step));
                twinkle(world, pos, true, spin++);

                LivingEntity hit = firstEnemy();
                if (hit != null) { burst(owner, hit); return; }
                moved += step;
            }
        }

        private void homeTowardEnemy() {
            // Drop a stale cache so we re-acquire immediately if the target died/despawned.
            if (target != null && (target.isDead() || !target.isValid())) target = null;

            // Only run the expensive AABB scan when the cache is empty or on the re-seek cadence;
            // between scans we steer toward the cached target's live position for free.
            if (target == null || ticks % RESEEK_TICKS == 0) {
                acquireTarget();
            }
            if (target == null) return;

            Vector to = center(target).subtract(pos.toVector());
            double dist = to.length();
            if (dist < 0.01) return;
            dir = dir.clone().multiply(1.0 - HOMING).add(to.multiply(HOMING / dist)).normalize();
        }

        private void acquireTarget() {
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : world.getNearbyEntities(pos, SEEK, SEEK, SEEK)) {
                if (e.getUniqueId().equals(ownerId) || e instanceof Player) continue;
                if (!(e instanceof LivingEntity le) || le.isDead()) continue;
                Vector to = center(le).subtract(pos.toVector());
                double dist = to.length();
                if (dist < 0.01) continue;
                if (to.clone().multiply(1.0 / dist).dot(dir) < SEEK_DOT) continue;
                if (dist < bestDist) { bestDist = dist; best = le; }
            }
            if (best != null) target = best;
        }

        private LivingEntity firstEnemy() {
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : world.getNearbyEntities(pos, HIT, HIT, HIT)) {
                if (e.getUniqueId().equals(ownerId) || e instanceof Player) continue;
                if (!(e instanceof LivingEntity le) || le.isDead()) continue;
                double d = center(le).subtract(pos.toVector()).lengthSquared();
                if (d < bestDist) { bestDist = d; best = le; }
            }
            return best;
        }

        private void burst(Player owner, LivingEntity victim) {
            dealBoltDamage(owner, victim);
            world.spawnParticle(Particle.FIREWORK, pos, 8, 0.2, 0.2, 0.2, 0.04);
            world.spawnParticle(Particle.DUST, pos, 8, 0.25, 0.25, 0.25, 0, DUST_RED);
            world.spawnParticle(Particle.DUST, pos, 6, 0.25, 0.25, 0.25, 0, DUST_VIOLET);
            world.playSound(pos, Sound.ENTITY_BLAZE_SHOOT, 0.6f, 0.9f);
            cancel();
        }

        private void fizzle() {
            world.spawnParticle(Particle.ELECTRIC_SPARK, pos, 5, 0.15, 0.15, 0.15, 0.02);
            world.spawnParticle(Particle.DUST, pos, 4, 0.15, 0.15, 0.15, 0, DUST_VIOLET);
            cancel();
        }
    }

    // ---- Hate · Reverse Arcana Slave (the aria) -----------------------------------

    private void castReverseArcana(Player player) {
        if (gated(player, lastReverse, ultCooldown(player, REVERSE_CD_MS), true)) return;
        lastReverse.put(player.getUniqueId(), System.currentTimeMillis()); // claim at chant-start
        EgoDurability.wearMainHand(player);
        showHud(player);

        UUID ownerId = player.getUniqueId();
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 0.7f);
        world.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.6f);

        // The transformation chant: converging magic circles gathering power in front of the wand.
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                Player owner = plugin.getServer().getPlayer(ownerId);
                if (owner == null || !owner.isOnline()) { cancel(); return; }
                if (t >= CHARGE_TICKS) {
                    cancel();
                    world.playSound(owner.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 0.8f);
                    world.playSound(owner.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.6f);
                    startLaser(owner);
                    return;
                }
                drawCharge(owner, t);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Draw the converging circles: rings summoned in front, spiralling inward toward a focal point. */
    private void drawCharge(Player owner, int t) {
        World world = owner.getWorld();
        double frac = t / (double) CHARGE_TICKS;
        Location eye = owner.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector[] b = perp(dir);
        Location focus = eye.clone().add(dir.clone().multiply(2.5));

        int rings = 3;
        for (int r = 0; r < rings; r++) {
            // Rings begin far out and slide inward to the focus as the chant crescendos.
            double ringDist = (1.6 + r * 1.4) * (1.0 - frac) + 0.15;
            Location c = focus.clone().add(dir.clone().multiply(ringDist));
            double radius = (2.0 - r * 0.4) * (1.0 - frac * 0.75) + 0.15;
            int pts = 14;
            double phase = t * (0.30 + r * 0.06) + r;
            for (int i = 0; i < pts; i++) {
                double a = (i / (double) pts) * Math.PI * 2 + phase;
                Location p = c.clone()
                        .add(b[0].clone().multiply(Math.cos(a) * radius))
                        .add(b[1].clone().multiply(Math.sin(a) * radius));
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, (i % 2 == 0) ? DUST_VIOLET : DUST_RED);
                if (i % 4 == 0) world.spawnParticle(Particle.ELECTRIC_SPARK, p, 1, 0, 0, 0, 0);
            }
        }
        // A growing core of gathered power at the focus.
        double coreR = 0.15 + frac * 0.55;
        world.spawnParticle(Particle.FIREWORK, focus, 1 + (int) (frac * 4), coreR, coreR, coreR, 0.01);
        world.spawnParticle(Particle.DUST, focus, 2, coreR, coreR, coreR, 0, LASER_CORE);
        if (t % 4 == 0) {
            float pitch = 0.6f + (float) frac;
            world.playSound(owner.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.7f, pitch);
            world.playSound(owner.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.5f, pitch);
        }
    }

    /** Fire the sustained laser: follows the caster's aim for {@link #LASER_TICKS}, carving + scorching. */
    private void startLaser(Player owner) {
        CarveBatch batch = new CarveBatch(owner.getUniqueId());
        batches.add(batch);
        new ReverseArcanaLaser(owner.getUniqueId(), batch, laserRange(owner)).runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * ENCHANT — Heart's Reach (custom id {@code "hearts_reach"}): the beam length after the enchant extends
     * it, capped. Read once at cast time so the whole channel draws and scans to one consistent length.
     */
    private double laserRange(Player owner) {
        int level = Math.min(HEARTS_REACH_CAP,
                EgoEnchants.level(owner.getInventory().getItemInMainHand(), "hearts_reach"));
        return LASER_RANGE + Math.max(0, level) * HEARTS_REACH_PER_LEVEL;
    }

    /**
     * The ten-second laser. Each tick it re-reads the caster's eye and aim (so it follows wherever they
     * look), paints a broad red-violet lance, carves a temporary tunnel through the breakable blocks in
     * front (never at/below the caster's feet, never protected blocks), and on a periodic pulse deals
     * tick damage — with no knockback — to everything along its line.
     */
    private final class ReverseArcanaLaser extends BukkitRunnable {
        private final UUID ownerId;
        private final CarveBatch batch;
        private final double range; // Heart's Reach — fixed for the whole channel
        private int ticks = 0;

        ReverseArcanaLaser(UUID ownerId, CarveBatch batch, double range) {
            this.ownerId = ownerId;
            this.batch = batch;
            this.range = range;
        }

        @Override
        public void run() {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) { finish(); return; }
            if (++ticks > LASER_TICKS) { finish(); return; }

            Location origin = owner.getEyeLocation();
            Vector dir = origin.getDirection().normalize();
            int feetY = owner.getLocation().getBlockY();
            World world = owner.getWorld();

            carve(world, origin, dir, feetY);
            batch.restoreExpired(); // blocks the beam passed a few seconds ago pop back mid-channel
            drawBeam(world, origin, dir);
            if (ticks % 5 == 0) world.playSound(origin, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.35f, 1.4f);
            if (ticks % LASER_DMG_PERIOD == 0) scorch(owner, origin, dir);
        }

        /** Carve a short cross-section tunnel along the beam line, respecting all the guards. */
        private void carve(World world, Location origin, Vector dir, int feetY) {
            if (batch.size() >= CARVE_MAX) return;
            Vector[] b = perp(dir);
            Vector[] offsets = { new Vector(0, 0, 0), b[0], b[0].clone().multiply(-1), b[1], b[1].clone().multiply(-1) };
            for (double d = 1.5; d <= range; d += 1.0) {
                Location p = origin.clone().add(dir.clone().multiply(d));
                for (Vector off : offsets) {
                    if (batch.size() >= CARVE_MAX) return;
                    Block blk = p.clone().add(off).getBlock();
                    if (blk.getY() <= feetY) continue;          // never at/below the wielder's feet
                    if (!isTempBreakable(blk)) continue;        // never bedrock/barrier/obsidian/etc
                    batch.carve(blk, CARVE_TTL_MS);
                }
            }
        }

        private void drawBeam(World world, Location origin, Vector dir) {
            int idx = 0;
            for (double d = 0.5; d <= range; d += 0.6, idx++) {
                Location p = origin.clone().add(dir.clone().multiply(d));
                world.spawnParticle(Particle.DUST, p, 1, 0.12, 0.12, 0.12, 0, LASER_CORE);
                if (idx % 2 == 0) world.spawnParticle(Particle.DUST, p, 1, 0.25, 0.25, 0.25, 0, LASER_EDGE);
                if (idx % 3 == 0) world.spawnParticle(Particle.ELECTRIC_SPARK, p, 1, 0.1, 0.1, 0.1, 0.0);
                if (idx % 5 == 0) world.spawnParticle(Particle.FIREWORK, p, 1, 0.08, 0.08, 0.08, 0.0);
            }
        }

        /** Scan the beam line and deal tick damage to bodies near it — capped, and with no knockback. */
        private void scorch(Player owner, Location origin, Vector dir) {
            Location mid = origin.clone().add(dir.clone().multiply(range * 0.5));
            double half = range * 0.5 + 2.0;
            World world = owner.getWorld();
            int struck = 0;
            for (Entity e : world.getNearbyEntities(mid, half, half, half)) {
                if (e.getUniqueId().equals(ownerId)) continue;
                if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
                Vector v = center(le).subtract(origin.toVector());
                double t = v.dot(dir);
                if (t < 0 || t > range) continue;
                double perpDist = v.clone().subtract(dir.clone().multiply(t)).length();
                if (perpDist > LASER_RADIUS) continue;

                Vector keep = le.getVelocity();      // preserve motion so the hit imparts no knockback
                le.damage(LASER_DAMAGE, owner);
                if (le.isValid()) le.setVelocity(keep);
                Location c = center(le).toLocation(world);
                world.spawnParticle(Particle.DUST, c, 4, 0.3, 0.4, 0.3, 0, DUST_RED);
                world.spawnParticle(Particle.ELECTRIC_SPARK, c, 3, 0.3, 0.4, 0.3, 0.02);
                if (++struck >= LASER_MAX_TARGETS) break;
            }
        }

        /** Stop channelling and hand the carved tunnel over to a slow restore sweep. */
        private void finish() {
            cancel();
            new BukkitRunnable() {
                @Override
                public void run() {
                    batch.restoreExpired();
                    if (batch.isEmpty()) { batches.remove(batch); cancel(); }
                }
            }.runTaskTimer(plugin, 10L, 10L);
        }
    }

    /** True if this block may be temporarily carved: solid, breakable, not a protected/special block. */
    private static boolean isTempBreakable(Block b) {
        Material m = b.getType();
        if (m.isAir() || !m.isSolid()) return false;
        return switch (m) {
            case BEDROCK, BARRIER, LIGHT, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK,
                 STRUCTURE_BLOCK, JIGSAW, END_PORTAL_FRAME, END_PORTAL, NETHER_PORTAL, REINFORCED_DEEPSLATE,
                 SPAWNER, OBSIDIAN, CRYING_OBSIDIAN -> false;
            default -> true;
        };
    }

    /**
     * A self-contained record of the blocks one laser cast has temporarily carved. Each block is saved as
     * a {@link BlockState} and replaced with air (no drops); it is restored either when its per-block timer
     * expires, or force-restored on quit/disable. All access is on the main thread.
     */
    private final class CarveBatch {
        private final UUID owner;
        private final Map<Location, BlockState> saved = new HashMap<>();
        private final Map<Location, Long> expireAt = new HashMap<>();

        CarveBatch(UUID owner) { this.owner = owner; }

        int size() { return saved.size(); }

        boolean isEmpty() { return saved.isEmpty(); }

        void carve(Block b, long ttlMs) {
            Location keyLoc = b.getLocation();
            if (saved.containsKey(keyLoc)) return;
            saved.put(keyLoc, b.getState());
            expireAt.put(keyLoc, System.currentTimeMillis() + ttlMs);
            b.setType(Material.AIR, false); // false == no physics/drops
        }

        void restoreExpired() {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<Location, Long>> it = expireAt.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Location, Long> e = it.next();
                if (e.getValue() <= now) {
                    restoreOne(e.getKey());
                    saved.remove(e.getKey());
                    it.remove();
                }
            }
        }

        void restoreAll() {
            for (Location keyLoc : new ArrayList<>(saved.keySet())) restoreOne(keyLoc);
            saved.clear();
            expireAt.clear();
        }

        private void restoreOne(Location keyLoc) {
            BlockState st = saved.get(keyLoc);
            if (st == null) return;
            if (st.getBlock().getType() == Material.AIR) { // don't stomp anything placed in the hole
                st.update(true, false);
                Location ctr = keyLoc.clone().add(0.5, 0.5, 0.5);
                keyLoc.getWorld().spawnParticle(Particle.DUST, ctr, 4, 0.3, 0.3, 0.3, 0, DUST_VIOLET);
            }
        }
    }

    // ---- twinkly trail ------------------------------------------------------------

    /** A twinkly sparkle mote — pink/white for Love, red/violet for Hate. */
    private void twinkle(World w, Location p, boolean hate, int spin) {
        w.spawnParticle(Particle.GLOW, p, 1, 0.02, 0.02, 0.02, 0);
        if (spin % 2 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, p, 1, 0.05, 0.05, 0.05, 0);
        if (spin % 3 == 0) w.spawnParticle(Particle.FIREWORK, p, 1, 0.02, 0.02, 0.02, 0.01);
        w.spawnParticle(Particle.DUST, p, 1, 0.06, 0.06, 0.06, 0, hate ? DUST_VIOLET : DUST_PINK);
        if (spin % 2 == 1) {
            w.spawnParticle(Particle.DUST, p, 1, 0.06, 0.06, 0.06, 0, hate ? DUST_RED : DUST_WHITE);
        }
    }

    // ---- shared helpers -----------------------------------------------------------

    private static Vector center(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0).toVector();
    }

    /** Two unit vectors spanning the plane perpendicular to u. */
    private static Vector[] perp(Vector u) {
        Vector n = u.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : u.clone().normalize();
        Vector ref = Math.abs(n.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector a = n.clone().crossProduct(ref).normalize();
        Vector b = n.clone().crossProduct(a).normalize();
        return new Vector[]{a, b};
    }

    // ---- item ---------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.LOVE_AND_HATE.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.LOVE_AND_HATE.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.LOVE_AND_HATE);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ---------------------------------------------------------------------

    // The wand speaks in its own two hearts: the name stays the rose pink it has always been, and the
    // Abnormality title line takes HATE_TEXT — the harsh red-violet the action bar already reads Hate in.
    // The old block set that line in NAME too, which the shared format no longer allows: the name and the
    // title must not repeat one another's colour. Of the palette's live accents it is the apt one — the
    // Queen of Hatred, in the colour of her own hatred — and both are bright enough to read against the
    // tooltip's dark background.

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "In the Name of Love and Hate",
            "Queen of Hatred",
            NAME,
            HATE_TEXT,
            List.of(
                    "A magical girl's wand of",
                    "love — and of hate."
            ),
            List.of(
                    new EgoLore.Ability("[Left Click] Switch Form",
                            "Cycle the wand between its Love and",
                            "Hate forms. Every other ability",
                            "changes with it."),
                    new EgoLore.Ability("[Right Click] Mending Mote",
                            "Love form. Loose a mote that flies",
                            "through the living, healing each body",
                            "it passes 1.5 hearts and granting",
                            "Regeneration for 5 seconds. It deals",
                            "no damage. 0.9 second cooldown."),
                    new EgoLore.Ability("[Right Click] Homing Bolts",
                            "Hate form. Four bolts rise from",
                            "behind you and chase the nearest mobs",
                            "ahead, bursting for 4 damage each.",
                            "All four land. 3.5 second cooldown."),
                    new EgoLore.Ability("[Shift + Right-click] Minor Arcana",
                            "Love form. A bright beam that",
                            "ricochets off walls, showering",
                            "Regeneration III for 6 seconds on",
                            "every living body its light touches.",
                            "It deals no damage. 2 minute",
                            "cooldown."),
                    new EgoLore.Ability("[Shift + Right-click] Reverse Arcana",
                            "Hate form. A 5 second chant draws",
                            "magic circles inward, then fires a",
                            "40 block laser that follows your aim",
                            "for 10 seconds, dealing 4 damage a",
                            "pulse with no knockback. It carves a",
                            "tunnel through the blocks ahead;",
                            "every one of them grows back.",
                            "5 minute cooldown.")
            ));

    // ---- lifecycle ----------------------------------------------------------------

    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false;
        if (tick % 3 == 0) showHud(player); // refresh the form + cooldown readout
        return true;
    }

    @Override
    public void onQuit(UUID id) {
        hateForm.remove(id);
        lastMend.remove(id);
        lastSalvo.remove(id);
        lastMinor.remove(id);
        lastReverse.remove(id);
        // Force-restore any tunnel this player's laser was still holding open.
        Iterator<CarveBatch> it = batches.iterator();
        while (it.hasNext()) {
            CarveBatch cb = it.next();
            if (cb.owner.equals(id)) { cb.restoreAll(); it.remove(); }
        }
    }

    @Override
    public void onDisable() {
        for (CarveBatch cb : new ArrayList<>(batches)) cb.restoreAll();
        batches.clear();
    }
}
