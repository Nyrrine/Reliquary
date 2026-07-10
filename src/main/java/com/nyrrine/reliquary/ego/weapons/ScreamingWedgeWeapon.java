package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Screaming Wedge — "The Lady Facing the Wall". A WAW-tier Lobotomy Corp E.G.O Equipment crossbow.
 *
 * <p>The Lady's hair has grown over the stock of the crossbow, and it does not forget her dejection.
 * <b>Right-click</b> looses a single black strand of that hair — a slow, creeping bolt that curls
 * gently onto whatever enemy strays roughly ahead of it (a subtle auto-aim, never a hard lock). On a
 * body it bites for a little damage, and roughly a third of the time the hair tangles the victim into
 * a near-root: a strong Slowness for a couple of seconds. Meeting a wall, or finding no one after
 * several seconds aloft, the strand simply fizzles away. The projectile logic lives in
 * {@link ScreamingWedgeStrand}.
 *
 * <ul>
 *   <li><b>Fire rate</b> — one shot every {@value #COOLDOWN_MS}ms; the cooldown reads in whole seconds
 *       on the action bar via {@link EgoHud#cooldown} (never milliseconds).</li>
 *   <li><b>Self-cost</b> — her hair tangles the wielder's hands too. Each shot has a
 *       {@value #COST_CHANCE}-in-1 chance to drain the wielder's nourishment as if they'd un-eaten a
 *       steak: their saturation drops by a steak's worth, or — if they're already running on empty —
 *       a bite of food itself.</li>
 *   <li><b>Durability</b> — no longer unbreakable; each shot pays one point of mild wear via
 *       {@link EgoDurability#wearMainHand(Player)}.</li>
 * </ul>
 *
 * <p>The only state kept is a per-wielder last-fire stamp for the cooldown, cleared on quit.
 */
public final class ScreamingWedgeWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as the Screaming Wedge. */
    private final NamespacedKey key;

    /** Wielder -> last-fire timestamp (ms). The only state this weapon keeps. */
    private final Map<UUID, Long> lastFire = new HashMap<>();

    /** One shot every five seconds. */
    private static final long COOLDOWN_MS = 5000L;

    /** Small per-shot damage — the tangle, not the bite, is the payoff. */
    private static final double SHOT_DAMAGE = 3.0;

    /** Chance, per shot, that firing costs the wielder a steak's worth of nourishment. */
    private static final double COST_CHANCE = 0.50;

    /** A steak restores ~12.8 saturation — un-eating one drops the wielder's saturation by this much. */
    private static final float STEAK_SATURATION = 12.8f;
    /** If saturation is already spent, the cost eats into the food bar instead (a steak's ~8 food, halved). */
    private static final int STEAK_FOOD_FALLBACK = 4;

    public ScreamingWedgeWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "screaming_wedge");
    }

    @Override
    public String id() {
        return "screaming_wedge";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.SCREAMING_WEDGE.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.SCREAMING_WEDGE.material());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Screaming Wedge").color(VIOLET).decoration(TextDecoration.ITALIC, false));
        meta.lore(LORE);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.SCREAMING_WEDGE);

        item.setItemMeta(meta);
        return item;
    }

    // ---- fire ----------------------------------------------------------------------

    /**
     * Right-click looses a strand of hair — unless the five-second cooldown is still running, in which
     * case the action bar shows the remaining seconds and nothing fires. A fired shot launches the
     * {@link ScreamingWedgeStrand} projectile, pays one point of mild wear, and — half the time —
     * costs the wielder a steak's worth of nourishment.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastFire.get(id);
        if (last != null && now - last < COOLDOWN_MS) {
            player.sendActionBar(EgoHud.cooldown("Screaming Wedge", COOLDOWN_MS - (now - last), VIOLET));
            return;
        }
        lastFire.put(id, now);

        fire(player);
        EgoDurability.wearMainHand(player); // mild — one point per shot
        if (ThreadLocalRandom.current().nextDouble() < COST_CHANCE) payHairCost(player);

        player.sendActionBar(EgoHud.cooldown("Screaming Wedge", COOLDOWN_MS, VIOLET));
    }

    /** Loose the strand and sell the shot with a piercing, air-splitting scream. */
    private void fire(Player player) {
        new ScreamingWedgeStrand(plugin, player, SHOT_DAMAGE).launch();

        World world = player.getWorld();
        Location at = player.getEyeLocation();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        world.playSound(at, Sound.ITEM_CROSSBOW_SHOOT, 0.7f, 0.6f);
        // The scream — kept low so it reads as a distant shriek, not a blaring screech in the ear.
        world.playSound(at, Sound.ENTITY_ENDERMAN_SCREAM, 0.18f, 0.5f + rng.nextFloat() * 0.1f);
    }

    /**
     * The hair tangles the wielder's own hands and drains their nourishment as if a steak went un-eaten:
     * saturation drops by a steak's worth, or — if saturation is already spent — a bite of food itself.
     */
    private void payHairCost(Player player) {
        float sat = player.getSaturation();
        if (sat <= 0.0f) {
            player.setFoodLevel(Math.max(0, player.getFoodLevel() - STEAK_FOOD_FALLBACK));
        } else {
            player.setSaturation(Math.max(0.0f, sat - STEAK_SATURATION));
        }
    }

    // ---- cooldown readout ----------------------------------------------------------

    /** While the wedge is held, keep the action bar showing the cooldown (or "ready"). */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false;
        long now = System.currentTimeMillis();
        Long last = lastFire.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            player.sendActionBar(EgoHud.cooldown("Screaming Wedge", COOLDOWN_MS - (now - last), VIOLET));
        } else {
            player.sendActionBar(EgoHud.ready("Screaming Wedge", VIOLET));
        }
        return true;
    }

    @Override
    public void onQuit(UUID id) {
        lastFire.remove(id);
    }

    // ---- lore ----------------------------------------------------------------------

    private static final TextColor VIOLET = TextColor.color(0x8A6FA3); // name / discordant dark-violet
    private static final TextColor PALE   = TextColor.color(0xBFB4C6); // base description
    private static final TextColor FAINT  = TextColor.color(0x6E6874); // conditions

    private record Seg(String text, TextColor color, boolean italic) {
        Seg(String text, TextColor color) { this(text, color, false); }
    }

    private static final List<List<Seg>> LORE_SRC = List.of(
        List.of(new Seg("The Lady Facing the Wall", VIOLET)),
        List.of(),
        List.of(new Seg("Her hair has grown over the", PALE)),
        List.of(new Seg("crossbow; its scream never", PALE)),
        List.of(new Seg("forgets her dejection.", PALE)),
        List.of(),
        List.of(new Seg("How to use:", FAINT, true)),
        List.of(new Seg("Right-click: a slow homing", FAINT)),
        List.of(new Seg("hair-strand every 5s.", FAINT)),
        List.of(new Seg("30% root, 50% saturation cost.", FAINT))
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
