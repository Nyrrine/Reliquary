package com.nyrrine.reliquary.distortion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code /abeautifulvoice} and {@code /carmen}.
 *
 * <pre>
 * /abeautifulvoice form [player]      become Carmen; no name means you
 * /abeautifulvoice form off [player]  give them back
 * /carmen &lt;message&gt;                   speak one line as her, no transform
 * </pre>
 *
 * <p>{@code /carmen} is the zero-risk way to use the voice: it prints the line and touches nothing —
 * no profile swap, no chat renderer, no listener in anyone's way. For a single line it's strictly
 * better than transforming, and it's the thing to reach for first.
 */
final class DistortionCommand implements CommandExecutor, TabCompleter {

    /** Matches the node the rest of the plugin's operator commands already use. */
    private static final String ADMIN_PERM = "reliquary.admin";

    private final CarmenForm form;

    DistortionCommand(CarmenForm form) {
        this.form = form;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(ADMIN_PERM)) {
            sender.sendMessage(Component.text("The voice doesn't answer to you.").color(NamedTextColor.RED));
            return true;
        }

        if (command.getName().equalsIgnoreCase("carmen")) {
            return speak(sender, args);
        }

        if (args.length == 0 || !args[0].equalsIgnoreCase("form")) {
            sendHelp(sender);
            return true;
        }
        return form(sender, args);
    }

    /** /carmen &lt;message&gt; — her line, spoken to the server, with nothing else moved. */
    private boolean speak(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /carmen <message>").color(NamedTextColor.GRAY));
            return true;
        }
        Bukkit.getServer().sendMessage(CarmenVoice.line(String.join(" ", args)));
        return true;
    }

    /** /abeautifulvoice form [player] | form off [player] */
    private boolean form(CommandSender sender, String[] args) {
        boolean off = args.length >= 2 && args[1].equalsIgnoreCase("off");

        // form off [player] -> args[2]; form [player] -> args[1]. Absent means the sender.
        int nameAt = off ? 2 : 1;
        Player target;
        if (args.length > nameAt) {
            target = Bukkit.getPlayerExact(args[nameAt]);
            if (target == null) {
                sender.sendMessage(Component.text("Nobody here by that name: " + args[nameAt])
                        .color(NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text("Name someone: /abeautifulvoice form "
                    + (off ? "off " : "") + "<player>").color(NamedTextColor.RED));
            return true;
        }

        if (off) {
            if (!form.revert(target)) {
                sender.sendMessage(Component.text(describe(sender, target) + " not wearing her.")
                        .color(NamedTextColor.GRAY));
                return true;
            }
            sender.sendMessage(Component.text("The voice lets go.").color(NamedTextColor.GRAY));
            if (!target.equals(sender)) {
                target.sendMessage(Component.text("The voice lets go of you.").color(NamedTextColor.GRAY));
            }
            return true;
        }

        if (!form.become(target)) {
            sender.sendMessage(Component.text(describe(sender, target) + " already wearing her.")
                    .color(NamedTextColor.GRAY));
            return true;
        }
        sender.sendMessage(Component.text("A beautiful voice.").color(NamedTextColor.LIGHT_PURPLE));
        if (!target.equals(sender)) {
            target.sendMessage(Component.text("A beautiful voice takes you.").color(NamedTextColor.LIGHT_PURPLE));
        }
        return true;
    }

    /** "You're" when they mean themselves, otherwise the name — reads right in either message. */
    private static String describe(CommandSender sender, Player target) {
        return target.equals(sender) ? "You're" : target.getName() + " is";
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("A beautiful voice:").color(NamedTextColor.LIGHT_PURPLE));
        help(sender, "/abeautifulvoice form [player]", "become Carmen — name, skin, voice");
        help(sender, "/abeautifulvoice form off [player]", "give them back");
        help(sender, "/carmen <message>", "speak one line as her, without transforming");
    }

    private void help(CommandSender sender, String cmd, String desc) {
        sender.sendMessage(Component.text("  " + cmd).color(NamedTextColor.GRAY)
                .append(Component.text("  — " + desc).color(NamedTextColor.DARK_GRAY)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(ADMIN_PERM) || command.getName().equalsIgnoreCase("carmen")) {
            return Collections.emptyList();
        }
        if (args.length == 1) return filter(List.of("form"), args[0]);
        if (!args[0].equalsIgnoreCase("form")) return Collections.emptyList();

        if (args.length == 2) {
            List<String> options = new ArrayList<>(List.of("off"));
            options.addAll(names());
            return filter(options, args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("off")) return filter(names(), args[2]);
        return Collections.emptyList();
    }

    private static List<String> names() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase().startsWith(p)) out.add(o);
        }
        return out;
    }
}
