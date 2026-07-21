package com.nyrrine.reliquary.weapons.arayashiki;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.ego.EgoHud;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs every 2 ticks (per online player). For the given player it:
 *   - drains the blade's use-time while it's held, regenerates it while sheathed,
 *   - repaints the held blade's name as its letters decay into blank space, and
 *   - draws the red "entanglement" string aura winding around a wielder.
 */
public final class ArayashikiWielder {

    private final Reliquary plugin;
    private final ArayashikiWeapon weapon;

    // Per 2-tick step. Full drain over 9 min of holding; full regen in ~45s of rest. The drain was
    // tripled (over the 10800-tick pool) so the memory lasts 3x; regen is scaled to match the larger
    // pool so recovery still takes ~45s, unchanged.
    private static final int DRAIN_PER_STEP = 2;
    private static final int REGEN_PER_STEP = 24;

    private final Map<UUID, Integer> lastLevel = new HashMap<>();
    private final Set<UUID> hollowSeen = new HashSet<>();

    // Muga: subtle dark smoke exerted low around the feet, faint red ember tint.
    private static final Particle.DustOptions EMBER =
            new Particle.DustOptions(Color.fromRGB(130, 16, 16), 0.7f);

    public ArayashikiWielder(Reliquary plugin, ArayashikiWeapon weapon) {
        this.plugin = plugin;
        this.weapon = weapon;
    }

    /** @return true to keep ticking this player (still holding or regenerating), false to disengage. */
    public boolean tick(Player player, long ticks) {
        UUID id = player.getUniqueId();
        ItemStack main = player.getInventory().getItemInMainHand();
        boolean holding = weapon.matches(main);
        // True Arayashiki (admin) keeps cutting even at 0% memory — flag it so hasCharge stays true.
        if (holding && weapon.isTrue(main)) weapon.markTrue(id); else weapon.unmarkTrue(id);

        int cur = weapon.useTicksOf(id);
        cur += holding ? -DRAIN_PER_STEP : REGEN_PER_STEP;
        weapon.setUseTicks(id, cur);
        cur = weapon.useTicksOf(id);

        boolean owns = holding || inventoryHasBlade(player);
        if (!owns) {
            lastLevel.remove(id);
            hollowSeen.remove(id);
            // Keep resting the mind back to full even with the blade away; stop once whole.
            return cur < ArayashikiWeapon.MAX_USE_TICKS;
        }

        if (cur <= 0) hollowSeen.add(id); // remember the mind bottomed out this cycle

        if (holding) {
            updateName(player, id, cur);
            if (player.getGameMode() != GameMode.SPECTATOR) {
                drawMugaSmoke(player, cur, ticks);
            }
        } else {
            lastLevel.remove(id); // repaint the item the next time it's drawn
        }

        // Live readout while there's memory to spend or clear (unless briefly muted).
        if (cur < ArayashikiWeapon.MAX_USE_TICKS) {
            if (!weapon.isActionBarMuted(id)) player.sendActionBar(chargeBar(cur, holding));
        } else if (!holding && hollowSeen.remove(id)) {
            // Only pops when the mind has climbed all the way back from hollow.
            player.sendActionBar(EgoHud.status("Your mind is clear again.", TextColor.color(0x9FD8FF)));
        }

        // Stay engaged while actively holding or still regenerating; idle-at-full disengages.
        return holding || cur < ArayashikiWeapon.MAX_USE_TICKS;
    }

    /** Drop this player's wielder state on quit. */
    public void clear(UUID id) {
        lastLevel.remove(id);
        hollowSeen.remove(id);
    }

    private boolean inventoryHasBlade(Player player) {
        for (ItemStack it : player.getInventory().getContents()) {
            if (weapon.matches(it)) return true;
        }
        return false;
    }

    /** Repaints the held blade's name + lore only when its erosion level actually changes. */
    private void updateName(Player player, UUID id, int cur) {
        int level = weapon.erosionLevel(cur);
        Integer prev = lastLevel.get(id);
        if (prev != null && prev == level) return;

        // A memory spent: a wisp slips from the mind as another letter is cut away.
        if (prev != null && level > prev) {
            Location head = player.getEyeLocation().add(0, 0.45, 0);
            player.getWorld().spawnParticle(Particle.DUST, head, 2, 0.15, 0.1, 0.15, 0,
                    new Particle.DustOptions(Color.WHITE, 0.7f));
        }
        lastLevel.put(id, level);

        ItemStack held = player.getInventory().getItemInMainHand();
        ItemMeta meta = held.getItemMeta();
        if (meta == null) return;
        meta.displayName(weapon.erodedName(cur));
        meta.lore(weapon.erodedLore(cur));
        held.setItemMeta(meta);
        player.getInventory().setItemInMainHand(held);
    }

    /** A segmented gauge + state word that itself frays away as memory runs low. */
    private Component chargeBar(int cur, boolean holding) {
        double charge = (double) cur / ArayashikiWeapon.MAX_USE_TICKS;
        // The readout only starts fraying in the lower half — heaviest near empty.
        double fray = Math.max(0.0, (0.5 - charge) / 0.5);

        int seg = 10;
        int filled = (int) Math.round(charge * seg);
        TextColor fill = charge > 0.5 ? TextColor.color(0x74E48C)
                : charge > 0.2 ? TextColor.color(0xE8C87A) : TextColor.color(0xC22035);

        String filledStr = "▮".repeat(filled);
        String emptyStr = "▮".repeat(seg - filled);
        String state = cur <= 0 ? "Hollow" : holding ? "Erasing me" : "Clearing";
        TextColor stateColor = cur <= 0 ? NamedTextColor.RED
                : holding ? NamedTextColor.GRAY : TextColor.color(0x9FD8FF);
        int pct = (int) Math.round(charge * 100);

        return Component.text(weapon.eraseText("[", fray), NamedTextColor.DARK_GRAY)
                .append(Component.text(weapon.eraseText(filledStr, fray), fill))
                .append(Component.text(weapon.eraseText(emptyStr, fray), NamedTextColor.DARK_GRAY))
                .append(Component.text(weapon.eraseText("]", fray), NamedTextColor.DARK_GRAY))
                .append(Component.text("  "))
                .append(Component.text(weapon.eraseText(state, fray), stateColor))
                .append(Component.text(weapon.eraseText("  " + pct + "%", fray), NamedTextColor.GRAY));
    }

    /** Muga — subtle dark smoke exerted low around the wielder's feet. Visible to everyone. */
    private void drawMugaSmoke(Player player, int cur, long ticks) {
        World world = player.getWorld();
        Location feet = player.getLocation();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Sparse on purpose: a couple of low wisps per step so it never crowds the view.
        double charge = (double) cur / ArayashikiWeapon.MAX_USE_TICKS;
        int puffs = 2 + (charge > 0.5 ? 1 : 0);
        for (int i = 0; i < puffs; i++) {
            double ang = rng.nextDouble(0, Math.PI * 2);
            double r = 0.22 + rng.nextDouble() * 0.28;
            double y = 0.05 + rng.nextDouble() * 0.15;               // stays around the ankles
            Location p = feet.clone().add(Math.cos(ang) * r, y, Math.sin(ang) * r);
            // Small smoke, gentle upward drift so it curls and dies before reaching the face.
            world.spawnParticle(Particle.SMOKE, p, 1, 0.03, 0.02, 0.03, 0.012);
        }

        // Faint red ember every few steps for the Muga tint — kept rare.
        if (ticks % 3 == 0) {
            double ang = rng.nextDouble(0, Math.PI * 2);
            double r = 0.18 + rng.nextDouble() * 0.24;
            Location p = feet.clone().add(Math.cos(ang) * r, 0.08, Math.sin(ang) * r);
            world.spawnParticle(Particle.DUST, p, 1, 0.02, 0.02, 0.02, 0, EMBER);
        }
    }
}
