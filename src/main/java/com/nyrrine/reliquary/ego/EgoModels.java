package com.nyrrine.reliquary.ego;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Model + balance registry for the E.G.O weapons.
 *
 * <p>Each weapon's item is a plain vanilla {@link Material} (so it looks and works fine with no pack)
 * carrying a <b>CustomModelData</b> string; a resource pack keys off that string to swap in the real
 * E.G.O model. CMD strings are namespaced {@code "ego/<id>"}.
 *
 * <p>Unlike the relics (which repaint their meta every tick and so can't hold vanilla enchants), E.G.O
 * weapons set their item once and are meant to be <b>enchantable alternatives to vanilla weapons</b>:
 * apply Sharpness and the rest as normal. To keep them true alternatives and not overshadow vanilla, a
 * melee weapon's <b>base attack damage and attack speed are set here</b> (not left to the fallback
 * material, which would be inconsistent — a netherite axe hits 10, an iron sword 6). This is the one
 * place to retune the whole roster: change {@code atk}/{@code spd} below.
 *
 * <p>{@code atk == 0} means "leave the vanilla item's melee alone" — used for the ranged weapons
 * (crossbows, wands, the lighter), whose damage comes from their fired shots, not a melee swing.
 * A melee weapon's real per-hit damage is {@code atk} plus whatever its gimmick adds, before enchants.
 */
public final class EgoModels {

    private EgoModels() {}

    /** A weapon's vanilla fallback material, its custom-model-data key, and its melee stats. */
    public record Model(Material material, String cmd, double atk, double spd) {}

    /** Melee weapon: material, id, base attack damage (shown on the item), attack speed. */
    private static Model melee(Material m, String id, double atk, double spd) {
        return new Model(m, "ego/" + id, atk, spd);
    }

    /** Ranged/special weapon: no melee tuning (damage comes from its fired shots). */
    private static Model ranged(Material m, String id) {
        return new Model(m, "ego/" + id, 0.0, 0.0);
    }

    // Attack speeds by feel: swords ~1.6, pickaxe ~1.2, maces/axes ~0.9-1.0 (heavy).
    // Base damage sits in the vanilla-weapon band so base + Sharpness V (~+3) stays near/under a
    // Sharpness-V netherite sword (~11) — an alternative, never a strict upgrade.

    // ---- ZAYIN ----
    public static final Model PENITENCE   = melee(Material.MACE, "penitence", 2.0, 1.2);
    public static final Model SODA        = ranged(Material.CROSSBOW, "soda");

    // ---- TETH ----
    public static final Model SOLITUDE    = ranged(Material.CROSSBOW, "solitude");
    public static final Model FRAGMENTS_FROM_SOMEWHERE = melee(Material.NETHERITE_SWORD, "fragments_from_somewhere", 6.0, 1.4);
    public static final Model LANTERN     = melee(Material.MACE, "lantern", 6.0, 0.9);
    public static final Model FOURTH_MATCH_FLAME = ranged(Material.FLINT_AND_STEEL, "fourth_match_flame");
    public static final Model RED_EYES    = melee(Material.IRON_SWORD, "red_eyes", 6.0, 1.6);
    public static final Model REGRET      = melee(Material.MACE, "regret", 7.0, 0.9);
    public static final Model BEAK        = ranged(Material.CROSSBOW, "beak");
    public static final Model LOGGING     = melee(Material.NETHERITE_AXE, "logging", 7.0, 0.9);
    public static final Model WRIST_CUTTER = melee(Material.IRON_SWORD, "wrist_cutter", 5.0, 1.7);
    public static final Model CHRISTMAS   = melee(Material.IRON_SWORD, "christmas", 6.0, 0.9);

    // ---- HE ----
    public static final Model FROST_SPLINTER = melee(Material.NETHERITE_SWORD, "frost_splinter", 6.5, 1.5);
    public static final Model GRINDER_MK4 = melee(Material.NETHERITE_PICKAXE, "grinder_mk4", 5.0, 1.2);
    public static final Model CRIMSON_SCAR = melee(Material.NETHERITE_AXE, "crimson_scar", 7.0, 1.2);
    public static final Model COBALT_SCAR = melee(Material.NETHERITE_SHOVEL, "cobalt_scar", 5.0, 16.0);
    public static final Model OUR_GALAXY  = ranged(Material.BREEZE_ROD, "our_galaxy");
    public static final Model HARVEST     = melee(Material.NETHERITE_HOE, "harvest", 4.0, 1.0);
    public static final Model LIFE_FOR_A_DAREDEVIL = melee(Material.NETHERITE_SWORD, "life_for_a_daredevil", 7.0, 1.6);
    public static final Model LAETITIA    = ranged(Material.CROSSBOW, "laetitia");

    // ---- WAW ----
    public static final Model HARMONY     = ranged(Material.CROSSBOW, "harmony");
    // Gaze swings twice per attack, so its base sits at half the sword band — a swing totals ~7.
    public static final Model GAZE        = melee(Material.NETHERITE_SWORD, "gaze", 3.5, 1.6);
    public static final Model HORNET      = ranged(Material.CROSSBOW, "hornet");
    public static final Model FAINT_AROMA = ranged(Material.CROSSBOW, "faint_aroma");
    public static final Model DISCORD     = melee(Material.NETHERITE_SWORD, "discord", 6.5, 1.6);
    public static final Model LAMP        = ranged(Material.LANTERN, "lamp");
    public static final Model MAGIC_BULLET = ranged(Material.CROSSBOW, "magic_bullet");
    public static final Model SOLEMN_LAMENT = ranged(Material.CROSSBOW, "solemn_lament");
    public static final Model LOVE_AND_HATE = ranged(Material.BREEZE_ROD, "love_and_hate");
    public static final Model SWORD_OF_TEARS = melee(Material.IRON_SWORD, "sword_of_tears", 7.0, 1.6);
    public static final Model GREEN_STEM  = melee(Material.IRON_SWORD, "green_stem", 6.0, 1.6);
    public static final Model SCREAMING_WEDGE = ranged(Material.CROSSBOW, "screaming_wedge");
    public static final Model HEAVEN      = melee(Material.NETHERITE_SWORD, "heaven", 7.0, 1.6);

    // ---- ALEPH ----
    public static final Model JUSTITIA    = melee(Material.NETHERITE_SWORD, "justitia", 7.5, 1.0);
    public static final Model MIMICRY     = melee(Material.NETHERITE_SWORD, "mimicry", 6.5, 1.8);

    // Vanilla base attributes: player attack damage = 1.0, attack speed = 4.0. An item modifier of
    // (atk - 1) / (spd - 4) makes the held weapon read exactly atk / spd.
    private static final NamespacedKey AD_KEY = new NamespacedKey("reliquary", "ego_attack_damage");
    private static final NamespacedKey AS_KEY = new NamespacedKey("reliquary", "ego_attack_speed");

    /** Stamp the custom-model-data string (pack-swappable, bare-safe). */
    public static void stamp(ItemMeta meta, Model model) {
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model.cmd()));
        meta.setCustomModelDataComponent(cmd);
    }

    /**
     * Stamp the model AND, for a melee weapon, its controlled base attack damage + speed so the whole
     * roster shares one balance curve regardless of fallback material. Ranged weapons ({@code atk == 0})
     * get only the model stamp and keep the vanilla item's (irrelevant) melee stats.
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
