package com.nyrrine.reliquary.distortion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * The voice itself — how a Carmen line reads in chat.
 *
 * <p>{@code [A beautiful voice] <Carmen> >> message}
 *
 * <p>Both ways of speaking as her share this one definition: the chat mask that catches a
 * transformed player's messages, and {@code /carmen}, which speaks a single line without
 * transforming at all. They must never drift apart — the whole point is that a player can't tell
 * which one she used.
 */
final class CarmenVoice {

    private CarmenVoice() {
    }

    /**
     * Builds the line. The name is passed in rather than hard-coded so the mask can render whatever
     * display name the speaker is actually wearing — if a transform is ever partial, the line tells
     * the truth about it instead of quietly printing "Carmen" over the top.
     */
    static Component line(Component name, Component message) {
        return Component.text()
                .append(Component.text("[A beautiful voice] ", NamedTextColor.LIGHT_PURPLE))
                .append(Component.text("<", NamedTextColor.DARK_GRAY))
                .append(name.colorIfAbsent(NamedTextColor.WHITE))
                .append(Component.text("> ", NamedTextColor.DARK_GRAY))
                .append(Component.text(">> ", NamedTextColor.LIGHT_PURPLE))
                .append(message.colorIfAbsent(NamedTextColor.WHITE))
                .build();
    }

    /** The line as she speaks it by name, with no transform behind it. */
    static Component line(String message) {
        return line(Component.text(CarmenSkin.NAME), Component.text(message));
    }
}
