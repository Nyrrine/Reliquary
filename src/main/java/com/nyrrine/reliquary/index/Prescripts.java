package com.nyrrine.reliquary.index;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The preset pool a randomized prescript is drawn from — the Index's whole content, and the thing most worth
 * arguing about.
 *
 * <p>The register is deliberately mundane. Vanilla items, small numbers, and errands whose comedy is their
 * specificity and their pointless social intrusion: not <i>slay a dragon</i> but <i>eat 16 kelp near someone
 * who did not ask you to</i>. A grand institution formally instructing you to go be a mild nuisance is the
 * joke, and every line here is written to that register rather than to a difficulty curve.
 *
 * <p><b>Nothing here is machine-checked.</b> A Weaver reads the line and rules on it, which is what lets the
 * pool contain instructions no event could ever detect — <i>without acknowledging them</i>, <i>saying
 * nothing</i>, <i>without comment</i>. Those clauses are the best part of the pool and they are only
 * affordable because a person is the judge. If a tracker ever had to see them, every line would have to be
 * rewritten to what a tracker can see, and the pool would collapse into a list of vanilla advancements.
 *
 * <p>Kept as a Java constant, matching {@code Reagents} / {@code EgoModels} / {@code WeaponSignatures}. The
 * trade-off is stated plainly in the sprint contract: culling an entry is a rebuild, not a file edit.
 */
public final class Prescripts {

    private Prescripts() {}

    /**
     * One drawable line. {@code id} is stable — it is written into a recipient's record, so renaming one
     * orphans any prescript already issued from it. Add freely; rename never.
     */
    public record Entry(String id, String text) {}

    /**
     * The pool, in no meaningful order. The first four are Nyrrine's own, verbatim — they are the register
     * every other line is written to match, so they lead.
     */
    private static final List<Entry> POOL = List.of(
            // — hers, verbatim —
            new Entry("mud_stack_throw",      "Mine a stack of mud and throw it to a player today"),
            new Entry("harming_potion",       "Hurt any player using a potion of harming"),

            // "dried kelp", not "kelp": raw kelp is not edible. Foods on 26.1.2 defines DRIED_KELP and no
            // KELP, so the original wording asked for something no recipient could carry out however willing.
            // Ruled on rather than assumed — the line was Nyrrine's, so the correction was hers to make.
            new Entry("kelp_vicinity",        "Eat 16 dried kelp in the vicinity of a player"),
            new Entry("harming_then_kelp",    "Hurt any player using a potion of harming and then eat 16 "
                                            + "dried kelp 3 blocks near any player"),

            // — drafted to match —
            new Entry("golden_apple_full",    "Eat a golden apple at full health, for no reason at all"),
            new Entry("pig_past_player",      "Ride a pig past a player without acknowledging them"),
            new Entry("composter_regret",     "Feed 64 items into a composter until it is quite full of your regret"),
            new Entry("hoe_at_feet",          "Drop a wooden hoe at a player's feet and leave it there"),
            new Entry("berries_crouched",     "Eat 8 sweet berries while crouched beside a player"),
            new Entry("rotten_flesh_gift",    "Give a player a single piece of rotten flesh, and nothing else"),
            new Entry("three_eggs",           "Throw 3 eggs at the same player"),
            new Entry("slowness_no_watch",    "Splash a player with a potion of slowness and do not stay to watch"),
            new Entry("cows_witnessed",       "Breed two cows in the vicinity of a player"),
            new Entry("villager_five_trades", "Trade with the same villager 5 times without walking away"),
            new Entry("dirt_diamond_shovel",  "Break 64 dirt with a diamond shovel, and use nothing else"),
            new Entry("fall_death_watched",   "Die of fall damage from at least 20 blocks while a player watches"),
            new Entry("pearl_two_blocks",     "Throw an ender pearl and travel no more than 2 blocks with it"),
            new Entry("bell_ten_times",       "Ring a bell 10 times within earshot of a player"),
            new Entry("bed_beside_bed",       "Sleep in a bed placed within 5 blocks of another player's bed"),

            // — chained: two ordered halves, one sentence. Free to a Weaver; the most expensive thing in the
            //   system to anything else. —
            new Entry("sheep_pink_herd",      "Dye a sheep pink and then return it to its herd without comment"),
            new Entry("chicken_then_milk",    "Eat a raw chicken in front of a player and then drink milk to undo it"),
            new Entry("cobble_there_back",    "Mine 64 cobblestone and then place all 64 of it back down"),
            new Entry("fish_unasked",         "Catch 3 fish and then give every one of them to a player who did not ask"),
            new Entry("weakness_witnessed",   "Brew a potion of weakness and then drink it in front of a player"),
            new Entry("enchant_discard",      "Enchant a wooden shovel and then drop it on the ground and walk away"),
            new Entry("shear_give_nearest",   "Shear a sheep and then give the wool to whichever player stands nearest"),
            new Entry("trade_break_station",  "Trade with a villager and then break its workstation in front of it"),
            new Entry("cake_one_slice",       "Place a cake somewhere public and then eat exactly one slice of it"),
            new Entry("sticks_in_water",      "Craft 16 sticks and then drop every one of them into water"),
            new Entry("invisible_silent",     "Drink a potion of invisibility and stand beside a player for "
                                            + "30 seconds, saying nothing"),
            new Entry("book_lectern",         "Write a book and then place it in a lectern where strangers will read it"),
            new Entry("stew_unexplained",     "Milk a mooshroom and give the stew to a player without saying "
                                            + "what is in it"),
            new Entry("pumpkin_till_dusk",    "Carve a pumpkin and then wear it on your head until the sun goes down"));

    /** Every entry, in declaration order. */
    public static List<Entry> all() {
        return POOL;
    }

    /** How many lines the register holds. */
    public static int size() {
        return POOL.size();
    }

    /** The entry with this id, or {@code null} — a prescript issued from a since-renamed id reads as custom. */
    public static Entry byId(String id) {
        for (Entry e : POOL) if (e.id().equals(id)) return e;
        return null;
    }

    /**
     * A line at random.
     *
     * <p>Flat weighting, and no memory of what a player has already been handed: repeats are not a bug here.
     * The Index issuing you the same errand twice is funnier than the Index keeping careful track, and a
     * Weaver who dislikes a draw can simply withdraw it and draw again.
     */
    public static Entry draw() {
        return POOL.get(ThreadLocalRandom.current().nextInt(POOL.size()));
    }
}
