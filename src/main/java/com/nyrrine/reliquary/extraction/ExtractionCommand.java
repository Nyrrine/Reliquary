package com.nyrrine.reliquary.extraction;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final Map<UUID, Long> fontCooldown = new HashMap<>(); // the Font's fuel-draw cooldown

    public ExtractionCommand(Reliquary plugin) {
        this.plugin = plugin;
    }

    private static final TextColor GREEN = TextColor.color(0x74F066);
    private static final TextColor GREY  = NamedTextColor.GRAY;
    private static final TextColor FAINT = TextColor.color(0x7A7A84);
    private static final TextColor GOLD  = TextColor.color(0xFFD54A);

    /** The subcommands, in help/tab order. */
    private static final List<String> SUBS = List.of(
            "vial", "fuel", "giveall", "stations", "reagents", "add", "assay", "lectern", "distill", "blend",
            "recipes", "forge", "pour");

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
            case "giveall"  -> giveAll(player);
            case "stations" -> giveStations(player);
            case "reagents" -> listReagents(player);
            case "add"      -> add(player, a);
            case "assay"    -> assay(player);
            case "lectern"  -> lectern(player, a);
            case "distill"  -> distill(player);
            case "blend"    -> blend(player);
            case "recipes"  -> recipes(player, a);
            case "forge"    -> forge(player, a);
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
        if (a.length == 2 && (a[0].equalsIgnoreCase("pour") || a[0].equalsIgnoreCase("recipes")
                || a[0].equalsIgnoreCase("forge"))) {
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
            giveOrDrop(player, vial);
        }
        player.sendMessage(msg("A blank Cogito vial forms.", GREEN));
    }

    private void giveFuel(Player player, String[] a) {
        int n = parseInt(a, 1, 16);
        giveOrDrop(player, Enkephalin.create(n));
        player.sendMessage(msg("Drew " + n + " Enkephalin.", GREEN));
    }

    /** Hand over the 7 crafted station items (for testing without gathering the recipe parts). */
    private void giveStations(Player player) {
        for (StationType t : StationType.values()) giveOrDrop(player, t.createItem());
        player.sendMessage(msg("Dispensed the " + StationType.values().length
                + " station blocks — place them and right-click. (They're also craftable.)", GREEN));
    }

    /** Dispense one of everything the system uses — a vial, fuel, every reagent's item form, and every
     *  catalyst grind component. Overflow drops at your feet. For creative/testing. */
    private void giveAll(Player player) {
        giveOrDrop(player, Cogito.create(new PotState()));
        giveOrDrop(player, Enkephalin.create(64));
        java.util.Set<Material> mats = new java.util.LinkedHashSet<>();
        for (Reagent r : Reagents.all()) if (r.item() != null) mats.add(r.item());
        for (Catalysts.Recipe rec : Catalysts.all()) mats.addAll(rec.components().keySet());
        for (Material m : mats) giveOrDrop(player, new ItemStack(m, 16));
        player.sendMessage(msg("Dispensed a vial, 64 Enkephalin, and " + mats.size()
                + " item types (reagents + catalyst components). Overflow is at your feet.", GREEN));
    }

    /**
     * The Lectern readout — tell the player what the lectern knows about the item they're holding: a Cogito's
     * full assay, a catalyst's lock, a reagent's fingerprint, or which catalysts a grind component feeds.
     */
    public void describeItem(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            player.sendMessage(msg("Hold an item and right-click the lectern to identify it.", GREY));
            return;
        }

        PotState st = Cogito.read(item);
        if (st != null) {
            player.sendMessage(msg("— Cogito vial —", NamedTextColor.WHITE));
            player.sendMessage(assayLine(st.profile()));
            showGauges(player, st);
            showTaints(player, st);
            showPool(player, st);
            return;
        }
        if (Enkephalin.matches(item)) {
            player.sendMessage(msg("Enkephalin — distilled mental energy. Fuels the Centrifuge (distilling) "
                    + "and the Well (pours + forging).", GREEN));
            return;
        }
        String cw = Catalyst.weaponId(item);
        if (cw != null) {
            WeaponSpec w = WeaponSignatures.byId(cw);
            player.sendMessage(msg("Catalyst — guarantees " + (w != null ? w.display() : cw)
                    + (w != null ? " (" + w.grade().display() + ")" : "")
                    + ". Pour it with a matching cogito to lock the extraction (+ Certified at 99%+).", GOLD));
            return;
        }

        Material m = item.getType();
        boolean found = false;
        Reagent r = Reagents.byItem(m);
        if (r != null) { describeReagent(player, r); found = true; }

        List<String> usedBy = new ArrayList<>();
        for (Catalysts.Recipe rec : Catalysts.all()) {
            Integer need = rec.components().get(m);
            if (need == null) continue;
            WeaponSpec w = WeaponSignatures.byId(rec.weaponId());
            usedBy.add((w != null ? w.display() : rec.weaponId()) + " x" + need);
        }
        if (!usedBy.isEmpty()) {
            player.sendMessage(msg("Grind component — used to forge: " + String.join(", ", usedBy), FAINT));
            found = true;
        }
        if (!found) player.sendMessage(msg("The lectern has nothing on this.", GREY));
    }

    private void describeReagent(Player player, Reagent r) {
        player.sendMessage(msg("Reagent: " + r.display() + "  [" + r.tier() + ", ceiling "
                + (int) r.tierCeiling() + "]", NamedTextColor.WHITE));
        Component d = Component.text("  ", GREY);
        boolean first = true;
        for (Sin s : Sin.values()) {
            double v = r.delta()[s.index()];
            if (v == 0) continue;
            if (!first) d = d.append(Component.text("  ", FAINT));
            d = d.append(Component.text((v > 0 ? "+" : "") + (int) v + " " + s.display(), s.color()));
            first = false;
        }
        if (r.isVolatile()) {
            d = d.append(Component.text((first ? "" : "  ") + "+[" + (int) r.roll().min() + "-"
                    + (int) r.roll().max() + "] " + r.roll().sin().display(), r.roll().sin().color()));
            first = false;
        }
        if (!first) player.sendMessage(d);
        player.sendMessage(msg(String.format("  contam %.1f  stability %+.0f%s", r.contam(), r.stab(),
                r.flux() > 0 ? "  flux +" + r.flux() : ""), FAINT));
        if (r.inflicts() != null) {
            player.sendMessage(Component.text("  risks " + r.inflicts().taint().display() + " ("
                    + Math.round(r.inflicts().chance() * 100) + "%)", r.inflicts().taint().color()));
        }
        if (r.cures() != null && !r.cures().isEmpty()) {
            List<String> c = new ArrayList<>();
            for (Taint t : r.cures()) c.add(t.display());
            player.sendMessage(msg("  cures: " + String.join(", ", c), GREEN));
        }
        player.sendMessage(msg("  " + r.source(), FAINT));
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
        applyReagent(player, r);
    }

    /** Titrate one reagent into the player's vial, with all the feedback. Shared by /cogito add + the Censer. */
    public void applyReagent(Player player, Reagent r) {
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

        if (result.breached()) {
            // Stability bottomed out — the pot ruptures and the whole batch is lost.
            destroyVial(player, v);
            player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.7f);
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.5f);
            player.sendMessage(msg("The pot RUPTURES — stability hit zero and the batch is LOST. "
                    + "Buffer with amethyst_shard before it bottoms out.", NamedTextColor.RED));
            return;
        }

        writeVial(player, v);
        if (result.stepFailed()) {
            player.sendMessage(msg("Your hand slips — the " + r.display() + " never enters the pot (wasted). "
                    + "The batch survives, but the fumble cost a little purity + stability. Retry.",
                    NamedTextColor.GOLD));
        } else {
            player.sendMessage(msg("Added " + r.display() + ".", GREEN));
        }
        // Diagnosis feedback: what the touch cured, and what it may have tainted.
        for (Taint t : result.cured()) {
            player.sendMessage(msg("Cured " + t.display() + "!", GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
        }
        if (result.inflicted() != null) {
            Taint t = result.inflicted();
            player.sendMessage(msg("⚠ " + t.display() + " sets in — " + t.symptom()
                    + ". Cure with " + t.cureId() + " within " + Math.round(t.timerSec()) + "s.", t.color()));
            player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 0.8f);
        }
        showGauges(player, st);
        showNearest(player, st);
    }

    private void destroyVial(Player player, Vial v) {
        if (v.slot() < 0) player.getInventory().setItemInMainHand(null);
        else player.getInventory().setItem(v.slot(), null);
    }

    // ---- stations: block front-ends (§35) — each calls the same logic as its /cogito command ----

    /** The Font (Cauldron): draw Enkephalin, cooldown-gated so it isn't an infinite tap. */
    public void stationFont(Player player) {
        long now = System.currentTimeMillis();
        long ready = fontCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now < ready) {
            player.sendActionBar(msg("The Font is replenishing… " + ((ready - now) / 1000 + 1) + "s", FAINT));
            return;
        }
        fontCooldown.put(player.getUniqueId(), now + 5000L);
        giveOrDrop(player, Enkephalin.create(4));
        player.sendMessage(msg("The Font yields 4 Enkephalin.", GREEN));
        player.playSound(player.getLocation(), Sound.ITEM_BUCKET_FILL, 0.7f, 1.3f);
    }

    /** The Alembic (Blast Furnace): spend 1 Enkephalin → a blank Cogito vial. */
    public void stationAlembic(Player player) {
        if (!consumeEnkephalin(player, 1)) {
            player.sendMessage(msg("The Alembic needs 1 Enkephalin to draw a vial. /cogito fuel or use the Font.",
                    NamedTextColor.RED));
            return;
        }
        ItemStack vial = Cogito.create(new PotState());
        if (player.getInventory().getItemInMainHand().getType().isAir()) {
            player.getInventory().setItemInMainHand(vial);
        } else {
            giveOrDrop(player, vial);
        }
        player.sendMessage(msg("The Alembic condenses a blank Cogito vial (−1 Enkephalin).", GREEN));
        player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.7f, 1.2f);
    }

    /** The Censer (Brewing Stand): the held reagent item is titrated into your vial (spends one). */
    public void stationCenser(Player player, ItemStack held) {
        Reagent r = Reagents.byItem(held.getType());
        if (r == null) {
            player.sendMessage(msg("The Censer doesn't take that — hold a reagent item.", NamedTextColor.RED));
            return;
        }
        if (locateVial(player) == null) { noVial(player); return; }
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);
        applyReagent(player, r);
    }

    /** The Centrifuge (Grindstone): distill the held vial. */
    public void stationCentrifuge(Player player) { distill(player); }

    /** The Manifold (Chiseled Bookshelf): blend all charged vials you carry. */
    public void stationManifold(Player player) { blend(player); }

    /** The Pocket Well (Vault): pour to manifest, or (sneaking) forge the best catalyst you can afford. */
    public void stationWell(Player player, boolean sneaking) {
        if (sneaking) {
            // Forge the highest-grade catalyst whose recipe the player can fully afford right now.
            Catalysts.Recipe best = null;
            EgoGrade bestGrade = null;
            for (Catalysts.Recipe rec : Catalysts.all()) {
                if (!canAfford(player, rec)) continue;
                WeaponSpec w = WeaponSignatures.byId(rec.weaponId());
                EgoGrade g = w != null ? w.grade() : EgoGrade.ZAYIN;
                if (best == null || g.ordinal() > bestGrade.ordinal()) { best = rec; bestGrade = g; }
            }
            if (best == null) {
                player.sendMessage(msg("The Well can't forge anything — you're short on grind components. "
                        + "Check /cogito recipes <id>.", NamedTextColor.RED));
                return;
            }
            forge(player, new String[]{"forge", best.weaponId()});
            return;
        }
        pour(player, new String[]{"pour"});
    }

    private boolean canAfford(Player player, Catalysts.Recipe rec) {
        for (var e : rec.components().entrySet()) {
            if (countMaterial(player, e.getKey()) < e.getValue()) return false;
        }
        return countEnkephalin(player) >= rec.enkephalin();
    }

    /** Give an item, dropping any overflow at the player's feet so a full inventory never eats it. */
    private void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    // ---- assay + lectern -----------------------------------------------------------

    private void assay(Player player) {
        PotState st = held(player);
        if (st == null) return;
        player.sendMessage(msg("Assay:", NamedTextColor.WHITE));
        player.sendMessage(assayLine(st.profile()));
        showGauges(player, st);
        showTaints(player, st);
        showPool(player, st);
    }

    /** List active afflictions with their remaining time + cure — the triage panel. */
    private void showTaints(Player player, PotState st) {
        if (st.taints().isEmpty()) return;
        player.sendMessage(msg("Afflictions (treat before the timer runs out):", NamedTextColor.RED));
        for (var e : st.taints().entrySet()) {
            Taint t = e.getKey();
            player.sendMessage(Component.text(String.format("  ⚠ %s  %.0fs left", t.display(), e.getValue()), t.color())
                    .append(Component.text("  " + t.symptom() + " — cure: " + t.cureId(), FAINT)));
        }
    }

    /**
     * The Well's live gacha odds for the current mix — each reachable weapon's pull chance (your cogito tilts
     * these), the overall success chance, and the closest weapon you're still approaching but can't yet pull.
     * The legible heart of the testbed.
     */
    private void showPool(Player player, PotState st) {
        if (st.isBlank()) return;
        List<WellRoll.Chance> pool = WellRoll.pool(st);

        if (!pool.isEmpty()) {
            player.sendMessage(msg("If you pour now you'll extract:", NamedTextColor.WHITE));
            int shown = 0;
            for (WellRoll.Chance c : pool) {
                if (shown >= 4) break;
                player.sendMessage(Component.text(String.format("  %d%%  ", Math.round(c.odds() * 100)),
                        shown == 0 ? GREEN : NamedTextColor.GRAY)
                        .append(Component.text(c.weapon().display() + " (" + c.weapon().grade().display() + ")",
                                shown == 0 ? GREEN : NamedTextColor.GRAY))
                        .append(Component.text("   " + pct(c.match()) + " match", FAINT)));
                shown++;
            }
            double breach = WellRoll.breachChance(st, 0.0);
            String risk = breach < 0.02 ? "steady — safe to pour"
                    : String.format("%d%% chance it ruptures — steady it first (buffer/distill)", Math.round(breach * 100));
            player.sendMessage(Component.text("  " + risk + ".  Catalyst (pour <id>) guarantees the exact weapon.",
                    breach < 0.02 ? FAINT : NamedTextColor.GOLD));
        }

        // What you're closest to but can't yet reach — the thing to keep brewing toward.
        WeaponSpec locked = nearestLocked(st);
        if (locked != null) {
            List<String> need = new ArrayList<>();
            if (!locked.grade().minCogito().atMost(st.grade())) need.add("grade >=" + locked.grade().minCogito().display());
            if (st.titer() < locked.grade().minVolume()) need.add(String.format("vol >=%.0f", locked.grade().minVolume()));
            player.sendMessage(Component.text(String.format("  Approaching: %s (%s)  %s match  needs %s",
                    locked.display(), locked.grade().display(), pct(locked.matchOf(st.profile())),
                    String.join(", ", need)), FAINT));
        }
    }

    /** The closest weapon by shape that ISN'T yet reachable (grade/volume gated) — the aspirational target. */
    private WeaponSpec nearestLocked(PotState st) {
        WeaponSpec best = null;
        double bestMatch = -1;
        for (WeaponSpec w : WeaponSignatures.all()) {
            if (w.reachableBy(st.grade(), st.titer())) continue;
            double m = w.matchOf(st.profile());
            if (m > bestMatch) { bestMatch = m; best = w; }
        }
        return best;
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
        if (v.state().anyTaintBlocksDistill()) {
            player.sendMessage(msg("The pot's too hot to distill — quench the Fever first (add snowball).",
                    NamedTextColor.RED));
            return;
        }
        if (!consumeEnkephalin(player, 1)) {
            player.sendMessage(msg("Out of Enkephalin — the Centrifuge burns 1 per pass. Draw more: /cogito fuel",
                    NamedTextColor.RED));
            return;
        }
        PotState st = v.state();
        double beforeN = st.noise();
        double beforeV = st.titer();
        Engine.distill(st);
        writeVial(player, v);
        player.sendMessage(msg(String.format(
                "Distilled — purity up (noise %.1f->%.1f), but it concentrates: volume %.0f->%.0f (pass %d, -1 Enkephalin).",
                beforeN, st.noise(), beforeV, st.titer(), st.distillPasses()), GREEN));
        showGauges(player, st);
    }

    /** Remove {@code amount} Enkephalin from the player's inventory; false (and no change) if short. */
    private boolean consumeEnkephalin(Player player, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int have = 0;
        for (ItemStack it : contents) if (Enkephalin.matches(it)) have += it.getAmount();
        if (have < amount) return false;
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            if (!Enkephalin.matches(contents[i])) continue;
            int take = Math.min(remaining, contents[i].getAmount());
            contents[i].setAmount(contents[i].getAmount() - take);
            if (contents[i].getAmount() <= 0) player.getInventory().setItem(i, null);
            remaining -= take;
        }
        return true;
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
        giveOrDrop(player, Cogito.create(blended));
        player.sendMessage(msg("Blended " + pots.size() + " vials at the Manifold.", GREEN));
        player.sendMessage(assayLine(blended.profile()));
        showGauges(player, blended);
    }

    // ---- the Well: forge mode (catalysts) ------------------------------------------

    /** Show what a weapon's catalyst costs (legibility for the grind), or list all defined recipes. */
    private void recipes(Player player, String[] a) {
        if (a.length >= 2) {
            WeaponSpec w = WeaponSignatures.byId(a[1].toLowerCase());
            Catalysts.Recipe rec = w == null ? null : Catalysts.forWeapon(w.id());
            if (rec == null) {
                player.sendMessage(msg("No catalyst recipe defined for '" + a[1] + "'.", NamedTextColor.RED));
                return;
            }
            player.sendMessage(msg(w.display() + " Catalyst (" + w.grade().display() + ") — forge needs:",
                    NamedTextColor.WHITE));
            for (var e : rec.components().entrySet()) {
                player.sendMessage(msg("  " + e.getValue() + "x " + pretty(e.getKey()), FAINT));
            }
            player.sendMessage(msg("  + " + rec.enkephalin() + " Enkephalin", FAINT));
            return;
        }
        player.sendMessage(msg("Catalyst recipes (" + Catalysts.count() + " defined) — /cogito recipes <id>:",
                NamedTextColor.WHITE));
        for (Catalysts.Recipe rec : Catalysts.all()) {
            WeaponSpec w = WeaponSignatures.byId(rec.weaponId());
            player.sendMessage(msg("  " + rec.weaponId() + (w != null ? "  (" + w.grade().display() + ")" : ""), FAINT));
        }
    }

    /** The Well's forge mode: consume the grind components + Enkephalin, produce the weapon's catalyst. */
    private void forge(Player player, String[] a) {
        if (a.length < 2) { player.sendMessage(msg("Usage: /cogito forge <weaponId>", GREY)); return; }
        WeaponSpec w = WeaponSignatures.byId(a[1].toLowerCase());
        if (w == null) { player.sendMessage(msg("No such weapon: " + a[1], NamedTextColor.RED)); return; }
        Catalysts.Recipe rec = Catalysts.forWeapon(w.id());
        if (rec == null) {
            player.sendMessage(msg("No catalyst recipe defined for " + w.display() + " yet.", NamedTextColor.RED));
            return;
        }

        // Check everything up front, list all shortfalls at once.
        List<String> missing = new ArrayList<>();
        for (var e : rec.components().entrySet()) {
            int have = countMaterial(player, e.getKey());
            if (have < e.getValue()) missing.add((e.getValue() - have) + " more " + pretty(e.getKey()));
        }
        int enk = countEnkephalin(player);
        if (enk < rec.enkephalin()) missing.add((rec.enkephalin() - enk) + " more Enkephalin");
        if (!missing.isEmpty()) {
            player.sendMessage(msg("The forge is short: " + String.join(", ", missing) + ".", NamedTextColor.RED));
            return;
        }

        // Consume and forge.
        for (var e : rec.components().entrySet()) consumeMaterial(player, e.getKey(), e.getValue());
        consumeEnkephalin(player, rec.enkephalin());
        giveOrDrop(player, Catalyst.create(w));
        player.sendMessage(msg("The Well forges the " + w.display() + " Catalyst — pour it with a matching "
                + w.grade().display() + " cogito to guarantee the extraction.", GREEN));
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 0.7f);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.4f);
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Count plain vanilla items of a material (never the special extraction items). */
    private int countMaterial(Player player, Material m) {
        int n = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it == null || it.getType() != m) continue;
            if (Enkephalin.matches(it) || Catalyst.matches(it) || Cogito.matches(it)) continue;
            n += it.getAmount();
        }
        return n;
    }

    private void consumeMaterial(Player player, Material m, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != m) continue;
            if (Enkephalin.matches(it) || Catalyst.matches(it) || Cogito.matches(it)) continue;
            int take = Math.min(remaining, it.getAmount());
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) player.getInventory().setItem(i, null);
            remaining -= take;
        }
    }

    private int countEnkephalin(Player player) {
        int n = 0;
        for (ItemStack it : player.getInventory().getContents()) if (Enkephalin.matches(it)) n += it.getAmount();
        return n;
    }

    private void consumeSlotOne(Player player, int slot) {
        ItemStack it = player.getInventory().getItem(slot);
        if (it == null) return;
        it.setAmount(it.getAmount() - 1);
        player.getInventory().setItem(slot, it.getAmount() <= 0 ? null : it);
    }

    // ---- the Well: pour ------------------------------------------------------------

    private void pour(Player player, String[] a) {
        Vial v = locateVial(player);
        if (v == null) { noVial(player); return; }
        PotState st = v.state();
        if (st.isBlank()) { player.sendMessage(msg("The vial is empty.", NamedTextColor.RED)); return; }

        // Catalyst: an explicit id is the testbed shortcut (simulated); otherwise auto-detect a forged
        // catalyst ITEM in the inventory and use its locked weapon.
        String catalyst = a.length >= 2 ? a[1].toLowerCase() : null;
        int catalystSlot = -1;
        if (catalyst == null) {
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                String w = Catalyst.weaponId(contents[i]);
                if (w != null) { catalyst = w; catalystSlot = i; break; }
            }
        }

        WellRoll.Result r = WellRoll.resolve(st, catalyst, 0.0, ThreadLocalRandom.current());

        // Too thin/crude to reach ANY weapon (nothing cleared the grade floor + volume gate). Rather than
        // a cryptic breach, explain the shortfall and keep the vial + catalyst — this is the testbed helping.
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

        // A forged catalyst ITEM is spent only if it actually locked its weapon (did its job).
        if (catalystSlot >= 0 && r.outcome() == WellRoll.Outcome.MANIFEST
                && r.weapon() != null && r.weapon().id().equals(catalyst)) {
            consumeSlotOne(player, catalystSlot);
        }

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
        giveOrDrop(player, item);
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
        line(player, "giveall", "dispense every reagent + catalyst component (creative)");
        line(player, "reagents", "list reagent ids");
        line(player, "add <id>", "add a reagent to the held vial (the Censer)");
        line(player, "assay", "reveal the held vial's composition");
        line(player, "lectern <id>", "what-if: project a reagent without committing");
        line(player, "distill", "run the held vial through the Centrifuge");
        line(player, "blend", "blend all charged vials you carry (the Manifold)");
        line(player, "recipes [id]", "what a weapon's catalyst costs to forge");
        line(player, "forge <id>", "forge a signature-lock catalyst from grind components");
        line(player, "pour [catalystId]", "pour into the Well — a forged catalyst guarantees the weapon");
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
