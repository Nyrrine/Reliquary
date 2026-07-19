package com.nyrrine.reliquary.weapons.laevateinn;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;

/**
 * Ridiculous Grit — the blade won't let its wielder die on their feet.
 *
 * <p>For a long time the tooltip named this passive and the code held nothing behind the name: "Grit"
 * was only ever the seal-decay meter dressed up as a mechanic, and a killing blow killed you like any
 * other. The audit filed it as a ghost. This is the passive the name was always promising — a real
 * Totem of Undying baked into the sword, so Matthias grits his teeth and refuses to go down.
 *
 * <p>When a blow would drop the wielder to nothing and the blade is in their hand, the blow is eaten:
 * they're left on a sliver of health with the totem's own kit (Regeneration II, Fire Resistance,
 * Absorption II) under a custom awakening — a big purple-and-white dust burst, its own sound, not the
 * vanilla totem pop. Then it sleeps for fifteen minutes, per player — spend
 * the save and the next lethal hit lands for real until the clock comes back around. Nothing is
 * persisted: a restart is a fresh window, same as every other bit of this blade's state.
 */
final class LaevateinnGrit implements Listener {

    /** Fifteen minutes between saves, per player. Kept in step with the tooltip's "every 15 minutes". */
    private static final long COOLDOWN_MS = 15L * 60L * 1000L;

    /** Health the save leaves the wielder on — a sliver, ~1 heart (clamped to their real max). */
    private static final double REVIVE_HEALTH = 2.0;

    // The vanilla Totem of Undying kit, at its own levels and canonical durations. Amplifier 1 reads as
    // level II. Retune here if the numbers ever want moving — nothing else reads them.
    private static final PotionEffect REGEN = new PotionEffect(PotionEffectType.REGENERATION, 900, 1);
    private static final PotionEffect FIRE_RES = new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0);
    private static final PotionEffect ABSORPTION = new PotionEffect(PotionEffectType.ABSORPTION, 100, 1);

    private final LaevateinnWeapon weapon;

    /** The revive's gate. Arms itself the moment a save is authorised, so no save can skip its lockout. */
    private final GritCooldown cooldown = new GritCooldown(COOLDOWN_MS);

    LaevateinnGrit(Reliquary plugin, LaevateinnWeapon weapon) {
        this.weapon = weapon;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Lethal or nothing: getFinalDamage is the settled figure — armour, resistance, blocking and the
        // absorption it eats are already folded in — so this is the health the blow actually clears. If a
        // sliver survives, it isn't a killing blow and Grit stays out of it.
        if (event.getFinalDamage() < player.getHealth()) return;

        // The void and /kill go under a totem in vanilla; they go under this too, so the blade can't be
        // used to sit in the void or shrug off an admin kill.
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return;

        // Only the hand that holds the blade grits — off-hand it's inert, the same rule the rest of the
        // sword lives by.
        if (!weapon.matches(player.getInventory().getItemInMainHand())) return;

        // Authorise the save and arm the lockout in one atomic step, up front. Everything below can be
        // skipped by a throw or slipped by a re-entrant hit — the cooldown can't, because it's already
        // recorded by the time we get here. One lethal hit, one save, then locked out for the window.
        UUID id = player.getUniqueId();
        if (!cooldown.tryConsume(id, System.currentTimeMillis())) return; // still cooling — the blow lands

        // Grit through it. Cancel the blow outright so no death fires, drop them to a sliver, hand them
        // the totem kit, and light it up.
        event.setCancelled(true);
        player.setFireTicks(0);
        player.setHealth(Math.min(REVIVE_HEALTH, maxHealth(player)));
        player.setAbsorptionAmount(0); // let the Absorption effect set the gold hearts cleanly
        player.addPotionEffect(REGEN);
        player.addPotionEffect(FIRE_RES);
        player.addPotionEffect(ABSORPTION);
        awakeningBurst(player);
    }

    /**
     * The revive's show — a custom awakening in place of the vanilla totem pop (§2.1). Layered,
     * resonant sound for the wake; a big purple-and-white <b>dust</b> burst for the light — no
     * explosion particles, she doesn't want the TNT look. Cosmetic only — the save above is what
     * actually happens.
     */
    private static void awakeningBurst(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation();
        Location core = at.clone().add(0, 1.0, 0);

        // The awakening: a deep beacon swell for the wake, a conduit shimmer riding over it, and a deep
        // bell toll over a resurrection-anchor resonance for the punch — a resonant boom, no explosion in
        // it. Layered on purpose — no single vanilla sound reads as "awakening".
        world.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 1.4f, 0.7f);
        world.playSound(at, Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 1.1f);
        world.playSound(at, Sound.BLOCK_BELL_USE, 1.2f, 0.55f);
        world.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 0.8f);

        // The burst, dust only: a wide purple cloud with a deep-purple shadow for body, then a tighter
        // bright-white core inside it. Big and soft, no explosion smoke.
        Particle.DustOptions purple = new Particle.DustOptions(LaevateinnVfx.PURPLE, 1.7f);
        Particle.DustOptions purpleDeep = new Particle.DustOptions(LaevateinnVfx.PURPLE_DEEP, 1.4f);
        Particle.DustOptions white = new Particle.DustOptions(LaevateinnVfx.WHITE, 1.3f);
        world.spawnParticle(Particle.DUST, core, 160, 1.3, 1.4, 1.3, 0.0, purple);
        world.spawnParticle(Particle.DUST, core, 90, 1.0, 1.1, 1.0, 0.0, purpleDeep);
        world.spawnParticle(Particle.DUST, core, 120, 0.7, 0.9, 0.7, 0.0, white);
    }

    private static double maxHealth(Player player) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        return max != null ? max.getValue() : 20.0;
    }

    /** Drop one player's cooldown (they left). */
    void clear(UUID id) {
        cooldown.clear(id);
    }

    /** Drop every cooldown (plugin shutdown / reload). */
    void clearAll() {
        cooldown.clearAll();
    }
}
