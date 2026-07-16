package com.nyrrine.reliquary;

import com.nyrrine.reliquary.core.RelicTracker;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.core.WeaponManager;
import com.nyrrine.reliquary.extraction.ExtractionCommand;
import com.nyrrine.reliquary.busego.weapons.FlowerBuryingWedgeReckoning;
import com.nyrrine.reliquary.busego.weapons.FlowerBuryingWedgeWeapon;
import com.nyrrine.reliquary.ego.weapons.BeakWeapon;
import com.nyrrine.reliquary.ego.weapons.ChristmasWeapon;
import com.nyrrine.reliquary.ego.weapons.CobaltScarWeapon;
import com.nyrrine.reliquary.ego.weapons.CrimsonScarWeapon;
import com.nyrrine.reliquary.ego.weapons.GreenStemWeapon;
import com.nyrrine.reliquary.ego.weapons.GrinderMk4Weapon;
import com.nyrrine.reliquary.ego.weapons.HarvestWeapon;
import com.nyrrine.reliquary.ego.weapons.HeavenWeapon;
import com.nyrrine.reliquary.ego.weapons.LaetitiaWeapon;
import com.nyrrine.reliquary.ego.weapons.LampWeapon;
import com.nyrrine.reliquary.ego.weapons.LifeForADaredevilWeapon;
import com.nyrrine.reliquary.ego.weapons.FourthMatchFlameWeapon;
import com.nyrrine.reliquary.ego.weapons.LoggingWeapon;
import com.nyrrine.reliquary.ego.weapons.LoveAndHateWeapon;
import com.nyrrine.reliquary.ego.weapons.MagicBulletWeapon;
import com.nyrrine.reliquary.ego.weapons.OurGalaxyWeapon;
import com.nyrrine.reliquary.ego.weapons.PenitenceWeapon;
import com.nyrrine.reliquary.ego.weapons.RedEyesWeapon;
import com.nyrrine.reliquary.ego.weapons.RegretWeapon;
import com.nyrrine.reliquary.ego.weapons.ScreamingWedgeWeapon;
import com.nyrrine.reliquary.ego.weapons.SodaWeapon;
import com.nyrrine.reliquary.ego.weapons.SolemnLamentWeapon;
import com.nyrrine.reliquary.ego.weapons.SwordOfTearsWeapon;
import com.nyrrine.reliquary.ego.weapons.WristCutterWeapon;
import com.nyrrine.reliquary.weapons.arayashiki.ArayashikiWeapon;
import com.nyrrine.reliquary.weapons.gungnir.GungnirOffhandGuard;
import com.nyrrine.reliquary.weapons.gungnir.GungnirVibration;
import com.nyrrine.reliquary.weapons.gungnir.GungnirWeapon;
import com.nyrrine.reliquary.weapons.laevateinn.LaevateinnDoubleJump;
import com.nyrrine.reliquary.weapons.laevateinn.LaevateinnMelee;
import com.nyrrine.reliquary.weapons.laevateinn.LaevateinnWeapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reliquary — a vault of custom relic weapons. Wires up the {@link WeaponManager}
 * and {@link RelicTracker}, registers each relic, and exposes the operator-only
 * /reliquary command.
 */
public final class Reliquary extends JavaPlugin implements TabCompleter {

    private WeaponManager weapons;
    private RelicTracker tracker;
    private ExtractionCommand extraction;
    private com.nyrrine.reliquary.extraction.Stations stations;

    @Override
    public void onEnable() {
        this.weapons = new WeaponManager(this);
        weapons.register(new ArayashikiWeapon(this));
        GungnirWeapon gungnir = new GungnirWeapon(this);
        weapons.register(gungnir);
        LaevateinnWeapon laevateinn = new LaevateinnWeapon(this);
        weapons.register(laevateinn);

        // ---- E.G.O weapons (Lobotomy Corp roster, ZAYIN..WAW) ----
        // ZAYIN
        weapons.register(new PenitenceWeapon(this));
        weapons.register(new SodaWeapon(this));
        // TETH
        weapons.register(new FourthMatchFlameWeapon(this));
        weapons.register(new RedEyesWeapon(this));
        weapons.register(new RegretWeapon(this));
        weapons.register(new BeakWeapon(this));
        weapons.register(new LoggingWeapon(this));
        weapons.register(new WristCutterWeapon(this));
        weapons.register(new ChristmasWeapon(this));
        // HE
        weapons.register(new GrinderMk4Weapon(this));
        weapons.register(new CrimsonScarWeapon(this));
        weapons.register(new CobaltScarWeapon(this));
        weapons.register(new OurGalaxyWeapon(this));
        weapons.register(new HarvestWeapon(this));
        weapons.register(new LifeForADaredevilWeapon(this));
        weapons.register(new LaetitiaWeapon(this));
        // WAW
        weapons.register(new LampWeapon(this));
        weapons.register(new SolemnLamentWeapon(this));
        weapons.register(new SwordOfTearsWeapon(this));
        weapons.register(new GreenStemWeapon(this));
        weapons.register(new ScreamingWedgeWeapon(this));
        weapons.register(new MagicBulletWeapon(this));
        weapons.register(new HeavenWeapon(this));
        weapons.register(new LoveAndHateWeapon(this));

        // ---- bus ego ----
        FlowerBuryingWedgeWeapon flowerWedge = new FlowerBuryingWedgeWeapon(this);
        weapons.register(flowerWedge);

        weapons.start();
        getServer().getPluginManager().registerEvents(new GungnirOffhandGuard(gungnir), this);
        getServer().getPluginManager().registerEvents(new GungnirVibration(gungnir), this);
        getServer().getPluginManager().registerEvents(new LaevateinnDoubleJump(this, laevateinn), this);
        getServer().getPluginManager().registerEvents(new LaevateinnMelee(laevateinn), this);
        getServer().getPluginManager().registerEvents(new FlowerBuryingWedgeReckoning(flowerWedge), this);

        this.tracker = new RelicTracker(this);
        tracker.start();

        this.extraction = new ExtractionCommand(this);
        new com.nyrrine.reliquary.extraction.CogitoTicker(this).start();
        this.stations = new com.nyrrine.reliquary.extraction.Stations(this);
        stations.load();
        stations.registerRecipes();
        com.nyrrine.reliquary.extraction.SinConcentrate.registerRecipes(this);
        com.nyrrine.reliquary.extraction.RefinedReagent.registerRecipes(this);
        com.nyrrine.reliquary.extraction.CureItem.registerRecipes(this);
        getServer().getPluginManager().registerEvents(
                new com.nyrrine.reliquary.extraction.StationListener(extraction, stations), this);

        // The Index (/prescript) wires its own command and listener, and holds no state to disable.
        new com.nyrrine.reliquary.index.PrescriptCommand(this).register();

        PluginCommand cmd = getCommand("reliquary");
        if (cmd != null) cmd.setTabCompleter(this);

        // The short extraction command (/cogito, aliases /ext /co) shares this class as executor + completer.
        PluginCommand cogito = getCommand("cogito");
        if (cogito != null) {
            cogito.setExecutor(this);
            cogito.setTabCompleter(this);
        }

        getLogger().info("Reliquary opens. Its relics stir.");
    }

    @Override
    public void onDisable() {
        if (weapons != null) weapons.disable();
        if (stations != null) stations.save();
        if (extraction != null) extraction.disable(); // return seated vials + reap any live Well carousel
    }

    public WeaponManager weapons() {
        return weapons;
    }

    public RelicTracker tracker() {
        return tracker;
    }

    /** True if this player's client has the server resource pack loaded (cosmetic pack models render). */
    public boolean hasPack(org.bukkit.entity.Player player) {
        return weapons != null && weapons.hasPack(player.getUniqueId());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        // No blanket gate — /cogito's open lookups (recipes/track/…) and /reliquary list/help are for everyone;
        // the give/brew/admin operations are gated individually below and in ExtractionCommand.handle.

        // /cogito (aliases /ext /co): args ARE the extraction sub-args directly.
        if (command.getName().equalsIgnoreCase("cogito")) {
            extraction.handle(sender, args);
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        if ((sub.equals("give") || sub.equals("giveall") || sub.equals("admin") || sub.equals("purge"))
                && !sender.hasPermission(com.nyrrine.reliquary.extraction.ExtractionCommand.ADMIN_PERM)) {
            sender.sendMessage(Component.text("Admin only — normal play is at the crafted stations.")
                    .color(NamedTextColor.RED));
            return true;
        }
        switch (sub) {
            case "list" -> {
                StringBuilder sb = new StringBuilder("Relics:");
                for (Weapon w : weapons.all()) sb.append(' ').append(w.id());
                sender.sendMessage(Component.text(sb.toString()).color(NamedTextColor.GRAY));
            }
            case "give" -> giveWeapon(sender, args);
            case "giveall" -> giveAll(sender, args);
            case "admin" -> adminGive(sender, args);
            case "track" -> trackCmd(sender);
            case "purge" -> purgeCmd(sender, args);
            case "ext", "extraction", "cogito" ->
                    extraction.handle(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
            default -> sendHelp(sender);
        }
        return true;
    }

    /** /reliquary give <id> [player] */
    private void giveWeapon(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /reliquary give <id> [player]").color(NamedTextColor.GRAY));
            return;
        }

        String id = args[1].toLowerCase();
        Weapon weapon = weapons.get(id);
        if (weapon == null) {
            sender.sendMessage(Component.text("No such relic: " + id).color(NamedTextColor.RED));
            return;
        }

        Player target;
        if (args.length >= 3) {
            target = getServer().getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[2]).color(NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text("Specify a player: /reliquary give " + id + " <player>")
                    .color(NamedTextColor.RED));
            return;
        }

        // Stamp a unique instance id and record who received it before handing it over.
        ItemStack item = tracker.register(weapon.createItem(), weapon.id(), target.getName());
        target.getInventory().addItem(item);
        weapons.engage(weapon, target.getUniqueId());

        sender.sendMessage(Component.text("Gave ").color(NamedTextColor.GRAY)
                .append(Component.text(id).color(NamedTextColor.WHITE))
                .append(Component.text(" to ").color(NamedTextColor.GRAY))
                .append(Component.text(target.getName()).color(NamedTextColor.WHITE))
                .append(Component.text(".").color(NamedTextColor.GRAY)));
        if (!target.equals(sender)) {
            target.sendMessage(Component.text("You received ").color(NamedTextColor.GRAY)
                    .append(Component.text(id).color(NamedTextColor.WHITE))
                    .append(Component.text(".").color(NamedTextColor.GRAY)));
        }
    }

    /**
     * /reliquary giveall &lt;relics|egoequipment|busego&gt; [player] — hand over every weapon of a category.
     * Relics are the bespoke weapons (Arayashiki/Gungnir/Lævateinn); E.G.O equipment is the Lobotomy Corp
     * roster (package {@code ego.weapons}); bus ego is the {@code busego.weapons} roster. Each item is
     * tracked + engaged like a normal give.
     */
    private void giveAll(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /reliquary giveall <relics|egoequipment|busego> [player]")
                    .color(NamedTextColor.GRAY));
            return;
        }
        String cat = args[1].toLowerCase();
        String want;   // which package fragment identifies the category
        String label;  // human label for the result message
        switch (cat) {
            case "egoequipment", "ego" -> { want = ".ego.weapons.";    label = "E.G.O equipment"; }
            case "busego", "bus"       -> { want = ".busego.weapons."; label = "bus ego"; }
            case "relics", "relic"     -> { want = "";                 label = "relic(s)"; }
            default -> {
                sender.sendMessage(Component.text("Category must be 'relics', 'egoequipment', or 'busego'.")
                        .color(NamedTextColor.RED));
                return;
            }
        }

        Player target;
        if (args.length >= 3) {
            target = getServer().getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[2]).color(NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text("Specify a player: /reliquary giveall " + cat + " <player>")
                    .color(NamedTextColor.RED));
            return;
        }

        int given = 0;
        for (Weapon w : weapons.all()) {
            if (!inCategory(w, want)) continue;
            ItemStack item = tracker.register(w.createItem(), w.id(), target.getName());
            target.getInventory().addItem(item);
            weapons.engage(w, target.getUniqueId());
            given++;
        }
        sender.sendMessage(Component.text("Gave " + given + " " + label + " to " + target.getName() + ".")
                .color(NamedTextColor.GRAY));
    }

    /**
     * Whether weapon {@code w} belongs to the category identified by package fragment {@code want}.
     * An empty fragment means "relics" — anything in neither the ego nor the bus-ego package.
     */
    private static boolean inCategory(Weapon w, String want) {
        String cls = w.getClass().getName();
        if (want.isEmpty()) return !cls.contains(".ego.weapons.") && !cls.contains(".busego.weapons.");
        return cls.contains(want);
    }

    /** /reliquary admin <id> [player] — give an admin/debug variant of a relic (if it has one). */
    private void adminGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /reliquary admin <id> [player]").color(NamedTextColor.GRAY));
            return;
        }
        String id = args[1].toLowerCase();
        Weapon weapon = weapons.get(id);
        if (weapon == null) {
            sender.sendMessage(Component.text("No such relic: " + id).color(NamedTextColor.RED));
            return;
        }
        ItemStack variant = weapon.adminVariant();
        if (variant == null) {
            sender.sendMessage(Component.text(id + " has no admin variant.").color(NamedTextColor.RED));
            return;
        }

        Player target;
        if (args.length >= 3) {
            target = getServer().getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[2]).color(NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text("Specify a player: /reliquary admin " + id + " <player>")
                    .color(NamedTextColor.RED));
            return;
        }

        ItemStack item = tracker.register(variant, weapon.id(), target.getName() + " (admin)");
        target.getInventory().addItem(item);
        weapons.engage(weapon, target.getUniqueId());
        sender.sendMessage(Component.text("Gave admin variant of ").color(NamedTextColor.GRAY)
                .append(Component.text(id).color(NamedTextColor.WHITE))
                .append(Component.text(" to " + target.getName() + ".").color(NamedTextColor.GRAY)));
    }

    /** /reliquary track — list every relic instance and who holds it, plus any out in flight. */
    private void trackCmd(CommandSender sender) {
        List<RelicTracker.Entry> entries = tracker.list();
        List<String> inFlight = new ArrayList<>();
        for (Weapon w : weapons.all()) inFlight.addAll(w.outstandingReport());

        if (entries.isEmpty() && inFlight.isEmpty()) {
            sender.sendMessage(Component.text("No relics are currently in play.").color(NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("Tracked relics (" + entries.size() + "):").color(NamedTextColor.WHITE));
        for (RelicTracker.Entry e : entries) {
            sender.sendMessage(Component.text("  " + RelicTracker.shortId(e.id())).color(NamedTextColor.AQUA)
                    .append(Component.text("  " + e.weaponId()).color(NamedTextColor.GRAY))
                    .append(Component.text("  " + e.where()).color(NamedTextColor.WHITE))
                    .append(Component.text("  (" + e.origin() + ")").color(NamedTextColor.DARK_GRAY)));
        }
        if (!inFlight.isEmpty()) {
            sender.sendMessage(Component.text("In flight (" + inFlight.size() + "):").color(NamedTextColor.WHITE));
            for (String line : inFlight) {
                sender.sendMessage(Component.text("  " + line).color(NamedTextColor.GOLD));
            }
        }
    }

    /** /reliquary purge <player> — strip all relics from a player. */
    private void purgeCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /reliquary purge <player>").color(NamedTextColor.GRAY));
            return;
        }
        Player target = getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: " + args[1]).color(NamedTextColor.RED));
            return;
        }
        int removed = tracker.purge(target);
        sender.sendMessage(Component.text("Removed " + removed + " relic(s) from " + target.getName() + ".")
                .color(NamedTextColor.GRAY));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("Reliquary commands (operator only):").color(NamedTextColor.WHITE));
        help(sender, "/reliquary help", "this help");
        help(sender, "/reliquary list", "list relic ids");
        help(sender, "/reliquary give <id> [player]", "give a relic to yourself or a player");
        help(sender, "/reliquary giveall <relics|egoequipment|busego> [player]", "give every weapon of a category");
        help(sender, "/reliquary admin <id> [player]", "give an admin/debug variant (e.g. Worthy Lævateinn)");
        help(sender, "/reliquary track", "list every relic and who holds it");
        help(sender, "/reliquary purge <player>", "remove all relics from a player");
        help(sender, "/reliquary ext ...", "the Cogito extraction testbed (brew, add, distill, pour)");
    }

    private void help(CommandSender sender, String cmd, String desc) {
        sender.sendMessage(Component.text("  " + cmd).color(NamedTextColor.GRAY)
                .append(Component.text("  — " + desc).color(NamedTextColor.DARK_GRAY)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        boolean admin = sender.hasPermission(com.nyrrine.reliquary.extraction.ExtractionCommand.ADMIN_PERM);

        // /cogito (aliases /ext /co): the completer itself gates admin subs vs the open lookups (recipes/track…).
        if (command.getName().equalsIgnoreCase("cogito")) {
            return extraction.tabComplete(args, admin);
        }

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("list", "track", "help"));
            if (admin) subs.addAll(List.of("give", "giveall", "admin", "purge", "ext"));
            return filter(subs, args[0]);
        }
        // /reliquary ext ...: hand the tail (sub-args) to the extraction completer (admin-only).
        if (args[0].equalsIgnoreCase("ext") || args[0].equalsIgnoreCase("extraction")
                || args[0].equalsIgnoreCase("cogito")) {
            return extraction.tabComplete(java.util.Arrays.copyOfRange(args, 1, args.length), admin);
        }
        if (!admin && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("giveall")
                || args[0].equalsIgnoreCase("admin") || args[0].equalsIgnoreCase("purge"))) {
            return Collections.emptyList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("giveall")) {
            return filter(List.of("relics", "egoequipment", "busego"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("giveall")) {
            return filter(onlinePlayerNames(), args[2]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("admin"))) {
            List<String> ids = new ArrayList<>();
            for (Weapon w : weapons.all()) {
                if (args[0].equalsIgnoreCase("admin") && w.adminVariant() == null) continue;
                ids.add(w.id());
            }
            return filter(ids, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("purge")) {
            return filter(onlinePlayerNames(), args[1]);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("admin"))) {
            return filter(onlinePlayerNames(), args[2]);
        }
        return Collections.emptyList();
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : getServer().getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase().startsWith(p)) out.add(o);
        }
        return out;
    }
}
