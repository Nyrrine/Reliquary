package com.nyrrine.reliquary.extraction;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
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

    public ExtractionCommand(Reliquary plugin) {
        this.plugin = plugin;
        this.wellDisplay = new WellDisplay(plugin);
    }

    /** Permission for every subcommand — give and ticket both hand out items, so all are admin-gated. */
    public static final String ADMIN_PERM = "reliquary.admin";
    /** No player-open subcommands remain (the read-only chemistry lookups are gone). */
    public static final List<String> PLAYER_SUBS = List.of();

    /** The subcommands, in help/tab order. */
    private static final List<String> SUBS = List.of("give", "giveall", "ticket");

    /** The cosmetic items {@code /cogito give <id>} can dispense (the 8 keepers; the 7 concentrates by sin). */
    private static final List<String> GIVE_ITEMS = List.of(
            "well", "enkephalin", "raw_cogito", "cogito", "radiant_cogito", "catalyst", "ember_distillate",
            "wrath", "pride", "lust", "gloom", "sloth", "envy", "gluttony");

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
        ItemStack item = cosmetic(a[1].toLowerCase());
        if (item == null) {
            player.sendMessage(msg("Unknown cosmetic: " + a[1], NamedTextColor.RED));
            return;
        }
        giveOrDrop(player, item);
        player.sendMessage(msg("Gave " + a[1].toLowerCase() + ".", GREEN));
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
                + "hold it near a Carmen's Brain to preview (sneak = pull).", GREEN));
    }

    // ---- the Well ------------------------------------------------------------------

    /** Hold an Extraction Ticket near a Carmen's Brain: preview, or (sneaking) pull. Only takes a ticket now. */
    public void stationWell(Player player, Location brainCentre, boolean sneaking) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (ExtractionTicket.matches(held)) { ticketWell(player, brainCentre, sneaking, held); return; }
        player.sendMessage(msg("Carmen's Brain draws from an Extraction Ticket — hold one nearby.", GREY));
    }

    /** Preview the ticket's pool as the solar-system show; sneaking, pull a random weapon from it. */
    private void ticketWell(Player player, Location brainCentre, boolean sneaking, ItemStack ticket) {
        java.util.Set<String> pools = ExtractionTicket.pools(ticket);
        List<WeaponSpec> pool = new ArrayList<>();
        for (WeaponSpec w : WeaponSignatures.all()) if (pools.contains(w.grade().name())) pool.add(w);
        if (pool.isEmpty()) {
            player.sendMessage(msg("This ticket has no reachable pools — add one with /cogito ticket add <grade>.",
                    NamedTextColor.RED));
            return;
        }
        // Build/keep the floating preview; on a pull we transition this same scene.
        wellDisplay.reveal(brainCentre, pool, this::weaponItem);

        if (!sneaking) {
            player.sendActionBar(msg("Ticket — " + pool.size() + " weapons across "
                    + String.join("/", pools) + ". Sneak to pull.", GREEN));
            return;
        }
        // Pull a random weapon; only spend the ticket if a weapon is actually extracted.
        WeaponSpec pick = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        Weapon weapon = plugin.weapons().get(pick.id());
        if (weapon == null) {
            player.sendMessage(msg("No item is wired for '" + pick.id() + "' yet — ticket kept.", NamedTextColor.RED));
            return;
        }
        ItemStack item = weapon.createItem();
        stampAttribution(item, player.getName());
        item = plugin.tracker().register(item, weapon.id(), player.getName() + " (ticket)");
        giveOrDrop(player, item);
        plugin.weapons().engage(weapon, player.getUniqueId());
        ticket.setAmount(ticket.getAmount() - 1);
        player.getInventory().setItemInMainHand(ticket.getAmount() <= 0 ? null : ticket);
        wellDisplay.pull(brainCentre, pick); // the gacha pull: others dissolve, the chosen blooms out
        player.sendMessage(msg("Carmen's Brain grants " + pick.display() + " (" + pick.grade().display()
                + ") from the ticket.", GREEN));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);
    }

    /** The weapon item a spec resolves to (via the plugin's registry), for the floating show. */
    private ItemStack weaponItem(WeaponSpec spec) {
        Weapon w = plugin.weapons().get(spec.id());
        return w != null ? w.createItem() : null;
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
