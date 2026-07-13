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
    private final WellDisplay wellDisplay;
    private final WeaponTracker weaponTracker;
    private final Map<String, Integer> fontProgress = new HashMap<>(); // Font compost fill, per block location
    private static final int FONT_THRESHOLD = 24; // resonance accumulated per 1 Enkephalin yielded

    public ExtractionCommand(Reliquary plugin) {
        this.plugin = plugin;
        this.wellDisplay = new WellDisplay(plugin);
        this.weaponTracker = new WeaponTracker(plugin);
    }

    /** The weapon item a spec resolves to (via the plugin's registry), for the Well carousel. */
    private ItemStack weaponItem(WeaponSpec spec) {
        Weapon w = plugin.weapons().get(spec.id());
        return w != null ? w.createItem() : null;
    }

    private static final TextColor GREEN = TextColor.color(0x74F066);
    private static final TextColor GREY  = NamedTextColor.GRAY;
    private static final TextColor FAINT = TextColor.color(0x7A7A84);
    private static final TextColor GOLD  = TextColor.color(0xFFD54A);

    /** The subcommands, in help/tab order. */
    private static final List<String> SUBS = List.of(
            "vial", "fuel", "giveall", "stations", "reagents", "add", "assay", "distill", "blend",
            "recipes", "forge", "insert", "pour", "track", "untrack", "ticket");

    /**
     * Dispatch an extraction subcommand. {@code a} is the sub-args where {@code a[0]} is the subcommand name
     * and {@code a[1]...} its parameters — so both {@code /cogito add x} and {@code /reliquary ext add x}
     * feed the same array shape here.
     */
    /** Permission for the give / brew commands — the read-only lookups below stay open to everyone. */
    public static final String ADMIN_PERM = "reliquary.admin";
    /** Subcommands any player may use (read-only info; the rest give items or brew via command → admin-only). */
    public static final List<String> PLAYER_SUBS = List.of("recipes", "track", "untrack", "reagents", "assay");

    public void handle(CommandSender sender, String[] a) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Extraction is a player command.").color(NamedTextColor.RED));
            return;
        }
        if (a.length < 1) { help(player); return; }
        String sub = a[0].toLowerCase();
        if (!PLAYER_SUBS.contains(sub) && !player.hasPermission(ADMIN_PERM)) {
            player.sendMessage(msg("That's an admin command — normal extraction is at the crafted stations. "
                    + "You can still use /cogito recipes, track, untrack, reagents, assay.", NamedTextColor.RED));
            return;
        }

        switch (sub) {
            case "vial"     -> giveVial(player);
            case "fuel"     -> giveFuel(player, a);
            case "giveall"  -> giveAll(player, a);
            case "stations" -> giveStations(player);
            case "reagents" -> listReagents(player);
            case "add"      -> add(player, a);
            case "assay"    -> assay(player, a);
            case "distill"  -> distill(player);
            case "blend"    -> blend(player);
            case "recipes"  -> recipes(player, a);
            case "forge"    -> forge(player, a);
            case "insert"   -> insertCatalyst(player);
            case "pour"     -> pour(player, a);
            case "ticket"   -> ticket(player, a);
            case "track"    -> trackWeapon(player, a);
            case "untrack"  -> { weaponTracker.clear(player.getUniqueId());
                                 player.sendActionBar(msg("No longer tracking a weapon.", FAINT)); }
            default          -> help(player);
        }
    }

    /** Track a weapon so the Assay hints what to fetch next. */
    private void trackWeapon(Player player, String[] a) {
        if (a.length < 2) { player.sendMessage(msg("Usage: /cogito track <weapon_id>", GREY)); return; }
        WeaponSpec w = WeaponSignatures.byId(a[1].toLowerCase());
        if (w == null) { player.sendMessage(msg("No such weapon: " + a[1], NamedTextColor.RED)); return; }
        trackAndGuide(player, w.id());
    }

    /** Start (or refresh) a forge hint for a weapon and print its next-step guidance to chat. */
    private void trackAndGuide(Player player, String weaponId) {
        if (WeaponSignatures.byId(weaponId) == null) return;
        weaponTracker.track(player.getUniqueId(), weaponId);
        for (Component line : weaponTracker.shoppingList(player, weaponId)) player.sendMessage(line);
        player.sendMessage(msg("Re-check anytime at the Assay (empty hand); /cogito untrack to stop.", FAINT));
    }

    /**
     * The Assay readout on an empty hand (all chat). If you're on a forge quest it reprints your next step +
     * checklist; otherwise it explains what the Assay does.
     */
    public void assayOverview(Player player) {
        String tracked = weaponTracker.tracked(player.getUniqueId());
        if (tracked != null) {
            for (Component line : weaponTracker.shoppingList(player, tracked)) player.sendMessage(line);
            player.sendMessage(msg("Hold an item to identify it · /cogito untrack to stop.", FAINT));
            return;
        }
        player.sendMessage(msg("The Assay", NamedTextColor.WHITE));
        player.sendMessage(msg("• Hold an item and right-click to identify it — a weapon or catalyst starts a "
                + "step-by-step forge quest.", FAINT));
        player.sendMessage(msg("• /cogito track <weapon> — begin a forge quest (tab-completes).", FAINT));
        player.sendMessage(msg("• /cogito recipes <weapon> — see a catalyst's full cost.", FAINT));
    }

    /** Tab completion for the extraction subcommands. {@code a[0]} = subcommand, {@code a[1]} = its arg. */
    public List<String> tabComplete(String[] a, boolean admin) {
        if (a.length == 1) return filter(admin ? SUBS : PLAYER_SUBS, a[0]);
        if (a.length == 2) {
            // Player-open lookups (available to everyone).
            switch (a[0].toLowerCase()) {
                case "recipes" -> { return filter(recipeIds(), a[1]); }
                case "track"   -> { return filter(weaponIds(), a[1]); }
                case "assay"   -> { return filter(reagentIds(), a[1]); }
                default -> { }
            }
            if (!admin) return List.of();
            switch (a[0].toLowerCase()) {
                case "add"           -> { return filter(reagentIds(), a[1]); }
                case "pour", "forge" -> { return filter(weaponIds(), a[1]); }
                case "giveall"       -> { return filter(GIVE_CATS, a[1]); }
                case "ticket"        -> { return filter(ticketArgs(true), a[1]); }
                default -> { }
            }
        }
        if (admin && a.length == 3 && a[0].equalsIgnoreCase("ticket") && a[1].equalsIgnoreCase("add")) {
            return filter(ticketArgs(false), a[2]);
        }
        return List.of();
    }

    /** Tab options for the ticket command — grades (+ all), plus add/clear at the first arg. */
    private List<String> ticketArgs(boolean firstArg) {
        List<String> out = new ArrayList<>();
        if (firstArg) { out.add("add"); out.add("clear"); }
        out.add("all");
        for (String p : ExtractionTicket.POOL_NAMES) out.add(p.toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    /** Everything /cogito recipes can look up: weapons, refined reagents, and sins. */
    private List<String> recipeIds() {
        List<String> out = new ArrayList<>(weaponIds());
        out.addAll(refinedIds());
        for (Sin s : Sin.values()) out.add(s.name().toLowerCase(java.util.Locale.ROOT));
        return out;
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

    /** Hand over the 8 crafted station items (for testing without gathering the recipe parts). */
    private void giveStations(Player player) {
        for (StationType t : StationType.values()) giveOrDrop(player, t.createItem());
        player.sendMessage(msg("Dispensed the " + StationType.values().length
                + " station blocks — place them and right-click. (They're also craftable.)", GREEN));
    }

    /** The giveall categories, for tab completion + usage. */
    private static final List<String> GIVE_CATS =
            List.of("all", "rawmaterials", "materials", "cures", "catalysts");

    /**
     * Creative dispenser, split by category so you can grab just what you're testing:
     * {@code rawmaterials} (gatherable reagent items), {@code materials} (crafted refined reagents +
     * concentrates), {@code cures} (remedies + buffers), {@code catalysts} (grind components + fuel), or
     * {@code all}. Everything is handed out in valid stacks — unstackable items (buckets, potions, pots)
     * come as separate items, never a broken 16-stack.
     */
    private void giveAll(Player player, String[] a) {
        String cat = a.length > 1 ? a[1].toLowerCase(java.util.Locale.ROOT) : "all";
        if (!GIVE_CATS.contains(cat)) {
            player.sendMessage(msg("Usage: /cogito giveall <" + String.join("|", GIVE_CATS) + ">", GREY));
            return;
        }
        boolean all = cat.equals("all");
        List<String> got = new ArrayList<>();

        if (all) {
            giveOrDrop(player, Cogito.create(new PotState()));
            giveOrDrop(player, Enkephalin.create(64));
            giveOrDrop(player, RawCogito.create(16));
        }
        if (all || cat.equals("rawmaterials")) {
            int n = 0;
            for (Reagent r : Reagents.all())
                if (r.item() != null && !isSupportReagent(r)) { giveMaterial(player, r.item(), 32); n++; }
            got.add(n + " raw materials");
        }
        if (all || cat.equals("cures")) {
            int n = 0;
            for (String id : CureItem.ids()) { giveOrDrop(player, CureItem.create(id, 16)); n++; }
            // Affinity reagents that also cure a taint (blaze/honey/glistering) stay their plain vanilla item.
            for (Reagent r : Reagents.all())
                if (r.item() != null && isSupportReagent(r)) { giveMaterial(player, r.item(), 16); n++; }
            got.add(n + " cures/buffers");
        }
        if (all || cat.equals("materials")) {
            for (String id : RefinedReagent.ids()) giveOrDrop(player, RefinedReagent.create(id, 64));
            for (Sin s : Sin.values()) giveOrDrop(player, SinConcentrate.create(s, 32));
            got.add("refined reagents + 7 concentrates");
        }
        if (all || cat.equals("catalysts")) {
            java.util.Set<Material> mats = new java.util.LinkedHashSet<>();
            for (Catalysts.Recipe rec : Catalysts.all()) mats.addAll(rec.components().keySet());
            for (Material m : mats) giveMaterial(player, m, 64);
            giveOrDrop(player, Enkephalin.create(64));
            got.add(mats.size() + " catalyst components + fuel");
        }
        player.sendMessage(msg("Dispensed (" + cat + "): " + String.join(", ", got)
                + ". Overflow is at your feet.", GREEN));
    }

    /** A support reagent — a remedy (cures a taint) or a buffer/solvent (UTILITY tier), vs. an affinity item. */
    private boolean isSupportReagent(Reagent r) {
        return r.tier() == Reagent.Tier.UTILITY || (r.cures() != null && !r.cures().isEmpty());
    }

    /** Give {@code desired} of a material in valid stacks — unstackables come as separate items (max 4). */
    private void giveMaterial(Player player, Material m, int desired) {
        int max = Math.max(1, m.getMaxStackSize());
        int remaining = max <= 1 ? Math.min(desired, 4) : desired; // don't spew 16 single buckets
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            giveOrDrop(player, new ItemStack(m, n));
            remaining -= n;
        }
    }

    /**
     * The Lectern readout — tell the player what the lectern knows about the item they're holding: a Cogito's
     * full assay, a catalyst's lock, a reagent's fingerprint, or which catalysts a grind component feeds.
     */
    public void describeItem(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            // Nothing meaningful in hand — fall back to the vial in the bag so a bare assay still works.
            Vial v = locateVial(player);
            if (v != null) { assayPot(player, v.state()); return; }
            player.sendMessage(msg("Hold an item (or a Cogito vial) to identify it.", GREY));
            return;
        }

        PotState st = Cogito.read(item);
        if (st != null) {
            assayPot(player, st);
            // If you're on a forge hint, also read this cogito against the target (arrows + what to fix).
            String tracked = weaponTracker.tracked(player.getUniqueId());
            if (tracked != null && !st.isBlank()) {
                for (Component line : weaponTracker.shoppingList(player, tracked)) player.sendMessage(line);
            }
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
            boolean apex = w != null && w.grade().isApex();
            player.sendMessage(msg("Catalyst — " + (w != null ? w.display() : cw)
                    + (w != null ? " (" + w.grade().display() + ")" : "")
                    + ". Insert it into a cogito (sneak the Crucible, or /cogito insert): "
                    + (apex ? "REQUIRED to manifest this apex weapon — it makes a Radiant Cogito."
                            : "adds 1–15% to its odds per stack (up to 3), once it's your top pull past 70%."),
                    GOLD));
            trackAndGuide(player, cw);
            return;
        }
        // An E.G.O weapon in hand — the Assay reads it and starts a forge quest for it.
        Weapon wpn = plugin.weapons().fromItem(item);
        if (wpn != null) {
            WeaponSpec w = WeaponSignatures.byId(wpn.id());
            player.sendMessage(msg((w != null ? w.display() : wpn.id())
                    + (w != null ? " (" + w.grade().display() + ")" : "") + " — a manifested E.G.O.", GOLD));
            trackAndGuide(player, wpn.id());
            return;
        }

        Material m = item.getType();
        boolean found = false;
        Reagent r = Reagents.fromItem(item);
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
        reagentFeedback(player, r, result, st);
    }

    /** The shared feedback after a reagent lands — used by the inventory path and the Censer station path. */
    private void reagentFeedback(Player player, Reagent r, Engine.AddResult result, PotState st) {
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

    /**
     * The Font (Cauldron) — a composter for feelings. Feed it an <b>emotion-resonating item</b> (any reagent
     * / memory) and it accumulates resonance; each {@link #FONT_THRESHOLD} yields exactly one Enkephalin and
     * one Raw Cogito (stronger emotions fill faster), which the Alembic then distills into a blank vial.
     */
    /** Forget a broken Font's compost fill so a new Font placed at the same spot starts empty. */
    public void clearFont(org.bukkit.Location loc) {
        fontProgress.remove(locKey(loc));
    }

    public void stationFont(Player player, org.bukkit.Location loc, ItemStack held) {
        // Raw Cogito fed back into the Font renders into Enkephalin (the mental-energy fuel).
        if (RawCogito.matches(held)) {
            consumeOneMainHand(player, held);
            giveOrDrop(player, Enkephalin.create(ENKEPHALIN_PER_RAW));
            player.sendActionBar(msg("The Font renders Raw Cogito into " + ENKEPHALIN_PER_RAW + " Enkephalin.", GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.6f, 1.4f);
            loc.getWorld().spawnParticle(org.bukkit.Particle.SOUL, loc.clone().add(0.5, 0.9, 0.5),
                    10, 0.2, 0.1, 0.2, 0.01);
            return;
        }
        int resonance = resonanceOf(held);
        if (resonance <= 0) {
            player.sendActionBar(msg("Feed the Font an emotion item for Raw Cogito, or Raw Cogito for Enkephalin.",
                    FAINT));
            return;
        }
        consumeOneMainHand(player, held);
        String k = locKey(loc);
        int prog = fontProgress.getOrDefault(k, 0) + resonance;
        // The lava rises; each full pool yields exactly ONE Enkephalin + ONE Raw Cogito, and carries the rest.
        if (prog >= FONT_THRESHOLD) {
            fontProgress.put(k, prog - FONT_THRESHOLD);
            giveOrDrop(player, RawCogito.create(1));
            player.sendActionBar(msg("The Font overflows — 1 Raw Cogito. Feed it back here for Enkephalin, "
                    + "or make a vial at the Alembic.", GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_COMPOSTER_READY, 0.8f, 0.9f);
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.5f, 1.2f);
            loc.getWorld().spawnParticle(org.bukkit.Particle.LAVA, loc.clone().add(0.5, 0.9, 0.5), 12, 0.2, 0.1, 0.2, 0);
        } else {
            fontProgress.put(k, prog);
            player.playSound(player.getLocation(), Sound.BLOCK_COMPOSTER_FILL, 0.7f, 1.2f);
            int pct = prog * 100 / FONT_THRESHOLD;
            player.sendActionBar(msg("The lava rises… " + pct + "%", FAINT));
            loc.getWorld().spawnParticle(org.bukkit.Particle.FLAME, loc.clone().add(0.5, 0.5, 0.5),
                    Math.max(1, pct / 20), 0.15, 0.05, 0.15, 0.005);
        }
    }

    /** How much an item's emotional charge is worth to the Font (its positive sin magnitude). Fuel items
     *  (Enkephalin / Raw Cogito) don't resonate — you don't compost your own output. */
    private int resonanceOf(ItemStack item) {
        if (item == null || Enkephalin.matches(item) || RawCogito.matches(item)) return 0;
        // A vanilla reagent (memory/emotion items, affinity items) — resonance from its positive sin charge.
        Reagent r = Reagents.byItem(item.getType());
        if (r != null) {
            double sum = 0;
            for (Sin s : Sin.values()) sum += Math.max(0.0, r.delta()[s.index()]);
            if (r.isVolatile()) sum += r.roll().max();
            if (sum <= 0) return 0; // buffers / remedies (no positive charge) aren't emotion fuel
            return (int) Math.round(sum);
        }
        // A raw sin mob drop (bone, string, leather, gunpowder…) is a sin item too — it resonates a little.
        if (SinConcentrate.sinOfRaw(item.getType()) != null) return 4;
        return 0;
    }

    private void consumeOneMainHand(Player player, ItemStack held) {
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);
    }

    private static String locKey(org.bukkit.Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    /** The Alembic (Blast Furnace): process 1 Raw Cogito + 1 Enkephalin → a blank, workable Cogito vial. */
    /** Enkephalin drawn from one Raw Cogito on a sneak-refine. */
    private static final int ENKEPHALIN_PER_RAW = 2;

    /**
     * The Alembic renders Raw Cogito (the Font's only output) two ways, so there's a single source resource and
     * no overflow: right-click distills 1 Raw Cogito into a blank <b>vial</b>; sneak-right-click renders 1 Raw
     * Cogito into {@link #ENKEPHALIN_PER_RAW} <b>Enkephalin</b> (the fuel for distilling + pouring).
     */
    public void stationAlembic(Player player, boolean sneaking) {
        if (countItem(player, RawCogito::matches) < 1) {
            player.sendActionBar(msg("The Alembic needs Raw Cogito — draw some at the Font first.",
                    NamedTextColor.RED));
            return;
        }
        consumeItem(player, RawCogito::matches, 1);
        if (sneaking) {
            giveOrDrop(player, Enkephalin.create(ENKEPHALIN_PER_RAW));
            player.sendActionBar(msg("The Alembic renders Raw Cogito into " + ENKEPHALIN_PER_RAW
                    + " Enkephalin.", GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.7f, 1.5f);
        } else {
            giveOrDrop(player, Cogito.create(new PotState()));
            player.sendActionBar(msg("The Alembic distills Raw Cogito into a workable vial.", GREEN));
            player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.7f, 1.1f);
        }
    }

    /** A Censer that is holding a vial: the vial item, its floating display, and the block it sits on. */
    private record CenserSlot(ItemStack vial, org.bukkit.entity.ItemDisplay display, org.bukkit.Location block) {}

    private final Map<String, CenserSlot> censers = new HashMap<>();

    /**
     * The Censer (Brewing Stand) is a stateful vessel. Right-click <b>holding a Cogito vial</b> to seat it in
     * the Censer (it lifts and turns above the stand); then right-click <b>holding reagents</b> to titrate them
     * into the seated vial one at a time. Sneak-right-click (or empty hand) lifts the vial back out.
     */
    public void stationCenser(Player player, ItemStack held, org.bukkit.Location loc, boolean sneaking) {
        String k = locKey(loc);
        CenserSlot slot = censers.get(k);

        if (sneaking) { // take the vial back out
            if (slot == null) { player.sendActionBar(msg("The Censer is empty.", FAINT)); return; }
            censerRetrieve(player, k);
            return;
        }

        if (Cogito.matches(held)) { // seat a vial
            if (slot != null) { player.sendActionBar(msg("A vial is already seated — sneak-click to lift it out.",
                    NamedTextColor.RED)); return; }
            censerSeat(player, k, loc, held);
            return;
        }

        Reagent r = Reagents.fromItem(held);
        if (r != null) {
            if (slot != null) { censerFeed(player, slot, held, r); return; } // into the seated vial
            // No vial seated — titrate a vial from the bag directly, so the Censer just works.
            Vial v = locateVial(player);
            if (v == null) {
                player.sendActionBar(msg("No Cogito vial — make one at the Alembic, then hold it (or seat it here).",
                        NamedTextColor.RED));
                return;
            }
            if (v.state().titer() >= Engine.VIAL_CAP
                    && Engine.addReagent(v.state().copy(), r, new java.util.Random(0L)).full()) {
                player.sendActionBar(msg(String.format("The vial is full (%.0f cap) — distill or blend it.",
                        Engine.VIAL_CAP), NamedTextColor.RED));
                return;
            }
            held.setAmount(held.getAmount() - 1);
            player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);
            applyReagent(player, r);
            return;
        }

        player.sendActionBar(msg(slot == null
                ? "Hold a Cogito vial to seat it, or a reagent to titrate into your vial."
                : "Right-click holding a reagent to titrate it in (sneak to lift the vial out).", FAINT));
    }

    private void censerSeat(Player player, String k, org.bukkit.Location loc, ItemStack held) {
        ItemStack one = held.clone();
        one.setAmount(1);
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);
        org.bukkit.Location at = loc.clone().add(0.5, 1.05, 0.5);
        org.bukkit.entity.ItemDisplay disp = loc.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setItemStack(one);
            d.setBillboard(org.bukkit.entity.Display.Billboard.VERTICAL);
            d.setPersistent(false);
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            var tr = d.getTransformation();
            tr.getScale().set(0.6f);
            d.setTransformation(tr);
        });
        censers.put(k, new CenserSlot(one, disp, loc.clone()));
        player.playSound(loc, Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 1.6f);
        player.sendActionBar(msg("Vial seated — titrate reagents in.", GREEN));
    }

    private void censerRetrieve(Player player, String k) {
        CenserSlot slot = censers.remove(k);
        if (slot == null) return;
        if (slot.display().isValid()) slot.display().remove();
        giveOrDrop(player, slot.vial());
        player.playSound(slot.block(), Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 1.0f);
        player.sendActionBar(msg("Lifted the vial out.", GREEN));
    }

    private void censerFeed(Player player, CenserSlot slot, ItemStack held, Reagent r) {
        ItemStack vialItem = slot.vial();
        PotState st = Cogito.read(vialItem);
        if (st == null) { // unreadable — don't delete it; hand the vial back and clear the slot
            censers.remove(locKey(slot.block()));
            if (slot.display().isValid()) slot.display().remove();
            giveOrDrop(player, slot.vial());
            return;
        }

        if (st.titer() >= Engine.VIAL_CAP && Engine.addReagent(st.copy(), r, new java.util.Random(0L)).full()) {
            player.sendActionBar(msg(String.format("The vial is full (%.0f cap) — distill or blend it.",
                    Engine.VIAL_CAP), NamedTextColor.RED));
            return;
        }
        // spend one reagent from hand
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);

        Engine.AddResult result = Engine.addReagent(st, r, ThreadLocalRandom.current());
        if (result.breached()) {
            censers.remove(locKey(slot.block()));
            if (slot.display().isValid()) slot.display().remove();
            slot.block().getWorld().playSound(slot.block(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.7f);
            slot.block().getWorld().playSound(slot.block(), Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.5f);
            slot.block().getWorld().spawnParticle(org.bukkit.Particle.SMOKE,
                    slot.block().clone().add(0.5, 1.1, 0.5), 25, 0.2, 0.2, 0.2, 0.02);
            player.sendMessage(msg("The pot RUPTURES in the Censer — the batch is LOST. "
                    + "Buffer with amethyst_shard before stability bottoms out.", NamedTextColor.RED));
            return;
        }
        Cogito.write(vialItem, st);
        if (slot.display().isValid()) slot.display().setItemStack(vialItem); // reflect the new colour/composition
        reagentFeedback(player, r, result, st);
    }

    /** Return a seated vial when its Censer is broken (don't lose the player's work). */
    public void censerReturnOnBreak(org.bukkit.Location loc) {
        CenserSlot slot = censers.remove(locKey(loc));
        if (slot == null) return;
        if (slot.display().isValid()) slot.display().remove();
        loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), slot.vial());
    }

    /** Drop every seated vial back into the world on shutdown so none are lost. */
    public void censerDropAll() {
        for (CenserSlot slot : censers.values()) {
            if (slot.display().isValid()) slot.display().remove();
            slot.block().getWorld().dropItemNaturally(slot.block().clone().add(0.5, 0.5, 0.5), slot.vial());
        }
        censers.clear();
    }

    /** Plugin shutdown: return every seated vial and reap any live Well carousel so no entities are stranded. */
    public void disable() {
        censerDropAll();
        wellDisplay.stop();
    }

    /** The Centrifuge (Grindstone): distill the held vial. */
    public void stationCentrifuge(Player player) { distill(player); }

    /** The Manifold (Chiseled Bookshelf): blend all charged vials you carry. */
    public void stationManifold(Player player) { blend(player); }

    /** The Crucible (Smithing Table): right-click forges the best catalyst you can afford; sneak = sublimate. */
    public void stationCrucible(Player player, boolean sneaking) {
        if (sneaking) {
            insertCatalyst(player); // sneak = bond a forged catalyst into your cogito vial
            return;
        }
        Catalysts.Recipe best = null;
        EgoGrade bestGrade = null;
        for (Catalysts.Recipe rec : Catalysts.all()) {
            if (!canAfford(player, rec)) continue;
            WeaponSpec w = WeaponSignatures.byId(rec.weaponId());
            EgoGrade g = w != null ? w.grade() : EgoGrade.ZAYIN;
            if (best == null || g.ordinal() > bestGrade.ordinal()) { best = rec; bestGrade = g; }
        }
        if (best == null) {
            player.sendMessage(msg("The Crucible has nothing to forge — gather a catalyst's grind components "
                    + "first (right-click the Assay to identify one, or /cogito recipes <id>).", NamedTextColor.RED));
            return;
        }
        forge(player, new String[]{"forge", best.weaponId()});
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 0.8f);
    }

    private boolean canAfford(Player player, Catalysts.Recipe rec) {
        WeaponSpec w = WeaponSignatures.byId(rec.weaponId());
        EgoGrade g = w != null ? w.grade() : EgoGrade.ZAYIN;
        for (var e : CatalystCost.components(rec, g).entrySet()) {
            if (countMaterial(player, e.getKey()) < e.getValue()) return false;
        }
        if (w != null) for (var e : CatalystCost.refinedTax(w).entrySet()) {
            if (countRefined(player, e.getKey()) < e.getValue()) return false;
        }
        return countEnkephalin(player) >= CatalystCost.enkephalin(rec, g);
    }

    /** How many of a specific refined reagent (by id, via its PDC tag) the player holds. */
    private int countRefined(Player player, String reagentId) {
        int c = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && reagentId.equals(RefinedReagent.idOf(it))) c += it.getAmount();
        }
        return c;
    }

    /** Remove {@code n} of a specific refined reagent (by id) from the player's inventory. */
    private void consumeRefined(Player player, String reagentId, int n) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && n > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || !reagentId.equals(RefinedReagent.idOf(it))) continue;
            int take = Math.min(n, it.getAmount());
            it.setAmount(it.getAmount() - take);
            n -= take;
            player.getInventory().setItem(i, it.getAmount() <= 0 ? null : it);
        }
    }

    /**
     * The Pocket Well — an ominous chamber. Right-click <b>peers in</b>: it reveals (floating inside) the
     * weapon your current cogito is most likely to manifest, without consuming anything. Sneak-right-click
     * <b>pours</b> for real (the commit).
     */
    public void stationWell(Player player, org.bukkit.Location wellLoc, boolean sneaking) {
        // An Extraction Ticket pours straight from its pools — no cogito needed.
        ItemStack held = player.getInventory().getItemInMainHand();
        if (ExtractionTicket.matches(held)) { ticketWell(player, wellLoc, sneaking, held); return; }

        if (sneaking) { pour(player, new String[]{"pour"}); return; }

        Vial v = locateVial(player);
        if (v == null) { player.sendMessage(msg("Hold a Cogito vial and peer into the Well.", GREY)); return; }
        PotState st = v.state();
        if (st.isBlank()) { player.sendMessage(msg("The chamber is dark — the vial is empty.", GREY)); return; }
        List<WellRoll.Chance> pool = WellRoll.pool(st);
        if (pool.isEmpty()) {
            player.sendMessage(msg("The chamber shows nothing — too thin or crude to reach any weapon.",
                    NamedTextColor.RED));
            return;
        }
        WellRoll.Chance top = pool.get(0);
        // The living carousel: top pick spinning at centre, candidates orbiting with live odds tags.
        wellDisplay.reveal(wellLoc, pool, this::weaponItem);
        player.sendActionBar(msg("The Well shows " + top.weapon().display() + " ("
                + Math.round(top.odds() * 100) + "%) — sneak-click to pour.", GREEN));
    }

    /** Pour an Extraction Ticket at the Well: spin the carousel over its pools, then manifest a random weapon. */
    private void ticketWell(Player player, org.bukkit.Location wellLoc, boolean sneaking, ItemStack ticket) {
        java.util.Set<String> pools = ExtractionTicket.pools(ticket);
        List<WeaponSpec> pool = new ArrayList<>();
        for (WeaponSpec w : WeaponSignatures.all()) if (pools.contains(w.grade().name())) pool.add(w);
        if (pool.isEmpty()) {
            player.sendMessage(msg("This ticket has no reachable pools — add one with /cogito ticket add <grade>.",
                    NamedTextColor.RED));
            return;
        }
        double odds = 1.0 / pool.size();
        List<WellRoll.Chance> chances = new ArrayList<>();
        for (WeaponSpec w : pool) chances.add(new WellRoll.Chance(w, 1.0, odds));
        wellDisplay.reveal(wellLoc, chances, this::weaponItem);

        if (!sneaking) {
            player.sendActionBar(msg("Ticket — " + pool.size() + " weapons across "
                    + String.join("/", pools) + ". Sneak-click to pull.", GREEN));
            return;
        }
        // Pull a random weapon; only spend the ticket if a weapon actually manifests.
        WeaponSpec pick = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        Weapon weapon = plugin.weapons().get(pick.id());
        if (weapon == null) {
            player.sendMessage(msg("No item is wired for '" + pick.id() + "' yet — ticket kept.", NamedTextColor.RED));
            return;
        }
        ItemStack item = weapon.createItem();
        stampAttribution(item, player.getName(), pick.grade().minCogito(), 100.0);
        item = plugin.tracker().register(item, weapon.id(), player.getName() + " (ticket)");
        giveOrDrop(player, item);
        plugin.weapons().engage(weapon, player.getUniqueId());
        ticket.setAmount(ticket.getAmount() - 1);
        player.getInventory().setItemInMainHand(ticket.getAmount() <= 0 ? null : ticket);
        player.sendMessage(msg("The Well grants " + pick.display() + " (" + pick.grade().display()
                + ") from the ticket.", GREEN));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);
    }

    /** {@code /cogito ticket [grades…] | add <grade|all> | clear} — the admin/event Extraction Ticket. */
    private void ticket(Player player, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("add")) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!ExtractionTicket.matches(held)) {
                player.sendMessage(msg("Hold an Extraction Ticket to add a pool (/cogito ticket to get one).",
                        NamedTextColor.RED));
                return;
            }
            if (a.length < 3) {
                player.sendMessage(msg("Usage: /cogito ticket add <zayin|teth|he|waw|aleph|all>", GREY));
                return;
            }
            if (a[2].equalsIgnoreCase("all")) ExtractionTicket.addAllPools(held);
            else if (!ExtractionTicket.addPool(held, a[2])) {
                player.sendMessage(msg("Invalid or already-present pool: " + a[2], NamedTextColor.RED));
                return;
            }
            player.getInventory().setItemInMainHand(held);
            player.sendMessage(msg("Ticket pools: " + String.join(", ", ExtractionTicket.pools(held)), GREEN));
            return;
        }
        if (a.length >= 2 && a[1].equalsIgnoreCase("clear")) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!ExtractionTicket.matches(held)) {
                player.sendMessage(msg("Hold an Extraction Ticket to clear it.", NamedTextColor.RED));
                return;
            }
            ExtractionTicket.clearPools(held);
            player.getInventory().setItemInMainHand(held);
            player.sendMessage(msg("Cleared the ticket's pools.", GREEN));
            return;
        }
        // Default: give a blank ticket (plus any grades listed after "ticket").
        ItemStack t = ExtractionTicket.create();
        for (int i = 1; i < a.length; i++) {
            if (a[i].equalsIgnoreCase("all")) ExtractionTicket.addAllPools(t);
            else ExtractionTicket.addPool(t, a[i]);
        }
        giveOrDrop(player, t);
        player.sendMessage(msg("Gave an Extraction Ticket. Chain pools with /cogito ticket add <grade>, then "
                + "right-click the Well to preview (sneak = pull).", GREEN));
    }

    /** Float the most-likely weapon above the Well for a few seconds — the item inside the ominous chamber. */
    /** Give an item, dropping any overflow at the player's feet so a full inventory never eats it. */
    private void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    // ---- assay + lectern -----------------------------------------------------------

    /** Assay = query state. Bare: identify the held item (Cogito → full readout, else its info — same as the
     *  Lectern). With a reagent id: the lectern's what-if projection. */
    private void assay(Player player, String[] a) {
        if (a.length >= 2) { whatIf(player, a); return; }
        describeItem(player, player.getInventory().getItemInMainHand());
    }

    /** The full Cogito readout — composition, gauges, active afflictions, and live Well odds. */
    private void assayPot(Player player, PotState st) {
        player.sendMessage(msg("— Cogito assay —", NamedTextColor.WHITE));
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

    /** The what-if projection: assay <reagentId> shows what the pot WOULD look like after that reagent. */
    private void whatIf(Player player, String[] a) {
        Reagent r = Reagents.byId(a[1].toLowerCase());
        if (r == null) { player.sendMessage(msg("No such reagent: " + a[1], NamedTextColor.RED)); return; }
        PotState st = held(player);
        if (st == null) return;

        // Project onto a copy — this never touches the real pot, and never rolls a failure.
        PotState projected = st.copy();
        Engine.AddResult ignored = Engine.addReagent(projected, r, new java.util.Random(0L));

        player.sendMessage(msg("What-if — if you add " + r.display() + ":", NamedTextColor.WHITE));
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
        // Don't silently lose a catalyst: refuse to blend vials aimed at different weapons.
        String aim = null;
        for (PotState p : pots) {
            if (p.catalystTarget() == null) continue;
            if (aim == null) aim = p.catalystTarget();
            else if (!aim.equals(p.catalystTarget())) {
                player.sendMessage(msg("These vials hold catalysts for different weapons — blending would "
                        + "lose one. Pour or separate them first.", NamedTextColor.RED));
                return;
            }
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
            String id = a[1].toLowerCase();

            // 1) A weapon catalyst.
            WeaponSpec w = WeaponSignatures.byId(id);
            Catalysts.Recipe rec = w == null ? null : Catalysts.forWeapon(w.id());
            if (rec != null) {
                player.sendMessage(msg(w.display() + " Catalyst (" + w.grade().display() + ") — forge needs:",
                        NamedTextColor.WHITE));
                for (var e : CatalystCost.components(rec, w.grade()).entrySet()) {
                    player.sendMessage(msg("  " + e.getValue() + "x " + pretty(e.getKey()), FAINT));
                }
                for (var e : CatalystCost.refinedTax(w).entrySet()) {
                    Reagent rr = Reagents.byId(e.getKey());
                    player.sendMessage(msg("  " + e.getValue() + "x " + (rr != null ? rr.display() : e.getKey())
                            + " (refined)", TextColor.color(0xB8F0E4)));
                }
                player.sendMessage(msg("  + " + CatalystCost.enkephalin(rec, w.grade()) + " Enkephalin", FAINT));
                return;
            }

            // 2) A refined reagent (Pure/Standard) — a shapeless crafting grid + legend.
            if (RefinedReagent.has(id)) {
                Reagent r = Reagents.byId(id);
                Sin rs = RefinedReagent.sinOf(id);
                List<GridIng> ings = new ArrayList<>();
                if (RefinedReagent.isStandard(id)) {
                    Reagent pureR = Reagents.byId(RefinedReagent.pureId(rs));
                    ings.add(new GridIng(RefinedReagent.PURE_PER_STANDARD, rs.color(),
                            pureR != null ? pureR.display() : "Pure"));
                    ings.add(new GridIng(1, GOLD, pretty(RefinedReagent.gateFor(id))));
                } else {
                    ings.add(new GridIng(RefinedReagent.CONCENTRATE_PER_PURE, rs.color(),
                            rs.display() + " Concentrate"));
                    ings.add(new GridIng(1, TextColor.color(0x9B59D0), "Amethyst Shard"));
                    ings.add(new GridIng(1, TextColor.color(0xBBBBBB), "Iron Ingot"));
                }
                sendCraftGrid(player, (r != null ? r.display() : id) + " — crafting table:", ings);
                return;
            }

            // 3) A sin concentrate (by sin name, e.g. "gloom").
            Sin sin = sinByName(id);
            if (sin != null) {
                Material prim = SinConcentrate.rawFor(sin), sec = SinConcentrate.secondaryFor(sin);
                Sin secSin = SinConcentrate.sinOfRaw(sec);
                sendCraftGrid(player, sin.display() + " Concentrate — crafting table:", List.of(
                        new GridIng(SinConcentrate.RAW_PER_CONCENTRATE, sin.color(), pretty(prim)),
                        new GridIng(1, TextColor.color(0xBBBBBB), "Iron Nugget"),
                        new GridIng(1, secSin != null ? secSin.color() : NamedTextColor.WHITE, pretty(sec))));
                return;
            }

            player.sendMessage(msg("No recipe for '" + a[1] + "'. Try a weapon, a refined reagent id, or a sin.",
                    NamedTextColor.RED));
            return;
        }

        // No arg — the index of everything you can look up.
        player.sendMessage(msg("Recipes — /cogito recipes <id>:", NamedTextColor.WHITE));
        player.sendMessage(msg("Catalysts (" + Catalysts.count() + "): "
                + String.join(", ", weaponIds()), FAINT));
        player.sendMessage(msg("Refined reagents: "
                + String.join(", ", refinedIds()), TextColor.color(0xB8F0E4)));
        player.sendMessage(msg("Concentrates (by sin): " + sinNames(), FAINT));
    }

    /** A visual ingredient for the crafting grid. */
    private record GridIng(int count, TextColor color, String name) {}

    /** Render a shapeless recipe as a 3×3 grid of coloured squares + a legend (order is illustrative). */
    private void sendCraftGrid(Player player, String title, List<GridIng> ings) {
        player.sendMessage(msg(title, NamedTextColor.WHITE));
        List<TextColor> slots = new ArrayList<>();
        for (GridIng g : ings) for (int i = 0; i < g.count() && slots.size() < 9; i++) slots.add(g.color());
        for (int row = 0; row < 3; row++) {
            Component line = Component.text("   ");
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                line = line.append(idx < slots.size()
                        ? Component.text("■ ", slots.get(idx))
                        : Component.text("▫ ", FAINT));
            }
            player.sendMessage(line);
        }
        player.sendMessage(msg("Legend (shapeless — any arrangement):", FAINT));
        for (GridIng g : ings) {
            player.sendMessage(Component.text("   ").append(Component.text("■ ", g.color()))
                    .append(Component.text(g.name() + " ×" + g.count(), FAINT)));
        }
    }

    /** A sin by its name (e.g. "gloom"), tolerant of a trailing "_concentrate"; null if unknown. */
    private Sin sinByName(String name) {
        String n = name.replace("_concentrate", "").replace(" concentrate", "").trim().toUpperCase(java.util.Locale.ROOT);
        try { return Sin.valueOf(n); } catch (IllegalArgumentException e) { return null; }
    }

    private List<String> refinedIds() {
        List<String> out = new ArrayList<>();
        for (String id : RefinedReagent.ids()) out.add(id);
        return out;
    }

    private String sinNames() {
        java.util.StringJoiner j = new java.util.StringJoiner(", ");
        for (Sin s : Sin.values()) j.add(s.name().toLowerCase(java.util.Locale.ROOT));
        return j.toString();
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

        // Steep, grade-scaled cost: the multiplied vanilla grind + Enkephalin + the refined-reagent tax.
        var components = CatalystCost.components(rec, w.grade());
        int enkCost = CatalystCost.enkephalin(rec, w.grade());
        var refinedTax = CatalystCost.refinedTax(w);

        List<String> missing = new ArrayList<>();
        for (var e : components.entrySet()) {
            int have = countMaterial(player, e.getKey());
            if (have < e.getValue()) missing.add((e.getValue() - have) + " more " + pretty(e.getKey()));
        }
        for (var e : refinedTax.entrySet()) {
            int have = countRefined(player, e.getKey());
            Reagent rr = Reagents.byId(e.getKey());
            if (have < e.getValue()) missing.add((e.getValue() - have) + " more "
                    + (rr != null ? rr.display() : e.getKey()));
        }
        int enk = countEnkephalin(player);
        if (enk < enkCost) missing.add((enkCost - enk) + " more Enkephalin");
        if (!missing.isEmpty()) {
            player.sendMessage(msg("The forge is short: " + String.join(", ", missing) + ".", NamedTextColor.RED));
            return;
        }

        // Consume and forge.
        for (var e : components.entrySet()) consumeMaterial(player, e.getKey(), e.getValue());
        for (var e : refinedTax.entrySet()) consumeRefined(player, e.getKey(), e.getValue());
        consumeEnkephalin(player, enkCost);
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
            if (isSpecialItem(it)) continue;
            n += it.getAmount();
        }
        return n;
    }

    /** A plugin item that must NOT be counted/consumed as a plain vanilla grind material (shares a Material). */
    private boolean isSpecialItem(ItemStack it) {
        return Enkephalin.matches(it) || Catalyst.matches(it) || Cogito.matches(it)
                || RefinedReagent.idOf(it) != null || SinConcentrate.sinOf(it) != null
                || CureItem.idOf(it) != null;
    }

    private void consumeMaterial(Player player, Material m, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != m) continue;
            if (isSpecialItem(it)) continue;
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

    /** Count inventory items matching a predicate. */
    private int countItem(Player player, java.util.function.Predicate<ItemStack> pred) {
        int n = 0;
        for (ItemStack it : player.getInventory().getContents()) if (it != null && pred.test(it)) n += it.getAmount();
        return n;
    }

    /** Remove {@code amount} inventory items matching a predicate. */
    private void consumeItem(Player player, java.util.function.Predicate<ItemStack> pred, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            if (contents[i] == null || !pred.test(contents[i])) continue;
            int take = Math.min(remaining, contents[i].getAmount());
            contents[i].setAmount(contents[i].getAmount() - take);
            if (contents[i].getAmount() <= 0) player.getInventory().setItem(i, null);
            remaining -= take;
        }
    }

    private void consumeSlotOne(Player player, int slot) {
        ItemStack it = player.getInventory().getItem(slot);
        if (it == null) return;
        it.setAmount(it.getAmount() - 1);
        player.getInventory().setItem(slot, it.getAmount() <= 0 ? null : it);
    }

    // ---- the Well: pour ------------------------------------------------------------

    /**
     * Bond a forged catalyst ITEM (from the inventory) into the located Cogito vial — the catalyst then lives
     * inside the vial (shown in its lore), inherited through blend + distill, and spent when you pour. Called
     * by {@code /cogito insert} and by sneak-clicking the Crucible.
     */
    public void insertCatalyst(Player player) {
        Vial v = locateVial(player);
        if (v == null) { noVial(player); return; }
        PotState st = v.state();

        ItemStack[] contents = player.getInventory().getContents();
        int slot = -1; String wid = null;
        for (int i = 0; i < contents.length; i++) {
            String w = Catalyst.weaponId(contents[i]);
            if (w != null) { slot = i; wid = w; break; }
        }
        if (wid == null) {
            player.sendMessage(msg("You have no forged catalyst to insert — forge one at the Crucible first.",
                    NamedTextColor.RED));
            return;
        }
        if (st.catalystTarget() != null && !st.catalystTarget().equals(wid)) {
            WeaponSpec cur = WeaponSignatures.byId(st.catalystTarget());
            player.sendMessage(msg("This vial is already aimed at " + (cur != null ? cur.display()
                    : st.catalystTarget()) + " — you can't mix catalysts.", NamedTextColor.RED));
            return;
        }
        WeaponSpec w = WeaponSignatures.byId(wid);
        boolean apex = w != null && w.grade().isApex();
        if (apex && st.catalystCount() >= 1) {
            player.sendMessage(msg("An apex catalyst is a lock, not a stack — one is enough.", NamedTextColor.RED));
            return;
        }
        if (st.catalystCount() >= 3) {
            player.sendMessage(msg("This vial is already at max catalysts (3/3).", NamedTextColor.RED));
            return;
        }
        st.catalystTarget(wid);
        st.catalystCount(st.catalystCount() + 1);
        writeVial(player, v);
        consumeSlotOne(player, slot);
        String name = w != null ? w.display() : wid;
        if (apex) {
            player.sendMessage(msg("Bonded the " + name + " catalyst — this is now a Radiant Cogito, the "
                    + "REQUIRED form to manifest it. Get it to your top pull past 70% and pour.", GREEN));
        } else {
            player.sendMessage(msg("Bonded the " + name + " catalyst (" + st.catalystCount() + "/3) — each "
                    + "stack adds 1–15% to its odds, once it's your top pull past 70%.", GREEN));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.4f, 1.6f);
    }

    private void pour(Player player, String[] a) {
        Vial v = locateVial(player);
        if (v == null) { noVial(player); return; }
        PotState st = v.state();
        if (st.isBlank()) { player.sendMessage(msg("The vial is empty.", NamedTextColor.RED)); return; }

        // Catalyst = the one inserted into this vial (inherited through blend/distill). An explicit id arg is
        // a testbed override.
        String catalyst = a.length >= 2 ? a[1].toLowerCase() : st.catalystTarget();

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

        // The inserted catalyst is spent with the vial (already consumed above).

        switch (r.outcome()) {
            case MANIFEST -> onManifest(player, r);
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
        // Cogito is a POTION (max stack 1) so a located vial is always a single item — write it in place.
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
        line(player, "giveall <cat>", "dispense items by category (rawmaterials/materials/cures/catalysts/all)");
        line(player, "reagents", "list reagent ids");
        line(player, "add <id>", "add a reagent to the held vial (the Censer)");
        line(player, "assay [reagentId]", "identify the held item; with a reagent id = what-if projection");
        line(player, "stations", "get the 8 craftable station blocks");
        line(player, "distill", "run the held vial through the Centrifuge");
        line(player, "blend", "blend all charged vials you carry (the Manifold)");
        line(player, "recipes [id]", "recipe grid for a weapon/reagent/sin (open to all)");
        line(player, "forge <id>", "forge a per-weapon catalyst from grind components");
        line(player, "insert", "bond a held catalyst into your vial (Crucible sneak)");
        line(player, "pour", "pour the held vial into the Well (its inserted catalyst applies)");
        line(player, "track <id> / untrack", "forge HINT for a weapon (open to all)");
        line(player, "ticket [add <grade>]", "an Extraction Ticket — pull at the Well without a cogito");
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
