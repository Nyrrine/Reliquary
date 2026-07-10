package com.nyrrine.reliquary.ego;

import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Mild durability wear for E.G.O weapons.
 *
 * <p>E.G.O weapons are no longer unbreakable — they wear like real gear so they eventually want Mending
 * or Unbreaking III, but never to an annoying extent. A normal melee weapon already loses one point of
 * durability per vanilla swing, so nothing extra is needed there. This helper exists for uses that do
 * <b>not</b> go through a vanilla swing — a ranged shot, a cancelled/custom melee hit, an ability cast —
 * so those weapons still wear down over time.
 *
 * <p>Vanilla Unbreaking is honoured: each point of wear has a {@code 1/(level+1)} chance to actually
 * land. Creative-mode wielders never wear their gear, and ability-wear never lands the final,
 * item-breaking point (it stops one short) — only real combat should be able to snap a blade.
 */
public final class EgoDurability {

    private EgoDurability() {}

    /** Wear {@code points} of durability off the item, honouring Unbreaking. See class docs. */
    public static void wear(Player player, ItemStack item, int points) {
        if (item == null || points <= 0) return;
        if (player != null && player.getGameMode() == GameMode.CREATIVE) return;
        int max = item.getType().getMaxDurability();
        if (max <= 0) return;                                   // e.g. rod/wand items carry no durability
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable dmg)) return;

        int unbreaking = item.getEnchantmentLevel(Enchantment.UNBREAKING);
        int applied = 0;
        for (int i = 0; i < points; i++) {
            if (unbreaking <= 0 || ThreadLocalRandom.current().nextInt(unbreaking + 1) == 0) applied++;
        }
        if (applied == 0) return;

        int next = Math.min(max - 1, dmg.getDamage() + applied);  // ability-wear never snaps the item itself
        if (next == dmg.getDamage()) return;
        dmg.setDamage(next);
        item.setItemMeta(dmg);
    }

    /** Wear a single point (the common case). */
    public static void wear(Player player, ItemStack item) {
        wear(player, item, 1);
    }

    /**
     * Wear the player's main-hand item and write it back through the inventory so the change persists.
     * This is the convenience most E.G.O weapons want after a ranged shot or an ability cast (the weapon
     * is in the main hand). Honours Unbreaking and Creative exactly like {@link #wear}.
     */
    public static void wearMainHand(Player player, int points) {
        if (player == null) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        int before = (item.getItemMeta() instanceof Damageable d) ? d.getDamage() : -1;
        wear(player, item, points);
        int after = (item.getItemMeta() instanceof Damageable d) ? d.getDamage() : -1;
        if (after != before) player.getInventory().setItemInMainHand(item);
    }

    /** Wear a single point off the main-hand item. */
    public static void wearMainHand(Player player) {
        wearMainHand(player, 1);
    }
}
