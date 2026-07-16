package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Laetitia — "Laetitia" (Lobotomy Corp E.G.O, HE). The creepy doll-girl's ranged toy.
 *
 * <p>A childlike crossbow-toy that spits eerie <b>maroon toy shots</b>. Each shot weaves through the air
 * on a sickly sine-sway — a wobbling point of deep red that drifts unsettlingly toward whatever it
 * fancies. The doll likes to pick a <em>playmate</em>: mark someone, and her shots prefer to strike them,
 * curving across the room to find them.
 *
 * <ul>
 *   <li><b>Right-click</b> — fire a wobbling maroon toy shot (a lightweight {@link Bolt} runnable, not an
 *       Arrow). It weaves along its flight, deals {@value #DAMAGE} on contact / block-hit / after
 *       {@value #MAX_LIFETIME} ticks. If the caster has a live, nearby playmate it gently homes onto
 *       them; otherwise it flies nearly straight. A raised shield facing the shot blocks it entirely.
 *       Each shot wears the toy a little (durability). Fire cooldown {@value #COOLDOWN_MS} ms.</li>
 *   <li><b>Sneak + right-click</b> — mark a playmate: the living body under the crosshair (or the
 *       nearest one in front) becomes this caster's playmate for {@value #MARK_TTL_MS} ms. Re-marking
 *       replaces the old one.</li>
 * </ul>
 *
 * <p>State is two in-memory UUID-keyed maps (fire-cooldown + playmate marks), both cleared on quit.
 * No world edits; each bolt is a single cheap runnable that always caps its lifetime and cancels itself
 * on end / when its caster goes offline.
 */
public final class LaetitiaWeapon implements Weapon {

    private final Reliquary plugin;
    private final NamespacedKey key;

    /** Caster -> epoch-millis of their last fire. */
    private final Map<UUID, Long> lastFire = new HashMap<>();

    /** Caster -> the playmate they've marked (target UUID + when the mark expires). */
    private final Map<UUID, Mark> playmates = new HashMap<>();

    /** A marked playmate: whose body, and when the doll loses interest. */
    private record Mark(UUID target, long expiresAt) {}

    // Tuning.
    private static final double DAMAGE       = 5.0;    // 2.5 hearts — modest
    private static final long   COOLDOWN_MS  = 1000L;  // ~1s between bolts
    private static final int    MAX_LIFETIME = 50;     // ticks before a bolt gives up
    private static final double MARK_RANGE   = 30.0;   // how far the crosshair-mark reaches
    private static final long   MARK_TTL_MS  = 30_000L;// a playmate is forgotten after ~30s

    // Bolt flight.
    private static final double BOLT_SPEED   = 1.35;   // blocks/tick
    private static final double BOLT_STEP    = 0.45;   // sub-step (anti-tunnel)
    private static final double HIT_RADIUS   = 1.1;    // contact reach
    private static final double WOBBLE_AMP   = 0.38;   // sideways sway (blocks)
    private static final double WOBBLE_FREQ  = 0.85;   // sway phase advance per sub-step
    private static final double HOME_STRENGTH = 0.16;  // per-tick lerp toward a playmate
    private static final double HOME_RANGE   = 34.0;   // playmate must be within this to be homed onto
    private static final double AIM_RADIUS   = 3.0;    // tiny straight-flight auto-aim
    private static final double AIM_DOT      = 0.6;    // ~53° cone ahead
    private static final double AIM_STRENGTH = 0.10;   // very gentle

    // Shield block.
    private static final double BLOCK_FACING_DOT = 0.2; // shot heading vs blocker's look: <this = roughly facing it

    // Palette — deep maroon red, a childlike sparkle of light, and near-black dried blood.
    private static final TextColor NAME   = TextColor.color(0xA5323A); // maroon red — the tooltip's primary
    private static final TextColor BODY   = TextColor.color(0xC2565C); // lighter maroon — action bar
    private static final TextColor GLOOM  = TextColor.color(0x7B1E23); // deep maroon accent — the tooltip's
                                                                       // secondary, and the fire cooldown
    private static final TextColor FAINT  = TextColor.color(0x8A6A6C); // conditions / controls (muted rose-grey)

    private static final Color VOID_C   = Color.fromRGB(0x3D, 0x0F, 0x12); // near-black maroon
    private static final Color MAROON_C = Color.fromRGB(0xA5, 0x32, 0x3A); // maroon red shot
    private static final Color SPARK_C  = Color.fromRGB(0xFF, 0xE6, 0xD8); // childlike warm light sparkle
    private static final Particle.DustOptions VOID_DUST   = new Particle.DustOptions(VOID_C, 1.1f);
    private static final Particle.DustOptions MAROON_DUST = new Particle.DustOptions(MAROON_C, 0.9f);
    private static final Particle.DustOptions SPARK_DUST  = new Particle.DustOptions(SPARK_C, 0.6f);
    private static final Particle.DustOptions MARK_DUST   = new Particle.DustOptions(SPARK_C, 0.8f);

    public LaetitiaWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "laetitia");
    }

    @Override
    public String id() {
        return "laetitia";
    }

    // ---- fire & mark ---------------------------------------------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (sneaking) {
            markPlaymate(player);
        } else {
            fireBolt(player);
        }
    }

    /** Right-click: loose a wobbling void bolt, gated by a soft ~1s cooldown. */
    private void fireBolt(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long last = lastFire.get(id);
        if (last != null) {
            long remaining = COOLDOWN_MS - (now - last);
            if (remaining > 0) {
                player.sendActionBar(EgoHud.cooldown("Winding up", remaining, GLOOM));
                player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 1.8f);
                return;
            }
        }
        lastFire.put(id, now);

        LivingEntity playmate = resolvePlaymate(player);

        Bolt bolt = new Bolt(player, playmate);
        bolt.runTaskTimer(plugin, 0L, 1L);

        // The toy wears a little with every shot (Unbreaking/Creative honoured inside).
        EgoDurability.wearMainHand(player);

        // A giggly music-box cue + a soft eerie whoosh as the bolt leaves the toy.
        giggle(player.getWorld(), player.getEyeLocation());
        player.getWorld().playSound(player.getEyeLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.35f, 0.65f);
        player.getWorld().playSound(player.getEyeLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.4f, 0.8f);
    }

    /** Sneak + right-click: mark the body under the crosshair (or the nearest in front) as a playmate. */
    private void markPlaymate(Player player) {
        LivingEntity target = pickTarget(player);
        if (target == null) {
            player.sendActionBar(EgoHud.status("No one to play with…", FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 1.4f);
            return;
        }

        playmates.put(player.getUniqueId(),
                new Mark(target.getUniqueId(), System.currentTimeMillis() + MARK_TTL_MS));

        giggle(player.getWorld(), player.getEyeLocation());
        player.getWorld().playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.9f);
        markFx(target);

        player.sendActionBar(EgoHud.status("A new playmate is chosen…", BODY));
    }

    /** The living body under the crosshair, else the nearest one roughly in front within reach. */
    private LivingEntity pickTarget(Player player) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        RayTraceResult ray = world.rayTraceEntities(
                eye, dir, MARK_RANGE, 1.0,
                e -> e instanceof LivingEntity && !e.getUniqueId().equals(player.getUniqueId()));
        if (ray != null && ray.getHitEntity() instanceof LivingEntity le) return le;

        // Fallback: nearest living body inside a forward cone.
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : world.getNearbyEntities(eye, MARK_RANGE, MARK_RANGE, MARK_RANGE)) {
            if (e.getUniqueId().equals(player.getUniqueId()) || !(e instanceof LivingEntity le) || le.isDead()) continue;
            Vector to = center(le).subtract(eye.toVector());
            double dist = to.length();
            if (dist < 0.01 || dist > MARK_RANGE) continue;
            if (to.clone().multiply(1.0 / dist).dot(dir) < AIM_DOT) continue; // off to the side / behind
            if (dist < bestDist) { bestDist = dist; best = le; }
        }
        return best;
    }

    /** The caster's current playmate as a live entity, or null if none / expired / gone. */
    private LivingEntity resolvePlaymate(Player player) {
        Mark m = playmates.get(player.getUniqueId());
        if (m == null) return null;
        if (System.currentTimeMillis() > m.expiresAt()) {
            playmates.remove(player.getUniqueId());
            return null;
        }
        Entity e = plugin.getServer().getEntity(m.target());
        if (e instanceof LivingEntity le && !le.isDead() && le.isValid()) return le;
        return null;
    }

    // ---- presentation --------------------------------------------------------------

    /** A detuned, pitch-jittered music-box giggle — two chimes just out of tune with each other. */
    private void giggle(World world, Location at) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float j = rng.nextFloat() * 0.25f;
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.55f + j);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.35f, 1.9f + j * 0.5f); // slightly detuned, creepy
        world.playSound(at, Sound.ENTITY_VEX_AMBIENT, 0.2f, 1.6f + j);
    }

    /** A sparkly mark that lingers over a freshly-chosen playmate for a beat. */
    private void markFx(LivingEntity target) {
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t >= 4 || target.isDead() || !target.isValid()) { cancel(); return; }
                World w = target.getWorld();
                Location crown = target.getLocation().add(0, target.getHeight() + 0.4, 0);
                w.spawnParticle(Particle.DUST, crown, 6, 0.25, 0.15, 0.25, 0, MARK_DUST);
                w.spawnParticle(Particle.DUST, crown, 3, 0.30, 0.20, 0.30, 0, MAROON_DUST);
                w.spawnParticle(Particle.END_ROD, crown, 2, 0.15, 0.10, 0.15, 0.01);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 3L);
    }

    // ---- the wobbling void bolt ----------------------------------------------------

    /**
     * One void bolt in flight. A lightweight moving point (no Arrow entity): it advances its true
     * centre along {@code dir} each tick, but the drawn/collision position is swayed sideways on a sine
     * so it visibly weaves. If the caster has a live, nearby playmate it gently homes onto them; else it
     * flies nearly straight with a whisper of auto-aim. Always caps its lifetime and cancels itself.
     */
    private final class Bolt extends BukkitRunnable {

        private final UUID casterId;
        private final World world;
        private final Location center;   // the bolt's true (un-swayed) centre
        private Vector dir;              // heading (unit)
        private LivingEntity playmate;  // preferred victim, or null
        private double phase = 0.0;     // wobble phase
        private int ticks = 0;

        Bolt(Player caster, LivingEntity playmate) {
            this.casterId = caster.getUniqueId();
            this.world = caster.getWorld();
            this.center = caster.getEyeLocation().add(caster.getEyeLocation().getDirection().normalize().multiply(0.6));
            this.dir = caster.getEyeLocation().getDirection().normalize();
            this.playmate = playmate;
        }

        @Override
        public void run() {
            Player caster = plugin.getServer().getPlayer(casterId);
            if (caster == null || !caster.isOnline()) { cancel(); return; }

            if (++ticks > MAX_LIFETIME) { fizzle(); cancel(); return; }

            // Steer: prefer a live, nearby playmate; otherwise a whisper of straight-flight auto-aim.
            if (playmate != null
                    && (playmate.isDead() || !playmate.isValid()
                        || center(playmate).distance(center.toVector()) > HOME_RANGE)) {
                playmate = null;
            }
            if (playmate != null) {
                Vector to = center(playmate).subtract(center.toVector());
                if (to.lengthSquared() > 1e-4) {
                    dir = dir.clone().multiply(1 - HOME_STRENGTH)
                            .add(to.normalize().multiply(HOME_STRENGTH)).normalize();
                }
            } else {
                steerTiny();
            }

            double moved = 0.0;
            while (moved < BOLT_SPEED) {
                double step = Math.min(BOLT_STEP, BOLT_SPEED - moved);
                center.add(dir.clone().multiply(step));
                phase += WOBBLE_FREQ;

                Location draw = wobbled();

                // Block hit (either the true centre or the swayed draw point buried in a wall).
                if (draw.getBlock().getType().isSolid() || center.getBlock().getType().isSolid()) {
                    burst(draw, caster);
                    cancel();
                    return;
                }

                LivingEntity hit = firstHit(draw, caster);
                if (hit != null) {
                    // A raised shield facing the incoming shot swallows it whole — no damage, just a clang.
                    if (hit instanceof Player p && isShieldBlocking(p)) {
                        shieldBlock(p);
                    } else {
                        hit.damage(DAMAGE, caster);
                        burst(hit.getLocation().add(0, hit.getHeight() * 0.5, 0), caster);
                    }
                    cancel();
                    return;
                }

                trail(draw);
                moved += step;
            }

            // A soft, occasional eerie whoosh as it weaves.
            if (ticks % 6 == 0) {
                world.playSound(center, Sound.ENTITY_PHANTOM_FLAP, 0.18f, 0.6f);
            }
        }

        /** The swayed draw/collision point: centre offset sideways on a sine, perpendicular to travel. */
        private Location wobbled() {
            Vector side = perpendicular(dir);
            return center.clone().add(side.multiply(Math.sin(phase) * WOBBLE_AMP));
        }

        /** A whisper of auto-aim toward a body roughly ahead, so a straight bolt still feels alive. */
        private void steerTiny() {
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : world.getNearbyEntities(center, AIM_RADIUS, AIM_RADIUS, AIM_RADIUS)) {
                if (e.getUniqueId().equals(casterId) || !(e instanceof LivingEntity le) || le.isDead()) continue;
                Vector to = center(le).subtract(center.toVector());
                double dist = to.length();
                if (dist < 0.01) continue;
                if (to.clone().multiply(1.0 / dist).dot(dir) < AIM_DOT) continue;
                if (dist < bestDist) { bestDist = dist; best = le; }
            }
            if (best == null) return;
            Vector to = center(best).subtract(center.toVector()).normalize();
            dir = dir.clone().multiply(1.0 - AIM_STRENGTH).add(to.multiply(AIM_STRENGTH)).normalize();
        }

        /** Nearest living body (not the caster) within contact reach of the swayed point, else null. */
        private LivingEntity firstHit(Location at, Player caster) {
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : world.getNearbyEntities(at, HIT_RADIUS, HIT_RADIUS, HIT_RADIUS)) {
                if (e.getUniqueId().equals(casterId) || !(e instanceof LivingEntity le) || le.isDead()) continue;
                double d = center(le).subtract(at.toVector()).lengthSquared();
                if (d < bestDist) { bestDist = d; best = le; }
            }
            return best;
        }

        /**
         * True when {@code p} has a shield raised and is roughly turned toward the incoming shot. The bolt
         * travels along {@link #dir}; a blocker facing it looks back down that heading, so their look
         * vector dotted with the shot heading is negative (or nearly so). Perpendicular still counts as a
         * "rough" face, so the threshold is a touch above zero.
         */
        private boolean isShieldBlocking(Player p) {
            if (!p.isBlocking()) return false;
            return p.getEyeLocation().getDirection().dot(dir) < BLOCK_FACING_DOT;
        }

        /** A shield swallows the shot: a metal clang and a small spark-and-maroon puff, no damage dealt. */
        private void shieldBlock(Player p) {
            Location at = p.getLocation().add(0, p.getHeight() * 0.6, 0);
            world.playSound(at, Sound.ITEM_SHIELD_BLOCK, 0.9f, 1.0f);
            world.spawnParticle(Particle.CRIT, at, 8, 0.20, 0.20, 0.20, 0.05);
            world.spawnParticle(Particle.DUST, at, 6, 0.20, 0.25, 0.20, 0, MAROON_DUST);
        }

        /** Maroon/void shot with a childlike light sparkle and faint static, drawn at the sway point. */
        private void trail(Location at) {
            world.spawnParticle(Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0, VOID_DUST);
            world.spawnParticle(Particle.DUST, at, 1, 0.05, 0.05, 0.05, 0, MAROON_DUST);
            if ((ticks & 1) == 0) world.spawnParticle(Particle.DUST, at, 1, 0.04, 0.04, 0.04, 0, SPARK_DUST);
            if (ticks % 4 == 0) world.spawnParticle(Particle.CRIT, at, 1, 0, 0, 0, 0.0);
            if (ticks % 7 == 0) world.spawnParticle(Particle.END_ROD, at, 1, 0, 0, 0, 0.01);
        }

        /** A dark burst where the shot ends — void puff, a scatter of maroon, a childlike sparkle. */
        private void burst(Location at, Player caster) {
            world.spawnParticle(Particle.DUST, at, 12, 0.25, 0.25, 0.25, 0, VOID_DUST);
            world.spawnParticle(Particle.DUST, at, 8, 0.30, 0.30, 0.30, 0, MAROON_DUST);
            world.spawnParticle(Particle.DUST, at, 5, 0.20, 0.20, 0.20, 0, SPARK_DUST);
            world.spawnParticle(Particle.SMOKE, at, 6, 0.15, 0.15, 0.15, 0.02);
            world.spawnParticle(Particle.END_ROD, at, 3, 0.12, 0.12, 0.12, 0.02);
            world.playSound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 0.8f);
            world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.4f, 1.5f);
        }

        /** Out of life with nothing struck — a quiet dark fizzle. */
        private void fizzle() {
            world.spawnParticle(Particle.DUST, center, 5, 0.15, 0.15, 0.15, 0, VOID_DUST);
            world.spawnParticle(Particle.SMOKE, center, 3, 0.1, 0.1, 0.1, 0.01);
            world.playSound(center, Sound.ENTITY_VEX_AMBIENT, 0.25f, 0.7f);
        }
    }

    // ---- shared geometry -----------------------------------------------------------

    private static Vector center(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0).toVector();
    }

    /** A unit vector perpendicular to u (the sway axis), stable for any heading. */
    private static Vector perpendicular(Vector u) {
        Vector n = u.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : u.clone().normalize();
        Vector ref = Math.abs(n.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        return n.crossProduct(ref).normalize();
    }

    // ---- item ----------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.LAETITIA.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.LAETITIA.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.LAETITIA);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ----------------------------------------------------------------------

    // The title line here is a PLACEHOLDER and is meant to be overwritten.
    //
    // The house rule is that the display name is the weapon and the bold title line is the Abnormality it
    // came from, and that the two never repeat each other. This item is the one place the rule cannot be
    // kept: the spec records it as "Laetitia — Laetitia", because the toy and the Abnormality genuinely
    // share the name, and the old lore opened by simply saying "Laetitia" a second time. Rather than mint a
    // lore name for the Abnormality and pass it off as settled, the title below just describes what the
    // Abnormality is, marked TODO so it cannot be mistaken for the final wording.
    //
    // Ability names are placeholders on the same footing — plain descriptions of what each input does, not
    // titles. "Playmate" is the one word here that is not up for grabs: the mark is called that in the code
    // and on the action bar ("A new playmate is chosen…"), so the tooltip says it too.

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Laetitia",
            "TODO — The Doll That Yearned for Happiness",
            NAME,
            GLOOM,
            List.of(
                    "A child's toy that once yearned",
                    "for happiness, long ago."
            ),
            List.of(
                    new EgoLore.Ability("[Right Click] Curving Shot",
                            "Fire a weaving maroon shot — 5 damage",
                            "on contact. It curves toward your",
                            "playmate while the shot is within 34",
                            "blocks of them, else flies nearly",
                            "straight. A shield raised toward it",
                            "blocks it. 1 second cooldown."),
                    new EgoLore.Ability("[Shift + Right-click] Mark Playmate",
                            "Mark the body under your crosshair —",
                            "or the nearest one ahead, within 30",
                            "blocks — as your playmate for 30",
                            "seconds. Marking again replaces them.")
            ));

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        lastFire.remove(id);
        playmates.remove(id);
    }
}
