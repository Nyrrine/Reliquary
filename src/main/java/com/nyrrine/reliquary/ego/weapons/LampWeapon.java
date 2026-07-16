package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lamp — the E.G.O of <b>Big Bird</b>, the abnormality that gained one more eye for every creature it
 * "saved". This is no little handheld light: it is a great warm lantern whose radiant pride spills out
 * over everyone who stands near it. The item is a LANTERN — it does no real melee damage of its own; its
 * only blow is the SLAM, and its true work is the protective glow it hangs over its allies.
 *
 * <ul>
 *   <li><b>Aura</b> (passive, {@link #onTick}) — a circular lantern-glow of radius {@value #AURA_RADIUS}.
 *       Every player and every tamed ally standing inside it (the wielder included) is bathed in warm
 *       light and kept under {@link PotionEffectType#RESISTANCE Resistance} I, refreshed continuously. A
 *       Gaze-marked enemy is <em>excluded</em> from this benefit until the fight is over.</li>
 *   <li><b>Gaze</b> — <b>sneak + right-click</b>. Big Bird fixes another eye on the looked-at target and
 *       {@code marks} it. Every harmful effect on the WIELDER is transferred onto the marked body — a
 *       snapshot on the cast, then any freshly-received debuff for a short window ({@value #TRANSFER_WINDOW_MS}
 *       ms). A marked enemy earns no aura Resistance until it has been out of combat for
 *       {@value #COMBAT_CLEAR_MS} ms, at which point the mark is forgotten.</li>
 *   <li><b>Slam</b> — plain <b>right-click</b> (non-sneak), a {@value #SLAM_COOLDOWN_MS}-ms cooldown. The
 *       heavy lantern is driven onto the looked-at / nearby body: a blunt CLANG and impact-dust, LOTS of
 *       knockback and very LITTLE damage. Not spammable (cooldown shown via {@link EgoHud#cooldown}).</li>
 *   <li>Left-click ({@code onSwing}) does nothing special — it is a lantern.</li>
 * </ul>
 *
 * <p>The slam's {@code victim.damage(...)} re-enters {@link #onHit}, so a {@link #slamming} fence stops
 * any loop. All per-player state — the Gaze marks and the slam cooldowns — is dropped in {@link #onQuit},
 * both for a quitting wielder and for a quitting marked target.
 */
public final class LampWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Lamp. */
    private final NamespacedKey key;

    // ---- aura tuning --------------------------------------------------------------

    /** How near the wielder an ally must stand to be bathed in the lantern's glow. */
    private static final double AURA_RADIUS = 5.0;
    /** onTick runs every 2 server ticks; refresh the aura once every this many pulses (~1s). */
    private static final int AURA_INTERVAL = 10;
    /** Resistance is refreshed at ~1s but granted for 3s so it never flickers between pulses. */
    private static final int RESISTANCE_TICKS = 60;
    /** A low amplifier — Resistance I, a soft protective glow, never true invulnerability. */
    private static final int RESISTANCE_AMP = 0;

    // ---- gaze tuning --------------------------------------------------------------

    /** How far the crosshair reaches when fixing a Gaze on a target. */
    private static final int GAZE_RANGE = 16;
    /** After marking, keep copying the wielder's freshly-received debuffs onto the target this long. */
    private static final long TRANSFER_WINDOW_MS = 8_000L;
    /** A marked enemy is forgiven (mark cleared, aura restored) after this long out of combat. */
    private static final long COMBAT_CLEAR_MS = 10_000L;
    /** A marked target counted as "in combat" while within this range of its marker. */
    private static final double COMBAT_RADIUS = 8.0;
    /** Copy the wielder's harmful effects onto the marked target every this many pulses (~0.4s). */
    private static final int GAZE_COPY_INTERVAL = 4;

    // ---- slam tuning --------------------------------------------------------------

    /** How far the slam reaches for a target. */
    private static final double SLAM_RANGE = 5.0;
    /** The slam rests this long between uses. */
    private static final long SLAM_COOLDOWN_MS = 3_000L;
    /** Little damage — the point is the heave, not the wound (half a heart). */
    private static final double SLAM_DAMAGE = 1.0;
    /** Lots of outward knockback. */
    private static final double SLAM_KB_HORIZONTAL = 1.7;
    private static final double SLAM_KB_UP = 0.55;

    /** Harmful potion effects the Gaze copies from the wielder onto the marked body. */
    private static final Set<PotionEffectType> HARMFUL = Set.of(
            PotionEffectType.SLOWNESS,
            PotionEffectType.MINING_FATIGUE,
            PotionEffectType.INSTANT_DAMAGE,
            PotionEffectType.NAUSEA,
            PotionEffectType.BLINDNESS,
            PotionEffectType.HUNGER,
            PotionEffectType.WEAKNESS,
            PotionEffectType.POISON,
            PotionEffectType.WITHER,
            PotionEffectType.LEVITATION,
            PotionEffectType.DARKNESS,
            PotionEffectType.UNLUCK);

    /** Wielder UUID -> the eye Big Bird has fixed on a target. */
    private final Map<UUID, Gaze> gazes = new HashMap<>();

    /** Wielder UUID -> epoch-millis at which the next slam is allowed. */
    private final Map<UUID, Long> slamCd = new HashMap<>();

    /**
     * Targets whose slam-damage is currently resolving. The slam's {@code victim.damage(...)} re-enters
     * {@link #onHit}; while a UUID sits here, onHit refuses to act on its own blow.
     */
    private final Set<UUID> slamming = new HashSet<>();

    /** A fixed eye: whose body, until when debuffs still copy, and when it was last seen in combat. */
    private static final class Gaze {
        final UUID target;
        long transferUntil;
        long lastCombatAt;

        Gaze(UUID target, long transferUntil, long lastCombatAt) {
            this.target = target;
            this.transferUntil = transferUntil;
            this.lastCombatAt = lastCombatAt;
        }
    }

    public LampWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "lamp");
    }

    @Override
    public String id() {
        return "lamp";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.LAMP.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.LAMP.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.LAMP);

        item.setItemMeta(meta);
        return item;
    }

    // ---- re-entrancy fence: a lantern's left-click does nothing --------------------

    /**
     * A landed melee "hit". A lantern has no bite of its own, so this is a no-op — the only thing it
     * guards against is the slam's own {@code victim.damage(...)} re-entering here (fenced by
     * {@link #slamming}). Nothing else to do.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (slamming.contains(victim.getUniqueId())) return; // our own slam re-entered — leave it be
        // A lantern does nothing on a plain swing.
    }

    // ---- passive: the lantern's protective glow ------------------------------------

    /**
     * Called every 2 server ticks while the player is an active wielder. Returns {@code true} while the
     * lantern is held in <em>either</em> hand (keep glowing), {@code false} the moment it leaves both (go
     * dark). The protective glow is a passive of simply carrying the lantern, so it burns just the same in
     * the off-hand — only the active abilities (Gaze / Slam, routed through the main-hand right-click in
     * {@link #onInteract}) go dark there, since the main hand then holds something else.
     *
     * <p>Once every {@link #AURA_INTERVAL} pulses it refreshes the Resistance aura and its warm light on
     * every nearby ally; every pulse it advances any Gaze this wielder holds (copying debuffs during the
     * window, tracking combat, and forgetting a foe that has been out of combat long enough).
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!isHeld(player)) return false; // sheathed in neither hand -> dark

        if (tick % AURA_INTERVAL == 0) applyAura(player);
        advanceGaze(player, tick);
        return true;
    }

    /** The lantern is being carried if it sits in either the main hand or the off-hand. */
    private boolean isHeld(Player player) {
        return matches(player.getInventory().getItemInMainHand())
                || matches(player.getInventory().getItemInOffHand());
    }

    /** Bathe the wielder and every nearby ally (players + tamed pets) in Resistance and warm light. */
    private void applyAura(Player wielder) {
        Set<UUID> marked = markedTargets();

        grant(wielder);
        glow(wielder);
        lanternRing(wielder);

        for (Entity e : wielder.getNearbyEntities(AURA_RADIUS, AURA_RADIUS, AURA_RADIUS)) {
            if (marked.contains(e.getUniqueId())) continue;           // a marked foe earns no glow yet
            boolean ally = e instanceof Player || (e instanceof Tameable t && t.isTamed());
            if (!ally || !(e instanceof LivingEntity le) || le.isDead()) continue;
            grant(le);
            glow(le);
        }
    }

    /** Refresh the protective Resistance on one body — ambient, no vanilla particles (we draw our own). */
    private void grant(LivingEntity le) {
        le.addPotionEffect(new PotionEffect(
                PotionEffectType.RESISTANCE, RESISTANCE_TICKS, RESISTANCE_AMP, true, false, true));
    }

    /** All bodies currently marked by any wielder's Gaze — excluded from the aura. */
    private Set<UUID> markedTargets() {
        if (gazes.isEmpty()) return Set.of();
        Set<UUID> out = new HashSet<>(gazes.size());
        for (Gaze g : gazes.values()) out.add(g.target);
        return out;
    }

    // ---- gaze: Big Bird fixes another eye ------------------------------------------

    /** Advance this wielder's Gaze: copy debuffs during the window, track combat, forget stale foes. */
    private void advanceGaze(Player wielder, long tick) {
        Gaze g = gazes.get(wielder.getUniqueId());
        if (g == null) return;

        long now = System.currentTimeMillis();
        Entity e = plugin.getServer().getEntity(g.target);

        if (e instanceof LivingEntity target && !target.isDead() && target.isValid()) {
            // Still nearby -> still in the fight; the mark (and aura-exclusion) holds.
            if (target.getWorld().equals(wielder.getWorld())
                    && target.getLocation().distanceSquared(wielder.getLocation()) <= COMBAT_RADIUS * COMBAT_RADIUS) {
                g.lastCombatAt = now;
            }
            // During the transfer window, keep pushing the wielder's fresh debuffs onto the marked body.
            if (now < g.transferUntil && tick % GAZE_COPY_INTERVAL == 0) {
                transferDebuffs(wielder, target);
            }
        } else if (e != null) {
            // Resolved but dead/invalid -> the fight is over, drop the mark now.
            gazes.remove(wielder.getUniqueId());
            return;
        }
        // Forget a foe that has been out of combat long enough (also catches an offline/unloaded target).
        if (now - g.lastCombatAt > COMBAT_CLEAR_MS) {
            gazes.remove(wielder.getUniqueId());
        }
    }

    /** Copy every harmful effect currently on the wielder onto the marked target, mirroring its state. */
    private void transferDebuffs(Player wielder, LivingEntity target) {
        for (PotionEffect eff : wielder.getActivePotionEffects()) {
            if (!HARMFUL.contains(eff.getType())) continue;
            target.addPotionEffect(new PotionEffect(
                    eff.getType(), eff.getDuration(), eff.getAmplifier(),
                    eff.isAmbient(), eff.hasParticles(), eff.hasIcon()));
        }
    }

    // ---- right-click routing: slam (plain) / gaze (sneak) --------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (sneaking) gaze(player);
        else slam(player);
    }

    /** Sneak + right-click: fix an eye on the looked-at target and pour the wielder's debuffs onto it. */
    private void gaze(Player player) {
        LivingEntity target = pickTarget(player, GAZE_RANGE);
        if (target == null) {
            player.sendActionBar(EgoHud.status("Gaze — no target", FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 0.7f);
            return;
        }

        long now = System.currentTimeMillis();
        gazes.put(player.getUniqueId(), new Gaze(target.getUniqueId(), now + TRANSFER_WINDOW_MS, now));
        transferDebuffs(player, target); // snapshot on the cast

        gazeFx(player, target);
        player.sendActionBar(EgoHud.status("Gaze — marked", EMBER));
    }

    /** A radiant eye opening over the marked body — warm dust, a soft chime, an eye-like ring. */
    private void gazeFx(Player wielder, LivingEntity target) {
        World world = target.getWorld();
        Location crown = target.getLocation().add(0, target.getHeight() + 0.4, 0);
        world.spawnParticle(Particle.DUST, crown, 12, 0.28, 0.20, 0.28, 0.0, EYE_GLOW);
        world.spawnParticle(Particle.END_ROD, crown, 4, 0.12, 0.10, 0.12, 0.01);
        world.playSound(target.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);
        wielder.playSound(wielder.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.35f, 1.6f);
    }

    // ---- slam: the heavy lantern comes down ----------------------------------------

    /** Plain right-click: drive the lantern onto the looked-at / nearby body — big knockback, little bite. */
    private void slam(Player player) {
        long now = System.currentTimeMillis();
        long ready = slamCd.getOrDefault(player.getUniqueId(), 0L);
        if (now < ready) {
            player.sendActionBar(EgoHud.cooldown("Slam", ready - now, FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_LANTERN_STEP, 0.35f, 0.7f);
            return;
        }

        LivingEntity target = pickTarget(player, (int) SLAM_RANGE);
        if (target == null) {
            player.playSound(player.getLocation(), Sound.ITEM_MACE_SMASH_AIR, 0.5f, 1.3f);
            return; // a whiff costs no cooldown
        }

        slamImpact(player, target);
        slamCd.put(player.getUniqueId(), now + SLAM_COOLDOWN_MS);

        // Slamming a marked foe keeps the fight — and its aura-exclusion — alive.
        Gaze g = gazes.get(player.getUniqueId());
        if (g != null && g.target.equals(target.getUniqueId())) g.lastCombatAt = now;

        player.sendActionBar(EgoHud.cooldown("Slam", SLAM_COOLDOWN_MS, EMBER));
    }

    /** Little damage (fenced), lots of outward knockback, a blunt clang and a burst of impact-dust. */
    private void slamImpact(Player player, LivingEntity target) {
        UUID tid = target.getUniqueId();
        slamming.add(tid); // fence: the damage below re-enters onHit — don't let it loop
        try {
            target.damage(SLAM_DAMAGE, player);
        } finally {
            slamming.remove(tid);
        }

        Vector out = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
        if (out.lengthSquared() < 1.0e-6) {
            out = player.getLocation().getDirection().setY(0);
            if (out.lengthSquared() < 1.0e-6) out = new Vector(1, 0, 0);
        }
        out.normalize().multiply(SLAM_KB_HORIZONTAL);
        target.setVelocity(target.getVelocity().add(new Vector(out.getX(), SLAM_KB_UP, out.getZ())));

        clang(target.getLocation());
    }

    /** A blunt metal CLANG and a low burst of warm impact-dust at the struck body. */
    private void clang(Location at) {
        World world = at.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.7f, 0.6f + rng.nextFloat() * 0.15f);
        world.playSound(at, Sound.ITEM_MACE_SMASH_GROUND, 0.8f, 1.0f);

        Location impact = at.clone().add(0, 0.6, 0);
        world.spawnParticle(Particle.DUST, impact, 14, 0.35, 0.25, 0.35, 0.0, IMPACT);
        world.spawnParticle(Particle.CRIT, impact, 8, 0.30, 0.20, 0.30, 0.05);
    }

    // ---- shared: pick the looked-at / nearest-in-front living body ------------------

    /** The living body under the crosshair within {@code range}, else the nearest one roughly in front. */
    private LivingEntity pickTarget(Player player, int range) {
        Entity looked = player.getTargetEntity(range);
        if (looked instanceof LivingEntity le && !le.getUniqueId().equals(player.getUniqueId()) && !le.isDead()) {
            return le;
        }

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : player.getNearbyEntities(range, range, range)) {
            if (e.getUniqueId().equals(player.getUniqueId()) || !(e instanceof LivingEntity le) || le.isDead()) continue;
            Vector to = le.getLocation().add(0, le.getHeight() * 0.5, 0).toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist < 0.01 || dist > range) continue;
            if (to.multiply(1.0 / dist).dot(dir) < 0.55) continue; // off to the side / behind
            if (dist < bestDist) { bestDist = dist; best = le; }
        }
        return best;
    }

    // ---- warm light presentation ---------------------------------------------------

    /** A soft warm halo hovering over an ally kept in the glow. */
    private void glow(LivingEntity ally) {
        World world = ally.getWorld();
        Location halo = ally.getLocation().add(0, ally.getHeight() + 0.25, 0);
        world.spawnParticle(Particle.DUST, halo, 2, 0.16, 0.10, 0.16, 0.0, GLOW);
    }

    /** A warm lantern-ring of light circling the wielder's feet each aura pulse — low count, drawn once. */
    private void lanternRing(Player wielder) {
        World world = wielder.getWorld();
        Location feet = wielder.getLocation().add(0, 0.15, 0);
        final int points = 10;
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            Location p = feet.clone().add(Math.cos(a) * AURA_RADIUS, 0.0, Math.sin(a) * AURA_RADIUS);
            world.spawnParticle(Particle.DUST, p, 1, 0.04, 0.02, 0.04, 0.0, RING);
        }
    }

    // ---- engagement: keep the glow alive when the lantern rides in the off-hand ----

    /**
     * Engagement is otherwise main-hand-driven, so a lantern carried only in the off-hand would never be
     * ticked and its passive glow would stay dark. Re-engage whenever the lantern is swapped into a hand so
     * the tick loop keeps calling {@link #onTick}; onTick itself drops the player once it leaves both hands.
     */
    @Override
    public void onSwapHands(Player player, PlayerSwapHandItemsEvent event) {
        if (matches(event.getMainHandItem()) || matches(event.getOffHandItem())) {
            plugin.weapons().engage(this, player.getUniqueId());
        }
    }

    /** Logging in with the lantern already in the off-hand also needs an engage — the main-hand join hook misses it. */
    @Override
    public void onJoin(Player player) {
        if (isHeld(player)) plugin.weapons().engage(this, player.getUniqueId());
    }

    // ---- state cleanup -------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        // Drop the quitter's own wielder state...
        gazes.remove(id);
        slamCd.remove(id);
        slamming.remove(id);
        // ...and forget any mark that was fixed on them as a target.
        gazes.values().removeIf(g -> g.target.equals(id));
    }

    // ---- palette & lore ------------------------------------------------------------

    /** Primary — the warm lamp glow. Display name, "How to use:", ability headers. */
    private static final TextColor LAMP_YELLOW = TextColor.color(0xFFD86E); // name / warm lamp glow
    /** Secondary — the deeper warmth inside the glow. The Abnormality title line; also the action bar. */
    private static final TextColor EMBER = TextColor.color(0xF2A94E); // warmth / action-bar accent
    private static final TextColor FAINT = TextColor.color(0x8C8069); // conditions / cooldown

    /** Warm lantern-yellow glow dust hovering over allies. */
    private static final Particle.DustOptions GLOW =
            new Particle.DustOptions(Color.fromRGB(0xFF, 0xD8, 0x6E), 1.0f);
    /** The circling lantern-ring — a touch deeper amber. */
    private static final Particle.DustOptions RING =
            new Particle.DustOptions(Color.fromRGB(0xF2, 0xA9, 0x4E), 1.0f);
    /** The radiant eye that opens over a Gaze-marked body — bright golden. */
    private static final Particle.DustOptions EYE_GLOW =
            new Particle.DustOptions(Color.fromRGB(0xFF, 0xE6, 0x9A), 1.1f);
    /** Warm dust kicked up by the blunt slam. */
    private static final Particle.DustOptions IMPACT =
            new Particle.DustOptions(Color.fromRGB(0xC9, 0x9A, 0x5A), 1.3f);

    // The moveset below is written from the code, not from the old lore block it replaces: the aura also
    // covers the wielder and tamed pets and burns in either hand, the slam refunds its cooldown on a whiff,
    // and the Gaze keeps copying for a window rather than only on the cast. None of that was on the item.
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Lamp",
            "Big Bird",
            LAMP_YELLOW,
            EMBER,
            List.of(
                    "A great warm lantern; its radiant",
                    "pride shelters all who stand near."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Aura",
                            "You, nearby players and tamed pets",
                            "within 5 blocks are kept under",
                            "Resistance I. Burns in either hand.",
                            "A Gaze-marked foe is excluded."),
                    new EgoLore.Ability("[Right Click] Slam",
                            "Drives the lantern onto a body within",
                            "5 blocks: heavy knockback, half a",
                            "heart of damage. 3 second cooldown,",
                            "though a miss costs none of it."),
                    new EgoLore.Ability("[Shift + Right-click] Gaze",
                            "Marks a target within 16 blocks and",
                            "copies your harmful effects onto it —",
                            "at once, then again for 8 seconds.",
                            "The mark denies it your aura until it",
                            "has been out of the fight 10 seconds.")
            ));
}
