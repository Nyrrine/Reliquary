package com.nyrrine.reliquary.extraction;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A driver for the extraction system that runs the whole pipeline through chat, before the polished
 * custom-block stations exist. It is the testbed for validating <b>feel and tuning</b>: brew a blank vial,
 * add reagents to it (the Censer), consult the lectern's what-if, distill (the Centrifuge), and pour it into
 * the Well to manifest, near-miss, or breach.
 *
 * <p>Reached via the short {@code /cogito <sub> ...} command (aliases {@code /ext}, {@code /co}) or the
 * equivalent {@code /reliquary ext <sub> ...}. Operates on the Cogito potion in the player's main hand.
 */
public final class ExtractionCommand {

    private final Reliquary plugin;

    public ExtractionCommand(Reliquary plugin) {
        this.plugin = plugin;
    }

    private static final TextColor GREEN = TextColor.color(0x74F066);
    private static final TextColor GREY  = NamedTextColor.GRAY;
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** The subcommands, in help/tab order. */
    private static final List<String> SUBS = List.of(
            "vial", "fuel", "reagents", "add", "assay", "lectern", "distill", "blend", "pour");

    /**
     * Dispatch an extraction subcommand. {@code a} is the sub-args where {@code a[0]} is the subcommand name
     * and {@code a[1]...} its parameters — so both {@code /cogito add x} and {@code /reliquary ext add x}
     * feed the same array shape here.
     */
    public void handle(CommandSender sender, String[] a) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Extraction is a player command.").color(NamedTextColor.RED));
            return;
        }
        if (a.length < 1) { help(player); return; }

        switch (a[0].toLowerCase()) {
            case "vial"     -> giveVial(player);
            case "fuel"     -> giveFuel(player, a);
            case "reagents" -> listReagents(player);
            case "add"      -> add(player, a);
            case "assay"    -> assay(player);
            case "lectern"  -> lectern(player, a);
            case "distill"  -> distill(player);
            case "blend"    -> blend(player);
            case "pour"     -> pour(player, a);
            default          -> help(player);
        }
    }

    /** Tab completion for the extraction subcommands. {@code a[0]} = subcommand, {@code a[1]} = its arg. */
    public List<String> tabComplete(String[] a) {
        if (a.length == 1) return filter(SUBS, a[0]);
        if (a.length == 2 && (a[0].equalsIgnoreCase("add") || a[0].equalsIgnoreCase("lectern"))) {
            return filter(reagentIds(), a[1]);
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("pour")) {
            return filter(weaponIds(), a[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase().startsWith(p)) out.add(o);
        return out;
    }

    // ---- item dispensers -----------------------------------------------------------

    private void giveVial(Player player) {
        ItemStack vial = Cogito.create(new PotState());
        // Land it in the main hand when free, so the very next `add` just works.
        if (player.getInventory().getItemInMainHand().getType().isAir()) {
            player.getInventory().setItemInMainHand(vial);
        } else {
            player.getInventory().addItem(vial);
        }
        player.sendMessage(msg("A blank Cogito vial forms.", GREEN));
    }

    private void giveFuel(Player player, String[] a) {
        int n = parseInt(a, 1, 16);
        player.getInventory().addItem(Enkephalin.create(n));
        player.sendMessage(msg("Drew " + n + " Enkephalin.", GREEN));
    }

    private void listReagents(Player player) {
        player.sendMessage(msg("Reagents (" + Reagents.count() + "):", NamedTextColor.WHITE));
        for (Reagent r : Reagents.all()) {
            player.sendMessage(Component.text("  " + r.id(), GREY)
                    .append(Component.text("  " + r.display(), NamedTextColor.WHITE))
                    .append(Component.text("  [" + r.tier() + "]", FAINT)));
        }
    }

    // ---- the Censer: add a reagent -------------------------------------------------

    private void add(Player player, String[] a) {
        if (a.length < 2) { player.sendMessage(msg("Usage: /cogito add <reagentId>", GREY)); return; }
        Reagent r = Reagents.byId(a[1].toLowerCase());
        if (r == null) { player.sendMessage(msg("No such reagent: " + a[1], NamedTextColor.RED)); return; }

        Vial v = locateVial(player);
        if (v == null) { noVial(player); return; }
        PotState st = v.state();

        if (st.titer() >= Engine.VIAL_CAP && Engine.addReagent(st.copy(), r, new java.util.Random(0L)).full()) {
            player.sendMessage(msg(String.format(
                    "The vial is full (%.0f titer cap). Distill it, or blend several vials for more volume.",
                    Engine.VIAL_CAP), NamedTextColor.RED));
            return;
        }

        Engine.AddResult result = Engine.addReagent(st, r, ThreadLocalRandom.current());
        writeVial(player, v);

        if (result.breached()) {
            player.sendMessage(msg("The pot RUPTURES — stability hit zero. Pour now and it breaches.",
                    NamedTextColor.RED));
        } else if (result.stepFailed()) {
            player.sendMessage(msg("Your hand slips — the " + r.display() + " is wasted. Steady, and retry.",
                    NamedTextColor.GOLD));
        } else {
            player.sendMessage(msg("Added " + r.display() + ".", GREEN));
        }
        showGauges(player, st);
        showNearest(player, st);
    }

    // ---- assay + lectern -----------------------------------------------------------

    private void assay(Player player) {
        PotState st = held(player);
        if (st == null) return;
        player.sendMessage(msg("Assay:", NamedTextColor.WHITE));
        player.sendMessage(assayLine(st.profile()));
        showGauges(player, st);
        showNearest(player, st);
    }

    /**
     * The status line under the gauges: a green "manifestable now" cue for anything already reachable (so you
     * know when to stop and pour), plus the closest weapon by shape and what it still needs.
     */
    private void showNearest(Player player, PotState st) {
        if (st.isBlank()) return;

        WeaponSpec reach = nearest(st); // best weapon that already clears the grade floor + volume gate
        if (reach != null) {
            double m = reach.matchOf(st.profile());
            player.sendMessage(Component.text("  READY: ", GREEN)
                    .append(Component.text(reach.display() + " (" + reach.grade().display() + ")  "
                            + pct(m) + " match", GREEN))
                    .append(Component.text("  -> /cogito pour " + reach.id(), FAINT)));
        }

        WeaponSpec near = nearestAny(st); // closest by shape, gates ignored — the aspiration
        if (near != null && near != reach) {
            double m = near.matchOf(st.profile());
            List<String> lacking = new ArrayList<>();
            if (!near.grade().minCogito().atMost(st.grade())) {
                lacking.add("grade >=" + near.grade().minCogito().display());
            }
            if (st.titer() < near.grade().minVolume()) {
                lacking.add(String.format("volume >=%.0f", near.grade().minVolume()));
            }
            Component line = Component.text("  Closest shape: ", FAINT)
                    .append(Component.text(near.display() + " (" + near.grade().display() + ")  "
                            + pct(m) + " match", FAINT));
            if (!lacking.isEmpty()) line = line.append(Component.text("  needs " + String.join(", ", lacking), FAINT));
            player.sendMessage(line);
        }
    }

    /** The closest weapon purely by composition, ignoring the grade/volume gates. */
    private WeaponSpec nearestAny(PotState st) {
        WeaponSpec best = null;
        double bestMatch = -1;
        for (WeaponSpec w : WeaponSignatures.all()) {
            double mm = w.matchOf(st.profile());
            if (mm > bestMatch) { bestMatch = mm; best = w; }
        }
        return best;
    }

    private void lectern(Player player, String[] a) {
        if (a.length < 2) { player.sendMessage(msg("Usage: /cogito lectern <reagentId>", GREY)); return; }
        Reagent r = Reagents.byId(a[1].toLowerCase());
        if (r == null) { player.sendMessage(msg("No such reagent: " + a[1], NamedTextColor.RED)); return; }
        PotState st = held(player);
        if (st == null) return;

        // Project onto a copy — the lectern never touches the real pot, and never rolls a failure.
        PotState projected = st.copy();
        Engine.AddResult ignored = Engine.addReagent(projected, r, new java.util.Random(0L));

        player.sendMessage(msg("Lectern — if you add " + r.display() + ":", NamedTextColor.WHITE));
        player.sendMessage(assayLine(projected.profile()));
        showGauges(player, projected);
        WeaponSpec near = nearest(projected);
        if (near != null) {
            player.sendMessage(Component.text("  Nearest target: ", FAINT)
                    .append(Component.text(near.display(), NamedTextColor.WHITE))
                    .append(Component.text(String.format("  %.0f%% match", near.matchOf(projected.profile()) * 100), FAINT)));
        }
    }

    // ---- the Centrifuge: distill ---------------------------------------------------

    private void distill(Player player) {
        Vial v = locateVial(player);
        if (v == null) { noVial(player); return; }
        PotState st = v.state();
        double before = st.noise();
        Engine.distill(st);
        writeVial(player, v);
        player.sendMessage(msg(String.format("Distilled — noise %.1f -> %.1f (pass %d).",
                before, st.noise(), st.distillPasses()), GREEN));
        showGauges(player, st);
    }

    // ---- the Manifold: blend -------------------------------------------------------

    private void blend(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        List<PotState> pots = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            PotState st = Cogito.read(contents[i]);
            if (st == null || st.isBlank()) continue;
            for (int c = 0; c < contents[i].getAmount(); c++) pots.add(st.copy());
            slots.add(i);
        }
        if (pots.size() < 2) {
            player.sendMessage(msg("Carry at least two charged vials to blend (the stock-solution route).",
                    NamedTextColor.RED));
            return;
        }
        PotState blended = Engine.blend(pots);
        for (int s : slots) player.getInventory().setItem(s, null);
        player.getInventory().addItem(Cogito.create(blended));
        player.sendMessage(msg("Blended " + pots.size() + " vials at the Manifold.", GREEN));
        player.sendMessage(assayLine(blended.profile()));
        showGauges(player, blended);
    }

    // ---- the Well: pour ------------------------------------------------------------

    private void pour(Player player, String[] a) {
        Vial v = locateVial(player);
        if (v == null) { noVial(player); return; }
        PotState st = v.state();
        if (st.isBlank()) { player.sendMessage(msg("The vial is empty.", NamedTextColor.RED)); return; }

        String catalyst = a.length >= 2 ? a[1].toLowerCase() : null;
        WellRoll.Result r = WellRoll.resolve(st, catalyst, 0.0, ThreadLocalRandom.current());

        // Too thin/crude to reach ANY weapon (nothing cleared the grade floor + volume gate). Rather than
        // a cryptic breach, explain the shortfall and keep the vial — this is the testbed being helpful.
        if (r.outcome() == WellRoll.Outcome.BREACH && r.weapon() == null) {
            player.sendMessage(msg(thinPourReason(st, catalyst), NamedTextColor.RED));
            return;
        }

        // The pour consumes one vial from wherever it was found.
        ItemStack item = v.item();
        item.setAmount(item.getAmount() - 1);
        ItemStack remainder = item.getAmount() <= 0 ? null : item;
        if (v.slot() < 0) player.getInventory().setItemInMainHand(remainder);
        else player.getInventory().setItem(v.slot(), remainder);

        switch (r.outcome()) {
            case MANIFEST -> onManifest(player, r);
            case NEAR_MISS -> player.sendMessage(msg("Near-miss — the Well settles on "
                    + (r.weapon() != null ? r.weapon().display() : "nothing")
                    + " (" + pct(r.match()) + " match). No weapon this time.", NamedTextColor.YELLOW));
            case BREACH -> player.sendMessage(msg(String.format(
                    "BREACH — the Well ruptures. A %s-class Abnormality would climb out. "
                    + "(your pour: %s match, %s, stability %d) (Combat: coming soon.)",
                    r.aimedGrade().display(), pct(r.match()), r.cogitoGrade().display(),
                    Math.round(st.stability())), NamedTextColor.RED));
        }
    }

    private void onManifest(Player player, WellRoll.Result r) {
        WeaponSpec spec = r.weapon();
        player.sendMessage(msg((r.certified() ? "CERTIFIED manifest — " : "Manifest — ")
                + spec.display() + " climbs out of the Well.", GREEN));

        Weapon weapon = plugin.weapons().get(spec.id());
        if (weapon == null) {
            player.sendMessage(msg("(No item wired for '" + spec.id() + "' yet.)", FAINT));
            return;
        }
        ItemStack item = weapon.createItem();
        stampAttribution(item, player.getName(), r.cogitoGrade(), r.certified() ? 100.0 : r.purity());
        item = plugin.tracker().register(item, weapon.id(), player.getName() + " (extracted)");
        player.getInventory().addItem(item);
        plugin.weapons().engage(weapon, player.getUniqueId());
    }

    /** Append the §17 attribution stamp — "Extracted by X" + grade — to the bottom of a weapon's lore. */
    private void stampAttribution(ItemStack item, String extractor, Grade grade, double purity) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<Component> lore = meta.lore();
        List<Component> out = lore != null ? new ArrayList<>(lore) : new ArrayList<>();
        out.add(Component.empty());
        out.add(Component.text("Extracted by " + extractor, FAINT).decoration(TextDecoration.ITALIC, true));
        out.add(Component.text(String.format("%s grade · %.1f%% purity", grade.display(), purity), grade.color())
                .decoration(TextDecoration.ITALIC, true));
        meta.lore(out);
        item.setItemMeta(meta);
    }

    // ---- shared helpers ------------------------------------------------------------

    private PotState held(Player player) {
        Vial v = locateVial(player);
        if (v == null) { noVial(player); return null; }
        return v.state();
    }

    /** A located vial: its stack, the slot it lives in ({@code -1} = main hand), and its decoded state. */
    private record Vial(ItemStack item, int slot, PotState state) {}

    /** The Cogito vial in the main hand, else the first one anywhere in the inventory, else {@code null}. */
    private Vial locateVial(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        PotState st = Cogito.read(main);
        if (st != null) return new Vial(main, -1, st);
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            PotState s = Cogito.read(contents[i]);
            if (s != null) return new Vial(contents[i], i, s);
        }
        return null;
    }

    /** Persist a mutated vial back to wherever it lives. */
    private void writeVial(Player player, Vial v) {
        Cogito.write(v.item(), v.state());
        if (v.slot() < 0) player.getInventory().setItemInMainHand(v.item());
        else player.getInventory().setItem(v.slot(), v.item());
    }

    private void noVial(Player player) {
        player.sendMessage(msg("No Cogito vial found — run /cogito vial first.", NamedTextColor.RED));
    }

    /** Why a pour couldn't reach any weapon, with the concrete shortfall, so testers know what to fix. */
    private String thinPourReason(PotState st, String catalyst) {
        WeaponSpec tgt = catalyst != null ? WeaponSignatures.byId(catalyst) : null;
        double minVol = tgt != null ? tgt.grade().minVolume() : EgoGrade.ZAYIN.minVolume();
        Grade minGrade = tgt != null ? tgt.grade().minCogito() : EgoGrade.ZAYIN.minCogito();
        String who = tgt != null ? tgt.display() : "anything (even a ZAYIN)";
        return String.format(
                "Too weak to manifest %s — you have %.0f volume at %.1f%% (%s); it needs >=%.0f volume and "
                + ">=%s grade. Add much more reagent, or blend vials for volume. (Vial kept.)",
                who, st.titer(), st.purity(), st.grade().display(), minVol, minGrade.display());
    }

    private WeaponSpec nearest(PotState st) {
        WeaponSpec best = null;
        double bestMatch = -1;
        Grade g = st.grade();
        double titer = st.titer();
        for (WeaponSpec w : WeaponSignatures.all()) {
            if (!w.reachableBy(g, titer)) continue;
            double m = w.matchOf(st.profile());
            if (m > bestMatch) { bestMatch = m; best = w; }
        }
        return best;
    }

    private Component assayLine(SinProfile p) {
        if (p.isEmpty()) return Component.text("  (blank)", FAINT);
        Component line = Component.text("  ", GREY);
        boolean first = true;
        for (Sin s : Sin.values()) {
            double v = p.get(s);
            if (v < 0.5) continue;
            if (!first) line = line.append(Component.text(" / ", FAINT));
            line = line.append(Component.text(s.display() + " " + Math.round(v), s.color()));
            first = false;
        }
        return line;
    }

    private void showGauges(Player player, PotState st) {
        Grade g = st.grade();
        player.sendMessage(Component.text("  Purity ", GREY)
                .append(Component.text(String.format("%.1f%% ", st.purity()), NamedTextColor.WHITE))
                .append(Component.text("(" + g.display() + ")", g.color()))
                .append(Component.text("   Stability ", GREY))
                .append(Component.text(Math.round(st.stability()) + "/100", stabColor(st.stability())))
                .append(Component.text("   Volume ", GREY))
                .append(Component.text(String.valueOf(Math.round(st.titer())), NamedTextColor.WHITE)));
    }

    private TextColor stabColor(double stability) {
        if (stability >= 60) return NamedTextColor.GREEN;
        if (stability >= 30) return NamedTextColor.GOLD;
        return NamedTextColor.RED;
    }

    private void help(Player player) {
        player.sendMessage(msg("Cogito extraction (testbed) — /cogito ...  (tab-completes)", NamedTextColor.WHITE));
        line(player, "vial", "brew a blank Cogito vial (into your hand)");
        line(player, "fuel [n]", "draw Enkephalin");
        line(player, "reagents", "list reagent ids");
        line(player, "add <id>", "add a reagent to the held vial (the Censer)");
        line(player, "assay", "reveal the held vial's composition");
        line(player, "lectern <id>", "what-if: project a reagent without committing");
        line(player, "distill", "run the held vial through the Centrifuge");
        line(player, "blend", "blend all charged vials you carry (the Manifold)");
        line(player, "pour [catalystId]", "pour into the Well — manifest / near-miss / breach");
    }

    private void line(Player player, String cmd, String desc) {
        player.sendMessage(Component.text("  /cogito " + cmd, GREY)
                .append(Component.text("  — " + desc, FAINT)));
    }

    private static Component msg(String text, TextColor color) {
        return Component.text(text, color);
    }

    private static String pct(double frac) { return Math.round(frac * 100) + "%"; }

    private static int parseInt(String[] args, int idx, int def) {
        if (args.length <= idx) return def;
        try { return Math.max(1, Integer.parseInt(args[idx])); } catch (NumberFormatException e) { return def; }
    }

    /** Reagent + weapon ids for tab-completion. */
    public static List<String> reagentIds() {
        List<String> ids = new ArrayList<>();
        for (Reagent r : Reagents.all()) ids.add(r.id());
        return ids;
    }

    public static List<String> weaponIds() {
        List<String> ids = new ArrayList<>();
        for (WeaponSpec w : WeaponSignatures.all()) ids.add(w.id());
        return ids;
    }
}
