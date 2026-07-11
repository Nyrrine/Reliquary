package com.nyrrine.reliquary.busego;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Model + balance registry for the <b>bus ego</b> weapons — a category alongside the relics and the
 * E.G.O equipment, kept in its own package ({@code busego.weapons}) so {@code /reliquary giveall busego}
 * and category tooling can tell them apart.
 *
 * <p>Mirrors {@link com.nyrrine.reliquary.ego.EgoModels}: each weapon's item is a plain vanilla
 * {@link Material} (so it works fine with no pack) carrying a <b>CustomModelData</b> string a resource
 * pack keys off to swap in the real model. Bus-ego CMD strings are namespaced {@code "busego/<id>"}.
 *
 * <p>{@code atk == 0} means "leave the vanilla item's melee alone" — used for command/summon tools whose
 * damage comes from what they conjure, not a melee swing.
 */
public final class BusEgoModels {

    private BusEgoModels() {}

    /** A weapon's vanilla fallback material, its custom-model-data key, and its melee stats. */
    public record Model(Material material, String cmd, double atk, double spd) {}

    /** Melee bus-ego weapon: material, id, base attack damage, attack speed. */
    private static Model melee(Material m, String id, double atk, double spd) {
        return new Model(m, "busego/" + id, atk, spd);
    }

    /** Command/summon bus-ego weapon: no melee tuning (its damage comes from what it conjures). */
    private static Model special(Material m, String id) {
        return new Model(m, "busego/" + id, 0.0, 0.0);
    }

    // ---- the roster ----
    public static final Model FLOWER_BURYING_WEDGE = special(Material.REDSTONE_TORCH, "flower_burying_wedge");

    private static final NamespacedKey AD_KEY = new NamespacedKey("reliquary", "busego_attack_damage");
    private static final NamespacedKey AS_KEY = new NamespacedKey("reliquary", "busego_attack_speed");

    /** Stamp the custom-model-data string (pack-swappable, bare-safe). */
    public static void stamp(ItemMeta meta, Model model) {
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model.cmd()));
        meta.setCustomModelDataComponent(cmd);
    }

    /**
     * Stamp the model AND, for a melee weapon, its controlled base attack damage + speed. Command tools
     * ({@code atk == 0}) get only the model stamp and keep the vanilla item's (irrelevant) melee stats.
     */
    public static void stampWeapon(ItemMeta meta, Model model) {
        stamp(meta, model);
        if (model.atk() <= 0.0) return;
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                AD_KEY, model.atk() - 1.0, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                AS_KEY, model.spd() - 4.0, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND));
    }
}
