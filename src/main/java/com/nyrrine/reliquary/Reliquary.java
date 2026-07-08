package com.nyrrine.reliquary;

import com.nyrrine.reliquary.core.RelicTracker;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.core.WeaponManager;
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

    private static final String PERMISSION = "reliquary.admin";

    private WeaponManager weapons;
    private RelicTracker tracker;

    @Override
    public void onEnable() {
        this.weapons = new WeaponManager(this);
        weapons.register(new ArayashikiWeapon(this));
        GungnirWeapon gungnir = new GungnirWeapon(this);
        weapons.register(gungnir);
        LaevateinnWeapon laevateinn = new LaevateinnWeapon(this);
        weapons.register(laevateinn);
        weapons.start();
        getServer().getPluginManager().registerEvents(new GungnirOffhandGuard(gungnir), this);
        getServer().getPluginManager().registerEvents(new GungnirVibration(gungnir), this);
        getServer().getPluginManager().registerEvents(new LaevateinnDoubleJump(this, laevateinn), this);
        getServer().getPluginManager().registerEvents(new LaevateinnMelee(laevateinn), this);

        this.tracker = new RelicTracker(this);
        tracker.start();

        PluginCommand cmd = getCommand("reliquary");
        if (cmd != null) cmd.setTabCompleter(this);

        getLogger().info("Reliquary opens. Its relics stir.");
    }

    @Override
    public void onDisable() {
        if (weapons != null) weapons.disable();
    }

    public WeaponManager weapons() {
        return weapons;
    }

    public RelicTracker tracker() {
        return tracker;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            // Light red so it stands out in chat.
            sender.sendMessage(Component.text("Contact Nyrrine if you're supposed to have access to this.")
                    .color(NamedTextColor.RED));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> {
                StringBuilder sb = new StringBuilder("Relics:");
                for (Weapon w : weapons.all()) sb.append(' ').append(w.id());
                sender.sendMessage(Component.text(sb.toString()).color(NamedTextColor.GRAY));
            }
            case "give" -> giveWeapon(sender, args);
            case "admin" -> adminGive(sender, args);
            case "track" -> trackCmd(sender);
            case "purge" -> purgeCmd(sender, args);
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
        help(sender, "/reliquary admin <id> [player]", "give an admin/debug variant (e.g. Worthy Lævateinn)");
        help(sender, "/reliquary track", "list every relic and who holds it");
        help(sender, "/reliquary purge <player>", "remove all relics from a player");
    }

    private void help(CommandSender sender, String cmd, String desc) {
        sender.sendMessage(Component.text("  " + cmd).color(NamedTextColor.GRAY)
                .append(Component.text("  — " + desc).color(NamedTextColor.DARK_GRAY)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) return Collections.emptyList();

        if (args.length == 1) {
            return filter(List.of("give", "admin", "list", "track", "purge", "help"), args[0]);
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
