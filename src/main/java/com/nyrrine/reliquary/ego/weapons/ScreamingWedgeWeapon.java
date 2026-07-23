package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoEnchants;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Screaming Wedge — "The Lady Facing the Wall". An HE-tier Lobotomy Corp E.G.O Equipment crossbow.
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
public final class ScreamingWedgeWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as the Screaming Wedge. */
    private final NamespacedKey key;

    /** Wielder -> last-fire timestamp (ms). The only state this weapon keeps. */
    private final Map<UUID, Long> lastFire = new HashMap<>();

    /** One shot every five seconds. */
    private static final long COOLDOWN_MS = 5000L;

    /** Small per-shot damage — the tangle, not the bite, is the payoff. */
    private static final double SHOT_DAMAGE = 3.0;

    // Long Hair (a custom enchant — id "long_hair"): longer strands reach further. +25% travel range and
    // acquisition radius per level, up to +75% at level 3. Reach only — it never touches the strand's blow,
    // its hit radius, or the tangle chance.
    private static final double LONG_HAIR_PER_LEVEL = 0.25;
    private static final int    LONG_HAIR_CAP       = 3;

    // Tangle (a custom enchant — id "tangle"): the hair clings harder. +25% to the on-hit root duration per
    // level, up to +75% at level 3. Crowd control only — it never touches the strand's blow, reach, or the
    // tangle chance, only how long a caught body stays rooted.
    private static final double TANGLE_PER_LEVEL = 0.25;
    private static final int    TANGLE_CAP       = 3;

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

        TOOLTIP.applyTo(meta);
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
        ItemStack held = player.getInventory().getItemInMainHand();
        double reach = 1.0 + LONG_HAIR_PER_LEVEL
                * Math.min(LONG_HAIR_CAP, EgoEnchants.level(held, "long_hair"));   // reach only, no damage
        double tangle = 1.0 + TANGLE_PER_LEVEL
                * Math.min(TANGLE_CAP, EgoEnchants.level(held, "tangle"));         // root duration only, no damage
        new ScreamingWedgeStrand(plugin, player, SHOT_DAMAGE, reach, tangle).launch();

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

    /** Primary — the discordant dark-violet. Display name, "How to use:", ability headers, action bar. */
    private static final TextColor VIOLET = TextColor.color(0x8A6FA3);
    /**
     * Secondary — the pale lavender the old block set its description in. The Abnormality title line.
     *
     * <p>The title line used to be the name's violet; the roster now wants the two apart, and this is the
     * only other accent the wedge has ever owned that a bold line can carry (the third, the dim 0x6E6874
     * the old block put its conditions in, sits almost on top of the helper's grey footer).
     */
    private static final TextColor PALE = TextColor.color(0xBFB4C6);

    /** Built once: the display name is the weapon, the title line is the Abnormality. Never the reverse. */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Screaming Wedge",
            "The Lady Facing the Wall",
            VIOLET,
            PALE,
            List.of(
                    "Her hair has grown over the crossbow;",
                    "its scream never forgets her dejection."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Saturation Cost",
                            "Each shot has a 50% chance to drain",
                            "12.8 saturation; if saturation is",
                            "already empty, it takes 4 food",
                            "points instead."),
                    new EgoLore.Ability("[Right Click] Homing Strand Shot",
                            "Loose a slow strand of hair that",
                            "curls onto a body roughly ahead of",
                            "it, biting for 3 damage. 30% of the",
                            "time it tangles them in Slowness VI",
                            "for 2.25s — heavily slowed, not",
                            "held in place. A wall or 3.5s aloft",
                            "fizzles it out. 5-second cooldown.")
            ));
}
