package com.nyrrine.reliquary.weapons.arayashiki;

import com.nyrrine.reliquary.Reliquary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Anything slain by Arayashiki is "erased" — it dissolves upward into bright
 * white as it fades from existence, dropping nothing (a true fade of the entity
 * model isn't possible in vanilla, so we sever it into a rising white silhouette).
 */
public final class ArayashikiErasure {

    private final Reliquary plugin;
    private final ArayashikiWeapon weapon;
    private static final int DURATION = 16; // ticks

    public ArayashikiErasure(Reliquary plugin, ArayashikiWeapon weapon) {
        this.plugin = plugin;
        this.weapon = weapon;
    }

    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!weapon.consumeErasedMark(victim.getUniqueId())) return;

        // Erased from existence -> leaves nothing behind.
        event.getDrops().clear();
        event.setDroppedExp(0);
        erase(victim);
    }

    public void onPlayerDeath(PlayerDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!weapon.consumeErasedMark(victim.getUniqueId())) return;

        event.deathMessage(Component.text(victim.getName() + " was erased from existence.")
                .color(NamedTextColor.WHITE));
        erase(victim);
    }

    /** Plays the rising fade-to-white dissolve at the victim's body. */
    private void erase(LivingEntity victim) {
        World world = victim.getWorld();
        Location base = victim.getLocation();
        double width = Math.max(0.4, victim.getWidth());
        double height = Math.max(0.6, victim.getHeight());

        world.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f, 1.5f);
        world.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.1f, 0.7f);
        world.playSound(base, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.1f);

        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t >= DURATION) { cancel(); return; }
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                double prog = (double) t / DURATION;        // 0..1
                int count = (int) (44 * (1.0 - prog)) + 4;   // dense at first, thinning out
                double rise = prog * height * 1.4;           // the silhouette lifts as it erases
                float sz = (float) (1.2 * (1.0 - 0.55 * prog));

                for (int i = 0; i < count; i++) {
                    double ang = rng.nextDouble(0, Math.PI * 2);
                    double r = Math.sqrt(rng.nextDouble()) * (width * 0.6);
                    double y = rng.nextDouble() * height * (1.0 - prog * 0.5) + rise;
                    Location p = base.clone().add(Math.cos(ang) * r, y, Math.sin(ang) * r);
                    world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.WHITE, sz));
                    if (rng.nextDouble() < 0.22) {
                        world.spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0.01);
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
