package com.nyrrine.reliquary.core;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * Landing a strike on a body that is still flinching from the last one.
 *
 * <p>A hurt body is briefly untouchable. Vanilla stamps a countdown on every blow it accepts and, for the
 * first half of it, refuses anything that is not strictly bigger than the blow that started it: an equal
 * follow-up is <b>dropped whole</b> — not reduced, dropped — and a larger one lands only its excess. Any
 * relic that strikes twice off one swing, or puts four blades through one body in one tick, walks into this
 * head-on: the extra strikes disappear with no error, no log line and no damage. <b>Sixteen weapons in this
 * vault each found that out and each answered it the same way</b> — {@code setNoDamageTicks(0)} before their
 * own {@code damage()}, under sixteen private comments all saying some version of <i>or this never lands</i>.
 *
 * <p>Clearing is half the job, and by 2026-07-17 <b>exactly one of the sixteen had found the other half.</b>
 * Your {@code damage()} does not merely spend the victim's immunity — it <b>re-stamps</b> it, a full fresh
 * countdown, and with it a fresh bar that the next blow must clear. Left standing that does two things nobody
 * asked for. It <b>taxes your own next swing</b>: Sword of Tears' Double Tag is algebraically zero — 7+2+5
 * with the tag, 7+7 without, at every level of Sharpness — because the tag is paid for out of the swing
 * behind it. And it hands the victim <b>partial immunity against everyone else</b>, so a flurry pins a shared
 * boss's clock and quietly eats your allies' hits. Gaze alone puts the countdown back where it found it.
 * Fifteen authors found the clear because a strike that deals nothing is loud; none found the restore,
 * because a strike that quietly bills someone else is silent. When fifteen strangers each derive half a
 * function and the sixteenth derives the rest, the function was always the framework's job. So it lives here
 * now.
 *
 * <p>The restore needs no constant and no bookkeeping: <b>the countdown already holds the answer.</b> Read it
 * before clearing it and you have exactly what the victim had left — a body struck two ticks ago reads 18,
 * which is the 20-tick stamp minus the two ticks it has already served. Put that number back afterwards and
 * the clock carries on decaying as though your strike never touched it. One nuance carries the whole
 * contract: <b>a clock already at zero means nothing was displaced</b>, so nothing is restored and the strike
 * keeps the ordinary stamp its own {@code damage()} just earned. A strike that rides alongside another blow
 * stays invisible to the clock; a strike that <i>is</i> the blow is treated as one.
 *
 * <p>Knockback is not this class's business. Several relics undo it around their own follow-ups — a second
 * cut, not a second shove — but plenty want the shove, so callers capture and restore velocity themselves.
 * Nor is re-entrancy: damage dealt here reaches the manager's dispatch like any other, and a relic whose
 * {@code onHit} must not see its own strike still fences it.
 */
public final class HurtClock {

    private HurtClock() {}

    /**
     * Land {@code amount} on {@code victim} from {@code source}, defeating the victim's hurt-immunity for
     * this strike alone and leaving their countdown exactly as it was found.
     *
     * <p>Returns false — having done nothing — if the victim is already dead or gone, which is what makes a
     * burst safe to write as a loop: each instance reports whether there was still a body to strike, so the
     * blade that kills ends the volley instead of a corpse soaking up the rest.
     *
     * <p>A {@code source} equal to the victim, or null, drops through to the sourceless
     * {@link LivingEntity#damage(double)} — being recorded as your own attacker is never what the caller
     * meant.
     */
    public static boolean strike(LivingEntity victim, double amount, Entity source) {
        if (victim == null || victim.isDead() || !victim.isValid()) return false;

        // Whatever the victim has left to serve. Read before the clear — afterwards it is gone, and after the
        // damage it reads as our own fresh stamp instead of theirs.
        int owed = victim.getNoDamageTicks();

        try {
            victim.setNoDamageTicks(0); // or the strike is dropped whole, in silence
            if (source == null || source.equals(victim)) {
                victim.damage(amount);
            } else {
                victim.damage(amount, source);
            }
        } finally {
            // Put back only what was displaced. A zero clock displaced nothing: this strike was the blow, not
            // a rider on one, so it keeps the stamp damage() just gave it rather than being stripped of it.
            if (owed > 0) victim.setNoDamageTicks(owed);
        }
        return true;
    }

    /** {@link #strike(LivingEntity, double, Entity)} with no attacker to credit — environmental damage. */
    public static boolean strike(LivingEntity victim, double amount) {
        return strike(victim, amount, null);
    }
}
