package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Beak — "Punishing Bird" (Lobotomy Corp E.G.O Equipment, TETH).
 *
 * <p>The little red bird that pecks. A magazine-fed peashooter that spits tiny, TOOTHED bullets — each
 * one barely stings, but they come out in a chattering stream. Low per-shot damage, high firepower: the
 * gimmick is cadence, not weight.
 *
 * <ul>
 *   <li><b>Right-click</b> — peck: spend one bullet from the {@value #MAG_SIZE}-round magazine and fire a
 *       hitscan spiked pellet along the eye line (range {@value #RANGE}). The pellet deals no knockback —
 *       the victim's velocity is captured and restored around the hit — and a crisp peck rings at the
 *       body it lands in. A short red spiked tracer + a chirpy muzzle puff sells the sting.</li>
 *   <li><b>Multishot</b> — a single trigger becomes a rapid three-round burst: three consecutive pecks
 *       fired straight down the aim line a couple ticks apart, each its own hitscan, for one bullet.</li>
 *   <li><b>Piercing</b> — each pellet punches through the first body and on into up to {@code level} more
 *       living entities along its line.</li>
 *   <li>When the magazine runs dry it reloads over {@value #RELOAD_MS}ms; firing is disabled meanwhile
 *       (right-click just clicks empty) and the action bar shows a filling reload gauge. One point of
 *       mild durability wear is paid per reload.</li>
 * </ul>
 *
 * <p>All state is a single in-memory UUID-&gt;magazine map, cleared on quit. No world edits — each peck
 * is one raytrace per pellet plus a brief particle draw; the tick loop only runs while a wielder holds
 * the bird or its magazine is reloading.
 */
public final class BeakWeapon implements Weapon {

    private final Reliquary plugin;
    private final NamespacedKey key;

    /** Wielder -> their magazine state. The only state this weapon keeps. */
    private final Map<UUID, Mag> mags = new HashMap<>();

    // Tuning — a chattering, feather-light peashooter.
    private static final double RANGE       = 22.0;  // hitscan reach
    private static final double DAMAGE      = 1.8;   // ~1 heart — deliberately tiny; the volume is the point
    private static final double RAY_SIZE    = 0.45;  // entity ray fatness (forgiving aim)
    private static final double SPREAD      = 0.035; // tiny random cone on each pellet
    private static final long   COOLDOWN_MS = 180L;  // machine-gun cadence limiter (silent — no action bar)

    private static final int  BURST_SHOTS     = 3;   // Multishot: consecutive pecks per trigger
    private static final long BURST_GAP_TICKS = 2L;  // ticks between the pecks of a burst

    private static final int  MAG_SIZE  = 12;        // bullets per magazine
    private static final long RELOAD_MS = 5000L;     // dry-magazine reload time

    // Palette — white/gray bird with a red-cannon accent.
    /** Primary — pale feather white. Display name, "How to use:", ability headers. */
    private static final TextColor NAME = TextColor.color(0xE8E8EC);
    /** The red-cannon accent — the Abnormality title line, and the action bar's ammo/reload gauge. */
    private static final TextColor RED  = TextColor.color(0xE23B3B);

    private static final Color RED_BULLET = Color.fromRGB(0xE2, 0x3B, 0x3B); // spiked-bullet red
    private static final Color RED_DARK   = Color.fromRGB(0x8C, 0x1F, 0x1F); // the toothed core, darker
    private static final Particle.DustOptions SPIKE = new Particle.DustOptions(RED_BULLET, 0.7f); // small tracer
    private static final Particle.DustOptions TOOTH = new Particle.DustOptions(RED_DARK, 0.9f);   // spiky dark fleck

    public BeakWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "beak");
    }

    @Override
    public String id() {
        return "beak";
    }

    /** Per-wielder magazine: rounds left, last-fire stamp for cadence, and a reload timer. */
    private static final class Mag {
        int  rounds     = MAG_SIZE;
        long lastFire   = 0L;
        long reloadStart = 0L;               // 0 = not reloading

        boolean reloading() { return reloadStart != 0L; }
    }

    // ---- fire ---------------------------------------------------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        // The bird only pecks — sneak+RC does nothing extra.
        UUID id = player.getUniqueId();
        Mag mag = mags.computeIfAbsent(id, k -> new Mag());

        // Mid-reload: firing is disabled, right-click just clicks empty.
        if (mag.reloading()) {
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.35f, 1.6f);
            return;
        }

        // Empty magazine (safety) — start the reload.
        if (mag.rounds <= 0) {
            beginReload(player, mag);
            renderBar(player, mag);
            return;
        }

        // Silent machine-gun cadence limiter — no action bar, the live ammo bar carries on.
        long now = System.currentTimeMillis();
        if (now - mag.lastFire < COOLDOWN_MS) return;
        mag.lastFire = now;

        // One trigger spends exactly one bullet, however many pellets it throws.
        mag.rounds--;

        ItemStack item = player.getInventory().getItemInMainHand();
        int multishot = item.getEnchantmentLevel(Enchantment.MULTISHOT);
        int piercing  = item.getEnchantmentLevel(Enchantment.PIERCING);

        // Fire immediately down the aim line. Multishot doesn't spread the shot sideways — it turns the
        // trigger into a rapid three-round burst: the first peck now, the rest queued a couple ticks apart,
        // all straight ahead, each its own hitscan. One trigger still spends exactly one bullet.
        fireOneShot(player, piercing);
        if (multishot > 0) {
            for (int s = 1; s < BURST_SHOTS; s++) {
                scheduleBurstShot(player, piercing, BURST_GAP_TICKS * s);
            }
        }

        // Emptied the magazine? Roll straight into the reload.
        if (mag.rounds <= 0) beginReload(player, mag);
        renderBar(player, mag);
    }

    /** Fire one spiked peck straight down the current aim line — its own hitscan, its own chirp. */
    private void fireOneShot(Player player, int piercing) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = scatterDir(ThreadLocalRandom.current(), eye.getDirection().normalize());
        resolvePellet(player, world, eye, dir, piercing);
        muzzleSound(player); // one squawk per peck
    }

    /** Queue a follow-up burst peck a few ticks out; skipped if the bird is no longer in hand. */
    private void scheduleBurstShot(Player player, int piercing, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && matches(player.getInventory().getItemInMainHand())) {
                fireOneShot(player, piercing);
            }
        }, delayTicks);
    }

    /** Nudge a shot direction by a hair of random scatter so the stream doesn't read as a laser. */
    private Vector scatterDir(ThreadLocalRandom rng, Vector base) {
        Vector d = base.clone();
        d.add(new Vector(rng.nextDouble(-SPREAD, SPREAD),
                         rng.nextDouble(-SPREAD, SPREAD),
                         rng.nextDouble(-SPREAD, SPREAD)));
        return d.normalize();
    }

    /**
     * One spiked-bullet peck along {@code dir}: clip at the first wall, then hit the first living body — or,
     * with Piercing, punch through it and on into up to {@code piercing} more bodies along the line. Every
     * hit is dealt without knockback and rings a peck at the victim.
     */
    private void resolvePellet(Player player, World world, Location eye, Vector dir, int piercing) {
        double maxDist = RANGE;
        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, RANGE, FluidCollisionMode.NEVER, true);
        if (blockHit != null && blockHit.getHitPosition() != null) {
            maxDist = eye.toVector().distance(blockHit.getHitPosition());
        }

        Location muzzle = eye.clone().add(dir.clone().multiply(0.6));
        muzzlePuff(world, muzzle);

        int maxHits = 1 + Math.max(0, piercing);
        Set<UUID> hitIds = new HashSet<>();
        Location firstHit = null;
        double advanced = 0.0; // distance from the eye already consumed by prior hits

        for (int h = 0; h < maxHits; h++) {
            double segLen = maxDist - advanced;
            if (segLen <= 1.0e-3) break;
            Location from = eye.clone().add(dir.clone().multiply(advanced));
            RayTraceResult entHit = world.rayTraceEntities(
                    from, dir, segLen, RAY_SIZE,
                    e -> e instanceof LivingEntity
                            && !e.getUniqueId().equals(player.getUniqueId())
                            && !hitIds.contains(e.getUniqueId()));
            if (entHit == null || !(entHit.getHitEntity() instanceof LivingEntity le)) break;

            Location hitLoc = entHit.getHitPosition().toLocation(world);
            damageNoKnockback(le, player);
            impactFx(world, hitLoc);
            hitIds.add(le.getUniqueId());
            if (firstHit == null) firstHit = hitLoc;
            advanced = eye.toVector().distance(entHit.getHitPosition()) + 0.05;
        }

        // Tracer: without Piercing it stops at the first body (as before); with Piercing it draws the full
        // line so the through-shot reads.
        Location end = (piercing <= 0 && firstHit != null)
                ? firstHit
                : eye.clone().add(dir.clone().multiply(maxDist));
        drawTracer(world, muzzle, end);
    }

    /** Deal the peck with zero knockback — capture the victim's velocity, damage, then restore it. */
    private void damageNoKnockback(LivingEntity victim, Player source) {
        Vector velocity = victim.getVelocity();
        victim.damage(DAMAGE, source);
        victim.setVelocity(velocity);
        peckSound(victim);
    }

    // ---- reload --------------------------------------------------------------------

    /** Begin the dry-magazine reload: start the timer, pay one point of mild durability wear, cue the sound. */
    private void beginReload(Player player, Mag mag) {
        if (mag.reloading()) return;
        mag.reloadStart = System.currentTimeMillis();
        EgoDurability.wearMainHand(player); // mild — once per reload, not per pellet
        player.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, 0.6f, 1.4f);
    }

    /** Render the held-weapon action bar: the reload gauge while reloading, else the live ammo count. */
    private void renderBar(Player player, Mag mag) {
        if (mag.reloading()) {
            long elapsed = System.currentTimeMillis() - mag.reloadStart;
            double frac = Math.min(1.0, (double) elapsed / RELOAD_MS);
            player.sendActionBar(EgoHud.gauge(RED, frac, EgoHud.status("Reloading", RED)));
        } else {
            player.sendActionBar(EgoHud.ammo(RED, "Bullets", mag.rounds, MAG_SIZE));
        }
    }

    @Override
    public boolean onTick(Player player, long tick) {
        boolean held = matches(player.getInventory().getItemInMainHand());
        Mag mag = mags.get(player.getUniqueId());

        if (mag != null && mag.reloading()) {
            long elapsed = System.currentTimeMillis() - mag.reloadStart;
            if (elapsed >= RELOAD_MS) {
                mag.rounds = MAG_SIZE;
                mag.reloadStart = 0L;
                if (held) {
                    player.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 0.6f, 1.5f);
                    renderBar(player, mag);
                }
                return held; // done — keep ticking only while still held
            }
            if (held) renderBar(player, mag); // filling reload gauge
            return true;                        // drive the reload to completion even if stowed
        }

        if (!held) return false; // idle and not held — stop ticking

        if (mag == null) mag = mags.computeIfAbsent(player.getUniqueId(), k -> new Mag());
        renderBar(player, mag); // live "Bullets cur/max"
        return true;
    }

    // ---- presentation --------------------------------------------------------------

    /** A quick chirpy pew, pitched high and jittered — one per trigger. */
    private void muzzleSound(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location at = player.getEyeLocation();
        float chirp = 1.7f + rng.nextFloat() * 0.5f; // high, bird-ish, jittered

        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, chirp);            // the *pew* chirp
        world.playSound(at, Sound.ENTITY_PARROT_IMITATE_BLAZE, 0.35f, 1.8f);       // a squawky bird edge
        world.playSound(at, Sound.ITEM_CROSSBOW_SHOOT, 0.3f, 1.9f + rng.nextFloat() * 0.2f);
    }

    /** A crisp peck at whatever the pellet lands in — separate from the muzzle sound, for on-hit fidelity. */
    private void peckSound(LivingEntity victim) {
        World world = victim.getWorld();
        Location at = victim.getLocation();
        float p = 1.8f + ThreadLocalRandom.current().nextFloat() * 0.3f;
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, p);              // sharp toothy tick
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.4f, 2.0f);     // a bright peck edge
    }

    /** A tiny spiked muzzle puff — low count, short-lived, drawn per pellet. */
    private void muzzlePuff(World world, Location muzzle) {
        world.spawnParticle(Particle.DUST, muzzle, 4, 0.06, 0.06, 0.06, 0, SPIKE);
        world.spawnParticle(Particle.DUST, muzzle, 2, 0.05, 0.05, 0.05, 0, TOOTH);
        world.spawnParticle(Particle.CRIT, muzzle, 3, 0.05, 0.05, 0.05, 0.05); // spiky flecks
    }

    /** A short red spiked tracer along the shot line — low count, drawn once, with dark toothed flecks. */
    private void drawTracer(World world, Location from, Location to) {
        Vector step = to.toVector().subtract(from.toVector());
        double length = step.length();
        if (length < 1.0e-4) return;
        step.multiply(1.0 / length);

        double spacing = 0.55;
        for (double d = 0.0; d < length; d += spacing) {
            Location p = from.clone().add(step.clone().multiply(d));
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, (d % (spacing * 2) < spacing) ? SPIKE : TOOTH);
        }
    }

    /** A little burst of red spikes bursting on whatever the pellet lands in. */
    private void impactFx(World world, Location at) {
        world.spawnParticle(Particle.DUST, at, 5, 0.12, 0.12, 0.12, 0, SPIKE);
        world.spawnParticle(Particle.CRIT, at, 4, 0.10, 0.10, 0.10, 0.08); // spiky sting
    }

    // ---- item ----------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.BEAK.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.BEAK.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.BEAK);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ----------------------------------------------------------------------

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Beak",
            "Punishing Bird",
            NAME,
            RED,
            List.of(
                    "Little bird decided to punish",
                    "bad creatures with his beak."
            ),
            List.of(
                    new EgoLore.Ability("[Right Click] Fire Spiked Pellet",
                            "Spends one bullet to fire a spiked",
                            "pellet up to 22 blocks. 1.8 damage,",
                            "no knockback. Fires up to about 5",
                            "times a second."),
                    new EgoLore.Ability("[Passive] 12-Round Magazine",
                            "Holds 12 bullets. Running dry starts",
                            "a 5 second reload; firing is disabled",
                            "until it finishes."),
                    new EgoLore.Ability("[Passive] Multishot Burst Fire",
                            "With Multishot, one trigger fires a",
                            "3-round burst down the aim line for",
                            "one bullet."),
                    new EgoLore.Ability("[Passive] Piercing Through-Shot",
                            "With Piercing, each pellet punches",
                            "through the first target and into one",
                            "more per level.")
            ));

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        mags.remove(id);
    }
}
