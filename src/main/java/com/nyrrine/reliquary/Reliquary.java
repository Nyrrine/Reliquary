package com.nyrrine.reliquary;

import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.core.WeaponManager;
import com.nyrrine.reliquary.weapons.arayashiki.ArayashikiWeapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Reliquary — a vault of unique, lore-driven relic weapons. The plugin main wires
 * up the {@link WeaponManager} and registers each relic; the first is Arayashiki.
 */
public final class Reliquary extends JavaPlugin {

    private WeaponManager weapons;

    @Override
    public void onEnable() {
        this.weapons = new WeaponManager(this);
        weapons.register(new ArayashikiWeapon(this));
        weapons.start();
        getLogger().info("Reliquary opens. Its relics stir.");
    }

    public WeaponManager weapons() {
        return weapons;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("aya")) {
            return giveWeapon(sender, "arayashiki");
        }

        // /reliquary ...
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("give")) {
            if (args.length < 2) {
                sendUsage(sender);
                return true;
            }
            return giveWeapon(sender, args[1].toLowerCase());
        }
        if (sub.equals("list")) {
            StringBuilder sb = new StringBuilder("Relics:");
            for (Weapon w : weapons.all()) {
                sb.append(' ').append(w.id());
            }
            sender.sendMessage(Component.text(sb.toString()).color(NamedTextColor.GRAY));
            return true;
        }
        sendUsage(sender);
        return true;
    }

    private boolean giveWeapon(CommandSender sender, String id) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a wielder can hold a relic.");
            return true;
        }
        Weapon weapon = weapons.get(id);
        if (weapon == null) {
            player.sendMessage(Component.text("No such relic: " + id).color(NamedTextColor.RED));
            return true;
        }
        player.getInventory().addItem(weapon.createItem());
        player.sendMessage(Component.text("The House of Spiders hands you ")
                .color(NamedTextColor.GRAY)
                .append(Component.text(ArayashikiWeapon.BLADE_NAME).color(NamedTextColor.WHITE))
                .append(Component.text(".").color(NamedTextColor.GRAY)));
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /reliquary give <id> | /reliquary list")
                .color(NamedTextColor.GRAY));
    }
}
