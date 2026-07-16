package com.nyrrine.reliquary.ego;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared tooltip for E.G.O Equipment.
 *
 * <p>Every E.G.O item reads top-to-bottom in the same order, so the roster looks like one kit: the
 * <b>weapon</b> name as the display name in the item's primary colour, the source <b>Abnormality</b> as
 * the bold title line in its secondary colour, the flavour block, then a {@code How to use:} moveset of
 * bracketed {@code [Input] Ability Name} headers with their descriptions, and the grey footer.
 *
 * <p>The name/title split is the rule of the house and the reason this helper exists: the display name is
 * always the weapon, the title line is always the Abnormality, and neither ever repeats the other. Callers
 * pass their text already wrapped (~38 characters a line reads well without filling the screen).
 */
public final class EgoLore {

    private EgoLore() {}

    /** Flavour and ability descriptions share one off-white. */
    private static final TextColor BODY = TextColor.color(0xF0F0F0);

    /** The footer marking the item as E.G.O Equipment. */
    private static final TextColor FOOTER_COLOR = TextColor.color(0x808080);
    private static final String FOOTER = "E.G.O Equipment";

    private static final String HOW_TO_USE = "How to use:";

    /**
     * One entry of a moveset: the bracketed {@code [Input] Ability Name} header and the description
     * line(s) beneath it.
     */
    public record Ability(String header, List<String> lines) {
        public Ability(String header, String... lines) {
            this(header, List.of(lines));
        }
    }

    /** A finished tooltip — the item's display name and its lore block. */
    public record Tooltip(Component displayName, List<Component> lore) {
        /** Stamp both onto an item's meta. */
        public void applyTo(ItemMeta meta) {
            meta.displayName(displayName);
            meta.lore(lore);
        }
    }

    private static Component line(String text, TextColor color, boolean bold) {
        return Component.text(text, color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, bold);
    }

    /**
     * Build an E.G.O tooltip.
     *
     * @param name        the weapon's name — becomes the display name, in {@code primary}
     * @param abnormality the source Abnormality — becomes the bold title line, in {@code secondary}
     * @param primary     the item's primary colour (name, {@code How to use:}, ability headers)
     * @param secondary   the item's secondary colour (the Abnormality title line)
     * @param descLines   the flavour block, pre-wrapped. An empty string renders as a blank line, which
     *                    is how a two-paragraph flavour block gets its break.
     * @param howLines    the moveset, in the order it should read
     */
    public static Tooltip egoLore(String name,
                                  String abnormality,
                                  TextColor primary,
                                  TextColor secondary,
                                  List<String> descLines,
                                  List<Ability> howLines) {
        List<Component> lore = new ArrayList<>();

        lore.add(line(abnormality, secondary, true));
        lore.add(Component.empty());

        for (String desc : descLines) lore.add(line(desc, BODY, false));
        lore.add(Component.empty());

        lore.add(line(HOW_TO_USE, primary, false));
        for (Ability ability : howLines) {
            lore.add(line(ability.header(), primary, true));
            for (String l : ability.lines()) lore.add(line(l, BODY, false));
            lore.add(Component.empty()); // separates abilities, and the last one from the footer
        }
        if (howLines.isEmpty()) lore.add(Component.empty());

        lore.add(line(FOOTER, FOOTER_COLOR, false));

        return new Tooltip(line(name, primary, false), lore);
    }
}
