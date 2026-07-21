package com.nyrrine.reliquary.ego;

import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.core.WeaponManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps an E.G.O weapon's Enchantments block current when a real vanilla enchant is applied to it at an anvil.
 *
 * <p>The tooltip renderer hides vanilla enchants' default lines ({@code HIDE_ENCHANTS}) and re-lists them in the
 * ego block, but that only happens when {@link EgoEnchants#reapplyLore} runs — which the {@code /reliquary
 * enchant} commands do, but an anvil does not. Without this, a vanilla enchant combined onto an E.G.O weapon at
 * an anvil would work mechanically yet read nowhere (hidden by the flag, absent from the stale block). This
 * re-renders the anvil's result so the new enchant shows beneath the ego, exactly like the command path.
 */
public final class EgoEnchantListener implements Listener {

    private final WeaponManager weapons;

    public EgoEnchantListener(WeaponManager weapons) {
        this.weapons = weapons;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null) return;
        Weapon weapon = weapons.fromItem(result);
        if (!(weapon instanceof EgoWeapon egoWeapon)) return;
        EgoEnchants.reapplyLore(egoWeapon, result); // pull the freshly-applied vanilla enchant beneath the ego
        event.setResult(result);
    }
}
