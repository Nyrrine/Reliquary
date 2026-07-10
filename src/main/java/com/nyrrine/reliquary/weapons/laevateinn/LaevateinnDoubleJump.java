package com.nyrrine.reliquary.weapons.laevateinn;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.util.Vector;

/**
 * Turns the wielder's mid-air jump into Lævateinn's air-leap. The wielder tick grants
 * {@code allowFlight} while the sealed/true blade is held; the client then fires a flight
 * toggle when they double-tap jump. We cancel that toggle (they never actually fly),
 * launch them upward instead, and arm an air slam so a left-click in the air crashes down.
 */
public final class LaevateinnDoubleJump implements Listener {

    private final Reliquary plugin;
    private final LaevateinnWeapon weapon;

    private static final double LEAP_UP = 1.15;   // a good vantage point
    private static final double LEAP_FORWARD = 0.30;
    private static final long AIR_SLAM_ARM_MS = 2500L;

    public LaevateinnDoubleJump(Reliquary plugin, LaevateinnWeapon weapon) {
        this.plugin = plugin;
        this.weapon = weapon;
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (!event.isFlying()) return; // only the "start flying" edge is a double-jump
        Player player = event.getPlayer();
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return; // real flight — leave it
        if (!weapon.matches(player.getInventory().getItemInMainHand())) return;
        if (weapon.comboBusy(player.getUniqueId())) return; // rooted mid-combo — no leaping out

        event.setCancelled(true);
        player.setFlying(false);
        player.setAllowFlight(false); // consume the leap — the tick re-grants once the slam cools

        Vector look = player.getEyeLocation().getDirection().normalize();
        Vector leap = look.clone().setY(0).normalize().multiply(LEAP_FORWARD).setY(LEAP_UP);
        player.setVelocity(player.getVelocity().add(leap));
        player.setFallDistance(0f);

        var id = player.getUniqueId();
        weapon.armAirSlam(id, AIR_SLAM_ARM_MS);
        // Admin (Worthy) mode gets a 2s slam cooldown for testing; otherwise the full 45s leash.
        long slamCd = weapon.isWorthy(player.getInventory().getItemInMainHand())
                ? 2000L : weapon.slamCdMs(weapon.formOf(id));
        weapon.setSlamReadyAt(id, System.currentTimeMillis() + slamCd); // gates the next leap
        weapon.grantFallGrace(id, 6000L); // no fall damage for the leap + any dive

        Location o = player.getLocation().add(0, 0.2, 0);
        player.getWorld().spawnParticle(Particle.DUST, o, 8, 0.3, 0.05, 0.3, 0,
                new Particle.DustOptions(LaevateinnVfx.PURPLE, 1.2f));
        LaevateinnVfx.ring(player.getWorld(), o, 0.6,
                new Particle.DustOptions(LaevateinnVfx.PURPLE_DEEP, 1.4f), 10);
        LaevateinnVfx.twinkle(player.getWorld(), player.getLocation().add(0, 0.5, 0), 3);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.4f);
    }

    /** Our Ground-Slam debris blocks are VFX only — never let them place into the world. */
    @EventHandler(ignoreCancelled = true)
    public void onDebrisLand(EntityChangeBlockEvent event) {
        if (event.getEntity().getScoreboardTags().contains(LaevateinnVfx.DEBRIS_TAG)) {
            event.setCancelled(true);
            event.getEntity().remove();
        }
    }
}
