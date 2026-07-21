package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fourth Match Flame — "Scorched Girl" (Lobotomy Corp E.G.O Equipment, TETH).
 *
 * <p>A silver cannon born from the girl who could not stop striking matches — the fourth strike that
 * finally caught. It is SLOW and it is HEAVY: not a peashooter but a hand-cannon shotgun that coughs a
 * massive, chaotic cone of flame and lava out of a silver barrel, then needs a long breath before it can
 * roar again.
 *
 * <ul>
 *   <li><b>Right-click</b> — fire the cannon: a muzzle boom, a hard recoil kick on the wielder, and a wide
 *       {@value #HALF_ANGLE_DEG}&deg;-half-angle cone of fire projectiles out to {@value #RANGE} blocks.
 *       Every living body in the cone (never the wielder) is set ablaze and takes heavy, distance-scaled
 *       fire damage — punchy up close, still respectable at range. Capped at {@value #MAX_TARGETS} bodies
 *       so a crowd can't blow up the tick.</li>
 *   <li><b>Sneak + right-click (with Magma Blocks)</b> — molten alt-fire: consumes one MAGMA_BLOCK from the
 *       inventory and fires a lava blast <em>alongside</em> the flame cone — extra lava spray, a longer
 *       burn, and bonus molten damage. Without magma in the bag, sneak-fire is just the plain flame cone.</li>
 * </ul>
 *
 * <p>Because it hits so hard it drops into a long {@value #COOLDOWN_MS}&nbsp;ms cooldown after each shot,
 * shown on the action bar in whole seconds via {@link EgoHud#cooldown}. Each shot wears one point of mild
 * durability ({@link EgoDurability#wearMainHand(Player)}); FLINT_AND_STEEL's 64 points last a long time at
 * this cadence.
 *
 * <p>State is two in-memory collections: a UUID-&gt;last-shot-time map that gates the cooldown (cleared on
 * quit) and a set of the block-ember flights currently in the air ({@link #onDisable} reaps any still live
 * on reload). No world edits — the right-click is cancelled in either hand, so the FLINT_AND_STEEL fallback
 * never lights a fire. The per-shot scan is one bounded {@code getNearbyEntities} plus a brief particle draw,
 * and each thrown ember runs a light per-tick teleport-and-transform for the length of its flight and then
 * cancels itself; a player not holding the weapon pays nothing per tick. The wielder is skipped in the scan,
 * so their own fire never touches them.
 */
public final class FourthMatchFlameWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;
    private final NamespacedKey key;

    /** Wielder -> epoch-millis of their last shot; gates the cooldown. Cleared on quit. */
    private final Map<UUID, Long> lastShot = new HashMap<>();

    /**
     * Every thrown-block flight currently in the air. Each entry removes its own BlockDisplay + cancels
     * its task on normal timeout/impact and deregisters here; on plugin disable / reload, {@link #onDisable}
     * reaps whatever is still live so no orphan display accumulates across reloads.
     */
    private final Set<FourthMatchBlockFlight> liveFlights = new HashSet<>();

    // Tuning — a slow, heavy silver cannon-shotgun.
    private static final double RANGE          = 14.0;   // long-enough reach for a cannon
    private static final double HALF_ANGLE_DEG = 34.0;   // ~68 deg total — a wide, chaotic shotgun cone
    private static final double DAMAGE_NEAR    = 15.0;   // point-blank wallop (7.5 hearts)
    private static final double DAMAGE_FAR     =  7.0;   // still bites at max range (3.5 hearts)
    private static final double MOLTEN_BONUS   =  4.0;   // extra fire damage on the magma alt-fire
    private static final int    FIRE_TICKS     = 120;    // ~6s ablaze
    private static final int    MOLTEN_TICKS   = 200;    // ~10s ablaze on the molten blast
    private static final int    MAX_TARGETS    = 12;     // cap the cone scan
    private static final long   COOLDOWN_MS    = 8000L;  // long reload — one heavy shot at a time

    private static final int    RAYS           = 16;     // fire projectiles fanned across the cone
    private static final double  RECOIL         = 0.55;   // self-knockback kick (screen-shake substitute)

    // Thrown "flint-and-steel fire block" visual — a BlockDisplay we integrate ourselves. Purely cosmetic:
    // a Display entity renders a block but never touches the world, so it can never place fire/magma.
    private static final double PROJ_SPEED   = 0.95;   // blocks/tick launch speed along the look line
    private static final double PROJ_LOFT    = 0.16;   // extra upward kick so it arcs like a thrown block
    private static final double PROJ_GRAVITY = 0.045;  // per-tick downward pull for the arc
    private static final double PROJ_SCALE   = 0.55;   // rendered cube edge (small tumbling ember)
    private static final float  PROJ_SPIN    = 0.55f;  // radians/tick tumble
    private static final int    PROJ_LIFE    = 40;     // ~2s hard cap before self-removal

    // Palette — a silver cannon spitting matchfire: warm orange body, ember-red accents.
    private static final TextColor NAME    = TextColor.color(0xFF7A2E); // match-flame orange — primary
    private static final TextColor EMBER   = TextColor.color(0xFF9A4A); // warm ember accent
    private static final TextColor CINDER  = TextColor.color(0xE0432B); // ember-red fire accent — secondary

    private static final Color FLARE   = Color.fromRGB(0xFF, 0xC2, 0x66); // bright muzzle flare
    private static final Color SPARK   = Color.fromRGB(0xFF, 0x6A, 0x2A); // orange ember spark
    private static final Color GUNMETAL = Color.fromRGB(0xC9, 0xCD, 0xD6); // silver smoke fleck
    private static final Particle.DustOptions FLARE_DUST  = new Particle.DustOptions(FLARE, 1.4f);
    private static final Particle.DustOptions SPARK_DUST  = new Particle.DustOptions(SPARK, 0.9f);
    private static final Particle.DustOptions SILVER_DUST = new Particle.DustOptions(GUNMETAL, 1.1f);

    public FourthMatchFlameWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "fourth_match_flame");
    }

    @Override
    public String id() {
        return "fourth_match_flame";
    }

    // ---- fire the cannon ----------------------------------------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long last = lastShot.get(id);
        if (last != null) {
            long remaining = COOLDOWN_MS - (now - last);
            if (remaining > 0) {
                renderBar(player); // the always-on line already shows the reload counting down
                player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 0.7f);
                return;
            }
        }

        // Molten alt-fire only when sneaking AND we can burn a magma block. The molten gate is driven off
        // the ACTUAL consumption result so it can never claim a molten shot without a block leaving the bag
        // (and vice-versa). In survival we atomically remove exactly one MAGMA_BLOCK; in Creative we fire
        // molten without consuming, because the creative client re-syncs its inventory and would silently
        // revert any server-side removal — which is exactly why the old removeItem "did nothing".
        boolean molten = false;
        if (sneaking) {
            if (player.getGameMode() == GameMode.CREATIVE) {
                molten = player.getInventory().contains(Material.MAGMA_BLOCK);
            } else {
                molten = consumeOneMagma(player); // true only when one was genuinely decremented + written back
            }
            if (!molten) {
                // Sneak-fired with no magma to burn — soft cue, then fall through to a plain fire cone.
                player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.3f);
            }
        }

        lastShot.put(id, now);
        fire(player, molten);

        if (molten) {
            // Unmistakable "magma mode fired" confirmation on top of the molten boom.
            player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.7f);
        }

        EgoDurability.wearMainHand(player);                 // one point of wear per heavy shot
        renderBar(player);                                  // reflect the fresh reload on the composed line at once
    }

    /**
     * The always-on readout. Fourth Match Flame is genuinely single-state — one heavy reload gate — so the
     * line is just that state, ready or counting down, rendered every tick rather than flashed on fire. Every
     * path that used to send a lone cooldown now sends this, so the readout is never a momentary flash.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false;
        renderBar(player);
        return true;
    }

    private void renderBar(Player player) {
        player.sendActionBar(cannonReadout(player.getUniqueId(), System.currentTimeMillis()));
    }

    /** The cannon's reload state: counting down while it winds, else ready to roar again. */
    private Component cannonReadout(UUID id, long now) {
        Long last = lastShot.get(id);
        long rem = last == null ? 0L : COOLDOWN_MS - (now - last);
        return rem > 0 ? EgoHud.cooldown("Fourth Match Flame", rem, CINDER)
                       : EgoHud.ready("Fourth Match Flame", EMBER);
    }

    /** One cannon blast: boom SFX, recoil kick, the big chaotic cone VFX, then scald the cone. */
    private void fire(Player player, boolean molten) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        boomFx(player, eye, dir, molten);
        recoil(player, dir);
        coneFx(world, eye, dir, molten);

        // Alongside the cone, hurl a purely-visual thrown "fire block" (the flint-and-steel look).
        Location muzzle = eye.clone().add(dir.clone().multiply(1.0));
        throwPhysicsBlock(world, muzzle, dir, Material.FIRE.createBlockData());
        // Molten alt-fire also tosses a spinning magma block. Launch it from a laterally + upward offset
        // muzzle on a slightly diverged arc so it does NOT render coincident (z-fighting) inside the fire
        // block — the old version reused the identical muzzle/velocity, so the magma glob was thrown but sat
        // perfectly hidden inside the fire display and never showed. Add a distinct molten blast too.
        if (molten) {
            Vector side = dir.clone().crossProduct(new Vector(0, 1, 0));
            if (side.lengthSquared() < 1.0e-6) side = new Vector(1, 0, 0);
            side.normalize();
            Location magmaMuzzle = muzzle.clone().add(side.clone().multiply(0.4)).add(0.0, 0.25, 0.0);
            Vector magmaDir = dir.clone().add(side.multiply(0.12)).normalize();
            throwPhysicsBlock(world, magmaMuzzle, magmaDir, Material.MAGMA_BLOCK.createBlockData());
            moltenBlastFx(world, eye, dir);
        }

        scald(player, eye, dir, molten);
    }

    /**
     * Launch a spinning, gravity-arced {@link BlockDisplay} out of the muzzle as a purely-cosmetic thrown
     * block — the "fire made by flint and steel" flourish. We integrate its motion ourselves each tick and
     * {@link BlockDisplay#remove() remove} it on the first solid-block impact or after {@link #PROJ_LIFE}
     * ticks. A {@code BlockDisplay} is a render-only entity: it has no block-place behaviour and never
     * interacts with the world, so this can <em>never</em> leave a real fire or magma block behind — and it
     * needs no listener or Reliquary wiring.
     */
    private void throwPhysicsBlock(World world, Location muzzle, Vector dir, BlockData block) {
        float half = (float) (PROJ_SCALE * 0.5);
        BlockDisplay display = world.spawn(muzzle, BlockDisplay.class, d -> {
            d.setBlock(block);
            // Small cube, roughly centred on the entity origin so the tumble reads as a spinning ember.
            d.setTransformation(new Transformation(
                    new Vector3f(-half, -half, -half),
                    new Quaternionf(),
                    new Vector3f((float) PROJ_SCALE, (float) PROJ_SCALE, (float) PROJ_SCALE),
                    new Quaternionf()));
            d.setBrightness(new Display.Brightness(15, 15)); // glow like open flame regardless of ambient light
            d.setInterpolationDuration(1);
            d.setInterpolationDelay(0);
            d.setPersistent(false); // a hard crash can never save this render-only ember to disk
        });

        Vector velocity = dir.clone().multiply(PROJ_SPEED).add(new Vector(0.0, PROJ_LOFT, 0.0));
        FourthMatchBlockFlight flight = new FourthMatchBlockFlight(display, velocity, half);
        liveFlights.add(flight); // tracked so onDisable can reap an in-flight ember on reload
        flight.runTaskTimer(plugin, 1L, 1L);
    }

    /** Self-contained per-tick integrator for one thrown {@link BlockDisplay}. Removes it on impact/timeout. */
    private final class FourthMatchBlockFlight extends BukkitRunnable {
        private final BlockDisplay display;
        private final Vector velocity;
        private final float half;
        private final Vector3f scale;
        private int life = PROJ_LIFE;
        private float angle = 0.0f;

        FourthMatchBlockFlight(BlockDisplay display, Vector velocity, float half) {
            this.display = display;
            this.velocity = velocity;
            this.half = half;
            this.scale = new Vector3f((float) PROJ_SCALE, (float) PROJ_SCALE, (float) PROJ_SCALE);
        }

        @Override
        public void run() {
            if (!display.isValid() || --life < 0) {
                finishFlight();
                return;
            }

            velocity.setY(velocity.getY() - PROJ_GRAVITY);
            Location from = display.getLocation();
            Location next = from.clone().add(velocity);

            // Stop the instant it would enter a solid block — leave a small flame puff, then vanish.
            if (next.getBlock().getType().isSolid()) {
                display.getWorld().spawnParticle(Particle.FLAME, next, 6, 0.1, 0.1, 0.1, 0.02);
                finishFlight();
                return;
            }

            display.teleport(next);

            // Tumble it and trail a lick of flame so the "thrown fire" reads in flight.
            angle += PROJ_SPIN;
            Quaternionf spin = new Quaternionf().rotateXYZ(angle, angle * 0.7f, angle * 0.3f);
            display.setTransformation(new Transformation(
                    new Vector3f(-half, -half, -half), spin, scale, new Quaternionf()));
            display.getWorld().spawnParticle(Particle.SMALL_FLAME, next, 1, 0.02, 0.02, 0.02, 0.0);
        }

        /** Normal end (timeout / impact): remove the display, cancel, and drop out of the live set. */
        private void finishFlight() {
            display.remove();
            cancel();
            liveFlights.remove(this);
        }

        /** Disable-time reap: remove the display and cancel; the caller clears the live set. */
        void shutdown() {
            display.remove();
            cancel();
        }
    }

    /**
     * Remove exactly one {@link Material#MAGMA_BLOCK} from the player's inventory, writing the slot back so
     * the change persists. Returns {@code true} iff one was genuinely consumed. Scans slots directly and
     * decrements the first matching stack — an atomic check-and-take that (unlike a decoupled
     * {@code contains(...)} then {@code removeItem(...)}) can never let the molten gate disagree with what
     * actually left the bag. Callers skip this in Creative, where a client re-sync reverts the removal.
     */
    private boolean consumeOneMagma(Player player) {
        var inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.getType() != Material.MAGMA_BLOCK || stack.getAmount() < 1) continue;
            int amount = stack.getAmount();
            if (amount <= 1) {
                inv.setItem(slot, null);
            } else {
                stack.setAmount(amount - 1);
                inv.setItem(slot, stack);
            }
            return true;
        }
        return false;
    }

    /** A distinctive molten spray at the muzzle so the alt-fire reads unmistakably as lava, not just flame. */
    private void moltenBlastFx(World world, Location eye, Vector dir) {
        Location muzzle = eye.clone().add(dir.clone().multiply(0.9));
        world.spawnParticle(Particle.LAVA, muzzle, 14, 0.25, 0.25, 0.25, 0);
        world.spawnParticle(Particle.FALLING_LAVA, muzzle, 10, 0.3, 0.3, 0.3, 0);
        world.spawnParticle(Particle.DRIPPING_LAVA, muzzle, 8, 0.25, 0.25, 0.25, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, muzzle, 6, 0.2, 0.2, 0.2, 0.03);
    }

    /** Scorch every living body (never the wielder) inside the forward cone — distance-scaled, applied once. */
    private void scald(Player player, Location eye, Vector dir, boolean molten) {
        double cosLimit = Math.cos(Math.toRadians(HALF_ANGLE_DEG));
        UUID selfId = player.getUniqueId();
        int fireTicks = molten ? MOLTEN_TICKS : FIRE_TICKS;
        int hit = 0;

        for (var entity : player.getNearbyEntities(RANGE, RANGE, RANGE)) {
            if (entity.getUniqueId().equals(selfId)) continue;      // defensively skip the wielder
            if (!(entity instanceof LivingEntity target)) continue;

            Vector to = target.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist < 1.0e-4 || dist > RANGE) continue;
            if (to.multiply(1.0 / dist).dot(dir) < cosLimit) continue;

            // Distance falloff: point-blank wallop bleeding down to a still-solid hit at range.
            double t = Math.min(1.0, dist / RANGE);
            double dmg = DAMAGE_NEAR + (DAMAGE_FAR - DAMAGE_NEAR) * t + (molten ? MOLTEN_BONUS : 0.0);

            // Re-enters onHit dispatch, but this weapon doesn't override onHit — a harmless no-op.
            target.damage(dmg, player);
            if (target.getFireTicks() < fireTicks) target.setFireTicks(fireTicks);

            Location body = target.getLocation().add(0, 1, 0);
            target.getWorld().spawnParticle(Particle.FLAME, body, 12, 0.3, 0.4, 0.3, 0.02);
            if (molten) {
                target.getWorld().spawnParticle(Particle.LAVA, body, 6, 0.3, 0.4, 0.3, 0);
                target.getWorld().spawnParticle(Particle.DRIPPING_LAVA, body, 5, 0.3, 0.4, 0.3, 0);
            }

            if (++hit >= MAX_TARGETS) break;                        // cap the crowd
        }
    }

    /** A hard recoil kick backward-and-up — the "heavy cannon shoves you" feel, a stand-in for screen shake. */
    private void recoil(Player player, Vector dir) {
        Vector kick = dir.clone().multiply(-RECOIL).setY(Math.max(0.12, -dir.getY() * RECOIL + 0.12));
        player.setVelocity(player.getVelocity().add(kick));
    }

    // ---- presentation --------------------------------------------------------------

    /** A big silver-cannon boom: explosion crack, a fire-charge whoomph, and a molten hiss on the alt-fire. */
    private void boomFx(Player player, Location eye, Vector dir, boolean molten) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location muzzle = eye.clone().add(dir.clone().multiply(0.8));

        world.playSound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.7f + rng.nextFloat() * 0.15f);   // BOOM
        world.playSound(muzzle, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.6f + rng.nextFloat() * 0.2f);       // whoomph
        world.playSound(muzzle, Sound.ENTITY_GHAST_SHOOT, 0.8f, 0.7f + rng.nextFloat() * 0.2f);        // heavy cough
        world.playSound(muzzle, Sound.ITEM_FLINTANDSTEEL_USE, 0.9f, 0.8f + rng.nextFloat() * 0.2f);    // the strike
        world.playSound(muzzle, Sound.BLOCK_FIRE_AMBIENT, 1.0f, 0.7f);                                  // roar
        if (molten) {
            world.playSound(muzzle, Sound.ENTITY_GENERIC_BURN, 1.0f, 0.6f);
            world.playSound(muzzle, Sound.BLOCK_LAVA_POP, 1.0f, 0.7f + rng.nextFloat() * 0.2f);
        }

        // Muzzle blast: a flare, a puff of silver smoke, and a lick of fire at the barrel (no tnt puff).
        world.spawnParticle(Particle.DUST, muzzle, 10, 0.15, 0.15, 0.15, 0, FLARE_DUST);
        world.spawnParticle(Particle.DUST, muzzle, 8, 0.2, 0.2, 0.2, 0, SILVER_DUST);
        world.spawnParticle(Particle.LARGE_SMOKE, muzzle, 8, 0.15, 0.15, 0.15, 0.02);
        world.spawnParticle(Particle.FLAME, muzzle, 12, 0.12, 0.12, 0.12, 0.05);
        if (molten) world.spawnParticle(Particle.LAVA, muzzle, 6, 0.15, 0.15, 0.15, 0);
    }

    /**
     * A massive, chaotic cone of fire projectiles fanned out along the look line — {@value #RANGE}-block
     * rays of FLAME + SMALL_FLAME with LAVA sparks and drifting cinders, plus a molten lava spray on the
     * alt-fire. Randomised per-ray so no two blasts look the same. Low-ish per-point count, drawn once.
     */
    private void coneFx(World world, Location eye, Vector dir, boolean molten) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Build a frame around the look direction so we can fan rays across the cone.
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(dir).normalize();

        double spread = Math.toRadians(HALF_ANGLE_DEG);

        for (int r = 0; r < RAYS; r++) {
            // Chaotic scatter across the full cone — not an even fan.
            double yaw   = (rng.nextDouble() * 2.0 - 1.0) * spread;
            double pitch = (rng.nextDouble() * 2.0 - 1.0) * spread;
            Vector rayDir = dir.clone()
                    .multiply(Math.cos(yaw) * Math.cos(pitch))
                    .add(right.clone().multiply(Math.sin(yaw) * Math.cos(pitch)))
                    .add(up.clone().multiply(Math.sin(pitch)))
                    .normalize();

            double reach = RANGE * (0.6 + rng.nextDouble() * 0.4); // ragged ray lengths
            for (double d = 0.8; d <= reach; d += 0.6) {
                Location p = eye.clone().add(rayDir.clone().multiply(d));
                world.spawnParticle(Particle.FLAME, p, 1, 0.08, 0.08, 0.08, 0.01);
                world.spawnParticle(Particle.SMALL_FLAME, p, 1, 0.08, 0.08, 0.08, 0.006);
                if (rng.nextInt(3) == 0) world.spawnParticle(Particle.LAVA, p, 1, 0.05, 0.05, 0.05, 0);
                if (rng.nextInt(4) == 0) world.spawnParticle(Particle.DUST, p, 1, 0.06, 0.06, 0.06, 0, SPARK_DUST);
                if (molten && rng.nextInt(3) == 0) {
                    world.spawnParticle(Particle.LAVA, p, 1, 0.08, 0.08, 0.08, 0);
                    world.spawnParticle(Particle.FALLING_LAVA, p, 1, 0.06, 0.06, 0.06, 0);
                }
            }
        }
    }

    // ---- item ----------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.FOURTH_MATCH_FLAME.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.FOURTH_MATCH_FLAME.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.FOURTH_MATCH_FLAME);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ----------------------------------------------------------------------

    // The old block opened with "Fourth Match Flame" as its title line — the weapon's own name, repeated
    // under itself. The title line is the Abnormality, so it now reads "Scorched Girl" (the name the spec
    // gives the girl who could not stop striking matches); the display name is still the weapon.
    //
    // Primary is the match-flame orange the name has always been. Secondary is CINDER, the ember-red this
    // weapon already burns its flavour and its molten cooldown in — an accent from its own palette, and far
    // enough off the primary orange that the title line doesn't read as more of the name.
    //
    // The moveset below is taken from onInteract/fire/scald rather than from the old how-to lines, which
    // had drifted: they hung "Long reload" off the molten shot alone (the 8s cooldown is charged on EVERY
    // shot), and read as though magma were required to sneak-fire (it isn't — a sneak with an empty bag
    // hisses and fires the plain cone). Numbers here are the constants, not a re-description of them.

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Fourth Match Flame",
            "Scorched Girl",
            NAME,
            CINDER,
            List.of(
                    "The fire roars and burns like",
                    "the first flame."
            ),
            List.of(
                    new EgoLore.Ability("[Right Click] Flame Cone",
                            "Fire the cannon: a wide cone of flame",
                            "out to 14 blocks, and a hard recoil",
                            "kick. Everything caught in it is set",
                            "ablaze for 6 seconds and takes 15",
                            "damage point-blank, falling to 7 at",
                            "the edge of the range. Hits up to 12",
                            "bodies. 8 second reload."),
                    new EgoLore.Ability("[Shift + Right-click] Molten Blast",
                            "Burns one Magma Block to fire a lava",
                            "blast alongside the flame cone: 4",
                            "extra damage and 10 seconds ablaze.",
                            "With no Magma Block to burn, fires",
                            "the plain cone instead.")
            ));

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        lastShot.remove(id);
    }

    @Override
    public void onDisable() {
        // Bukkit cancels our flight tasks on disable/reload before they remove their displays — reap the
        // still-live thrown BlockDisplays here so orphans can't accumulate across reloads.
        for (FourthMatchBlockFlight flight : new ArrayList<>(liveFlights)) {
            flight.shutdown();
        }
        liveFlights.clear();
    }
}
