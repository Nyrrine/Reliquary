package com.nyrrine.reliquary.index;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The physical prescript — the paper a Weaver hands over, and the only object in the system a player can hold.
 *
 * <p><b>It is a receipt, not a record.</b> The paper carries an id and nothing else of consequence; the
 * prescript itself lives in the recipient's section of the shared store. Paper burns, drops, and duplicates,
 * so a system that kept its state here would let a recipient escape an errand by walking into lava, and would
 * make {@code /prescript} unanswerable with an empty inventory. Lose the paper and a Weaver reissues it; the
 * errand is unmoved. The Library's copy is the real one — yours is paper.
 *
 * <p>The item is deliberately inert. No abilities, no attributes, no ticking: right-click to read it, and
 * that is the whole of its mechanism.
 */
public final class PrescriptPaper {

    private PrescriptPaper() {}

    /** The prescript this paper is a receipt for — the id of a record in the recipient's store section. */
    public static final NamespacedKey ID = new NamespacedKey("reliquary", "prescript_id");
    /** Who it was issued to. A stolen or dropped paper still names its owner, so a finder can hand it back. */
    public static final NamespacedKey TARGET = new NamespacedKey("reliquary", "prescript_target");

    private static final TextColor SEAL  = TextColor.color(0xC9A227); // Library gold — the name and the rule
    private static final TextColor INK   = TextColor.color(0xE8E0CC); // parchment — the instruction itself
    private static final TextColor FAINT = TextColor.color(0x7A7A84); // house faint — provenance, footers
    private static final TextColor CLAIM = TextColor.color(0x74F066); // house green — hand raised

    /** House lore convention: wrap at ~38 chars so a tooltip never fills the screen. */
    private static final int WRAP = 38;

    /**
     * The paper for {@code p}, issued to {@code targetName}.
     *
     * <p>Carries {@code CustomModelData} {@code index/prescript} rather than an {@code item_model} component,
     * matching Arayashiki's fallback: a pack-less player sees ordinary paper instead of a missing model, and
     * real paper is untouched.
     */
    public static ItemStack create(Prescript p, UUID target, String targetName, String issuerName) {
        ItemStack item = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Prescript").color(SEAL)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(line("For " + targetName, FAINT));
        lore.add(Component.empty());
        for (String l : wrap(p.text(), WRAP)) lore.add(line(l, INK)); // the instruction, in full
        lore.add(Component.empty());
        if (p.claimed()) lore.add(line("Claimed — awaiting judgement", CLAIM));
        lore.add(line("Issued by " + issuerName, FAINT));
        lore.add(line(dated(p.issued()), FAINT));
        lore.add(line("Right-click to read.", FAINT));
        meta.lore(lore);

        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(ID, PersistentDataType.STRING, p.id().toString());
        meta.getPersistentDataContainer().set(TARGET, PersistentDataType.STRING, target.toString());
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("index/prescript"));
        meta.setCustomModelDataComponent(cmd);

        item.setItemMeta(meta);
        return item;
    }

    /** The prescript id this paper is a receipt for, or {@code null} if it isn't a prescript paper. */
    public static UUID idOf(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer().get(ID, PersistentDataType.STRING);
        return parse(raw);
    }

    /** Who this paper was issued to, or {@code null} if it isn't a prescript paper. */
    public static UUID targetOf(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return parse(meta.getPersistentDataContainer().get(TARGET, PersistentDataType.STRING));
    }

    /** Whether this stack is a prescript paper at all. */
    public static boolean is(ItemStack item) {
        return idOf(item) != null;
    }

    private static UUID parse(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException hand_edited) {
            return null; // a rigged or corrupted stack simply isn't a prescript
        }
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Break {@code text} onto lines of at most {@code width}, on word boundaries. A single word longer than
     * the width is left to overhang rather than hyphenated — no line in the register is close.
     */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    /** The prescript rendered for chat — used by the read path and by {@code /prescript}. */
    public static List<Component> read(Prescript p, String issuerName) {
        List<Component> out = new ArrayList<>();
        out.add(line("Prescript", SEAL));
        for (String l : wrap(p.text(), 54)) out.add(line("  " + l, INK)); // chat is wider than a tooltip
        String age = ago(p.outstandingSeconds());
        out.add(line("  Issued by " + issuerName + " · " + age, FAINT));
        if (p.claimed()) out.add(line("  Claimed — awaiting a Weaver's judgement.", CLAIM));
        return out;
    }

    /**
     * The date a prescript was issued, for the paper — "16 July 2026".
     *
     * <p><b>Flavour, and only flavour.</b> A prescript bearing today's date reads like a summons and dates
     * the errand in a recipient's memory; it is not a due date, nothing expires, and nothing anywhere reads
     * this back. The Index enforces no clock. If an errand ought to have been done by now, that is a Weaver's
     * opinion, not the plugin's.
     */
    static String dated(long epochSeconds) {
        return java.time.Instant.ofEpochSecond(epochSeconds)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy"));
    }

    /**
     * A rough human age — "4 hours ago". Deliberately coarse, and for a Weaver's eyes: it exists so someone
     * ruling on a prescript can see who has been sitting on one, not so anything can be counted against a
     * clock. Nothing expires.
     */
    static String ago(long seconds) {
        if (seconds < 60) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    /** Grey, for a tally that reads zero. */
    public static TextColor faint() {
        return FAINT;
    }

    /** Library gold. */
    public static TextColor seal() {
        return SEAL;
    }

    /** Parchment. */
    public static TextColor ink() {
        return INK;
    }

    /** Used by the tally readout for an unaccomplished count that isn't zero. */
    public static TextColor red() {
        return NamedTextColor.RED;
    }

    /** Used by the tally readout for an accomplished count that isn't zero. */
    public static TextColor green() {
        return CLAIM;
    }
}
