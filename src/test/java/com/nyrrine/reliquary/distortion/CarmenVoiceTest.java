package com.nyrrine.reliquary.distortion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shape of the line. Chat itself needs a live server, but the words don't — and the words are the
 * part that has to match between speaking as her and being her.
 */
class CarmenVoiceTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void readsAsHerVoice() {
        assertEquals("[A beautiful voice] <Carmen> >> hello",
                plain(CarmenVoice.line("hello")));
    }

    @Test
    void speakingAsHerMatchesBeingHer() {
        // /carmen and the chat mask must be indistinguishable — if they ever drift, the trick is off.
        Component masked = CarmenVoice.line(Component.text("Carmen"), Component.text("hello"));
        assertEquals(plain(CarmenVoice.line("hello")), plain(masked));
    }

    @Test
    void keepsTheMessageWhole() {
        // Chat arrives as a component, not a string; nothing about it should be flattened or lost.
        Component message = Component.text("come ").append(Component.text("closer", NamedTextColor.RED));
        assertEquals("[A beautiful voice] <Carmen> >> come closer",
                plain(CarmenVoice.line(Component.text("Carmen"), message)));
    }

    @Test
    void wearsWhateverNameTheSpeakerHas() {
        // The mask renders the display name it's handed rather than assuming "Carmen", so a partial
        // transform shows up as itself instead of being papered over.
        assertEquals("[A beautiful voice] <Ame> >> hi",
                plain(CarmenVoice.line(Component.text("Ame"), Component.text("hi"))));
    }
}
