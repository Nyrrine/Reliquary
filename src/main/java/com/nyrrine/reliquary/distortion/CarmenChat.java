package com.nyrrine.reliquary.distortion;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Gives a transformed player Carmen's voice in chat — and gives everyone else absolutely nothing.
 *
 * <p>Chat has exactly one renderer slot. Setting it doesn't add to what's there, it replaces it, and
 * the last plugin to write wins by event priority. The chat plugin claims that slot at HIGHEST, so
 * anything written at NORMAL or below is overwritten without a word. MONITOR runs after HIGHEST,
 * which is the only place a line of ours survives.
 *
 * <p>MONITOR is contractually "watch, don't touch", and this bends it on purpose — that is the trade
 * being made, written down so nobody thinks it was an accident. What keeps it honest is the guard
 * below: if the speaker isn't wearing Carmen, this listener returns having done nothing at all, and
 * the chat plugin's renderer stands exactly as it set it. The blast radius is one player, the one
 * who asked for it. Everyone else's chat cannot be affected by this class, because for them it never
 * does anything.
 *
 * <p>Cost of ever setting a renderer, known and accepted: the message ships as raw server chat
 * rather than a signed player message, which is what player reporting hangs off. That's the price of
 * speaking in someone else's voice, and it only applies to the transformed player's lines.
 */
final class CarmenChat implements Listener {

    private final CarmenForm form;

    CarmenChat(CarmenForm form) {
        this.form = form;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        // The one line that matters. Not in form -> not our business.
        if (!form.isCarmen(event.getPlayer())) return;

        // ViewerUnaware: the line reads the same to everyone, so it's built once instead of once per
        // recipient. On a busy server that's the difference between one render and a hundred.
        event.renderer(ChatRenderer.viewerUnaware(
                (source, sourceDisplayName, message) -> CarmenVoice.line(sourceDisplayName, message)));
    }
}
