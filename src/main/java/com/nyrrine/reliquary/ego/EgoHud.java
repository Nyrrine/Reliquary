package com.nyrrine.reliquary.ego;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Shared action-bar vocabulary for the E.G.O weapons.
 *
 * <p>Every E.G.O weapon speaks to the wielder through the same grammar the relics use (Arayashiki's
 * charge bar, Lævateinn's heat gauge): a bracketed segmented bar, an optional state word, and a small
 * count or cooldown. This keeps the whole roster feeling like one kit instead of two dozen bespoke HUDs.
 *
 * <p><b>Rules of the house:</b> never show raw milliseconds to a player — cooldowns read in whole
 * seconds ({@link #cooldown}); ammo reads as a count ({@link #ammo}); charges read as pips
 * ({@link #pips}). All components come back with italics off, ready to hand straight to
 * {@code player.sendActionBar(...)}.
 */
public final class EgoHud {

    private EgoHud() {}

    private static final TextColor FRAME = NamedTextColor.DARK_GRAY; // brackets + empty segments
    private static final String SEG = "▮";                       // ▮ — the same glyph Arayashiki uses
    public static final int SEGMENTS = 10;

    private static Component plain(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** A bracketed segmented gauge {@code [▮▮▮▮▮▮▮▮▮▮]} filled to {@code frac}, then an optional label. */
    public static Component gauge(TextColor fill, double frac, int segments, Component label) {
        double f = Math.max(0.0, Math.min(1.0, frac));
        int filled = (int) Math.round(f * segments);
        Component bar = plain("[", FRAME)
                .append(plain(SEG.repeat(filled), fill))
                .append(plain(SEG.repeat(segments - filled), FRAME))
                .append(plain("]", FRAME));
        if (label != null) bar = bar.append(plain("  ", FRAME)).append(label);
        return bar.decoration(TextDecoration.ITALIC, false);
    }

    /** Ten-segment gauge with a label. */
    public static Component gauge(TextColor fill, double frac, Component label) {
        return gauge(fill, frac, SEGMENTS, label);
    }

    /**
     * An ammo readout: {@code [▮▮▮▮▮▮▮▮▮▮]  Name  cur/max}. The bar drains with the magazine and the
     * fill goes red when empty. Use for anything with a live shot count (Beak, Solemn Lament, …).
     */
    public static Component ammo(TextColor fill, String name, int cur, int max) {
        double frac = max <= 0 ? 0.0 : (double) cur / max;
        Component label = plain(name, cur <= 0 ? NamedTextColor.RED : fill)
                .append(plain("  " + cur + "/" + max, NamedTextColor.GRAY));
        return gauge(cur <= 0 ? NamedTextColor.RED : fill, frac, label);
    }

    /** Discrete charges as diamonds: {@code Dash ◆ ◆ ◇}. Mirrors Arayashiki's dash pips. */
    public static Component pips(String label, TextColor fill, int filled, int total) {
        Component c = plain(label + " ", NamedTextColor.GRAY);
        for (int i = 0; i < total; i++) {
            c = c.append(plain(i < filled ? "◆ " : "◇ ",  // ◆ / ◇
                    i < filled ? fill : FRAME));
        }
        return c.decoration(TextDecoration.ITALIC, false);
    }

    /** A cooldown, always in whole seconds (rounded up), never milliseconds: {@code Slam — 3s}. */
    public static Component cooldown(String name, long remainingMs, TextColor color) {
        long s = Math.max(1L, (remainingMs + 999L) / 1000L);
        return plain(name + " — " + s + "s", color);
    }

    /** The "ready" counterpart to {@link #cooldown}: {@code Slam — ready}. */
    public static Component ready(String name, TextColor color) {
        return plain(name + " — ready", color);
    }

    /** A bare status line (no bar) — for one-off cues like "No one to play with…". */
    public static Component status(String text, TextColor color) {
        return plain(text, color);
    }

    /** The gap between two state readouts sharing one line. */
    private static final String GAP = "   ";

    /**
     * Compose several state readouts into ONE action-bar line, in the order given, separated by a small gap.
     * Null parts are skipped, so a weapon passes every state it might show and lets the absent ones drop out.
     *
     * <p>This is the whole standard: instead of one cooldown flashing in and replacing another as the player
     * acts, a weapon builds its complete readout every tick — all charges, all cooldowns, at once — and hands
     * it to {@code sendActionBar}. Read {@link #row}'s arguments top-to-bottom and that is exactly what the
     * player sees, always in the same order, so the HUD never rearranges itself under them.
     */
    public static Component row(Component... parts) {
        Component line = null;
        for (Component part : parts) {
            if (part == null) continue;
            line = (line == null) ? part : line.append(plain(GAP, FRAME)).append(part);
        }
        return (line == null ? Component.empty() : line).decoration(TextDecoration.ITALIC, false);
    }
}
