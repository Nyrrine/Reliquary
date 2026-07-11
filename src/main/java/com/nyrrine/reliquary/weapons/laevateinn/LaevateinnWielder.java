package com.nyrrine.reliquary.weapons.laevateinn;

import com.nyrrine.reliquary.Reliquary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The tick (every 2 ticks per active wielder). Runs the live-Heat loop: Heat holds while
 * you fight and bleeds off once you fall out of combat, so the seals close a form at a
 * time. On a form change it repaints the blade and — going up — has the wielder "speak"
 * the unbox line in chat. A per-form attack-speed modifier keeps the vanilla swing bar in step
 * with the M1 combo pace (slow sealed → fast at True Form), and holding the blade grants a
 * double-jump. At True Form a fire-nova pulses, chipping nearby hostiles and the wielder's health.
 */
public final class LaevateinnWielder {

    private final Reliquary plugin;
    private final LaevateinnWeapon weapon;

    // ---- tuning --------------------------------------------------------------------
    private static final int DECAY_IN_HAND = 2;   // heat lost per tick out of combat, held (slower now)
    private static final int DECAY_SHEATHED = 8;   // faster cooling in the off-hand / not held

    /**
     * The sword is only swingable when its M1 combo is ready — while the combo cools, a big negative
     * attack-speed modifier freezes the swing (and the vanilla-melee guard blocks any raw hit), so the
     * blade can't be spam-clicked or used as a normal sword. True Form swings freely.
     */
    private static final double ATK_SWINGABLE = 6.0;  // ready / True Form: instant swing
    private static final double ATK_FROZEN = -1.5;    // combo cooling: near-frozen (unswingable)

    // True-Form pulse.
    private static final long PULSE_INTERVAL_MS = 2000L;
    private static final double PULSE_RADIUS = 4.0;
    private static final double PULSE_DAMAGE = 4.0;
    private static final int PULSE_MAX_TARGETS = 10;
    private static final double SELF_BURN_RAMP = 0.05;  // +HP per second past 1 min of True Form (ramps up)
    private static final double SELF_BURN_MAX = 8.0;    // cap
    private static final int MELTDOWN_DRAIN = 8;        // heat drained per tick at ≤50% HP — forces the seals shut
    private static final int PULSE_ANIM_TICKS = 8;

    // Unbox chat: tag + spoken line for reaching each form.
    private static final String[] SEAL_TAG = {"First Seal Broken", "Second Seal Broken", "Final Seal Broken"};
    private static final String[] SEAL_LINE = {
            "Gonna Unbox a Layer of Packaging!",
            "Looks Like I Gotta Unbox the Second Layer, too.",
            "Been a While Since I Had to Unbox the Whole Thing!"
    };

    private final Map<UUID, Integer> painted = new HashMap<>();   // last form painted onto the held item
    private final Set<UUID> grantedFlight = new HashSet<>();      // players we handed double-jump to
    private final Map<UUID, Double> atkVal = new HashMap<>();     // last attack-speed modifier value applied
    private boolean attrOk = true;                                 // attack-speed attribute available

    public LaevateinnWielder(Reliquary plugin, LaevateinnWeapon weapon) {
        this.plugin = plugin;
        this.weapon = weapon;
    }

    public boolean tick(Player player, long ticks) {
        UUID id = player.getUniqueId();
        boolean main = weapon.matches(player.getInventory().getItemInMainHand());

        manageFlight(player, id, main);

        if (!main) {
            // Off-hand or sheathed: the blade is inert and cools quickly; seals close silently.
            painted.remove(id);
            stripAtkSpeed(player);
            if (weapon.heatOf(id) > 0) weapon.setHeat(id, weapon.heatOf(id) - DECAY_SHEATHED);
            weapon.setForm(id, weapon.deriveForm(id));
            weapon.leaveTrueForm(id);
            return weapon.pulseActive(id) || weapon.comboBusy(id);
        }

        // Held in the main hand.
        boolean worthy = weapon.isWorthy(player.getInventory().getItemInMainHand());
        if (worthy) {
            weapon.setHeat(id, LaevateinnWeapon.HEAT_MAX);           // Worthy Lævateinn is pinned to True Form
        } else if (!weapon.inCombat(id) && weapon.heatOf(id) > 0) {
            weapon.setHeat(id, weapon.heatOf(id) - DECAY_IN_HAND);   // cool out of combat
        }

        updateForm(player, id);
        int form = weapon.formOf(id);
        if (!worthy) meltdownCheck(player, id, form, ticks);         // half health forces the seals shut
        applyAtkSpeed(player, form);

        if (player.getGameMode() != GameMode.SPECTATOR) {
            drawAura(player, form, ticks);
            if (form >= LaevateinnWeapon.MAX_FORM) maybePulse(player, id, worthy);
        }

        if (!weapon.comboBusy(id)) player.sendActionBar(heatBar(id, form));
        return true;
    }

    public void clear(UUID id) {
        painted.remove(id);
        Player p = plugin.getServer().getPlayer(id);
        if (p != null) stripAtkSpeed(p);
        if (grantedFlight.remove(id)) {
            if (p != null && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
                p.setAllowFlight(false);
                p.setFlying(false);
            }
        }
    }

    // ---- attack-speed: swingable only while the M1 combo is ready -------------------

    private void applyAtkSpeed(Player player, int form) {
        if (!attrOk) return;
        UUID id = player.getUniqueId();
        // Swingable only when the M1 is ready — sealed combo cooldown, or True Form's short slash cooldown
        // (fast but not spammable). Frozen while it cools.
        boolean swingable = System.currentTimeMillis() >= weapon.m1ReadyAt(id);
        double desired = swingable ? ATK_SWINGABLE : ATK_FROZEN;
        Double cur = atkVal.get(id);
        if (cur != null && cur == desired) return; // already set
        try {
            AttributeInstance inst = player.getAttribute(Attribute.ATTACK_SPEED);
            if (inst == null) return;
            removeAtkMod(inst);
            inst.addModifier(new AttributeModifier(weapon.atkSpeedKey(), desired,
                    AttributeModifier.Operation.ADD_NUMBER));
            atkVal.put(id, desired);
        } catch (Throwable t) {
            attrOk = false; // mapping surprise — stop touching attributes rather than spam errors
        }
    }

    private void stripAtkSpeed(Player player) {
        atkVal.remove(player.getUniqueId());
        if (!attrOk) return;
        try {
            AttributeInstance inst = player.getAttribute(Attribute.ATTACK_SPEED);
            if (inst != null) removeAtkMod(inst);
        } catch (Throwable t) {
            attrOk = false;
        }
    }

    private void removeAtkMod(AttributeInstance inst) {
        for (AttributeModifier m : inst.getModifiers()) {
            if (weapon.atkSpeedKey().equals(m.getKey())) inst.removeModifier(m);
        }
    }

    // ---- form transitions ----------------------------------------------------------

    private void updateForm(Player player, UUID id) {
        int derived = weapon.deriveForm(id);
        weapon.setForm(id, derived);
        if (derived >= LaevateinnWeapon.MAX_FORM) weapon.enterTrueForm(id); else weapon.leaveTrueForm(id);
        Integer prev = painted.get(id);
        if (prev != null && prev == derived) return;

        if (prev != null && derived > prev) unsealCue(player, derived);
        else if (prev != null && derived < prev) resealCue(player, derived);
        repaint(player, derived);
        painted.put(id, derived);
    }

    private void repaint(Player player, int form) {
        ItemStack held = player.getInventory().getItemInMainHand();
        ItemMeta meta = held.getItemMeta();
        if (meta == null) return;
        meta.displayName(weapon.formName(form));
        meta.lore(weapon.formLore(form));
        // Fresh slate: the old True-Form Blockbench model was removed, so wear no custom model data. This
        // also scrubs any residual "laev_true" tag off items forged before the model was pulled. Re-add a
        // case here once a new texture is embedded.
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(java.util.List.of());
        meta.setCustomModelDataComponent(cmd);
        held.setItemMeta(meta);
        player.getInventory().setItemInMainHand(held);
    }

    /** The wielder "speaks" the unbox line in server chat, plus a fire + twinkle burst. */
    private void unsealCue(Player player, int form) {
        int idx = Math.max(0, Math.min(SEAL_TAG.length - 1, form - 1));
        // Sealed breaks read purple/white; only the final seal (True Form) burns orange.
        boolean finalSeal = form >= LaevateinnWeapon.MAX_FORM;
        TextColor tagColor  = finalSeal ? TextColor.color(0xFF7A18) : TextColor.color(0x9A3CE0);
        TextColor lineColor = finalSeal ? TextColor.color(0xFFB050) : NamedTextColor.WHITE;
        Component msg = Component.text(player.getName() + "  ", NamedTextColor.WHITE)
                .append(Component.text("[" + SEAL_TAG[idx] + "]  ", tagColor))
                .append(Component.text(SEAL_LINE[idx], lineColor));
        // Heard by nearby players (~100 blocks), not the whole server — shouted in the moment.
        for (Player near : player.getWorld().getNearbyPlayers(player.getLocation(), 100)) {
            near.sendMessage(msg);
        }

        World world = player.getWorld();
        Location o = player.getLocation().add(0, 1.0, 0);
        // Steel peeling off the blade as a seal gives way — netherite shards break loose.
        world.spawnParticle(Particle.ITEM, o, 18 + 8 * form, 0.4, 0.6, 0.4, 0.14,
                new ItemStack(Material.NETHERITE_INGOT));
        if (form >= LaevateinnWeapon.MAX_FORM) {
            // Reaching True Form: the fiery break (orange is reserved for the final form).
            world.playSound(o, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.9f);
            world.playSound(o, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.9f);
            world.playSound(o, Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.4f);
            world.spawnParticle(Particle.FLAME, o, 60, 0.5, 0.9, 0.5, 0.06);
            world.spawnParticle(Particle.LAVA, o, 18, 0.4, 0.6, 0.4, 0);
        } else {
            // A sealed break: a purple implosion — muga-smoke and purple energy collapsing inward.
            world.playSound(o, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 0.6f + 0.15f * form);
            world.playSound(o, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.9f, 0.8f);
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int i = 0; i < 24; i++) {
                double ang = rng.nextDouble(0, Math.PI * 2);
                double r = 1.6 + rng.nextDouble() * 0.8;
                Location p = o.clone().add(Math.cos(ang) * r, rng.nextDouble() * 1.0 - 0.3, Math.sin(ang) * r);
                org.bukkit.util.Vector in = o.toVector().subtract(p.toVector()).normalize().multiply(0.35);
                world.spawnParticle(Particle.SMOKE, p, 0, in.getX(), in.getY(), in.getZ(), 0.25);
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(LaevateinnVfx.PURPLE, 1.4f));
            }
        }
        LaevateinnVfx.twinkle(world, o, 8);
    }

    /** Cooling back down a form: a quiet, wielder-only note + a puff of smoke. */
    private void resealCue(Player player, int form) {
        World world = player.getWorld();
        Location o = player.getLocation().add(0, 1.0, 0);
        world.spawnParticle(Particle.SMOKE, o, 10, 0.3, 0.5, 0.3, 0.02);
        world.playSound(o, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 0.9f);
        player.sendActionBar(Component.text("The packaging closes — a seal reforms.",
                TextColor.color(0x8C8A93)));
    }

    // ---- double-jump flight grant --------------------------------------------------

    private void manageFlight(Player player, UUID id, boolean holding) {
        GameMode gm = player.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return; // don't touch legit flight
        // The double-jump is available whenever the blade is held and the Ground Slam is off cooldown
        // (a leap starts that cooldown, so you can't leap again until it clears).
        boolean want = holding && !weapon.comboBusy(id)
                && System.currentTimeMillis() >= weapon.slamReadyAt(id);
        if (want) {
            if (!player.getAllowFlight()) player.setAllowFlight(true);
            grantedFlight.add(id);
        } else if (grantedFlight.remove(id)) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
    }

    // ---- per-form aura -------------------------------------------------------------

    /** Sealed forms grow a black-hole aura sucking purple inward; the true form burns orange. */
    private void drawAura(Player player, int form, long ticks) {
        World world = player.getWorld();
        Location feet = player.getLocation();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (form < LaevateinnWeapon.MAX_FORM) {
            // A purple black-hole swirl: spiral arms of purple curling into the wielder, plus low
            // muga-style smoke sucked inward. Subtle at form 0, biggest at the final seal. No dark
            // core, no portal — and kept low at the feet, not the torso.
            Location core = feet.clone().add(0, 0.15, 0);
            double maxR = 0.9 + 0.7 * form;   // reach grows per seal
            int arms = 2 + form;              // 2..4 spiral arms
            int perArm = 3 + form;            // points down each arm
            double spin = ticks * 0.5;        // rotation
            for (int a = 0; a < arms; a++) {
                double armBase = spin + a * (Math.PI * 2 / arms);
                for (int k = 0; k < perArm; k++) {
                    double frac = (k + 1) / (double) (perArm + 1);  // 1 = outer, →0 = into the centre
                    double radius = maxR * frac;
                    double angle = armBase + frac * 2.4;            // twist → a spiral that curls inward
                    Location p = core.clone().add(Math.cos(angle) * radius, 0.06 * frac, Math.sin(angle) * radius);
                    float size = 1.3f - 0.4f * (float) frac;        // fatter as it converges
                    world.spawnParticle(Particle.DUST, p, 1, 0.01, 0.01, 0.01, 0,
                            new Particle.DustOptions(k == 0 ? LaevateinnVfx.PURPLE_DEEP : LaevateinnVfx.PURPLE, size));
                }
            }
            // Low muga-smoke wisps streaming inward (the "suck"), kept low so they die before the face.
            int wisps = 1 + form;
            for (int i = 0; i < wisps; i++) {
                double ang = rng.nextDouble(0, Math.PI * 2);
                double r = maxR * (0.7 + rng.nextDouble() * 0.4);
                Location p = feet.clone().add(Math.cos(ang) * r, 0.05 + rng.nextDouble() * 0.25, Math.sin(ang) * r);
                org.bukkit.util.Vector in = feet.clone().add(0, 0.15, 0).toVector().subtract(p.toVector())
                        .normalize().multiply(0.15 + 0.04 * form);
                world.spawnParticle(Particle.SMOKE, p, 0, in.getX(), in.getY(), in.getZ(), 0.06 + 0.02 * form);
            }
        } else {
            // True Form: keep it at the FEET only so it never blocks the view — a hot orange ground ring,
            // low flame, and a hint of the muga purple/smoke drawn in. No body swirl, no white END_ROD.
            if (ticks % 2 == 0) LaevateinnVfx.ring(world, feet.clone().add(0, 0.08, 0), 1.0,
                    new Particle.DustOptions(LaevateinnVfx.ORANGE, 1.2f), 12);
            world.spawnParticle(Particle.SMALL_FLAME, feet.clone().add(0, 0.05, 0), 3, 0.7, 0.08, 0.7, 0.004);
            world.spawnParticle(Particle.FLAME, feet.clone().add(0, 0.05, 0), 1, 0.6, 0.06, 0.6, 0.006);
            double ang = rng.nextDouble(0, Math.PI * 2);
            double r = 0.6 + rng.nextDouble() * 0.5;
            Location p = feet.clone().add(Math.cos(ang) * r, 0.05, Math.sin(ang) * r);
            org.bukkit.util.Vector in = feet.clone().add(0, 0.1, 0).toVector().subtract(p.toVector()).normalize().multiply(0.1);
            world.spawnParticle(Particle.SMOKE, p, 0, in.getX(), in.getY(), in.getZ(), 0.04);
            if (ticks % 3 == 0) world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0,
                    new Particle.DustOptions(LaevateinnVfx.PURPLE, 0.9f));
        }
    }

    // ---- True-Form fire-nova pulse -------------------------------------------------

    /** Half health: the blade forces its seals shut one by one and locks up — unswingable. */
    private void meltdownCheck(Player player, UUID id, int form, long ticks) {
        if (form < 1 || player.getHealth() > player.getMaxHealth() * 0.5) return;
        weapon.setHeat(id, weapon.heatOf(id) - MELTDOWN_DRAIN);        // drain fast → forms step down one by one
        weapon.setM1ReadyAt(id, System.currentTimeMillis() + 1000L);  // stay frozen while melting down
        if (ticks % 4 == 0) {
            player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.02);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_CHAIN_HIT, 0.5f, 0.7f);
        }
    }

    private void maybePulse(Player player, UUID id, boolean worthy) {
        long now = System.currentTimeMillis();
        if (now - weapon.lastPulseAt(id) < PULSE_INTERVAL_MS) return;
        weapon.setLastPulseAt(id, now);
        weapon.markPulseActive(id, PULSE_ANIM_TICKS * 50L + 100L);

        World world = player.getWorld();
        Location center = player.getLocation().add(0, 1.0, 0);

        int hit = 0;
        for (var e : world.getNearbyEntities(center, PULSE_RADIUS, PULSE_RADIUS, PULSE_RADIUS)) {
            if (hit >= PULSE_MAX_TARGETS) break;
            if (!(e instanceof Monster) || !(e instanceof LivingEntity target)) continue;
            if (!weapon.canHarm(player, target)) continue;
            weapon.dealDamage(target, PULSE_DAMAGE, player);
            target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0, 1, 0),
                    3, 0.2, 0.3, 0.2, 0.01);
            hit++;
        }

        // The cost: a self-burn, direct HP. Nothing for the first minute; after that it ramps up so you
        // can't hold full Lævateinn out forever. Worthy Lævateinn never burns.
        if (!worthy) {
            double secs = weapon.trueFormSeconds(id);
            if (secs > 60) {
                double burn = Math.min(SELF_BURN_MAX, (secs - 60) * SELF_BURN_RAMP);
                player.setHealth(Math.max(0.0, player.getHealth() - burn));
            }
        }
        world.spawnParticle(Particle.FLAME, player.getLocation().add(0, 1.0, 0), 4, 0.25, 0.4, 0.25, 0.02);

        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t > PULSE_ANIM_TICKS || !player.isOnline()) { cancel(); return; }
                double radius = PULSE_RADIUS * ((double) t / PULSE_ANIM_TICKS);
                Location c = player.getLocation().add(0, 0.3, 0);
                for (int i = 0; i < 8; i++) {
                    double a = (Math.PI * 2 * i) / 8;
                    world.spawnParticle(Particle.FLAME, c.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius),
                            1, 0, 0, 0, 0.005);
                }
                t += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ---- action-bar Heat gauge -----------------------------------------------------

    private static final String[] FORM_LABEL = {"Sealed", "First Seal", "Second Seal", "TRUE FORM"};

    private Component heatBar(UUID id, int form) {
        int heat = weapon.heatOf(id);
        double frac;
        if (form >= LaevateinnWeapon.MAX_FORM) {
            frac = 1.0;
        } else {
            int lo = weapon.heatForForm(form);
            int hi = weapon.heatForForm(form + 1);
            frac = hi > lo ? (double) (heat - lo) / (hi - lo) : 1.0;
        }
        frac = Math.max(0.0, Math.min(1.0, frac));

        int seg = 10;
        int filled = (int) Math.round(frac * seg);
        TextColor fill = LaevateinnWeapon.tierColor(form);

        Component bar = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("▮".repeat(filled), fill))
                .append(Component.text("▮".repeat(seg - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text("]  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(FORM_LABEL[form], fill));

        // Cooling hint when out of combat.
        if (form > 0 && !weapon.inCombat(id)) {
            bar = bar.append(Component.text("  cooling", NamedTextColor.GRAY));
        }

        // Ability cooldowns, folded into the one bar alongside the unseal gauge.
        TextColor purple = LaevateinnVfx.toText(LaevateinnVfx.PURPLE);
        bar = bar.append(cdReadout("Slam", weapon.slamReadyAt(id), purple));
        bar = bar.append(cdReadout("Gut", weapon.gutstabReadyAt(id), purple));
        if (form >= LaevateinnWeapon.MAX_FORM) {
            bar = bar.append(cdReadout("Exterm", weapon.ultReadyAt(id), TextColor.color(0xFF7A18)));
        }
        return bar;
    }

    /** A compact "  Name ✦" (ready) or "  Name 3s" (cooling) readout for the action bar. */
    private Component cdReadout(String label, long readyAt, TextColor readyColor) {
        long now = System.currentTimeMillis();
        if (now >= readyAt) return Component.text("  " + label + " ✦", readyColor);
        long secs = (readyAt - now) / 1000 + 1;
        return Component.text("  " + label + " " + secs + "s", NamedTextColor.DARK_GRAY);
    }
}
