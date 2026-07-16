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
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
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
 * Fragments from Somewhere — "Fragment of the Universe" (Lobotomy Corp E.G.O, TETH).
 *
 * <p>A spear stabilized out of something that refused to hold one shape. It never arrived from the
 * workshop twice the same way; what finally got pinned down was the <b>gestalt</b>, not the object. Hold
 * it long enough and it starts pulling at the edges of the wielder's attention, trying to walk them off
 * into a long and endless realm of mind — and there is a persistent rumour that it lights up when it
 * catches an echo of a world that isn't this one.
 *
 * <p>Mechanically that rumour <em>is</em> the weapon. The spear does not travel with its wielder so much
 * as it refuses to fully agree that they moved:
 *
 * <ul>
 *   <li><b>Right-click — Universal.</b> A short forward <b>Spear Lunge</b> on a brisk
 *       {@value #LUNGE_COOLDOWN_MS}ms cooldown (the "faster lunge cooldown" of the moveset). Before the
 *       impulse lands it <b>records the spot the wielder stood on at the instant of the click</b> and
 *       leaves a refracted after-image standing there — an echo — for
 *       {@value #REFRACTION_WINDOW_MS}ms. It is pure mobility: the lunge itself deals no damage, so the
 *       weapon's whole output stays the melee profile stamped by {@link EgoModels}.</li>
 *   <li><b>Sneak + right-click — Refraction.</b> Only inside that window. The wielder snaps back into
 *       their own after-image at the recorded spot, then Refraction goes dark for
 *       {@value #REFRACTION_COOLDOWN_MS}ms. Outside the window it does nothing but sigh at them.</li>
 * </ul>
 *
 * <p>So the loop is lunge in → fight → refract out, with the echo standing behind you the whole time as
 * a visible, public promise of exactly where you are about to be. Opponents can read it. That is the
 * intended cost of the escape.
 *
 * <p><b>The window only opens when Refraction can actually answer it.</b> If Refraction is still cooling
 * down, a lunge is just a lunge — no echo, no gauge. Showing a wielder a draining window they cannot
 * spend would be a lie told by the HUD, and this weapon lies to its wielder quite enough already.
 *
 * <p><b>The return is wall-safe.</b> The recorded spot was standable when it was recorded, but six
 * seconds is plenty of time for someone to wall it up. Before teleporting, the destination is re-probed
 * and, if it is sealed, a small lattice around it is tried; if nothing there can hold a body the
 * Refraction is refused <em>without</em> burning its cooldown, and the window stays open in case the
 * blockage clears.
 *
 * <p><b>Holding the spear is part of the deal.</b> Sheathe it and the echo collapses — {@link #onTick}
 * disengages the instant the weapon leaves the main hand, which is also the only thing keeping this
 * weapon off the tick loop for everyone not currently using it. Death voids the record too (see
 * {@link #onPlayerDeath}); a corpse's echo would otherwise drag the respawned wielder straight back onto
 * whatever killed them.
 *
 * <p>All state is per-wielder and keyed by player UUID (no victim-keyed maps here, so nothing can leak
 * behind a mob that never fires a quit event); cooldown clocks are pruned the moment a wielder goes
 * fully idle, dropped on quit, and cleared on disable. Every echo is a non-persistent, tagged
 * {@link ItemDisplay} tracked in {@link #liveEchoes} and reaped on disable, with a tag sweep behind it
 * so a reload can never orphan one.
 */
public final class FragmentsFromSomewhereWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Fragments from Somewhere. */
    private final NamespacedKey key;

    // ---- tuning: the lunge --------------------------------------------------------

    /**
     * Lunge cooldown. The moveset asks for a "faster lunge cooldown" but names no baseline to be faster
     * than, so this is a chosen default: brisk enough to be the wielder's default way of closing, slow
     * enough that it can't be chained into permanent flight. Tune here.
     */
    private static final long LUNGE_COOLDOWN_MS = 4_000L;

    /** Lunge impulse in blocks/tick — a committed spear thrust (~6-8 blocks of glide), not a leap. */
    private static final double LUNGE_POWER = 1.0;

    /** Floor on the lunge's vertical component so the thrust skims over ground instead of digging in. */
    private static final double LUNGE_MIN_LIFT = 0.22;

    // ---- tuning: refraction -------------------------------------------------------

    /** How long the recorded spot stays returnable after a lunge. */
    private static final long REFRACTION_WINDOW_MS = 6_000L;

    /** How long Refraction stays dark after a completed return. */
    private static final long REFRACTION_COOLDOWN_MS = 7_000L;

    /** Fall-damage waiver after a lunge or a return — neither should be paid for on landing. */
    private static final long FALL_GRACE_MS = 1_500L;

    /** Vertical offsets tried when the recorded spot no longer holds a body, nearest first. */
    private static final double[] RETURN_PROBE_Y = {0.0, 1.0, -1.0, 2.0};

    /** Horizontal nudges tried at each probe height — a tight lattice; a shuffle, never a relocation. */
    private static final double[][] RETURN_PROBE_XZ = {{0.0, 0.0}, {0.6, 0.0}, {-0.6, 0.0}, {0.0, 0.6}, {0.0, -0.6}};

    // ---- tuning: the echo ---------------------------------------------------------

    /** Scoreboard tag on every echo display — the handle the disable-time orphan sweep grabs. */
    private static final String ECHO_TAG = "fragments_echo";

    /** Hover height of the after-image above the recorded spot. */
    private static final double ECHO_HEIGHT = 1.0;

    /** Degrees the after-image turns per tick — a slow, wrong-looking rotation. */
    private static final float ECHO_SPIN_DEG = 5.0f;

    /** Bob amplitude of the after-image, in blocks. */
    private static final double ECHO_BOB = 0.09;

    /** Emit the echo's shimmer every N onTick calls (onTick runs every 2 game ticks). */
    private static final int ECHO_FX_PERIOD = 3;

    // ---- state (all per-wielder, keyed by player UUID) ------------------------------

    /** Wielder -> epoch-millis at which their lunge comes back. Pruned when they go fully idle. */
    private final Map<UUID, Long> lungeReadyAt = new HashMap<>();

    /** Wielder -> epoch-millis at which Refraction comes back. Pruned when they go fully idle. */
    private final Map<UUID, Long> refractionReadyAt = new HashMap<>();

    /** Wielder -> epoch-millis until which lunge/return fall damage is waived. */
    private final Map<UUID, Long> fallGraceUntil = new HashMap<>();

    /** Wielder -> their live recorded spot + its after-image. At most one; a fresh lunge replaces it. */
    private final Map<UUID, Anchor> anchors = new HashMap<>();

    /** Every echo display currently standing — reaped on disable so a reload can never orphan one. */
    private final Set<ItemDisplay> liveEchoes = new HashSet<>();

    /** Wielders owed a one-off "Lunge — ready" flash once their cooldown elapses. Keeps the HUD quiet. */
    private final Set<UUID> readyPending = new HashSet<>();

    public FragmentsFromSomewhereWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "fragments_from_somewhere");
    }

    @Override
    public String id() {
        return "fragments_from_somewhere";
    }

    // ---- palette ------------------------------------------------------------------

    /** Primary — the spear's unsettled violet. Name, ability headers, the "How to use:" header. */
    private static final TextColor PRIMARY = TextColor.color(0x9B6FD4);

    /** Secondary — the rose the violet refracts into. The Abnormality title line, the live window gauge. */
    private static final TextColor SECONDARY = TextColor.color(0xE24F6E);

    /** Cooldowns and denials — deliberately quiet; the loud colours belong to the live window. */
    private static final TextColor FAINT = TextColor.color(0x7A6E8C);

    // Particle colours, kept apart from the text palette so retuning one never disturbs the other.
    private static final Color C_VIOLET = Color.fromRGB(0x9B, 0x6F, 0xD4);
    private static final Color C_ROSE   = Color.fromRGB(0xE2, 0x4F, 0x6E);
    private static final Color C_LIGHT  = Color.fromRGB(0xF2, 0xEA, 0xFF); // the rumoured bright light

    // Particle data classes are a runtime contract, not a compile-time one: DUST wants DustOptions,
    // DUST_COLOR_TRANSITION wants a DustTransition, and FLASH wants a bare Color on 26.1.2.
    private static final Particle.DustOptions VIOLET_DUST = new Particle.DustOptions(C_VIOLET, 0.9f);
    private static final Particle.DustOptions ROSE_DUST   = new Particle.DustOptions(C_ROSE, 0.9f);
    /** Violet bleeding into rose — the shimmer of something refusing to settle on a colour. */
    private static final Particle.DustTransition REFRACT_SHIMMER =
            new Particle.DustTransition(C_VIOLET, C_ROSE, 0.9f);

    // ---- tooltip ------------------------------------------------------------------

    /**
     * Built once at class-load and stamped in {@link #createItem()}. The display name is the weapon, the
     * title line is the Abnormality — never the other way round, and never both.
     */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Fragments from Somewhere",
            "Fragment of the Universe",
            PRIMARY,
            SECONDARY,
            List.of(
                    "Do not attempt to understand it, just",
                    "use it. The spear often tries to lead",
                    "the wielder into a long and endless",
                    "realm of mind, but they must try to",
                    "not be swayed by it. It took a",
                    "different form every time it was",
                    "produced, but we managed to stabilize",
                    "the gestalt after a lot of effort and",
                    "trouble. There is a rumor that this",
                    "spear emits a bright light when it",
                    "hears an echo from another world."
            ),
            List.of(
                    new EgoLore.Ability("[Right Click] Universal",
                            "Spear Lunge. Faster lunge cooldown.",
                            "Marks where you stood for 6s."),
                    new EgoLore.Ability("[Shift + Right-click] Refraction",
                            "Only within 6s of a lunge: snap back",
                            "to the marked spot. 7s cooldown.")
            ));

    // ---- item ---------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.FRAGMENTS_FROM_SOMEWHERE.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.FRAGMENTS_FROM_SOMEWHERE.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.FRAGMENTS_FROM_SOMEWHERE);

        item.setItemMeta(meta);
        return item;
    }

    // ---- input --------------------------------------------------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (sneaking) refract(player);
        else lunge(player);
    }

    // ---- [Right Click] Universal: the spear lunge -----------------------------------

    /**
     * A short forward thrust in the look direction, on the {@value #LUNGE_COOLDOWN_MS}ms lunge cooldown.
     *
     * <p>Order matters here: the spot is recorded <b>before</b> the impulse, because Refraction returns
     * the wielder to where they stood when they clicked, not to wherever the lunge threw them. The
     * record is only taken when Refraction is off cooldown — see the class docs.
     */
    private void lunge(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        long wait = remaining(lungeReadyAt, id, now);
        if (wait > 0) {
            player.sendActionBar(EgoHud.cooldown("Lunge", wait, FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 1.5f + jitter());
            return;
        }
        lungeReadyAt.put(id, now + LUNGE_COOLDOWN_MS);
        readyPending.add(id); // owed one "ready" flash when this cooldown elapses

        if (remaining(refractionReadyAt, id, now) <= 0) openWindow(player);

        Vector vel = player.getEyeLocation().getDirection().multiply(LUNGE_POWER);
        if (vel.getY() < LUNGE_MIN_LIFT) vel.setY(LUNGE_MIN_LIFT);
        player.setVelocity(vel);

        fallGraceUntil.put(id, now + FALL_GRACE_MS);
        EgoDurability.wearMainHand(player); // non-vanilla motion — the melee swing wears on its own

        lungeFx(player);
    }

    /** Record the wielder's current spot and stand a refracted after-image on it. Replaces any older one. */
    private void openWindow(Player player) {
        UUID id = player.getUniqueId();
        dropAnchor(id); // a fresh lunge supersedes the last record; never stack two echoes on one wielder

        Anchor anchor = new Anchor(player, spawnEcho(player.getWorld(),
                player.getLocation().clone().add(0, ECHO_HEIGHT, 0)));
        anchors.put(id, anchor);

        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.playSound(at, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.7f, 1.4f + jitter());
        world.spawnParticle(Particle.REVERSE_PORTAL, at, 14, 0.28, 0.5, 0.28, 0.02);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 8, 0.28, 0.5, 0.28, 0.0, REFRACT_SHIMMER);
    }

    /** The thrust itself: a riptide-shove of sound and a violet wake torn along the lunge line. */
    private void lungeFx(Player player) {
        World world = player.getWorld();
        Location from = player.getLocation().add(0, 1.0, 0);
        Vector dir = player.getEyeLocation().getDirection();

        world.playSound(from, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8f, 1.2f + jitter());
        world.playSound(from, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.6f + jitter());

        // A short wake behind the thrust — sparse points along the line, not a per-step spray.
        for (int i = 0; i < 5; i++) {
            Location p = from.clone().subtract(dir.clone().multiply(i * 0.35));
            world.spawnParticle(Particle.END_ROD, p, 1, 0.05, 0.05, 0.05, 0.005);
            world.spawnParticle(Particle.DUST, p, 2, 0.10, 0.10, 0.10, 0.0, VIOLET_DUST);
        }
    }

    // ---- [Shift + Right-click] Refraction: snap back into the echo -------------------

    /**
     * Return the wielder to the recorded spot, if there is one and it will still hold them.
     *
     * <p>Every refusal path here leaves the cooldown untouched — Refraction is only spent when a body
     * actually moves. A blocked return in particular keeps the window open, so a wielder who gets walled
     * in has the rest of their six seconds to try again.
     */
    private void refract(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        long cooling = remaining(refractionReadyAt, id, now);
        if (cooling > 0) {
            player.sendActionBar(EgoHud.cooldown("Refraction", cooling, FAINT));
            denyFx(player);
            return;
        }

        Anchor anchor = anchors.get(id);
        if (anchor == null || now >= anchor.expiresAt()) {
            if (anchor != null) dropAnchor(id);
            player.sendActionBar(EgoHud.status("No echo to return to…", FAINT));
            denyFx(player);
            return;
        }
        if (!player.getWorld().getUID().equals(anchor.worldId())) {
            dropAnchor(id); // the echo is in a world the wielder has walked out of — it means nothing now
            player.sendActionBar(EgoHud.status("The echo is a world away…", FAINT));
            denyFx(player);
            return;
        }

        Location dest = safeReturn(anchor.where());
        if (dest == null) {
            player.sendActionBar(EgoHud.status("The echo is buried…", FAINT));
            denyFx(player);
            return;
        }
        // Return the body, not the camera: the wielder keeps looking wherever they were looking.
        dest.setYaw(player.getLocation().getYaw());
        dest.setPitch(player.getLocation().getPitch());

        refractionReadyAt.put(id, now + REFRACTION_COOLDOWN_MS);
        fallGraceUntil.put(id, now + FALL_GRACE_MS);
        EgoDurability.wearMainHand(player);

        departFx(player.getLocation().add(0, 1.0, 0));
        dropAnchor(id); // the after-image is about to become the wielder again — collapse it first
        player.teleport(dest);
        arriveFx(dest.clone().add(0, 1.0, 0));

        player.sendActionBar(EgoHud.cooldown("Refraction", REFRACTION_COOLDOWN_MS, FAINT));
    }

    /**
     * The recorded spot if it will still hold a body, else the nearest spot in a tight lattice around it
     * that will, else null. Six seconds is long enough for the world to close over a footprint.
     */
    private static Location safeReturn(Location anchor) {
        for (double dy : RETURN_PROBE_Y) {
            for (double[] off : RETURN_PROBE_XZ) {
                Location cand = anchor.clone().add(off[0], dy, off[1]);
                if (canStand(cand)) return cand;
            }
        }
        return null;
    }

    /** True if a player's body (feet + head) fits at {@code feet} without clipping into a solid block. */
    private static boolean canStand(Location feet) {
        return feet.getBlock().isPassable() && feet.clone().add(0, 1, 0).getBlock().isPassable();
    }

    // ---- the echo: a refracted after-image standing where you were -------------------

    /**
     * A wielder's recorded return point: the spot, the world it belongs to, its expiry, and the
     * after-image standing on it. Immutable apart from the spin counter that drives its rotation.
     */
    private static final class Anchor {
        private final Location where;   // the exact spot recorded at the instant of the right-click
        private final UUID worldId;     // the world that spot belongs to — a wielder can leave it
        private final long expiresAt;
        private final ItemDisplay echo;
        private int spin = 0;

        Anchor(Player owner, ItemDisplay echo) {
            this.where = owner.getLocation().clone();
            this.worldId = owner.getWorld().getUID();
            this.expiresAt = System.currentTimeMillis() + REFRACTION_WINDOW_MS;
            this.echo = echo;
        }

        Location where() { return where; }

        UUID worldId() { return worldId; }

        long expiresAt() { return expiresAt; }

        /** Turn and bob the after-image, and let it shimmer every few ticks. */
        void animate(long tick) {
            if (echo == null || echo.isDead() || !echo.isValid()) return;
            Location spot = where.clone().add(0, ECHO_HEIGHT + Math.sin(tick * 0.14) * ECHO_BOB, 0);
            spot.setYaw(where.getYaw() + spin * ECHO_SPIN_DEG);
            spot.setPitch(0f);
            echo.teleport(spot);
            spin++;

            if ((tick % ECHO_FX_PERIOD) != 0) return;
            World w = echo.getWorld();
            w.spawnParticle(Particle.END_ROD, spot, 1, 0.06, 0.10, 0.06, 0.0);
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, spot, 2, 0.12, 0.22, 0.12, 0.0, REFRACT_SHIMMER);
        }

        void removeEcho() {
            if (echo != null && echo.isValid()) echo.remove();
        }
    }

    /** Spawn one after-image: the spear's own model, lit bright, non-persistent and tagged for the sweep. */
    private ItemDisplay spawnEcho(World world, Location at) {
        ItemDisplay d = world.spawn(at, ItemDisplay.class, e -> {
            ItemStack model = new ItemStack(EgoModels.FRAGMENTS_FROM_SOMEWHERE.material());
            ItemMeta m = model.getItemMeta();
            if (m != null) {
                // Model only — never stampWeapon on a display, or it carries attack modifiers it can't use.
                EgoModels.stamp(m, EgoModels.FRAGMENTS_FROM_SOMEWHERE);
                model.setItemMeta(m);
            }
            e.setItemStack(model);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setBillboard(Display.Billboard.FIXED);
            e.setBrightness(new Display.Brightness(15, 15)); // the rumoured light, always on
            e.setPersistent(false);                          // a crash can never leave an echo on disk
            e.setInterpolationDuration(2);
            e.setTeleportDuration(2);                        // smooth the spin between ticks
            e.setTransformation(echoPose());
            e.addScoreboardTag(ECHO_TAG);
        });
        liveEchoes.add(d);
        return d;
    }

    /** The after-image's pose: tip hanging down, slightly undersized — a memory of a spear, not a spear. */
    private static Transformation echoPose() {
        return new Transformation(new Vector3f(),
                new Quaternionf().rotationTo(0, 1, 0, 0, -1, 0),
                new Vector3f(0.85f, 0.85f, 0.85f), new Quaternionf());
    }

    /** Forget a wielder's record and take its after-image down with it. Safe to call when there is none. */
    private void dropAnchor(UUID id) {
        Anchor a = anchors.remove(id);
        if (a == null) return;
        a.removeEcho();
        liveEchoes.remove(a.echo);
    }

    // ---- SFX / VFX ----------------------------------------------------------------

    /** The window ran out (or the wielder left its world) — the echo comes apart where it stood. */
    private static void fadeEcho(Anchor anchor) {
        ItemDisplay e = anchor.echo;
        if (e == null || !e.isValid()) return;
        World w = e.getWorld();
        Location at = e.getLocation();
        w.spawnParticle(Particle.DUST, at, 8, 0.18, 0.30, 0.18, 0.0, VIOLET_DUST);
        w.spawnParticle(Particle.PORTAL, at, 10, 0.15, 0.30, 0.15, 0.05);
        w.playSound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.4f, 1.6f);
    }

    /** The wielder coming apart at the spot they're leaving. */
    private static void departFx(Location at) {
        World w = at.getWorld();
        w.playSound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 0.9f);
        w.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.5f);
        w.spawnParticle(Particle.PORTAL, at, 22, 0.28, 0.5, 0.28, 0.12);
        w.spawnParticle(Particle.DUST, at, 10, 0.28, 0.5, 0.28, 0.0, ROSE_DUST);
    }

    /**
     * The wielder snapping back into their own after-image — and the bright light the file notes keep
     * bringing up. The {@code force} flag matters: this fires at a spot the wielder has spent six seconds
     * running away from, which can easily be past the ~32-block range a client culls particles at.
     */
    private static void arriveFx(Location at) {
        World w = at.getWorld();
        // FLASH takes a bare Color as its data class on 26.1.2 — spawned without one it throws at runtime.
        w.spawnParticle(Particle.FLASH, at, 1, 0.0, 0.0, 0.0, 0.0, C_LIGHT, true);
        w.spawnParticle(Particle.END_ROD, at, 16, 0.22, 0.42, 0.22, 0.05, null, true);
        w.spawnParticle(Particle.REVERSE_PORTAL, at, 26, 0.28, 0.5, 0.28, 0.10, null, true);
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 12, 0.28, 0.5, 0.28, 0.0, REFRACT_SHIMMER, true);
        w.playSound(at, Sound.ITEM_TRIDENT_RETURN, 0.9f, 1.1f);
        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f);
        w.playSound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7f, 1.2f);
    }

    /** A small, dry refusal — the spear declining to do the interesting thing. */
    private static void denyFx(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 1.2f);
    }

    private static float jitter() {
        return (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.12f;
    }

    // ---- tick: the window gauge and the cooldowns -----------------------------------

    /**
     * Drives the after-image and the action bar while there is something to say, and gets off the tick
     * loop the moment there isn't.
     *
     * <p>Two exits, both load-bearing. The spear leaving the main hand disengages immediately (and takes
     * the echo with it) — without that this player ticks forever. And a wielder with no live window and
     * no running cooldown is fully idle: their clocks get pruned, they get one last "ready" flash if a
     * lunge just came back to them, and they come off the loop until their next click re-engages them.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();

        if (!matches(player.getInventory().getItemInMainHand())) {
            dropAnchor(id);        // sheathing collapses the echo — the window needs the spear held
            readyPending.remove(id);
            return false;
        }

        long now = System.currentTimeMillis();

        Anchor anchor = anchors.get(id);
        if (anchor != null) {
            boolean expired = now >= anchor.expiresAt();
            boolean strayed = !player.getWorld().getUID().equals(anchor.worldId());
            if (expired || strayed) {
                fadeEcho(anchor);
                dropAnchor(id);
                anchor = null;
            } else {
                anchor.animate(tick);
            }
        }

        long lungeWait = remaining(lungeReadyAt, id, now);
        long refractWait = remaining(refractionReadyAt, id, now);

        if (anchor == null && lungeWait <= 0 && refractWait <= 0) {
            // Fully idle. Both clocks are long past (>= 4s and >= 7s respectively), so the 1.5s fall
            // grace they might have written is dead too and goes with them.
            lungeReadyAt.remove(id);
            refractionReadyAt.remove(id);
            fallGraceUntil.remove(id);
            if (readyPending.remove(id)) player.sendActionBar(EgoHud.ready("Lunge", PRIMARY));
            return false;
        }

        // The live window outranks the cooldowns on the bar: it's the thing that's running out.
        if (anchor != null) {
            long left = anchor.expiresAt() - now;
            double frac = left / (double) REFRACTION_WINDOW_MS;
            long secs = Math.max(1L, (left + 999L) / 1000L); // whole seconds, always — never raw millis
            player.sendActionBar(EgoHud.gauge(SECONDARY, frac,
                    EgoHud.status("Refraction  " + secs + "s", SECONDARY)));
        } else if (refractWait > 0) {
            player.sendActionBar(EgoHud.cooldown("Refraction", refractWait, FAINT));
        } else {
            player.sendActionBar(EgoHud.cooldown("Lunge", lungeWait, FAINT));
        }
        return true;
    }

    /** Milliseconds left on a clock, or 0 if it's absent or already past. */
    private static long remaining(Map<UUID, Long> clock, UUID id, long now) {
        Long at = clock.get(id);
        return at == null ? 0L : Math.max(0L, at - now);
    }

    // ---- fall damage ---------------------------------------------------------------

    /** Neither the lunge nor the return is a fall the wielder chose to take. */
    @Override
    public boolean cancelsFallDamage(UUID id) {
        Long until = fallGraceUntil.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    // ---- lifecycle: never orphan an echo -------------------------------------------

    /**
     * Dying voids the record. Otherwise the echo outlives the wielder and a respawned player still
     * inside their window could Refraction themselves straight back onto whatever killed them.
     */
    @Override
    public void onPlayerDeath(PlayerDeathEvent event) {
        dropAnchor(event.getEntity().getUniqueId());
    }

    @Override
    public void onQuit(UUID id) {
        dropAnchor(id);
        lungeReadyAt.remove(id);
        refractionReadyAt.remove(id);
        fallGraceUntil.remove(id);
        readyPending.remove(id);
    }

    @Override
    public void onDisable() {
        for (Anchor a : anchors.values()) a.removeEcho();
        anchors.clear();
        for (ItemDisplay d : liveEchoes) {
            if (d != null && d.isValid()) d.remove();
        }
        liveEchoes.clear();
        lungeReadyAt.clear();
        refractionReadyAt.clear();
        fallGraceUntil.clear();
        readyPending.clear();
        sweepOrphans(); // belt-and-braces: reap any stray tagged echo anywhere in the world
    }

    /** Remove every echo display carrying our tag across all loaded worlds. */
    private void sweepOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) {
                if (e.getScoreboardTags().contains(ECHO_TAG)) e.remove();
            }
        }
    }

    @Override
    public List<String> outstandingReport() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Anchor> e : anchors.entrySet()) {
            Player p = plugin.getServer().getPlayer(e.getKey());
            String who = p != null ? p.getName() : e.getKey().toString().substring(0, 8);
            long secs = Math.max(0L, (e.getValue().expiresAt() - now + 999L) / 1000L);
            out.add("fragments_from_somewhere  echo held " + secs + "s  (" + who + ")");
        }
        return out;
    }
}
