package com.nyrrine.reliquary.weapons.gungnir;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gungnir — the yellow harpoon. A trident reworked into a thrown relic with a manual recall instead
 * of Loyalty.
 *
 * <ul>
 *   <li><b>Left-click</b> — loose it as a quick bolt: flies straight, buries into the first thing it
 *       meets.</li>
 *   <li><b>Right-click</b> — loose a <em>ricochet</em> bolt: it homes onto nearby mobs and bounces
 *       off the world, careening around and racking up hits until you call it back.</li>
 *   <li><b>Left-click with an empty hand</b>, or <b>swap-hands (F)</b> — recall the spear whenever
 *       you please.</li>
 * </ul>
 *
 * Only one spear exists — it leaves your hand on the throw and comes back only by recall, so it can
 * never be looted; it's kept out of the offhand (useless there), and quit/shutdown hand it back so
 * it's never lost.
 */
public final class GungnirWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Gungnir. */
    private final NamespacedKey spearKey;

    /** Owner -> the spear currently in flight/lodged for them. At most one per player. */
    private final Map<UUID, GungnirSpear> airborne = new HashMap<>();

    /** Owner -> a spear owed back to them because they logged out mid-flight; returned on rejoin. */
    private final Map<UUID, ItemStack> owed = new HashMap<>();

    /** Bodies with a Gungnir currently buried in them -> how many (its "vibrations" amplify strikes). */
    private final Map<UUID, Integer> embeddedTargets = new HashMap<>();

    /** Brief realign after the spear returns, so it can't be spam-thrown. Shows the item's cooldown sweep. */
    private static final int THROW_COOLDOWN_TICKS = 16;

    public GungnirWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.spearKey = new NamespacedKey(plugin, "gungnir_spear");
    }

    @Override
    public String id() {
        return "gungnir";
    }

    // ---- throw, ricochet & recall --------------------------------------------------

    /** Left-click: loose the spear as a straight bolt. */
    @Override
    public void onSwing(Player player) {
        launch(player, player.getInventory().getItemInMainHand(), false);
    }

    /** Right-click: loose the ricochet bolt. */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        launch(player, player.getInventory().getItemInMainHand(), true);
    }

    /** Left-click with an empty hand while the spear is out: recall it. */
    @Override
    public boolean onBareSwing(Player player) {
        GungnirSpear spear = airborne.get(player.getUniqueId());
        if (spear == null) return false;
        spear.beginRecall();
        return true;
    }

    @Override
    public void onSwapHands(Player player, PlayerSwapHandItemsEvent event) {
        GungnirSpear spear = airborne.get(player.getUniqueId());
        if (spear != null) {
            event.setCancelled(true);      // hand's empty anyway — F just recalls
            spear.beginRecall();
            return;
        }
        // Gungnir is useless in the offhand — never let F swap it in (or back out).
        if (matches(event.getMainHandItem()) || matches(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    /** Throw the spear. bouncing=false is a straight bolt; bouncing=true ricochets until recalled. */
    void launch(Player player, ItemStack sourceItem, boolean bouncing) {
        UUID id = player.getUniqueId();
        if (airborne.containsKey(id)) return;             // one spear at a time
        if (sourceItem == null || !matches(sourceItem)) return;
        if (player.hasCooldown(Material.TRIDENT)) return; // still realigning — not spammable

        ItemStack thrown = sourceItem.clone();
        player.getInventory().setItemInMainHand(null);

        GungnirSpear spear = new GungnirSpear(plugin, this, player, thrown, bouncing);
        airborne.put(id, spear);
        spear.runTaskTimer(plugin, 0L, 1L);

        GungnirSpear.launchFlourish(player);
        // NB: do NOT swingMainHand() here — Paper fires an arm-swing event for it, which (with the
        // hand now empty) the manager reads as a bare left-click and instantly recalls the spear.
    }

    /** Called by the spear once it reaches the owner's hand. */
    void finishReturn(Player owner, ItemStack item) {
        airborne.remove(owner.getUniqueId());
        returnToInventory(owner, item);
        owner.setCooldown(item.getType(), THROW_COOLDOWN_TICKS); // brief realign + cooldown sweep on the item
    }

    private void returnToInventory(Player owner, ItemStack item) {
        if (owner.getInventory().getItemInMainHand().getType() == Material.AIR) {
            owner.getInventory().setItemInMainHand(item);
        } else {
            owner.getInventory().addItem(item).values()
                    .forEach(leftover -> owner.getWorld().dropItem(owner.getLocation(), leftover));
        }
    }

    // ---- vibration mark (a buried Gungnir amplifies strikes on the body) -----------

    void markEmbedded(UUID target) {
        embeddedTargets.merge(target, 1, Integer::sum);
    }

    void unmarkEmbedded(UUID target) {
        embeddedTargets.computeIfPresent(target, (k, v) -> v <= 1 ? null : v - 1);
    }

    public boolean isEmbedded(UUID target) {
        return embeddedTargets.containsKey(target);
    }

    @Override
    public List<String> outstandingReport() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, GungnirSpear> e : airborne.entrySet()) {
            Player owner = plugin.getServer().getPlayer(e.getKey());
            String who = owner != null ? owner.getName() : e.getKey().toString().substring(0, 8);
            out.add("gungnir  " + e.getValue().describe() + "  (thrown by " + who + ")");
        }
        return out;
    }

    // ---- lifecycle: never lose the relic ------------------------------------------

    @Override
    public void onJoin(Player player) {
        ItemStack back = owed.remove(player.getUniqueId());
        if (back != null) returnToInventory(player, back);
    }

    @Override
    public void onQuit(UUID id) {
        GungnirSpear spear = airborne.remove(id);
        if (spear != null) {
            spear.end();                // releases any vibration mark it holds
            owed.put(id, spear.item()); // hand it back when they return
        }
    }

    @Override
    public void onDisable() {
        // Server stopping: return every outstanding spear to its owner if they're still online.
        for (Map.Entry<UUID, GungnirSpear> e : airborne.entrySet()) {
            e.getValue().end();
            Player owner = plugin.getServer().getPlayer(e.getKey());
            if (owner != null) returnToInventory(owner, e.getValue().item());
        }
        airborne.clear();
    }

    // ---- item ---------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(spearKey, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.TRIDENT);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Gungnir").color(BEE).decoration(TextDecoration.ITALIC, false));
        meta.lore(LORE);
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(spearKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ---------------------------------------------------------------------

    private static final TextColor BEE   = TextColor.color(0xFFC107); // the name / hornet gold
    private static final TextColor PALE  = TextColor.color(0xC9C9D6); // base description
    private static final TextColor VENOM = TextColor.color(0xE0A21E); // sting / venom accent
    private static final TextColor FAINT = TextColor.color(0x83838E); // conditions
    private static final TextColor QUOTE = TextColor.color(0x6E6E78); // epithet

    private record Seg(String text, TextColor color, boolean italic) {
        Seg(String text, TextColor color) { this(text, color, false); }
    }

    private static final List<List<Seg>> LORE_SRC = List.of(
        List.of(new Seg("The Yellow Harpoon", BEE)),
        List.of(),
        List.of(new Seg("A gold-colored harpoon, turned up in", PALE)),
        List.of(new Seg("old ruins by ", PALE), new Seg("pure coincidence", PALE), new Seg(".", PALE)),
        List.of(),
        List.of(new Seg("Loosed, it answers a ", PALE), new Seg("call back to the hand", BEE), new Seg(".", PALE)),
        List.of(new Seg("Sunk into a mark, its ", PALE), new Seg("vibrations swell", VENOM), new Seg(" —", PALE)),
        List.of(new Seg("every blow that follows lands the harder.", PALE)),
        List.of(),
        List.of(new Seg("Named for Odin's spear, which struck true", FAINT, true)),
        List.of(new Seg("from any hand that ever threw it.", FAINT, true)),
        List.of(),
        List.of(new Seg("Left-click to loose it · right-click to ricochet.", FAINT, true)),
        List.of(new Seg("Empty-handed left-click, or press F, to call it back.", FAINT, true)),
        List.of(),
        List.of(new Seg("\"It always comes home.\"", QUOTE, true))
    );

    private static final List<Component> LORE = buildLore();

    private static List<Component> buildLore() {
        List<Component> out = new ArrayList<>(LORE_SRC.size());
        for (List<Seg> line : LORE_SRC) {
            if (line.isEmpty()) { out.add(Component.empty()); continue; }
            Component c = Component.empty().decoration(TextDecoration.ITALIC, false);
            for (Seg seg : line) {
                c = c.append(Component.text(seg.text())
                        .color(seg.color())
                        .decoration(TextDecoration.ITALIC, seg.italic()));
            }
            out.add(c);
        }
        return out;
    }
}
