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
    private static final NamespacedKey FLUX      = key("cogito_flux");
    private static final NamespacedKey TAINTS    = key("cogito_taints");
    private static final NamespacedKey CATALYST  = key("cogito_catalyst");
    private static final NamespacedKey CATALYST_N = key("cogito_catalyst_n");
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

    // Stability breach-gauge tell: safe green > 60, uneasy yellow 30–60, critical red < 30.
    private static final TextColor STAB_GOOD = TextColor.color(0x4ADF7A);
    private static final TextColor STAB_WARN = TextColor.color(0xE8D23B);
    private static final TextColor STAB_BAD  = TextColor.color(0xE23B3B);

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
        pdc.set(FLUX,      PersistentDataType.INTEGER, state.fluxCharges());
        pdc.set(TAINTS,    PersistentDataType.STRING, encodeTaints(state));
        if (state.catalystTarget() != null) {
            pdc.set(CATALYST, PersistentDataType.STRING, state.catalystTarget());
            pdc.set(CATALYST_N, PersistentDataType.INTEGER, state.catalystCount());
        } else { pdc.remove(CATALYST); pdc.remove(CATALYST_N); }

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
        state.setFluxCharges(pdc.getOrDefault(FLUX, PersistentDataType.INTEGER, 0));
        decodeTaints(state, pdc.getOrDefault(TAINTS, PersistentDataType.STRING, ""));
        state.catalystTarget(pdc.get(CATALYST, PersistentDataType.STRING));
        state.catalystCount(pdc.getOrDefault(CATALYST_N, PersistentDataType.INTEGER, 0));
        return state;
    }

    // ---- presentation --------------------------------------------------------------

    /** Apply the green-by-purity tint, the grade-shaded name, and the quality-only lore. */
    private static void style(PotionMeta meta, PotState state) {
        boolean blank = state.isBlank();
        double purity = state.purity();
        Grade grade = state.grade();
        // A blank vial has no material, so its "purity" is meaningless — render it dim and empty.
        Color tint = blank ? greenFor(0.0) : greenFor(purity);
        // A taint overrides the vitals: the worst affliction pulls the tint toward its signal hue, so you SEE
        // something's wrong before you assay.
        Taint worst = state.worstTaint();
        if (!blank && worst != null) tint = blendToward(tint, worst.rgb(), 0.6);

        meta.setColor(tint);
        // Strip every vanilla-potion tell so ONLY our readout shows: no base effect, no "No Effects"
        // line, no potion-type name in the tooltip — just our green tint + custom lore.
        meta.clearCustomEffects();
        meta.setBasePotionType(org.bukkit.potion.PotionType.WATER);
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP); // suppress the vanilla effects/"no effects" line
        meta.setEnchantmentGlintOverride(!blank && grade == Grade.CERTIFIED); // certified vials catch the light

        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(CMD));
        meta.setCustomModelDataComponent(cmd);

        TextColor nameColor = TextColor.color(tint.getRed(), tint.getGreen(), tint.getBlue());
        // A vial carrying an apex (WAW/ALEPH) catalyst is a "Radiant Cogito" — the mandatory form for that tier.
        boolean radiant = !blank && state.catalystTarget() != null && isApexCatalyst(state);
        String name = blank ? "Empty Cogito Vial" : (radiant ? "Radiant Cogito" : "Cogito");
        meta.displayName(Component.text(name)
                .color(blank ? FAINT : (radiant ? TextColor.color(0xFFE9A3) : nameColor))
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(blank ? blankLore() : lore(state, grade, purity));
    }

    private static List<Component> blankLore() {
        List<Component> out = new ArrayList<>();
        out.add(line("Empty.", FAINT));
        out.add(Component.empty());
        out.add(line("Charge it with reagents at the Censer.", FAINT, true));
        return out;
    }

    /**
     * The at-a-glance composition readout: grade+purity headline, vitals, the full emotional signature, and
     * any active afflictions — everything the Assay would tell you, on hover, so a vial never needs the
     * lectern just to be identified. Data lines are upright; flavour/labels are italic.
     */
    private static List<Component> lore(PotState state, Grade grade, double purity) {
        List<Component> out = new ArrayList<>();
        SinProfile profile = state.profile();

        // Headline — the identity of the vial, in its grade's colour: "Analytical · 96.4%".
        out.add(line(String.format(java.util.Locale.ROOT, "%s · %.1f%%", grade.display(), purity), grade.color()));

        // Vitals — volume in body grey, stability tinted by how close it is to breaching.
        long vol = Math.round(state.titer());
        long stab = Math.round(state.stability());
        out.add(Component.text("", BODY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("Volume ", FAINT))
                .append(Component.text(Long.toString(vol), BODY))
                .append(Component.text("    ", FAINT))
                .append(Component.text("Stability ", FAINT))
                .append(Component.text(stab + "/100", stabilityColor(state.stability()))));

        // Emotional signature — every meaningful share (≥1%), high→low, each tinted with its sin's hue.
        out.add(Component.empty());
        out.add(line("Signature", FAINT, true));
        List<Sin> ordered = new ArrayList<>();
        for (Sin s : Sin.values()) if (profile.get(s) >= 1.0) ordered.add(s);
        ordered.sort((a, b) -> Double.compare(profile.get(b), profile.get(a)));
        if (ordered.isEmpty()) {
            out.add(line("trace only", FAINT, true));
        } else {
            for (Sin s : ordered) {
                out.add(line(String.format(java.util.Locale.ROOT, "%s %d%%",
                        s.display(), Math.round(profile.get(s))), s.color()));
            }
        }

        // Afflictions — name + remaining time in the taint's signal colour, symptom beneath in flavour italics,
        // so a sick vial visibly shows its problem on hover.
        if (!state.taints().isEmpty()) {
            out.add(Component.empty());
            for (java.util.Map.Entry<Taint, Double> e : sortedTaints(state)) {
                Taint t = e.getKey();
                out.add(line(String.format(java.util.Locale.ROOT, "⚠ %s — %ds left",
                        t.display(), Math.round(e.getValue())), t.color()));
                out.add(line(t.symptom(), FAINT, true));
            }
        }

        // An inserted catalyst — shown with its stack count, aim, and whether it's a lock (apex) or a buff.
        if (state.catalystTarget() != null) {
            WeaponSpec cw = WeaponSignatures.byId(state.catalystTarget());
            boolean apex = isApexCatalyst(state);
            out.add(Component.empty());
            out.add(line("◆ Catalyst: " + (cw != null ? cw.display() : state.catalystTarget())
                    + "  (" + Math.max(1, state.catalystCount()) + "/3)", TextColor.color(0xFFC94A)));
            out.add(line(apex
                    ? "REQUIRED to manifest this apex weapon"
                    : "adds 1–15% per stack to its odds, once it's your top pull past 70%", FAINT, true));
        }
        return out;
    }

    /** Whether this vial's inserted catalyst targets an apex (WAW/ALEPH) weapon — the Radiant lock. */
    private static boolean isApexCatalyst(PotState state) {
        if (state.catalystTarget() == null) return false;
        WeaponSpec w = WeaponSignatures.byId(state.catalystTarget());
        return w != null && w.grade().isApex();
    }

    /** Green/yellow/red by how far the breach gauge is from failing. */
    private static TextColor stabilityColor(double stability) {
        if (stability > 60.0) return STAB_GOOD;
        if (stability >= 30.0) return STAB_WARN;
        return STAB_BAD;
    }

    /** Active taints ordered most-urgent-first (least time remaining). */
    private static List<java.util.Map.Entry<Taint, Double>> sortedTaints(PotState state) {
        List<java.util.Map.Entry<Taint, Double>> entries = new ArrayList<>(state.taints().entrySet());
        entries.sort(java.util.Map.Entry.comparingByValue());
        return entries;
    }

    // ---- taint serialization -------------------------------------------------------

    private static String encodeTaints(PotState state) {
        if (state.taints().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var e : state.taints().entrySet()) {
            if (sb.length() > 0) sb.append(';');
            // Locale.ROOT so the decimal separator is always '.' — a comma-locale server would else corrupt this.
            sb.append(e.getKey().name()).append(':').append(String.format(java.util.Locale.ROOT, "%.2f", e.getValue()));
        }
        return sb.toString();
    }

    private static void decodeTaints(PotState state, String encoded) {
        if (encoded == null || encoded.isBlank()) return;
        for (String part : encoded.split(";")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            Taint t = Taint.byId(kv[0]);
            if (t == null) continue;
            try {
                double v = Double.parseDouble(kv[1]);
                if (Double.isFinite(v) && v > 0.0) state.setTaintTime(t, v);
            } catch (NumberFormatException ignored) {}
        }
    }

    /** Pull a base RGB {@code frac} of the way toward a target hue (0 = base, 1 = fully the target). */
    private static Color blendToward(Color base, int targetRgb, double frac) {
        int tr = (targetRgb >> 16) & 0xFF, tg = (targetRgb >> 8) & 0xFF, tb = targetRgb & 0xFF;
        return Color.fromRGB(lerp(base.getRed(), tr, frac), lerp(base.getGreen(), tg, frac),
                             lerp(base.getBlue(), tb, frac));
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
