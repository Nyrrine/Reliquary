package com.nyrrine.reliquary.extraction;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The {@code /cogito} command — now just the ticket + gacha Carmen's Brain and a cosmetic dispenser. The brewing
 * minigame (Font/Alembic/Censer/Centrifuge/Manifold/Crucible chemistry) has been removed; getting an E.G.O
 * weapon is: hold an Extraction Ticket near a deployed Carmen's Brain, right-click anywhere to preview the pool
 * as a floating show, sneak right-click to pull a random weapon from the ticket's grade pools. (The actual
 * weapon-give for the testbed stays on {@code /reliquary}.)
 *
 * <p>Reached via {@code /cogito <sub> ...} (aliases {@code /ext}, {@code /co}) or {@code /reliquary ext ...}.
 */
public final class ExtractionCommand {

    private final Reliquary plugin;
    private final WellDisplay wellDisplay;
    private CarmenBrainVfx brainVfx; // wired after construction (the Brain manager is built later)

    public ExtractionCommand(Reliquary plugin) {
        this.plugin = plugin;
        this.wellDisplay = new WellDisplay(plugin);
    }

    /** Wire the Brain manager so a pull can shrink/restore the Brain for focus. */
    public void attachBrain(CarmenBrainVfx brainVfx) { this.brainVfx = brainVfx; }

    /** Permission for every subcommand — give and ticket both hand out items, so all are admin-gated. */
    public static final String ADMIN_PERM = "reliquary.admin";
    /** No player-open subcommands remain (the read-only chemistry lookups are gone). */
    public static final List<String> PLAYER_SUBS = List.of();

    /** The subcommands, in help/tab order. */
    private static final List<String> SUBS = List.of("give", "giveall", "ticket");

    /** The cosmetic items {@code /cogito give <id>} can dispense (the 8 keepers; the 7 concentrates by sin;
     *  plus the pouch/bag testing gives, which take their own path in {@link #give}). */
    private static final List<String> GIVE_ITEMS = List.of(
            "well", "enkephalin", "raw_cogito", "cogito", "radiant_cogito", "catalyst", "ember_distillate",
            "wrath", "pride", "lust", "gloom", "sloth", "envy", "gluttony", "pouch", "bag");

    private static final TextColor GREEN = TextColor.color(0x74F066);
    private static final TextColor GREY  = NamedTextColor.GRAY;
    private static final TextColor FAINT = TextColor.color(0x7A7A84);

    // ---- dispatch ------------------------------------------------------------------

    public void handle(CommandSender sender, String[] a) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Extraction is a player command.").color(NamedTextColor.RED));
            return;
        }
        if (a.length < 1) { help(player); return; }
        String sub = a[0].toLowerCase();
        if (!PLAYER_SUBS.contains(sub) && !player.hasPermission(ADMIN_PERM)) {
            player.sendMessage(msg("That's an admin command.", NamedTextColor.RED));
            return;
        }
        switch (sub) {
            case "give"    -> give(player, a);
            case "giveall" -> giveAll(player);
            case "ticket"  -> ticket(player, a);
            default        -> help(player);
        }
    }

    private void help(Player player) {
        player.sendMessage(msg("Cogito extraction:", GREEN));
        player.sendMessage(msg("• /cogito ticket [grades…] — get an Extraction Ticket; add pools with "
                + "/cogito ticket add <grade>.", FAINT));
        player.sendMessage(msg("• Hold the ticket near a Carmen's Brain and right-click to preview, sneak "
                + "right-click to pull a random weapon.", FAINT));
        player.sendMessage(msg("• /cogito give <item> or /cogito giveall — the cosmetic items.", FAINT));
    }

    // ---- cosmetic give -------------------------------------------------------------

    private void give(Player player, String[] a) {
        if (a.length < 2) {
            player.sendMessage(msg("Usage: /cogito give <" + String.join("|", GIVE_ITEMS) + ">", GREY));
            return;
        }
        String id = a[1].toLowerCase();
        if (id.equals("pouch")) {
            Pouch.Rarity r = a.length >= 3 ? Pouch.Rarity.byId(a[2]) : null;
            if (r == null) {
                player.sendMessage(msg("Usage: /cogito give pouch <common|uncommon|rare|legendary>", GREY));
                return;
            }
            giveOrDrop(player, Pouch.create(r));
            player.sendMessage(msg("Gave a " + r.display() + " Loot.", GREEN));
            return;
        }
        if (id.equals("bag")) {
            giveOrDrop(player, DaughtersBag.create());
            player.sendMessage(msg("Gave A Certain Daughters Bag.", GREEN));
            return;
        }
        ItemStack item = cosmetic(id);
        if (item == null) {
            player.sendMessage(msg("Unknown cosmetic: " + a[1], NamedTextColor.RED));
            return;
        }
        giveOrDrop(player, item);
        player.sendMessage(msg("Gave " + id + ".", GREEN));
    }

    private void giveAll(Player player) {
        for (String id : GIVE_ITEMS) {
            ItemStack it = cosmetic(id);
            if (it != null) giveOrDrop(player, it);
        }
        player.sendMessage(msg("Dispensed the extraction cosmetics. Overflow is at your feet.", GREEN));
    }

    /** The cosmetic item for a give id, or {@code null} if unknown. */
    private ItemStack cosmetic(String id) {
        return switch (id) {
            case "well"             -> StationType.WELL.createItem(); // the craftable Carmen's Brain, for testing
            case "enkephalin"       -> Enkephalin.create(16);
            case "raw_cogito"       -> RawCogito.create(16);
            case "cogito"           -> Cogito.create();
            case "radiant_cogito"   -> Cogito.createRadiant();
            case "catalyst"         -> Catalyst.create();
            case "ember_distillate" -> Cosmetics.emberDistillate(16);
            default -> {
                SinConcentrate.Kind k = SinConcentrate.byId(id);
                yield k != null ? SinConcentrate.create(k, 16) : null;
            }
        };
    }

    // ---- ticket --------------------------------------------------------------------

    /** {@code /cogito ticket [grades…] | custom | add <grade|id|all> | clear} — the admin/event Extraction Ticket. */
    private void ticket(Player player, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("add")) { ticketAdd(player, a); return; }
        if (a.length >= 2 && a[1].equalsIgnoreCase("clear")) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!ExtractionTicket.matches(held)) {
                player.sendMessage(msg("Hold an Extraction Ticket to clear it.", NamedTextColor.RED));
                return;
            }
            ExtractionTicket.clearPools(held);
            player.getInventory().setItemInMainHand(held);
            player.sendMessage(msg("Cleared the ticket's pools and picks.", GREEN));
            return;
        }
        if (a.length >= 2 && a[1].equalsIgnoreCase("custom")) {
            giveOrDrop(player, ExtractionTicket.createCustom());
            player.sendMessage(msg("Gave a Custom Extraction Ticket. Hand-pick weapons with /cogito ticket add "
                    + "<id> (grades still work too), then hold it near a Carmen's Brain.", GREEN));
            return;
        }
        if (a.length >= 2 && a[1].equalsIgnoreCase("standard")) {
            giveOrDrop(player, ExtractionTicket.createStandard());
            player.sendMessage(msg("Gave a Standard Extraction Ticket. Hold it near a Carmen's Brain and sneak "
                    + "to roll the weighted table (weapons, pouches, or the bag).", GREEN));
            return;
        }
        // Default: give a blank grade ticket (plus any grades listed after "ticket").
        ItemStack t = ExtractionTicket.create();
        for (int i = 1; i < a.length; i++) {
            if (a[i].equalsIgnoreCase("all")) ExtractionTicket.addAllPools(t);
            else ExtractionTicket.addPool(t, a[i]);
        }
        giveOrDrop(player, t);
        player.sendMessage(msg("Gave an Extraction Ticket. Chain pools with /cogito ticket add <grade>, then "
                + "hold it near a Carmen's Brain to preview (sneak = pull).", GREEN));
    }

    /** {@code /cogito ticket add <arg>} — auto-routes: a grade goes to the grade pool, a weapon id to the picks. */
    private void ticketAdd(Player player, String[] a) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!ExtractionTicket.matches(held)) {
            player.sendMessage(msg("Hold an Extraction Ticket to add to it (/cogito ticket to get one).",
                    NamedTextColor.RED));
            return;
        }
        if (a.length < 3) {
            player.sendMessage(msg("Usage: /cogito ticket add <grade | weapon-id | all>", GREY));
            return;
        }
        String arg = a[2];
        if (arg.equalsIgnoreCase("all")) {
            ExtractionTicket.addAllPools(held);
        } else if (ExtractionTicket.isValidPool(arg)) {          // a grade → grade pool
            if (!ExtractionTicket.addPool(held, arg)) {
                player.sendMessage(msg("Pool already on the ticket: " + arg.toUpperCase(java.util.Locale.ROOT),
                        NamedTextColor.RED));
                return;
            }
        } else if (plugin.weapons().get(arg.toLowerCase(java.util.Locale.ROOT)) != null) { // a weapon id → picks
            if (!ExtractionTicket.addId(held, arg)) {
                player.sendMessage(msg("Pick already on the ticket: " + arg.toLowerCase(java.util.Locale.ROOT),
                        NamedTextColor.RED));
                return;
            }
        } else {
            player.sendMessage(msg("Not a grade or a known weapon id: " + arg, NamedTextColor.RED));
            return;
        }
        player.getInventory().setItemInMainHand(held);
        player.sendMessage(msg("Ticket now: " + ticketSummary(held), GREEN));
    }

    /** A short "pools + picks" summary of a ticket, for confirmations. */
    private String ticketSummary(ItemStack ticket) {
        java.util.Set<String> pools = ExtractionTicket.pools(ticket);
        java.util.Set<String> ids = ExtractionTicket.ids(ticket);
        StringBuilder sb = new StringBuilder();
        sb.append("pools ").append(pools.isEmpty() ? "none" : String.join("/", pools));
        if (!ids.isEmpty()) sb.append(", picks ").append(String.join("/", ids));
        return sb.toString();
    }

    // ---- the Well ------------------------------------------------------------------

    /** Hold an Extraction Ticket near a Carmen's Brain: preview, or (sneaking) pull. Only takes a ticket now. */
    public void stationWell(Player player, Location brainCentre, boolean sneaking) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (ExtractionTicket.matches(held)) { ticketWell(player, brainCentre, sneaking, held); return; }
        player.sendMessage(msg("Carmen's Brain draws from an Extraction Ticket — hold one nearby.", GREY));
    }

    /** Route a ticket-holder's Brain interaction: Standard rolls the weighted table, grade/custom the weapon pool. */
    private void ticketWell(Player player, Location brainCentre, boolean sneaking, ItemStack ticket) {
        if (ExtractionTicket.isStandard(ticket)) standardWell(player, brainCentre, sneaking, ticket);
        else poolWell(player, brainCentre, sneaking, ticket);
    }

    /** Grade/custom ticket: flat-random over the weapon pool (grade pools ∪ hand-picked ids). */
    private void poolWell(Player player, Location brainCentre, boolean sneaking, ItemStack ticket) {
        java.util.Set<String> pools = ExtractionTicket.pools(ticket);
        java.util.Set<String> ids = ExtractionTicket.ids(ticket);
        java.util.LinkedHashMap<String, WeaponSpec> byId = new java.util.LinkedHashMap<>();
        for (WeaponSpec w : WeaponSignatures.all()) if (pools.contains(w.grade().name())) byId.putIfAbsent(w.id(), w);
        for (String id : ids) { WeaponSpec s = customSpec(id); if (s != null) byId.putIfAbsent(s.id(), s); }
        List<WeaponSpec> pool = new ArrayList<>(byId.values());
        if (pool.isEmpty()) {
            player.sendMessage(msg("This ticket has no reachable pools — add a grade or a weapon id with "
                    + "/cogito ticket add <grade|id>.", NamedTextColor.RED));
            return;
        }
        List<WellDisplay.FloatItem> floats = new ArrayList<>();
        for (WeaponSpec w : pool) floats.add(weaponFloat(w));
        wellDisplay.reveal(brainCentre, floats);

        if (!sneaking) {
            String across = pools.isEmpty() ? "custom picks" : String.join("/", pools)
                    + (ids.isEmpty() ? "" : " + picks");
            player.sendActionBar(msg("Ticket: " + pool.size() + " weapons (" + across + "). Sneak to pull.", GREEN));
            return;
        }
        WeaponSpec pick = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        Weapon weapon = plugin.weapons().get(pick.id());
        if (weapon == null) { // pre-spend error: nothing is spent, ticket kept
            player.sendMessage(msg("No item is wired for '" + pick.id() + "' yet — ticket kept.", NamedTextColor.RED));
            return;
        }
        WellDisplay.PullShow show = new WellDisplay.PullShow(displayFor(weaponItem(pick)),
                pick.grade().color(), pick.grade().ordinal() + 1, false, floats);
        deliverAfterShow(player, brainCentre, ticket, weaponDeliverable(player, weapon), weapon, show);
    }

    /** Standard ticket: roll the fixed weighted table (weapons by grade, pouches, the 1% bag). */
    private void standardWell(Player player, Location brainCentre, boolean sneaking, ItemStack ticket) {
        // The whole board floats: every weapon (grade-coloured), the 4 loot tiers, and the Daughters Bag.
        List<WellDisplay.FloatItem> floats = new ArrayList<>();
        for (WeaponSpec w : WeaponSignatures.all()) floats.add(weaponFloat(w));
        for (Pouch.Rarity r : Pouch.Rarity.values()) {
            floats.add(new WellDisplay.FloatItem(Pouch.create(r), r.burst(), "loot_" + r.name()));
        }
        floats.add(new WellDisplay.FloatItem(DaughtersBag.create(), BAG_COLOR, "daughters_bag"));
        wellDisplay.reveal(brainCentre, floats);

        if (!sneaking) {
            player.sendActionBar(msg("Standard ticket — sneak to roll the table.", GREEN));
            return;
        }
        // A single weighted pick; deliver the predetermined result only when the show completes.
        StdRoll roll = rollStandard();
        ItemStack deliverable;
        Weapon engage = null;
        ItemStack display;
        Color color;
        int tier;
        boolean bag = false;
        if (roll.grade() != null) {
            WeaponSpec pick = randomWeaponOfGrade(roll.grade());
            Weapon weapon = pick != null ? plugin.weapons().get(pick.id()) : null;
            if (pick == null || weapon == null) { // pre-spend error: ticket kept
                player.sendMessage(msg("No " + roll.grade().display() + " weapon is wired yet — ticket kept.",
                        NamedTextColor.RED));
                return;
            }
            engage = weapon;
            deliverable = weaponDeliverable(player, weapon);
            display = displayFor(weaponItem(pick));
            color = pick.grade().color();
            tier = pick.grade().ordinal() + 1;
        } else if (roll.rarity() != null) {
            deliverable = Pouch.create(roll.rarity());
            display = Pouch.create(roll.rarity());
            color = roll.rarity().burst();
            tier = roll.rarity().ordinal() + 1;
        } else {
            deliverable = DaughtersBag.create();
            display = DaughtersBag.create();
            color = BAG_COLOR;
            tier = 5;
            bag = true;
        }
        WellDisplay.PullShow show = new WellDisplay.PullShow(display, color, tier, bag, floats);
        deliverAfterShow(player, brainCentre, ticket, deliverable, engage, show);
    }

    /**
     * Spend the ticket now, shrink the Brain, run the pull, and hand {@code deliverable} over only when the show
     * completes — the {@link WellDisplay#pull} callback fires <b>exactly once</b> on any termination, so a spent
     * ticket always pays out (to the player if online, else dropped at the Brain), the weapon engages on the same
     * beat, and the Brain is restored. No chat result line; the reveal is purely visual.
     */
    private void deliverAfterShow(Player player, Location brainCentre, ItemStack ticket,
                                  ItemStack deliverable, Weapon engage, WellDisplay.PullShow show) {
        spend(player, ticket);
        final java.util.UUID uuid = player.getUniqueId();
        final Location dropAt = brainCentre.clone();
        if (brainVfx != null) brainVfx.beginExtract(brainCentre);
        Runnable onComplete = () -> {
            if (brainVfx != null) brainVfx.endExtract(brainCentre);
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && p.isOnline()) giveOrDrop(p, deliverable);
            else if (dropAt.getWorld() != null) dropAt.getWorld().dropItemNaturally(dropAt, deliverable);
            if (engage != null) plugin.weapons().engage(engage, uuid);
        };
        wellDisplay.pull(brainCentre, show, onComplete);
    }

    /** Open a held Pouch: roll one loot entry, give it, consume exactly one, small rarity-coloured pop. */
    public void openPouch(Player player, ItemStack pouch) {
        Pouch.Rarity r = Pouch.rarityOf(pouch);
        if (r == null) return;
        giveOrDrop(player, Pouch.rollLoot(r));
        pouch.setAmount(pouch.getAmount() - 1);
        player.getInventory().setItemInMainHand(pouch.getAmount() <= 0 ? null : pouch);
        Location at = player.getLocation().add(0, 1.0, 0);
        player.getWorld().spawnParticle(Particle.DUST, at, 14, 0.3, 0.4, 0.3, 0,
                new Particle.DustOptions(r.burst(), 1.0f));
        player.playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
        player.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.4f);
        player.sendActionBar(msg("Opened a " + r.display() + " Loot.", GREEN));
    }

    /** The weapon item a spec resolves to (via the plugin's registry), or null if unwired. */
    private ItemStack weaponItem(WeaponSpec spec) {
        Weapon w = plugin.weapons().get(spec.id());
        return w != null ? w.createItem() : null;
    }

    /** A colour-coded floating entry for a weapon (grade colour + display + id). */
    private WellDisplay.FloatItem weaponFloat(WeaponSpec spec) {
        return new WellDisplay.FloatItem(displayFor(weaponItem(spec)), spec.grade().color(), spec.id());
    }

    /** A non-null display item, falling back to a Nether Star if a weapon isn't wired yet. */
    private ItemStack displayFor(ItemStack it) {
        return it != null ? it : new ItemStack(Material.NETHER_STAR);
    }

    /** The final deliverable for a rolled weapon: attribution + tracker register (give/engage happen on show end). */
    private ItemStack weaponDeliverable(Player player, Weapon weapon) {
        ItemStack item = weapon.createItem();
        stampAttribution(item, player.getName());
        return plugin.tracker().register(item, weapon.id(), player.getName() + " (ticket)");
    }

    /** Spend exactly one ticket from the main hand. */
    private void spend(Player player, ItemStack ticket) {
        ticket.setAmount(ticket.getAmount() - 1);
        player.getInventory().setItemInMainHand(ticket.getAmount() <= 0 ? null : ticket);
    }

    private WeaponSpec randomWeaponOfGrade(EgoGrade grade) {
        List<WeaponSpec> of = new ArrayList<>();
        for (WeaponSpec w : WeaponSignatures.all()) if (w.grade() == grade) of.add(w);
        return of.isEmpty() ? null : of.get(ThreadLocalRandom.current().nextInt(of.size()));
    }

    /** One outcome of the Standard table: a weapon grade, a pouch rarity, or the bag (exactly one non-null/true). */
    private record StdRoll(EgoGrade grade, Pouch.Rarity rarity, boolean bag) {}

    /** The weighted 100% pick across the 10 Standard buckets (weapons 20%, pouches 79%, bag 1%). */
    private StdRoll rollStandard() {
        double r = ThreadLocalRandom.current().nextDouble(100.0);
        double c = 0;
        if ((c += 1.0)  > r) return new StdRoll(EgoGrade.ALEPH, null, false);
        if ((c += 3.0)  > r) return new StdRoll(EgoGrade.WAW,   null, false);
        if ((c += 4.5)  > r) return new StdRoll(EgoGrade.HE,    null, false);
        if ((c += 5.0)  > r) return new StdRoll(EgoGrade.TETH,  null, false);
        if ((c += 6.5)  > r) return new StdRoll(EgoGrade.ZAYIN, null, false);
        if ((c += 1.0)  > r) return new StdRoll(null, null, true);                 // A Certain Daughters Bag
        if ((c += 49.0) > r) return new StdRoll(null, Pouch.Rarity.COMMON, false);
        if ((c += 20.0) > r) return new StdRoll(null, Pouch.Rarity.UNCOMMON, false);
        if ((c += 7.0)  > r) return new StdRoll(null, Pouch.Rarity.RARE, false);
        return new StdRoll(null, Pouch.Rarity.LEGENDARY, false);                   // remaining 3%
    }

    private static final Color BAG_COLOR = Color.fromRGB(0xFFC94D);

    /**
     * The spec for a hand-picked id: the roster spec if it is on the grade ladder, else a synthesized
     * {@link EgoGrade#SPECIAL} spec for any registered weapon (Twilight and the like). Null if unregistered.
     */
    private WeaponSpec customSpec(String id) {
        String key = id.toLowerCase(java.util.Locale.ROOT);
        WeaponSpec known = WeaponSignatures.byId(key);
        if (known != null) return known;
        Weapon w = plugin.weapons().get(key);
        return w != null ? new WeaponSpec(key, prettyId(key), EgoGrade.SPECIAL) : null;
    }

    /** A display label for an off-roster id: {@code sword_of_tears} → {@code Sword Of Tears}. */
    private static String prettyId(String id) {
        StringBuilder sb = new StringBuilder();
        for (String p : id.split("_")) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    // ---- lifecycle + tab -----------------------------------------------------------

    /** Reap any live extraction show on plugin disable (+ cross-world tag sweep). */
    public void disable() {
        wellDisplay.disable();
    }

    /** Tab completion. Everything is admin-gated. */
    public List<String> tabComplete(String[] a, boolean admin) {
        if (!admin) return List.of();
        if (a.length == 1) return filter(SUBS, a[0]);
        if (a.length == 2) {
            switch (a[0].toLowerCase()) {
                case "give"   -> { return filter(GIVE_ITEMS, a[1]); }
                case "ticket" -> { return filter(ticketArgs(true), a[1]); }
                default -> { }
            }
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("ticket") && a[1].equalsIgnoreCase("add")) {
            return filter(ticketArgs(false), a[2]);
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("give") && a[1].equalsIgnoreCase("pouch")) {
            return filter(List.of("common", "uncommon", "rare", "legendary"), a[2]);
        }
        return List.of();
    }

    /** Tab options for the ticket command — grades (+ all) and weapon ids, plus the subcommands at the first arg. */
    private List<String> ticketArgs(boolean firstArg) {
        List<String> out = new ArrayList<>();
        if (firstArg) { out.add("add"); out.add("clear"); out.add("custom"); out.add("standard"); }
        out.add("all");
        for (String p : ExtractionTicket.POOL_NAMES) out.add(p.toLowerCase(java.util.Locale.ROOT));
        if (!firstArg) for (Weapon w : plugin.weapons().all()) out.add(w.id()); // ids only offered under `add`
        return out;
    }

    // ---- shared helpers ------------------------------------------------------------

    /** Give an item, dropping any overflow at the player's feet so a full inventory never eats it. */
    private void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    /** Add an "Extracted by X" attribution line to a freshly-pulled weapon. */
    private void stampAttribution(ItemStack item, String extractor) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<Component> lore = meta.lore();
        List<Component> out = lore != null ? new ArrayList<>(lore) : new ArrayList<>();
        out.add(Component.empty());
        out.add(Component.text("Extracted by " + extractor, FAINT).decoration(TextDecoration.ITALIC, true));
        meta.lore(out);
        item.setItemMeta(meta);
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase().startsWith(p)) out.add(o);
        return out;
    }

    private static Component msg(String text, TextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
