package com.nyrrine.reliquary.weapons.arayashiki;

import com.nyrrine.reliquary.Reliquary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Arayashiki's heart-strip: each dash (RC) and each storm-tick (shift-RC) that lands on a body rolls a
 * {@link #CHANCE_DENOM}-in-one chance to erase one of its hearts — a temporary {@code -2} max-HP
 * {@link Attribute#MAX_HEALTH} modifier, carried as one running modifier per victim so it restores to
 * exactly what it found. The erased hearts come back the moment the body dies or drops out of combat with
 * the blade (see {@link HeartLedger#COMBAT_TIMEOUT_MS}). A one-heart floor keeps the erase a wound, never
 * the kill; the kill is the blade's own act.
 *
 * <p>The decision half lives in {@link HeartLedger} (server-free, tested); this class is the Bukkit hands:
 * it reads the live max-health, applies and removes the modifier, shows the strike, and runs the combat
 * sweeper. Every path that could leak a reduced max — quit, death, join, plugin disable — routes back here
 * to restore, so no body is ever left short a heart.
 */
public final class ArayashikiHeartStrip {

    private final Reliquary plugin;
    private final ArayashikiWeapon weapon;

    /** Keys the single running max-health modifier so it can be found and removed exactly. */
    private final NamespacedKey stripKey;

    /** One in this many landed strikes erases a heart (Nyrrine, playtest §5.3). */
    private static final int CHANCE_DENOM = 5;

    private final HeartLedger ledger = new HeartLedger();
    /** Live handles for the bodies we've stripped, so combat-lapse and shutdown can reach them to restore. */
    private final Map<UUID, LivingEntity> bodies = new HashMap<>();
    private BukkitTask sweeper;

    private static final Particle.DustOptions CRIMSON =
            new Particle.DustOptions(Color.fromRGB(194, 32, 53), 1.1f);

    public ArayashikiHeartStrip(Reliquary plugin, ArayashikiWeapon weapon) {
        this.plugin = plugin;
        this.weapon = weapon;
        this.stripKey = new NamespacedKey(plugin, "arayashiki_heartstrip");
    }

    /** A landed dash/storm strike on a body: keep its combat clock warm, then roll to erase a heart. */
    public void roll(LivingEntity victim, Player attacker) {
        UUID id = victim.getUniqueId();
        long now = System.currentTimeMillis();
        ledger.touch(id, now);                                   // still being cut -> still in combat
        if (ThreadLocalRandom.current().nextInt(CHANCE_DENOM) != 0) return;
        doStrip(victim, attacker, now);
    }

    private void doStrip(LivingEntity victim, Player attacker, long now) {
        AttributeInstance inst = victim.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;

        double amount = ledger.strip(victim.getUniqueId(), inst.getValue(), now);
        if (Double.isNaN(amount)) return;                        // at the one-heart floor — nothing to take

        removeMyModifiers(inst);
        inst.addModifier(new AttributeModifier(stripKey, amount, AttributeModifier.Operation.ADD_NUMBER));

        // Pull current health down to the new ceiling so the lost heart is actually gone, not just capped later.
        double max = inst.getValue();
        if (victim.getHealth() > max) victim.setHealth(Math.max(0.0, max));

        bodies.put(victim.getUniqueId(), victim);
        startSweeper();
        indicate(victim, attacker, ledger.count(victim.getUniqueId()));
    }

    /** The visible cost: a crimson burst on the body and a "-1 ❤ erased" readout to whoever cut it. */
    private void indicate(LivingEntity victim, Player attacker, int total) {
        World world = victim.getWorld();
        Location chest = victim.getLocation().add(0, Math.max(0.6, victim.getHeight()) * 0.5, 0);
        world.spawnParticle(Particle.DUST, chest, 18, 0.3, 0.4, 0.3, 0, CRIMSON);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, chest, 6, 0.25, 0.3, 0.25, 0.02);
        world.spawnParticle(Particle.END_ROD, chest, 4, 0.2, 0.3, 0.2, 0.01);
        world.playSound(victim.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.9f, 0.7f);

        if (attacker == null) return;
        Component bar = Component.text("-1 ", NamedTextColor.RED)
                .append(Component.text("❤", TextColor.color(0xC22035)))
                .append(Component.text(" erased", NamedTextColor.GRAY));
        if (total > 1) bar = bar.append(Component.text("  (" + total + ")", NamedTextColor.DARK_GRAY));
        weapon.muteActionBar(attacker.getUniqueId(), 1500); // hold it past the wielder tick's charge bar
        attacker.sendActionBar(bar);
    }

    // ---- restore paths -------------------------------------------------------------

    /** Return one body's erased hearts (a death, a quit). No-op if it wasn't stripped. */
    public void restore(UUID id) {
        if (!ledger.forget(id)) return;
        clearModifier(bodies.remove(id));
        stopSweeperIfIdle();
    }

    /** Defensive on login: shed any erase left saved on a player by an unclean shutdown. */
    public void onJoin(Player player) {
        clearModifier(player);
        ledger.forget(player.getUniqueId());
        bodies.remove(player.getUniqueId());
        stopSweeperIfIdle();
    }

    /** Plugin shutdown: return every outstanding erase so no body reloads short a heart. */
    public void restoreAll() {
        for (UUID id : ledger.drainAll()) clearModifier(bodies.remove(id));
        bodies.clear();
        if (sweeper != null) { sweeper.cancel(); sweeper = null; }
    }

    // ---- combat sweeper ------------------------------------------------------------

    private void startSweeper() {
        if (sweeper != null) return;
        sweeper = plugin.getServer().getScheduler().runTaskTimer(
                plugin, () -> sweep(System.currentTimeMillis()), 20L, 20L);
    }

    private void sweep(long now) {
        // A body that has left the world can't be restored and needn't be — drop it.
        for (UUID id : new ArrayList<>(bodies.keySet())) {
            LivingEntity b = bodies.get(id);
            if (b == null || b.isDead() || !b.isValid()) {
                ledger.forget(id);
                bodies.remove(id);
            }
        }
        for (UUID id : ledger.reapExpired(now)) {
            clearModifier(bodies.remove(id));
        }
        stopSweeperIfIdle();
    }

    private void stopSweeperIfIdle() {
        if (sweeper != null && ledger.isEmpty()) {
            sweeper.cancel();
            sweeper = null;
        }
    }

    // ---- attribute plumbing --------------------------------------------------------

    private void removeMyModifiers(AttributeInstance inst) {
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (stripKey.equals(m.getKey())) inst.removeModifier(m);
        }
    }

    private void clearModifier(LivingEntity victim) {
        if (victim == null) return;
        AttributeInstance inst = victim.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        removeMyModifiers(inst);
    }
}
