package com.nyrrine.reliquary.distortion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The voice itself — how a Carmen line reads in chat.
 *
 * <p>{@code [A beautiful voice] <Carmen> >> message}
 *
 * <p>Both ways of speaking as her share this one definition: the chat mask that catches a
 * transformed player's messages, and {@code /carmen}, which speaks a single line without
 * transforming at all. They must never drift apart — the whole point is that a player can't tell
 * which one she used.
 *
 * <p>The palette lives at the top so the whole voice can be retuned from one place.
 */
final class CarmenVoice {

    // Blood on ash. Deliberately dark and low-contrast — she should read as something speaking
    // through the chat rather than a name tag attached to it. Nothing here is bright: bright is
    // loud, and loud isn't the same as ominous.

    /** The tag and the marker — dried blood, dark enough to feel like it's receding. */
    private static final TextColor VOICE = TextColor.color(0x8B0000);

    /** The angle brackets — nearly black, so her name floats free of them. */
    private static final TextColor BRACKET = TextColor.color(0x3D0000);

    /** Her name — the one warm thing in the line, and the only crimson that carries. */
    private static final TextColor NAME = TextColor.color(0xC41E3A);

    /** What she says — bone, not white. Off enough to feel wrong beside ordinary chat. */
    private static final TextColor SPOKEN = TextColor.color(0xCFC5C5);

    private CarmenVoice() {
    }

    /**
     * Builds the line. The name is passed in rather than hard-coded so the mask can render whatever
     * display name the speaker is actually wearing — if a transform is ever partial, the line tells
     * the truth about it instead of quietly printing "Carmen" over the top.
     */
    static Component line(Component name, Component message) {
        return Component.text()
                .append(Component.text("[A beautiful voice] ", VOICE).decorate(TextDecoration.ITALIC))
                .append(Component.text("<", BRACKET))
                .append(name.colorIfAbsent(NAME))
                .append(Component.text("> ", BRACKET))
                .append(Component.text(">> ", VOICE))
                // ifAbsent, not outright: a message that already carries its own colour or format
                // keeps it. We're dressing what she says, not overruling it.
                .append(message.colorIfAbsent(SPOKEN)
                        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.TRUE))
                .build();
    }

    /** The line as she speaks it by name, with no transform behind it. */
    static Component line(String message) {
        return line(Component.text(CarmenSkin.NAME), Component.text(message));
    }
}
