package com.nyrrine.reliquary.extraction;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Carmen's Brain as a deployed <b>floating entity</b> (no placed block): a brain-skinned {@link ItemDisplay}
 * that bobs and slowly rotates with a green glow, wrapped in an {@link Interaction} hitbox so players can
 * punch it (to collect the item back) or right-click it with an Extraction Ticket (to pull a weapon). A light
 * green ambiance breathes around it. This is the stripped-down version — Nyrrine designs the full show later;
 * the old spine and nervous system are gone.
 *
 * <p>Deployed Brains are tracked by location in {@link Stations} (so they survive a restart — the item was
 * consumed on deploy and is only returned by collecting), and the display entities are {@code reliquary}-
 * tagged + non-persistent, re-grown lazily by this manager when a player is near a loaded chunk and reaped
 * when unwatched, on collect, or on disable (backed by a tag sweep). A future gacha pull can suspend the idle
 * via {@link Node#interrupted}.
 */
public final class CarmenBrainVfx {

    /** Scoreboard tag on every entity this show spawns — the belt-and-braces orphan-reap key. */
    public static final String TAG = "reliquary_carmen_brain";

    private static final double WATCH_RANGE     = 16.0;  // a player within this (blocks) keeps a Brain alive
    private static final long   PERIOD          = 2L;    // ticks between frames / punch polls
    private static final double HOVER           = 0.8;   // brain height above the deploy coord
    private static final float  BRAIN_SCALE     = 1.0f;
    private static final double BOB_AMP         = 0.12;
    private static final double BOB_RATE        = 0.12;
    private static final double YAW_RATE        = 0.05;
    private static final float  HITBOX_SIZE     = 1.0f;
    private static final int    PUNCH_TO_COLLECT = 4;    // hits to knock the Brain loose and drop the item

    private static final Color GREEN      = Color.fromRGB(0x5B, 0xE8, 0x7A);
    private static final Color GREEN_SOFT = Color.fromRGB(0x8F, 0xF0, 0xA8);
    private static final Particle.DustOptions AMBIENT_DUST = new Particle.DustOptions(GREEN_SOFT, 0.7f);

    private final Reliquary plugin;
    private final Stations stations;
    private final Map<String, Node> live = new HashMap<>();       // keyed by deploy-location string
    private final Map<UUID, Node> byInteraction = new HashMap<>(); // hitbox UUID -> its Node
    private BukkitRunnable task;

    public CarmenBrainVfx(Reliquary plugin, Stations stations) {
        this.plugin = plugin;
        this.stations = stations;
    }

    /** Start the single idle-driver task. */
    public void start() {
        task = new BukkitRunnable() {
            @Override public void run() { tick(); }
        };
        task.runTaskTimer(plugin, 20L, PERIOD);
    }

    private void tick() {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        List<Node> collect = new ArrayList<>();
        for (Location well : stations.wells()) {
            World w = well.getWorld();
            if (w == null || !w.isChunkLoaded(well.getBlockX() >> 4, well.getBlockZ() >> 4)) continue;
            if (!playerNear(well)) continue;
            String k = key(well);
            wanted.add(k);
            Node node = ensure(k, well);
            node.animate();
            if (node.pollPunch()) collect.add(node);
        }
        // Despawn shows that are no longer wanted (unwatched, unloaded, or the Brain was removed).
        live.entrySet().removeIf(e -> {
            if (wanted.contains(e.getKey())) return false;
            drop(e.getValue());
            return true;
        });
        // Collect (punched-out) Brains after the walk, so we never mutate the maps mid-iteration.
        for (Node n : collect) collect(n);
    }

    private Node ensure(String k, Location well) {
        Node node = live.get(k);
        if (node != null && node.valid()) return node;
        if (node != null) drop(node); // stale/invalid — clear it before re-growing
        node = new Node(well);
        live.put(k, node);
        byInteraction.put(node.hitbox.getUniqueId(), node);
        return node;
    }

    /** Just untrack + remove the entities (no item drop) — used when a Brain leaves the watched set. */
    private void drop(Node node) {
        byInteraction.remove(node.hitbox.getUniqueId());
        node.reap();
    }

    private boolean playerNear(Location well) {
        World w = well.getWorld();
        Location c = well.clone().add(0.5, 0.5, 0.5);
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(c) <= WATCH_RANGE * WATCH_RANGE) return true;
        }
        return false;
    }

    /**
     * Deploy a Carmen's Brain at {@code where} for {@code player}: register the location, grow the floating
     * entity set, and consume one item from the main hand. Refuses (and returns false) if a Brain is already
     * tracked at that coord.
     */
    public boolean deploy(Player player, Location where) {
        String k = key(where);
        if (live.containsKey(k)) return false;
        for (Location w : stations.wells()) if (key(w).equals(k)) return false;

        stations.register(where.getBlock(), StationType.WELL);
        Node node = new Node(where);
        live.put(k, node);
        byInteraction.put(node.hitbox.getUniqueId(), node);

        ItemStack held = player.getInventory().getItemInMainHand();
        held.setAmount(held.getAmount() - 1); // becomes AIR at 0
        node.world.playSound(node.brainAt, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.5f);
        return true;
    }

    /** The deploy location of the Brain whose hitbox is {@code interaction}, or null — for routing right-clicks. */
    public Location locationOf(Entity interaction) {
        Node node = byInteraction.get(interaction.getUniqueId());
        return node != null ? node.well.clone() : null;
    }

    private void collect(Node node) {
        node.world.playSound(node.brainAt, Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.2f);
        node.world.spawnParticle(Particle.DUST, node.brainAt, 20, 0.3, 0.3, 0.3, 0, AMBIENT_DUST);
        node.world.dropItemNaturally(node.brainAt, StationType.WELL.createItem()); // the movable Brain, back
        stations.unregister(node.well.getBlock());
        live.remove(key(node.well));
        byInteraction.remove(node.hitbox.getUniqueId());
        node.reap();
    }

    /** Stop the driver, reap every live show, and sweep any tagged orphan across worlds. */
    public void disable() {
        if (task != null) { task.cancel(); task = null; }
        for (Node n : live.values()) n.reap();
        live.clear();
        byInteraction.clear();
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class))  if (tagged(e)) e.remove();
            for (Entity e : w.getEntitiesByClass(BlockDisplay.class)) if (tagged(e)) e.remove();
            for (Entity e : w.getEntitiesByClass(Interaction.class))  if (tagged(e)) e.remove();
        }
    }

    private static boolean tagged(Entity e) { return e.getScoreboardTags().contains(TAG); }

    private static String key(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    // ---- one Brain's live entities ------------------------------------------------

    private final class Node {
        private final World world;
        private final Location well;     // the tracked deploy coord (block corner)
        private final Location brainAt;  // hover centre the brain floats around
        private final ItemDisplay brain;
        private final Interaction hitbox;
        private int frame = 0;
        private int punches = 0;
        private long lastAttackTs = 0L;
        boolean interrupted = false;     // the seam a future gacha pull sets to suspend the idle

        Node(Location where) {
            this.world = where.getWorld();
            this.well = where.clone();
            this.brainAt = where.clone().add(0.5, HOVER, 0.5);

            this.brain = world.spawn(brainAt, ItemDisplay.class, d -> {
                d.setItemStack(StationType.brainHead());
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setBillboard(Display.Billboard.FIXED); // true 3D — never billboarded flat
                d.setBrightness(new Display.Brightness(15, 15));
                d.setGlowColorOverride(GREEN);
                d.setGlowing(true);
                d.setInterpolationDuration((int) PERIOD);
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
            });

            // The hitbox: centred on the brain so a punch/right-click lands on the floating head.
            Location hb = brainAt.clone().subtract(0, HITBOX_SIZE / 2.0, 0);
            this.hitbox = world.spawn(hb, Interaction.class, i -> {
                i.setInteractionWidth(HITBOX_SIZE);
                i.setInteractionHeight(HITBOX_SIZE);
                i.setResponsive(true);
                i.setPersistent(false);
                i.addScoreboardTag(TAG);
            });
        }

        boolean valid() { return brain.isValid() && hitbox.isValid(); }

        void animate() {
            if (interrupted) return; // a future gacha pull owns the display while this is set
            frame++;
            floatBrain();
            ambiance();
        }

        /** Bob on a slow sine + rotate slowly on yaw, interpolated between frames. */
        private void floatBrain() {
            if (!brain.isValid()) return;
            double bob = Math.sin(frame * BOB_RATE) * BOB_AMP;
            float yaw = (float) (frame * YAW_RATE);
            float s = BRAIN_SCALE;
            brain.setInterpolationDelay(0);
            brain.setTransformation(new Transformation(
                    new Vector3f(0f, (float) bob, 0f), new Quaternionf().rotateY(yaw),
                    new Vector3f(s, s, s), new Quaternionf()));
        }

        /** A light green haze breathing around the brain — the "alive" ambiance. */
        private void ambiance() {
            if (frame % 3 != 0) return;
            double bob = Math.sin(frame * BOB_RATE) * BOB_AMP;
            world.spawnParticle(Particle.DUST, brainAt.clone().add(0, bob, 0),
                    2, 0.35, 0.3, 0.35, 0, AMBIENT_DUST);
        }

        /** Poll the hitbox for a new punch; returns true once enough have landed to knock it loose. */
        boolean pollPunch() {
            Interaction.PreviousInteraction atk = hitbox.getLastAttack();
            if (atk == null || atk.getTimestamp() <= lastAttackTs) return false;
            lastAttackTs = atk.getTimestamp();
            punches++;
            world.spawnParticle(Particle.DUST, brainAt, 6, 0.2, 0.2, 0.2, 0, AMBIENT_DUST);
            world.playSound(brainAt, Sound.ENTITY_SLIME_SQUISH, 0.6f, 1.2f);
            return punches >= PUNCH_TO_COLLECT;
        }

        void reap() {
            if (brain.isValid()) brain.remove();
            if (hitbox.isValid()) hitbox.remove();
        }
    }
}
