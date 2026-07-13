package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The Pocket Well preview as a living hologram (§35 — "make it feel better visually"). Right-clicking the Well
 * spins up a short-lived carousel: the most-likely weapon hovers and rotates at the centre, the other reachable
 * candidates orbit it as small models, each tagged with its live pull odds, over a spinning ring of light — so
 * a player <i>reads their odds at a glance</i> and is pulled to craft a cleaner sin match to tighten them.
 *
 * <p>All entities are non-persistent and self-remove after {@link #DURATION_TICKS}; a second right-click just
 * refreshes the carousel (the caller cancels the previous one).
 */
public final class WellDisplay {

    /** How long a preview lingers before it dissolves (ticks). */
    public static final int DURATION_TICKS = 220;
    private static final double ORBIT_RADIUS = 1.15;
    private static final double RING_RADIUS = 1.35;
    private static final int MAX_ORBITERS = 6;
    private static final float CENTER_SCALE = 0.85f;
    private static final float ORBIT_SCALE = 0.42f;

    private final Plugin plugin;
    private BukkitRunnable active;
    private final List<Display> current = new ArrayList<>(); // this carousel's entities, so stop() can reap them

    public WellDisplay(Plugin plugin) { this.plugin = plugin; }

    /** Stop any running carousel (call before starting a new one, and on plugin disable): cancel + reap. */
    public void stop() {
        if (active != null) { active.cancel(); active = null; }
        reap();
    }

    private void reap() {
        for (Display d : current) if (d.isValid()) d.remove();
        current.clear();
    }

    /**
     * Spin up the carousel above {@code wellLoc} for the given ranked pool (best first). Empty pool → a small
     * "nothing reachable" puff instead of a carousel. {@code itemFor} resolves a weapon spec to the display
     * item (the caller wires it to the plugin's weapon registry); a null result falls back to a Nether Star.
     */
    public void reveal(Location wellLoc, List<WellRoll.Chance> pool,
                       java.util.function.Function<WeaponSpec, ItemStack> itemFor) {
        stop();
        var world = wellLoc.getWorld();
        Location center = wellLoc.clone().add(0.5, 1.55, 0.5);

        if (pool.isEmpty()) {
            world.spawnParticle(Particle.SMOKE, center, 20, 0.25, 0.25, 0.25, 0.01);
            world.playSound(center, Sound.BLOCK_CONDUIT_DEACTIVATE, 0.6f, 0.8f);
            return;
        }

        world.playSound(center, Sound.BLOCK_CONDUIT_ACTIVATE, 0.7f, 1.1f);
        world.playSound(center, Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1.4f);

        // Centre: the top pick, larger, spinning on its own axis.
        WellRoll.Chance top = pool.get(0);
        List<Display> spawned = current; // reuse the field so stop()/reap() cleans this set on cancel or re-reveal
        ItemDisplay hub = spawnItem(center, resolve(top.weapon(), itemFor), CENTER_SCALE);
        spawned.add(hub);
        TextDisplay hubLabel = spawnLabel(center.clone().add(0, 0.55, 0),
                labelFor(top, true));
        spawned.add(hubLabel);

        // Orbiters: the next candidates ringing the hub, each tagged with its odds.
        int count = Math.min(pool.size() - 1, MAX_ORBITERS);
        List<Orbiter> orbiters = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WellRoll.Chance c = pool.get(i + 1);
            ItemDisplay body = spawnItem(center, resolve(c.weapon(), itemFor), ORBIT_SCALE);
            TextDisplay tag = spawnLabel(center, labelFor(c, false));
            spawned.add(body);
            spawned.add(tag);
            double phase = (2 * Math.PI * i) / Math.max(1, count);
            orbiters.add(new Orbiter(body, tag, phase));
        }

        active = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= DURATION_TICKS || !hub.isValid()) {
                    reap();
                    cancel();
                    if (active == this) active = null;
                    return;
                }
                double time = t / 20.0;
                // Hub: slow self-spin + a gentle bob.
                float hubYaw = (float) (time * Math.toRadians(70));
                setTransform(hub, new Vector3f(0, (float) (Math.sin(time * 1.3) * 0.05), 0),
                        yaw(hubYaw), CENTER_SCALE);

                // Orbiters sweep around the hub; labels ride just above each.
                double orbitAng = time * Math.toRadians(48); // ring revolution
                for (Orbiter o : orbiters) {
                    double a = o.phase + orbitAng;
                    float ox = (float) (Math.cos(a) * ORBIT_RADIUS);
                    float oz = (float) (Math.sin(a) * ORBIT_RADIUS);
                    float oy = (float) (Math.sin(time * 1.6 + o.phase) * 0.12);
                    setTransform(o.body, new Vector3f(ox, oy, oz), yaw((float) -a), ORBIT_SCALE);
                    // label a touch higher than its body
                    o.label.setInterpolationDelay(0);
                    o.label.setInterpolationDuration(2);
                    o.label.setTransformation(new Transformation(
                            new Vector3f(ox, oy + 0.42f, oz), new Quaternionf(),
                            new Vector3f(1f), new Quaternionf()));
                }

                // The spinning ring of light under the carousel.
                drawRing(center, time);
                t++;
            }
        };
        active.runTaskTimer(plugin, 0L, 1L);
    }

    // ---- helpers ------------------------------------------------------------------

    private record Orbiter(ItemDisplay body, TextDisplay label, double phase) {}

    private ItemStack resolve(WeaponSpec spec, java.util.function.Function<WeaponSpec, ItemStack> itemFor) {
        ItemStack it = itemFor.apply(spec);
        return it != null ? it : new ItemStack(org.bukkit.Material.NETHER_STAR);
    }

    private ItemDisplay spawnItem(Location at, ItemStack stack, float scale) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setInterpolationDuration(2);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale), new Quaternionf()));
        });
    }

    private TextDisplay spawnLabel(Location at, Component text) {
        return at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setSeeThrough(true);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setBackgroundColor(org.bukkit.Color.fromARGB(120, 8, 10, 18));
            d.setInterpolationDuration(2);
        });
    }

    private void setTransform(Display d, Vector3f translation, Quaternionf rot, float scale) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(new Transformation(translation, rot, new Vector3f(scale), new Quaternionf()));
    }

    private Quaternionf yaw(float radians) {
        return new Quaternionf(new AxisAngle4f(radians, 0f, 1f, 0f));
    }

    private void drawRing(Location center, double time) {
        var world = center.getWorld();
        int points = 16;
        for (int i = 0; i < points; i++) {
            double a = (2 * Math.PI * i) / points + time * Math.toRadians(90);
            double x = Math.cos(a) * RING_RADIUS;
            double z = Math.sin(a) * RING_RADIUS;
            Location p = center.clone().add(x, -0.35, z);
            world.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0);
        }
    }

    private Component labelFor(WellRoll.Chance c, boolean top) {
        int pct = (int) Math.round(c.odds() * 100);
        TextColor col = top ? TextColor.color(0x9BE7C4) : TextColor.color(0xBFC5D0);
        return Component.text(c.weapon().display() + "  ").color(col)
                .append(Component.text(pct + "%").color(TextColor.color(top ? 0xFFF1A8 : 0x8FE6DA)));
    }
}
