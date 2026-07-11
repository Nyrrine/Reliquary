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
 * <p>Lives under the operator-only {@code /reliquary ext ...} subcommand. Operates on the Cogito potion in
 * the player's main hand.
 */
public final class ExtractionCommand {

    private final Reliquary plugin;

    public ExtractionCommand(Reliquary plugin) {
        this.plugin = plugin;
    }

    private static final TextColor GREEN = TextColor.color(0x74F066);
    private static final TextColor GREY  = NamedTextColor.GRAY;
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    /** Dispatch {@code /reliquary ext <sub> ...}. {@code args} is the full command args (args[0] == "ext"). */
    public void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Extraction is a player command.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2) { help(player); return; }

        switch (args[1].toLowerCase()) {
            case "vial"     -> giveVial(player);
            case "fuel"     -> giveFuel(player, args);
            case "reagents" -> listReagents(player);
            case "add"      -> add(player, args);
            case "assay"    -> assay(player);
            case "lectern"  -> lectern(player, args);
            case "distill"  -> distill(player);
            case "pour"     -> pour(player, args);
            default          -> help(player);
        }
    }

    // ---- item dispensers -----------------------------------------------------------

    private void giveVial(Player player) {
        player.getInventory().addItem(Cogito.create(new PotState()));
        player.sendMessage(msg("A blank Cogito vial forms in your hand.", GREEN));
    }

    private void giveFuel(Player player, String[] args) {
        int n = parseInt(args, 2, 16);
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

    private void add(Player player, String[] args) {
        if (args.length < 3) { player.sendMessage(msg("Usage: /reliquary ext add <reagentId>", GREY)); return; }
        Reagent r = Reagents.byId(args[2].toLowerCase());
        if (r == null) { player.sendMessage(msg("No such reagent: " + args[2], NamedTextColor.RED)); return; }

        ItemStack held = player.getInventory().getItemInMainHand();
        PotState st = Cogito.read(held);
        if (st == null) { player.sendMessage(msg("Hold a Cogito vial to work it.", NamedTextColor.RED)); return; }

        Engine.AddResult result = Engine.addReagent(st, r, ThreadLocalRandom.current());
        Cogito.write(held, st);
        player.getInventory().setItemInMainHand(held);

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
    }

    // ---- assay + lectern -----------------------------------------------------------

    private void assay(Player player) {
        PotState st = held(player);
        if (st == null) return;
        player.sendMessage(msg("Assay:", NamedTextColor.WHITE));
        player.sendMessage(assayLine(st.profile()));
        showGauges(player, st);
    }

    private void lectern(Player player, String[] args) {
        if (args.length < 3) { player.sendMessage(msg("Usage: /reliquary ext lectern <reagentId>", GREY)); return; }
        Reagent r = Reagents.byId(args[2].toLowerCase());
        if (r == null) { player.sendMessage(msg("No such reagent: " + args[2], NamedTextColor.RED)); return; }
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
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        PotState st = Cogito.read(heldItem);
        if (st == null) { player.sendMessage(msg("Hold a Cogito vial to distill.", NamedTextColor.RED)); return; }
        double before = st.noise();
        Engine.distill(st);
        Cogito.write(heldItem, st);
        player.getInventory().setItemInMainHand(heldItem);
        player.sendMessage(msg(String.format("Distilled — noise %.1f -> %.1f (pass %d).",
                before, st.noise(), st.distillPasses()), GREEN));
        showGauges(player, st);
    }

    // ---- the Well: pour ------------------------------------------------------------

    private void pour(Player player, String[] args) {
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        PotState st = Cogito.read(heldItem);
        if (st == null) { player.sendMessage(msg("Hold a Cogito vial to pour.", NamedTextColor.RED)); return; }
        if (st.isBlank()) { player.sendMessage(msg("The vial is empty.", NamedTextColor.RED)); return; }

        String catalyst = args.length >= 3 ? args[2].toLowerCase() : null;
        WellRoll.Result r = WellRoll.resolve(st, catalyst, 0.0, ThreadLocalRandom.current());

        // The pour consumes the vial.
        heldItem.setAmount(heldItem.getAmount() - 1);
        player.getInventory().setItemInMainHand(heldItem.getAmount() <= 0 ? null : heldItem);

        switch (r.outcome()) {
            case MANIFEST -> onManifest(player, r);
            case NEAR_MISS -> player.sendMessage(msg("Near-miss — the Well settles on "
                    + (r.weapon() != null ? r.weapon().display() : "nothing")
                    + " (" + pct(r.match()) + " match). No weapon this time.", NamedTextColor.YELLOW));
            case BREACH -> player.sendMessage(msg("BREACH — the Well ruptures. A "
                    + r.aimedGrade().display() + "-class Abnormality would climb out. (Combat: coming soon.)",
                    NamedTextColor.RED));
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
        PotState st = Cogito.read(player.getInventory().getItemInMainHand());
        if (st == null) player.sendMessage(msg("Hold a Cogito vial first.", NamedTextColor.RED));
        return st;
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
        player.sendMessage(msg("Extraction (testbed) — /reliquary ext ...", NamedTextColor.WHITE));
        line(player, "vial", "brew a blank Cogito vial");
        line(player, "fuel [n]", "draw Enkephalin");
        line(player, "reagents", "list reagent ids");
        line(player, "add <id>", "add a reagent to the held vial (the Censer)");
        line(player, "assay", "reveal the held vial's composition");
        line(player, "lectern <id>", "what-if: project a reagent without committing");
        line(player, "distill", "run the held vial through the Centrifuge");
        line(player, "pour [catalystId]", "pour into the Well — manifest / near-miss / breach");
    }

    private void line(Player player, String cmd, String desc) {
        player.sendMessage(Component.text("  /reliquary ext " + cmd, GREY)
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
