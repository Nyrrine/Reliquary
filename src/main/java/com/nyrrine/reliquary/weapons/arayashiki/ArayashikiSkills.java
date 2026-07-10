package com.nyrrine.reliquary.weapons.arayashiki;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Arayashiki's active skills, driven by right-click:
 *   - right-click          -> a directional dash that cuts through entities it passes.
 *                             3 stored charges, each recharging on its own timer (Tracer-style).
 *   - sneak + right-click  -> a flurry of full slashes fired in every direction — a ball of
 *                             cuts around the wielder that shreds everything nearby.
 * Both require memory (won't fire while Hollow) and spend a burst of it.
 */
public final class ArayashikiSkills {

    private final Reliquary plugin;
    private final ArayashikiWeapon weapon;

    // Dash: Tracer-style charge pool.
    private static final int MAX_DASH = 3;
    private static final long DASH_RECHARGE_MS = 7500L;   // per-charge recharge (7.5s — a real cost, not spam)
    private static final long DASH_MIN_GAP_MS = 150L;     // tiny gap between consecutive dashes
    private static final long DASH_FALL_GRACE_MS = 4000L; // dashing breaks your fall for this long
    private final Map<UUID, Integer> dashCharges = new HashMap<>();
    private final Map<UUID, Long> dashRechargeAt = new HashMap<>();
    private final Map<UUID, Long> lastDash = new HashMap<>();

    // Nova = held channel: a sustained ball of slashes while shift is held.
    private static final long NOVA_CD_MS = 2000L; // brief cooldown after the storm ends
    private final Map<UUID, Long> lastNova = new HashMap<>();
    private final Set<UUID> channeling = new HashSet<>();

    private static final Particle.DustOptions WHITE =
            new Particle.DustOptions(Color.WHITE, 1.0f);

    public ArayashikiSkills(Reliquary plugin, ArayashikiWeapon weapon) {
        this.plugin = plugin;
        this.weapon = weapon;
    }

    /** True if the player dashed recently enough for it to still be breaking their fall. */
    public boolean dashedRecently(UUID id) {
        Long t = lastDash.get(id);
        return t != null && System.currentTimeMillis() - t < DASH_FALL_GRACE_MS;
    }

    /** Refill all dash charges instantly (an erased kill resets the wielder's dashes). */
    public void resetDashes(UUID id) {
        dashCharges.put(id, MAX_DASH);
        dashRechargeAt.remove(id);
    }

    /** An erased kill: refill dashes, ding, and re-show the pips so the subtitle updates. */
    public void onKillRefresh(Player killer) {
        UUID id = killer.getUniqueId();
        resetDashes(id);
        killer.playSound(killer.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 1.2f);
        weapon.muteActionBar(id, 2200);              // let the refreshed pips stay visible
        killer.sendActionBar(dashPips(MAX_DASH));    // <- the fix: push the updated charges
    }

    /** Drop this player's skill state on quit. */
    public void clear(UUID id) {
        dashCharges.remove(id);
        dashRechargeAt.remove(id);
        lastDash.remove(id);
        lastNova.remove(id);
        channeling.remove(id);
    }

    public void onInteract(Player player, boolean sneaking) {
        UUID id = player.getUniqueId();

        if (!weapon.hasCharge(id)) { fizzle(player); return; }
        long now = System.currentTimeMillis();

        if (sneaking) {
            startChannel(player, id, now);
        } else {
            tryDash(player, id, now);
        }
    }

    // ---- dash (charge pool) --------------------------------------------------------

    private void tryDash(Player player, UUID id, long now) {
        Long gap = lastDash.get(id);
        if (gap != null && now - gap < DASH_MIN_GAP_MS) return;

        refillDashes(id, now);
        int charges = dashCharges.getOrDefault(id, MAX_DASH);
        if (charges <= 0) return; // all dashes spent, still recharging

        boolean wasFull = charges == MAX_DASH;
        dashCharges.put(id, charges - 1);
        if (wasFull) dashRechargeAt.put(id, now + DASH_RECHARGE_MS); // start the clock
        lastDash.put(id, now);

        weapon.setUseTicks(id, weapon.useTicksOf(id) - ArayashikiWeapon.MAX_USE_TICKS / 20); // small cost
        dash(player);
        weapon.muteActionBar(id, 2200); // keep the memory bar quiet so the pips stay visible
        player.sendActionBar(dashPips(dashCharges.get(id)));
    }

    /** Restores charges that have finished recharging since we last looked. */
    private void refillDashes(UUID id, long now) {
        int c = dashCharges.getOrDefault(id, MAX_DASH);
        if (c >= MAX_DASH) return;
        long next = dashRechargeAt.getOrDefault(id, now);
        while (c < MAX_DASH && now >= next) {
            c++;
            next += DASH_RECHARGE_MS;
        }
        dashCharges.put(id, c);
        dashRechargeAt.put(id, next);
    }

    private net.kyori.adventure.text.Component dashPips(int charges) {
        var comp = net.kyori.adventure.text.Component.text("Dash ",
                net.kyori.adventure.text.format.NamedTextColor.GRAY);
        for (int i = 0; i < MAX_DASH; i++) {
            comp = comp.append(net.kyori.adventure.text.Component.text(i < charges ? "◆ " : "◇ ",
                    i < charges ? net.kyori.adventure.text.format.NamedTextColor.WHITE
                            : net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        }
        return comp;
    }

    private void dash(Player player) {
        World world = player.getWorld();
        // Directional: dash exactly where you're looking (up, down, or level).
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Vector v = dir.clone().multiply(1.9);
        v.setY(v.getY() + 0.1); // a touch of lift so a level dash still feels snappy
        player.setVelocity(v);
        player.setFallDistance(0f); // dashing breaks the fall you were in

        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.7f);
        world.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.7f, 1.9f);

        final Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t++ >= 6 || !player.isOnline()) { cancel(); return; }
                Location c = player.getLocation().add(0, 1.0, 0);
                world.spawnParticle(Particle.DUST, c, 8, 0.3, 0.5, 0.3, 0, WHITE);   // afterimage
                world.spawnParticle(Particle.END_ROD, c, 2, 0.2, 0.3, 0.2, 0.01);
                for (var e : player.getNearbyEntities(1.9, 1.9, 1.9)) {
                    if (e == player || !(e instanceof LivingEntity target)) continue;
                    if (!hit.add(e.getUniqueId())) continue;
                    weapon.markErased(target, player);
                    target.damage(8.0, player);
                    target.setVelocity(target.getVelocity().add(dir.clone().multiply(0.5).setY(0.2)));
                    // slash effect on the struck entity
                    Location tl = target.getLocation().add(0, 1, 0);
                    world.spawnParticle(Particle.SWEEP_ATTACK, tl, 2, 0.3, 0.3, 0.3, 0);
                    world.spawnParticle(Particle.DUST, tl, 12, 0.35, 0.4, 0.35, 0, WHITE);
                    world.spawnParticle(Particle.END_ROD, tl, 3, 0.2, 0.2, 0.2, 0.02);
                    world.playSound(tl, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.5f);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---- sphere-slash nova: a ball of real swoops ----------------------------------

    /** Begins the sustained ball-of-slashes storm; it keeps running while shift stays held. */
    private void startChannel(Player player, UUID id, long now) {
        if (channeling.contains(id)) return;                 // already storming
        Long last = lastNova.get(id);
        if (last != null && now - last < NOVA_CD_MS) return; // brief post-storm cooldown
        if (!weapon.hasCharge(id)) { fizzle(player); return; }

        channeling.add(id);
        World world = player.getWorld();
        Location origin = player.getLocation().add(0, 1.0, 0);
        world.spawnParticle(Particle.END_ROD, origin, 40, 0.4, 0.6, 0.4, 0.2);
        world.playSound(origin, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.7f);

        final double R = 5.0;
        new BukkitRunnable() {
            @Override
            public void run() {
                // Holds only while shift is down, the blade is out, and memory remains.
                if (!player.isOnline() || !player.isSneaking()
                        || !weapon.matches(player.getInventory().getItemInMainHand())
                        || !weapon.hasCharge(id)) {
                    channeling.remove(id);
                    lastNova.put(id, System.currentTimeMillis());
                    cancel();
                    return;
                }
                // Sustaining burns memory fast, which naturally caps the storm's length.
                weapon.setUseTicks(id, weapon.useTicksOf(id) - Math.max(1, ArayashikiWeapon.MAX_USE_TICKS / 60));

                ThreadLocalRandom rng = ThreadLocalRandom.current();
                Location center = player.getLocation().add(0, 1.0, 0);
                int arcs = 2 + rng.nextInt(2);
                for (int i = 0; i < arcs; i++) {
                    spawnSwoopArc(world, center, randomDir(rng), 3.0 + rng.nextDouble() * 2.0, rng);
                }
                world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.0f + rng.nextFloat() * 0.6f);

                for (var e : player.getNearbyEntities(R, R, R)) {
                    if (e == player || !(e instanceof LivingEntity target)) continue;
                    weapon.markErased(target, player);
                    target.damage(4.0, player);
                    Location tl = target.getLocation().add(0, 1, 0);
                    world.spawnParticle(Particle.SWEEP_ATTACK, tl, 2, 0.3, 0.3, 0.3, 0);
                    target.setVelocity(target.getVelocity().multiply(0.55).setY(0.1));
                }
            }
        }.runTaskTimer(plugin, 0L, 2L); // every 2 ticks while held
    }

    /** A single big traveling sword swoop around an arbitrary centre/orientation. */
    private void spawnSwoopArc(World world, Location center, Vector dir, double radius, ThreadLocalRandom rng) {
        Vector u = dir.clone().normalize();
        Vector ref = Math.abs(u.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector right = u.clone().crossProduct(ref).normalize();
        Vector perp = u.clone().crossProduct(right).normalize();
        double roll = rng.nextDouble(0, Math.PI * 2);
        Vector v = right.clone().multiply(Math.cos(roll)).add(perp.clone().multiply(Math.sin(roll)));

        double sweep = Math.toRadians(150 + rng.nextInt(80));
        double aMid = rng.nextDouble(-0.3, 0.3);
        boolean reverse = rng.nextBoolean();
        final int N = 30;
        final int reveal = 3, fade = 3;
        final Location[] pts = new Location[N + 1];
        final int[] birth = new int[N + 1];
        for (int i = 0; i <= N; i++) {
            double f = (double) i / N;
            double ang = aMid - sweep / 2.0 + sweep * f;
            Vector radial = u.clone().multiply(Math.cos(ang) * radius)
                    .add(v.clone().multiply(Math.sin(ang) * radius));
            Location p = center.clone().add(radial);
            p.add((rng.nextDouble() - 0.5) * 0.1, (rng.nextDouble() - 0.5) * 0.1, (rng.nextDouble() - 0.5) * 0.1);
            pts[i] = p;
            int order = reverse ? (N - i) : i;
            birth[i] = Math.round((float) reveal * order / N);
        }
        final float thick = 1.0f + rng.nextFloat() * 0.4f;
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t > reveal + fade) { cancel(); return; }
                for (int i = 0; i <= N; i++) {
                    int age = t - birth[i];
                    if (age < 0 || age >= fade) continue;
                    float sz = thick * (1.0f - 0.5f * age / fade);
                    world.spawnParticle(Particle.DUST, pts[i], 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.WHITE, sz));
                    if (age == 0 && i % 4 == 0) world.spawnParticle(Particle.END_ROD, pts[i], 1, 0, 0, 0, 0);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** A uniform-ish random unit direction (kept off the poles a touch for a rounder ball). */
    private Vector randomDir(ThreadLocalRandom rng) {
        double y = rng.nextDouble(-0.8, 0.8);
        double phi = rng.nextDouble(0, Math.PI * 2);
        double s = Math.sqrt(1.0 - y * y);
        return new Vector(s * Math.cos(phi), y, s * Math.sin(phi));
    }

    private void fizzle(Player player) {
        Location eye = player.getEyeLocation();
        player.getWorld().spawnParticle(Particle.DUST, eye.add(eye.getDirection().multiply(0.7)),
                6, 0.15, 0.15, 0.15, 0, new Particle.DustOptions(Color.fromRGB(90, 90, 90), 0.8f));
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.5f, 0.6f);
    }
}
