package com.nyrrine.reliquary.index;

import com.nyrrine.reliquary.Reliquary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /prescript} — the Index's whole surface.
 *
 * <p>Self-registering: it owns its command wiring and its one listener, so the plugin's {@code onEnable} adds
 * a single line and {@code core/} stays untouched. Nothing here ticks. The Index is entirely command- and
 * event-driven — no per-player loop, no scheduled task, no spawned entity — so it costs nothing at rest and
 * needs no {@code onDisable}.
 *
 * <p><b>A Weaver writes every prescript and rules on it.</b> There is no pool, no drawing, and no
 * auto-detection: an instruction can therefore be anything a person can read and judge, which is the whole
 * freedom the system trades its machinery for. It is also the lore — the Library has no hit detection, it has
 * someone who passes judgement.
 *
 * <p><b>That someone is anonymous.</b> No paper and no message names the Weaver who issued a prescript. It
 * arrives from the Index.
 *
 * <p>Nothing expires. A prescript bears the date it was issued, and that date is flavour — the Index keeps no
 * clock, and whether an errand has gone stale is a Weaver's opinion.
 */
public final class PrescriptCommand implements CommandExecutor, TabCompleter, Listener {

    private final Reliquary plugin;
    private final IndexStore store;

    /**
     * Raised hands, awaiting a Weaver — recipient UUID → the prescripts they say they've done.
     *
     * <p><b>In memory, and session-scoped on purpose.</b> Enumerating "everyone with an open prescript"
     * across offline players would need an index of its own, and the store deliberately doesn't offer one:
     * per-player data is get-by-UUID, so the alternative is reading every playerdata file the server has ever
     * accumulated. The claim <i>itself</i> is persisted on the recipient's record, so nothing durable is lost
     * here — only the convenience of a pre-assembled queue, which rebuilds the moment anyone re-claims. A
     * restart costs a Weaver one command, not a player their word.
     */
    private final Map<UUID, Set<UUID>> raised = new HashMap<>();

    public PrescriptCommand(Reliquary plugin) {
        this.plugin = plugin;
        this.store = new IndexStore(plugin.store());
    }

    /** Wire the command and the paper listener. The only thing {@code Reliquary.onEnable} needs to call. */
    public void register() {
        PluginCommand cmd = plugin.getCommand("prescript");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Op-only — granting the Weaver role is administration, not roleplay. */
    private static final String ADMIN_PERM = "reliquary.admin";

    /** Subcommands anyone may run. Everything else is a Weaver's or an admin's. */
    private static final List<String> PLAYER_SUBS = List.of("paper", "claim");
    private static final List<String> WEAVER_SUBS = List.of("issue", "withdraw", "judge", "pending");
    private static final List<String> ADMIN_SUBS  = List.of("weaver");

    /** How a Weaver rules. Nyrrine's words, not "complete" and "fail". */
    private static final List<String> RULINGS = List.of("accomplished", "unaccomplished");

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("The Index answers players.").color(NamedTextColor.RED));
            return true;
        }

        // Bare /prescript — your own standing.
        if (args.length == 0) {
            self(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("help")) { help(player); return true; }

        if (PLAYER_SUBS.contains(sub)) {
            switch (sub) {
                case "paper" -> paper(player);
                case "claim" -> claim(player, args);
                default      -> help(player);
            }
            return true;
        }

        if (WEAVER_SUBS.contains(sub)) {
            if (!store.isWeaver(player)) {
                player.sendMessage(msg("Only a Weaver may issue prescripts.", NamedTextColor.RED));
                return true;
            }
            switch (sub) {
                case "issue"    -> issue(player, args);
                case "withdraw" -> withdraw(player, args);
                case "judge"    -> judge(player, args);
                case "pending"  -> pending(player);
                default         -> help(player);
            }
            return true;
        }

        if (ADMIN_SUBS.contains(sub)) {
            if (!player.hasPermission(ADMIN_PERM)) {
                player.sendMessage(msg("Only an admin appoints Weavers.", NamedTextColor.RED));
                return true;
            }
            weaver(player, args);
            return true;
        }

        // /prescript <player> — anyone may read anyone's standing. The record is public by design: a tally
        // nobody can see does no social work, and the Library's records are not private.
        other(player, args[0]);
        return true;
    }

    // ---- reading ------------------------------------------------------------------------------------

    /** Your own tally and your outstanding prescripts. */
    private void self(Player player) {
        IndexStore.Tally t = store.tally(player.getUniqueId());
        player.sendMessage(tallyLine("Your standing in the Index", t));

        List<Prescript> active = store.active(player.getUniqueId());
        if (active.isEmpty()) {
            player.sendMessage(msg("  Nothing is asked of you.", PrescriptPaper.faint()));
            return;
        }
        int i = 1;
        for (Prescript p : active) {
            player.sendMessage(msg("  " + i++ + ".", PrescriptPaper.seal())
                    .append(msg(" " + p.text(), PrescriptPaper.ink())));
            player.sendMessage(msg("     issued " + PrescriptPaper.ago(p.outstandingSeconds())
                    + (p.claimed() ? " · claimed" : ""), PrescriptPaper.faint()));
        }
    }

    /** Someone else's tally. Their outstanding prescripts are their own business; the tally is public. */
    private void other(Player player, String targetName) {
        OfflinePlayer target = resolve(targetName);
        if (target == null) {
            player.sendMessage(msg("The Index has no record of " + targetName + ".", NamedTextColor.RED));
            return;
        }
        IndexStore.Tally t = store.tally(target.getUniqueId());
        player.sendMessage(tallyLine(nameOf(target) + "'s standing in the Index", t));
    }

    /** "Accomplished 4 · Unaccomplished 1" — or an honest nothing. */
    private Component tallyLine(String heading, IndexStore.Tally t) {
        Component head = msg(heading + " — ", PrescriptPaper.seal());
        if (t.isEmpty()) return head.append(msg("no prescripts ruled on.", PrescriptPaper.faint()));
        return head
                .append(msg("Accomplished " + t.accomplished(),
                        t.accomplished() > 0 ? PrescriptPaper.green() : PrescriptPaper.faint()))
                .append(msg(" · ", PrescriptPaper.faint()))
                .append(msg("Unaccomplished " + t.unaccomplished(),
                        t.unaccomplished() > 0 ? PrescriptPaper.red() : PrescriptPaper.faint()));
    }

    /** Reissue papers for your outstanding prescripts — the errand outlives the receipt. */
    private void paper(Player player) {
        List<Prescript> active = store.active(player.getUniqueId());
        if (active.isEmpty()) {
            player.sendMessage(msg("Nothing is asked of you. There is nothing to reissue.",
                    PrescriptPaper.faint()));
            return;
        }
        int given = 0;
        for (Prescript p : active) {
            if (holds(player, p.id())) continue; // don't hand out a second copy of one they already carry
            hand(player, PrescriptPaper.create(p, player.getUniqueId(), player.getName()));
            given++;
        }
        player.sendMessage(given == 0
                ? msg("You already carry every prescript asked of you.", PrescriptPaper.faint())
                : msg("Reissued " + given + (given == 1 ? " prescript." : " prescripts."), PrescriptPaper.seal()));
    }

    // ---- issuing ------------------------------------------------------------------------------------

    /** {@code /prescript issue <player> <text…>} — a Weaver writes one by hand. */
    private void issue(Player weaver, String[] args) {
        if (args.length < 3) {
            weaver.sendMessage(msg("Usage: /prescript issue <player> <text…>", PrescriptPaper.faint()));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            weaver.sendMessage(msg(args[1] + " is not here. A prescript is handed over in person.",
                    NamedTextColor.RED));
            return;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
        if (text.isEmpty()) {
            weaver.sendMessage(msg("A prescript with no instruction is just paper.", NamedTextColor.RED));
            return;
        }
        hand(weaver, target, new Prescript(UUID.randomUUID(), text, weaver.getUniqueId(), now(), false));
    }

    /** Record the prescript, hand over the paper, and tell them both. */
    private void hand(Player weaver, Player target, Prescript p) {
        store.issue(target.getUniqueId(), p);
        hand(target, PrescriptPaper.create(p, target.getUniqueId(), target.getName()));

        // The recipient is told the Index wants something, never who decided it should.
        target.sendMessage(Component.empty());
        target.sendMessage(msg("The Index has a prescript for you.", PrescriptPaper.seal()));
        for (Component line : PrescriptPaper.read(p)) target.sendMessage(line);
        target.playSound(target.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);

        weaver.sendMessage(msg("Issued to " + target.getName() + " — ", PrescriptPaper.seal())
                .append(msg(p.text(), PrescriptPaper.ink())));
    }

    /** {@code /prescript withdraw <player> [#]} — revoke. Never counts against anyone. */
    private void withdraw(Player weaver, String[] args) {
        if (args.length < 2) {
            weaver.sendMessage(msg("Usage: /prescript withdraw <player> [#]", PrescriptPaper.faint()));
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            weaver.sendMessage(msg("The Index has no record of " + args[1] + ".", NamedTextColor.RED));
            return;
        }
        List<Prescript> active = store.active(target.getUniqueId());
        if (active.isEmpty()) {
            weaver.sendMessage(msg("Nothing is asked of " + nameOf(target) + ".", PrescriptPaper.faint()));
            return;
        }
        Prescript p = pick(weaver, active, args, 2);
        if (p == null) return;
        store.withdraw(target.getUniqueId(), p.id());
        weaver.sendMessage(msg("Withdrawn — ", PrescriptPaper.seal()).append(msg(p.text(), PrescriptPaper.ink())));
        if (target.getPlayer() != null) {
            target.getPlayer().sendMessage(msg("The Index withdraws a prescript. Nothing is held against you.",
                    PrescriptPaper.faint()));
        }
    }

    // ---- adjudication -------------------------------------------------------------------------------
    //
    // A person rules on every prescript. Nothing is detected, which is what lets a Weaver write an
    // instruction no machine could ever check — "without acknowledging them", "saying nothing" — and still
    // have it mean something. A judge is also the lore: the Library does not have hit detection; it has
    // someone who passes judgement.

    /** {@code /prescript claim [#]} — the recipient says they've carried it out. */
    private void claim(Player player, String[] args) {
        List<Prescript> active = store.active(player.getUniqueId());
        if (active.isEmpty()) {
            player.sendMessage(msg("Nothing is asked of you.", PrescriptPaper.faint()));
            return;
        }
        Prescript p = pick(player, active, args, 1);
        if (p == null) return;
        if (!store.claim(player.getUniqueId(), p.id())) {
            player.sendMessage(msg("Your hand is already up on that one. A Weaver will get to it.",
                    PrescriptPaper.faint()));
            return;
        }
        raised.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashSet<>()).add(p.id());

        player.sendMessage(msg("The Index notes your claim. A Weaver will judge it.", PrescriptPaper.seal()));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.2f);

        // Tell whichever Weavers are here. Whoever is absent finds it via /prescript pending, or the
        // recipient simply claims again — a raised hand is cheap to raise twice.
        int told = 0;
        for (Player w : Bukkit.getOnlinePlayers()) {
            if (w.equals(player) || !store.isWeaver(w)) continue;
            w.sendMessage(msg(player.getName() + " claims a prescript — ", PrescriptPaper.seal())
                    .append(msg(p.text(), PrescriptPaper.ink())));
            w.sendMessage(msg("  /prescript judge " + player.getName()
                    + " accomplished | unaccomplished", PrescriptPaper.faint()));
            told++;
        }
        if (told == 0) {
            player.sendMessage(msg("No Weaver is here to see it. It will keep.", PrescriptPaper.faint()));
        }
    }

    /** {@code /prescript judge <player> [#] <accomplished|unaccomplished>} — the ruling. */
    private void judge(Player weaver, String[] args) {
        if (args.length < 3) {
            weaver.sendMessage(msg("Usage: /prescript judge <player> [#] <accomplished|unaccomplished>",
                    PrescriptPaper.faint()));
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            weaver.sendMessage(msg("The Index has no record of " + args[1] + ".", NamedTextColor.RED));
            return;
        }
        // A Weaver may not rule on their own prescript — that would make the tally a self-report.
        if (target.getUniqueId().equals(weaver.getUniqueId())) {
            weaver.sendMessage(msg("You may not rule on yourself.", NamedTextColor.RED));
            return;
        }
        List<Prescript> active = store.active(target.getUniqueId());
        if (active.isEmpty()) {
            weaver.sendMessage(msg("Nothing is asked of " + nameOf(target) + ".", PrescriptPaper.faint()));
            return;
        }

        // The ruling is the last argument; anything between the name and it is the optional index.
        String verdict = args[args.length - 1].toLowerCase();
        if (!RULINGS.contains(verdict)) {
            weaver.sendMessage(msg("Rule it accomplished or unaccomplished.", NamedTextColor.RED));
            return;
        }
        // "judge <player> <verdict>" carries no index; "judge <player> <#> <verdict>" puts it at 2. Passing
        // args.length as the position is how pick() is told the index is absent.
        Prescript p = pick(weaver, active, args, args.length > 3 ? 2 : args.length);
        if (p == null) return;

        boolean accomplished = verdict.equals("accomplished");
        if (!store.rule(target.getUniqueId(), p.id(), accomplished)) {
            weaver.sendMessage(msg("The Index has already closed that one.", PrescriptPaper.faint()));
            return;
        }
        forget(target.getUniqueId(), p.id());

        TextColor c = accomplished ? PrescriptPaper.green() : PrescriptPaper.red();
        weaver.sendMessage(msg(nameOf(target) + " — " + verdict + ". ", c)
                .append(msg(p.text(), PrescriptPaper.ink())));

        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(Component.empty());
            online.sendMessage(msg("The Index has ruled: ", PrescriptPaper.seal()).append(msg(verdict, c)));
            online.sendMessage(msg("  " + p.text(), PrescriptPaper.ink()));
            online.playSound(online.getLocation(),
                    accomplished ? Sound.BLOCK_AMETHYST_BLOCK_CHIME : Sound.BLOCK_BEACON_DEACTIVATE,
                    1f, accomplished ? 1.2f : 0.8f);
            sweep(online, p.id()); // the paper is spent; take it back
        }
    }

    /** {@code /prescript pending} — the raised hands a Weaver can see from here. */
    private void pending(Player weaver) {
        int shown = 0;
        for (var entry : raised.entrySet()) {
            for (UUID prescriptId : entry.getValue()) {
                Prescript p = store.find(entry.getKey(), prescriptId);
                if (p == null) continue; // ruled on or withdrawn since the hand went up
                if (shown++ == 0) weaver.sendMessage(msg("Claimed, awaiting judgement", PrescriptPaper.seal()));
                weaver.sendMessage(msg("  " + name(entry.getKey()) + " — ", PrescriptPaper.seal())
                        .append(msg(p.text(), PrescriptPaper.ink())));
            }
        }
        if (shown == 0) {
            weaver.sendMessage(msg("No hands are up.", PrescriptPaper.faint()));
            weaver.sendMessage(msg("This queue is only what's been claimed since the server started — a "
                    + "restart clears it, and claiming again refills it.", PrescriptPaper.faint()));
        }
    }

    /** Drop a raised hand once it's been ruled on or withdrawn. */
    private void forget(UUID target, UUID prescriptId) {
        Set<UUID> hands = raised.get(target);
        if (hands == null) return;
        hands.remove(prescriptId);
        if (hands.isEmpty()) raised.remove(target); // don't accumulate empty sets for every player who claims
    }

    /** Take back the paper for a prescript the Index has closed. */
    private void sweep(Player player, UUID prescriptId) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && prescriptId.equals(PrescriptPaper.idOf(item))) inv.setItem(i, null);
        }
    }

    // ---- the Weaver role ----------------------------------------------------------------------------

    /** {@code /prescript weaver <add|remove|list> [player]} — op-only. */
    private void weaver(Player admin, String[] args) {
        if (args.length < 2) {
            admin.sendMessage(msg("Usage: /prescript weaver <add|remove|list> [player]",
                    PrescriptPaper.faint()));
            return;
        }
        String action = args[1].toLowerCase();

        if (action.equals("list")) {
            var ids = store.weavers();
            if (ids.isEmpty()) {
                admin.sendMessage(msg("No Weavers are appointed. Ops may still issue prescripts.",
                        PrescriptPaper.faint()));
                return;
            }
            admin.sendMessage(msg("Weavers — ", PrescriptPaper.seal())
                    .append(msg(String.valueOf(ids.size()), PrescriptPaper.ink())));
            for (UUID id : ids) admin.sendMessage(msg("  " + name(id), PrescriptPaper.ink()));
            return;
        }

        if (args.length < 3) {
            admin.sendMessage(msg("Usage: /prescript weaver " + action + " <player>", PrescriptPaper.faint()));
            return;
        }
        OfflinePlayer target = resolve(args[2]);
        if (target == null) {
            admin.sendMessage(msg("The Index has no record of " + args[2] + ".", NamedTextColor.RED));
            return;
        }

        switch (action) {
            case "add" -> {
                if (store.grantWeaver(target.getUniqueId())) {
                    admin.sendMessage(msg(nameOf(target) + " is now a Weaver.", PrescriptPaper.seal()));
                    if (target.getPlayer() != null) {
                        target.getPlayer().sendMessage(
                                msg("You are a Weaver of the Index. You may issue prescripts.",
                                        PrescriptPaper.seal()));
                    }
                } else {
                    admin.sendMessage(msg(nameOf(target) + " is already a Weaver.", PrescriptPaper.faint()));
                }
            }
            case "remove" -> {
                if (store.revokeWeaver(target.getUniqueId())) {
                    // Their tally stands, and so do the prescripts they issued — history is not undone by a
                    // change of office. Any Weaver may rule on what they left behind.
                    admin.sendMessage(msg(nameOf(target) + " is no longer a Weaver.", PrescriptPaper.seal()));
                } else {
                    admin.sendMessage(msg(nameOf(target) + " was not a Weaver.", PrescriptPaper.faint()));
                }
            }
            default -> admin.sendMessage(msg("Usage: /prescript weaver <add|remove|list> [player]",
                    PrescriptPaper.faint()));
        }
    }

    // ---- the paper ----------------------------------------------------------------------------------

    /** Right-click a prescript to read it. The paper's only mechanism. */
    @EventHandler(ignoreCancelled = true)
    public void onRead(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return; // one read per click, not two
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        UUID id = PrescriptPaper.idOf(item);
        if (id == null) return;

        event.setCancelled(true);
        Player reader = event.getPlayer();
        UUID target = PrescriptPaper.targetOf(item);

        // The record is the authority — a paper only points at one. If the record is gone, the paper is a
        // souvenir: the prescript was ruled on or withdrawn while it sat in someone's pocket.
        Prescript p = target == null ? null : store.find(target, id);
        if (p == null) {
            reader.sendMessage(msg("This prescript is spent. The Index has closed it.", PrescriptPaper.faint()));
            return;
        }
        for (Component line : PrescriptPaper.read(p)) reader.sendMessage(line);
        if (target != null && !target.equals(reader.getUniqueId())) {
            reader.sendMessage(msg("It is addressed to " + name(target) + ", not to you.",
                    PrescriptPaper.faint()));
        }
        reader.playSound(reader.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.1f);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private void help(Player player) {
        player.sendMessage(msg("The Index", PrescriptPaper.seal()));
        player.sendMessage(msg("  /prescript — your standing", PrescriptPaper.ink()));
        player.sendMessage(msg("  /prescript <player> — theirs", PrescriptPaper.ink()));
        player.sendMessage(msg("  /prescript paper — reissue a lost prescript", PrescriptPaper.ink()));
        player.sendMessage(msg("  /prescript claim [#] — say you've done it", PrescriptPaper.ink()));
        if (store.isWeaver(player)) {
            player.sendMessage(msg("  /prescript issue <player> <text…>", PrescriptPaper.ink()));
            player.sendMessage(msg("  /prescript judge <player> [#] <accomplished|unaccomplished>",
                    PrescriptPaper.ink()));
            player.sendMessage(msg("  /prescript pending — claimed, awaiting you", PrescriptPaper.ink()));
            player.sendMessage(msg("  /prescript withdraw <player> [#]", PrescriptPaper.ink()));
        }
        if (player.hasPermission(ADMIN_PERM)) {
            player.sendMessage(msg("  /prescript weaver <add|remove|list> [player]", PrescriptPaper.ink()));
        }
    }

    /** Pick a prescript from {@code active} by 1-based index at {@code argIndex}, defaulting to the only one. */
    private Prescript pick(Player sender, List<Prescript> active, String[] args, int argIndex) {
        if (args.length <= argIndex) {
            if (active.size() == 1) return active.get(0);
            sender.sendMessage(msg("They hold " + active.size() + " prescripts — say which (1-"
                    + active.size() + ").", PrescriptPaper.faint()));
            int i = 1;
            for (Prescript p : active) {
                sender.sendMessage(msg("  " + i++ + ". ", PrescriptPaper.seal())
                        .append(msg(p.text(), PrescriptPaper.ink())));
            }
            return null;
        }
        try {
            int n = Integer.parseInt(args[argIndex]);
            if (n < 1 || n > active.size()) {
                sender.sendMessage(msg("No such prescript. They hold " + active.size() + ".",
                        NamedTextColor.RED));
                return null;
            }
            return active.get(n - 1);
        } catch (NumberFormatException e) {
            sender.sendMessage(msg("That is not a number.", NamedTextColor.RED));
            return null;
        }
    }

    /** Give an item, or drop it at their feet if they've no room — a prescript is not optional. */
    private void hand(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    /** Whether {@code player} already carries a paper for this prescript. */
    private boolean holds(Player player, UUID prescriptId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && prescriptId.equals(PrescriptPaper.idOf(item))) return true;
        }
        return false;
    }

    /**
     * Resolve a name to a player the Index knows. Online first; otherwise the server's cache only — never a
     * blocking Mojang lookup on the main thread for a roleplay tally.
     */
    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && cached.getUniqueId() != null ? cached : null;
    }

    private String name(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        String known = Bukkit.getOfflinePlayer(id).getName(); // by UUID: cache read, no network
        return known != null ? known : "someone";
    }

    private String nameOf(OfflinePlayer p) {
        return p.getName() != null ? p.getName() : "someone";
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static Component msg(String text, TextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }

    // ---- tab completion -----------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            out.addAll(PLAYER_SUBS);
            if (store.isWeaver(player)) out.addAll(WEAVER_SUBS);
            if (player.hasPermission(ADMIN_PERM)) out.addAll(ADMIN_SUBS);
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); // /prescript <player>
            return prefix(out, args[0]);
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            if (sub.equals("weaver") && player.hasPermission(ADMIN_PERM)) {
                return prefix(List.of("add", "remove", "list"), args[1]);
            }
            if (sub.equals("pending")) return List.of(); // takes nothing
            if (WEAVER_SUBS.contains(sub) && store.isWeaver(player)) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                return prefix(out, args[1]);
            }
            return List.of();
        }

        if (args.length == 3) {
            if (sub.equals("weaver") && player.hasPermission(ADMIN_PERM)
                    && !args[1].equalsIgnoreCase("list")) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                return prefix(out, args[2]);
            }
            // /prescript judge <player> → the ruling, since one prescript is the common case
            if (sub.equals("judge") && store.isWeaver(player)) return prefix(RULINGS, args[2]);
        }

        // /prescript judge <player> <#> → the ruling
        if (args.length == 4 && sub.equals("judge") && store.isWeaver(player)) {
            return prefix(RULINGS, args[3]);
        }

        return List.of();
    }

    private static List<String> prefix(List<String> options, String typed) {
        String t = typed.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase().startsWith(t)) out.add(o);
        return out;
    }
}
