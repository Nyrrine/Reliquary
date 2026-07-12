package com.nyrrine.reliquary.extraction;

import com.nyrrine.reliquary.Reliquary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;

/**
 * Drives taints in real time: once a second it advances any active affliction on the Cogito vial in a
 * player's <b>main hand</b> — applying its per-second harm, ticking its timer, and locking in a permanent
 * scar if it runs out untreated. The held vial's colour and lore update live (its "vitals"), and the most
 * urgent taint is shown on the action bar with its cure, so treatment is always one glance away.
 *
 * <p>Only the held vial ticks — a vial sealed in your bag is paused. Cheap: it only touches players actually
 * holding a tainted potion.
 */
public final class CogitoTicker extends BukkitRunnable {

    private final Reliquary plugin;

    public CogitoTicker(Reliquary plugin) {
        this.plugin = plugin;
    }

    /** Begin ticking once per second. */
    public void start() {
        runTaskTimer(plugin, 20L, 20L);
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack item = p.getInventory().getItemInMainHand();
            PotState st = Cogito.read(item);
            if (st == null || st.taints().isEmpty()) continue;

            Set<Taint> scarred = Engine.tickTaints(st, 1.0);
            Cogito.write(item, st);                     // refresh vitals: colour, lore, timers
            p.getInventory().setItemInMainHand(item);

            for (Taint t : scarred) {
                p.sendMessage(Component.text("The " + t.display() + " set in — a permanent scar. ("
                        + t.symptom() + ")", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.7f, 0.6f);
            }

            Taint worst = st.worstTaint();
            if (worst != null) {
                double left = st.taints().get(worst);
                p.sendActionBar(Component.text(String.format("⚠ %s  %.0fs left — cure with %s",
                        worst.display(), left, worst.cureId()), worst.color()));
                if (left <= 3.0) { // urgent tick
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.3f, 0.7f);
                }
            }
        }
    }
}
