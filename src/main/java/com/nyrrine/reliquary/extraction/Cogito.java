package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Cogito — the emotionally-charged, quality-graded reagent at the centre of the extraction system,
 * stored as a Minecraft <b>potion</b> so it can carry a custom green tint and rich persistent data.
 *
 * <p>This class is the bridge between a {@link PotState} (the chemistry) and an {@link ItemStack} (what
 * the player holds): {@link #create(PotState)} / {@link #write(ItemStack, PotState)} serialize a pot down,
 * {@link #read(ItemStack)} reads one back. It also owns the <b>presentation</b> — the potion is
 * <i>always green</i> and its <b>shade encodes purity</b> (dark murky green = crude, bright vivid green =
 * reference-quality). Emotional composition is deliberately <b>not</b> shown on the item: you can eyeball
 * quality in your hotbar, but must assay a pot at the lectern to learn what's in it.
 *
 * <p>Pure static utility — no plugin instance needed; keys live under the {@code reliquary} namespace.
 */
public final class Cogito {

    private Cogito() {}

    // ---- persistent-data keys ------------------------------------------------------

    private static final NamespacedKey MARK      = key("cogito");
    private static final NamespacedKey CEILING   = key("cogito_ceiling");
    private static final NamespacedKey NOISE     = key("cogito_noise");
    private static final NamespacedKey STABILITY = key("cogito_stability");
    private static final NamespacedKey ADDS      = key("cogito_adds");
    private static final NamespacedKey PASSES    = key("cogito_distill_passes");
    private static final NamespacedKey[] CHARGE  = new NamespacedKey[Sin.COUNT];

    static {
        for (Sin s : Sin.values()) CHARGE[s.index()] = key("cogito_charge_" + s.name().toLowerCase());
    }

    private static NamespacedKey key(String k) { return new NamespacedKey("reliquary", k); }

    /** Pack-swappable model key (resource pack reskins the vial by grade later). */
    private static final String CMD = "extraction/cogito";

    // ---- palette -------------------------------------------------------------------

    // The green ramp: purity 0 → dark murky green, purity 100 → bright vivid green.
    private static final int[] DIRTY = {0x2E, 0x4A, 0x2E};
    private static final int[] CLEAN = {0x74, 0xF0, 0x66};

    private static final TextColor FAINT = TextColor.color(0x7A7A84);
    private static final TextColor BODY  = TextColor.color(0xB8B8C0);

    // ---- item I/O ------------------------------------------------------------------

    /** Whether an item is a Cogito potion. */
    public static boolean matches(ItemStack item) {
        if (item == null || item.getType() != Material.POTION) return false;
        PotionMeta meta = potionMeta(item);
        return meta != null && meta.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE);
    }

    /** A fresh Cogito potion carrying the given pot state. */
    public static ItemStack create(PotState state) {
        ItemStack item = new ItemStack(Material.POTION);
        write(item, state);
        return item;
    }

    /** Serialize {@code state} onto {@code item} and restyle it (tint, name, lore) to match. */
    public static void write(ItemStack item, PotState state) {
        PotionMeta meta = potionMeta(item);
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(MARK, PersistentDataType.BYTE, (byte) 1);
        for (Sin s : Sin.values()) pdc.set(CHARGE[s.index()], PersistentDataType.DOUBLE, state.charge(s));
        pdc.set(CEILING,   PersistentDataType.DOUBLE, state.ceiling());
        pdc.set(NOISE,     PersistentDataType.DOUBLE, state.noise());
        pdc.set(STABILITY, PersistentDataType.DOUBLE, state.stability());
        pdc.set(ADDS,      PersistentDataType.INTEGER, state.adds());
        pdc.set(PASSES,    PersistentDataType.INTEGER, state.distillPasses());

        style(meta, state);
        item.setItemMeta(meta);
    }

    /** Read a pot state back off a Cogito potion, or {@code null} if it isn't one. */
    public static PotState read(ItemStack item) {
        if (!matches(item)) return null;
        PotionMeta meta = potionMeta(item);
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        PotState state = new PotState();
        for (Sin s : Sin.values()) {
            state.setCharge(s, pdc.getOrDefault(CHARGE[s.index()], PersistentDataType.DOUBLE, 0.0));
        }
        state.capCeiling(pdc.getOrDefault(CEILING, PersistentDataType.DOUBLE, 100.0));
        state.setNoise(pdc.getOrDefault(NOISE, PersistentDataType.DOUBLE, 0.0));
        state.setStability(pdc.getOrDefault(STABILITY, PersistentDataType.DOUBLE, 100.0));
        state.setAdds(pdc.getOrDefault(ADDS, PersistentDataType.INTEGER, 0));
        state.setDistillPasses(pdc.getOrDefault(PASSES, PersistentDataType.INTEGER, 0));
        return state;
    }

    // ---- presentation --------------------------------------------------------------

    /** Apply the green-by-purity tint, the grade-shaded name, and the quality-only lore. */
    private static void style(PotionMeta meta, PotState state) {
        double purity = state.purity();
        Grade grade = state.grade();
        Color tint = greenFor(purity);

        meta.setColor(tint);
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP); // suppress the vanilla "no effects" line
        meta.setEnchantmentGlintOverride(grade == Grade.CERTIFIED); // certified vials catch the light

        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(CMD));
        meta.setCustomModelDataComponent(cmd);

        TextColor nameColor = TextColor.color(tint.getRed(), tint.getGreen(), tint.getBlue());
        meta.displayName(Component.text("Cogito")
                .color(nameColor)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore(state, grade, purity));
    }

    private static List<Component> lore(PotState state, Grade grade, double purity) {
        List<Component> out = new ArrayList<>();
        out.add(line(grade.display() + " grade", grade.color()));
        out.add(line(String.format("Purity  %.1f%%", purity), BODY));
        out.add(line(String.format("Stability  %d / 100", Math.round(state.stability())), BODY));
        out.add(line(String.format("Volume  %d", Math.round(state.titer())), BODY));
        out.add(Component.empty());
        out.add(line("Composition unassayed.", FAINT, true));
        out.add(line("Assay at a lectern to reveal.", FAINT, true));
        return out;
    }

    private static Component line(String text, TextColor color) { return line(text, color, false); }

    private static Component line(String text, TextColor color, boolean italic) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, italic);
    }

    /** Interpolate the green ramp by purity (0–100). */
    private static Color greenFor(double purity) {
        double f = Math.max(0.0, Math.min(1.0, purity / 100.0));
        return Color.fromRGB(lerp(DIRTY[0], CLEAN[0], f),
                             lerp(DIRTY[1], CLEAN[1], f),
                             lerp(DIRTY[2], CLEAN[2], f));
    }

    private static int lerp(int a, int b, double f) { return (int) Math.round(a + (b - a) * f); }

    private static PotionMeta potionMeta(ItemStack item) {
        return item.getItemMeta() instanceof PotionMeta pm ? pm : null;
    }
}
