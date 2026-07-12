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
 * Drives taints in real time: once a second it advances any active fault on <b>every</b> Cogito vial anywhere
 * in a player's inventory (main hand, off hand, hotbar, storage) — applying its per-second harm, ticking its
 * timer, and locking in a permanent scar if it runs out untreated. Each mutated vial's colour and lore update
 * live (its "vitals") and are written straight back to their own slot. The most urgent taint is shown on the
 * action bar with its cure, so treatment is always one glance away.
 *
 * <p>Every vial ticks whether or not it is held — a fault keeps counting down in the bag, so players must keep
 * their cures nearby. The action-bar readout follows the currently-held vial if there is one, otherwise the
 * worst-afflicted vial in the bag.
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
            org.bukkit.inventory.PlayerInventory inv = p.getInventory();
            ItemStack[] contents = inv.getContents();   // full inventory incl. hotbar, storage, armor, off hand
            int heldSlot = inv.getHeldItemSlot();

            PotState heldSt = null;                     // vial in the main hand, if any
            PotState worstSt = null;                    // most-urgent afflicted vial in the bag
            double worstLeft = Double.MAX_VALUE;

            for (int i = 0; i < contents.length; i++) {
                ItemStack item = contents[i];
                if (item == null) continue;
                PotState st = Cogito.read(item);
                if (st == null || st.taints().isEmpty()) continue;

                Set<Taint> scarred = Engine.tickTaints(st, 1.0);
                Cogito.write(item, st);                 // refresh vitals: colour, lore, timers
                inv.setItem(i, item);                   // write the mutated vial back to its own slot

                for (Taint t : scarred) {
                    p.sendMessage(Component.text("The " + t.display() + " set in — a permanent scar. ("
                            + t.symptom() + ")", NamedTextColor.RED));
                    p.playSound(p.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.7f, 0.6f);
                }

                if (i == heldSlot) heldSt = st;
                Taint w = st.worstTaint();
                if (w != null) {
                    double left = st.taints().get(w);
                    if (left < worstLeft) { worstLeft = left; worstSt = st; }
                }
            }

            // Action bar follows the held vial if present, else the worst-afflicted vial in the bag.
            PotState focus = heldSt != null ? heldSt : worstSt;
            if (focus == null) continue;
            Taint worst = focus.worstTaint();
            if (worst != null) {
                double left = focus.taints().get(worst);
                p.sendActionBar(Component.text(String.format("⚠ %s  %.0fs left — cure with %s",
                        worst.display(), left, worst.cureId()), worst.color()));
                if (left <= 3.0) { // urgent tick
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.3f, 0.7f);
                }
            }
        }
    }
}
