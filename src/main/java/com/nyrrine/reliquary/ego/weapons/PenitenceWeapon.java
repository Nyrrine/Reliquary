package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Penitence — "One Sin and Hundreds of Good Deeds". A Lobotomy Corp E.G.O weapon shaped as a
 * cross-headed mace, materialized from a hollow-skulled archetype and reshaped by the observer.
 *
 * <p>It is a <b>penitent's weapon, not a killer's</b>: its outgoing damage is hard-capped in
 * {@link #onHit} so an ordinary blow only ever grazes. The one exception is a committed mace fall-slam,
 * which lands the cap and bites through half the target's armour — the atonement made forceful. Beyond
 * the blow, every landed strike can still return a little of the penitent's own body and comfort:
 *
 * <ul>
 *   <li><b>Saturation grace</b> — a flat 10% chance per hit to restore a little food + saturation.</li>
 *   <li><b>Mending grace</b> — a <i>ramping</i> chance that starts at 5% and climbs +5% for every strike
 *       that does not heal, until it finally procs: the wielder mends a little (clamped to max, never
 *       overhealing) and the chance resets to 5%. A soft church-bell toll and a faint white cross mark
 *       the mend.</li>
 * </ul>
 *
 * <p>The per-player ramp is held in {@link #healChance} and dropped on quit.
 */
public final class PenitenceWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Penitence. */
    private final NamespacedKey key;

    /** Hard ceiling on outgoing damage — an ordinary blow only ever grazes for this much. */
    private static final double MACE_DAMAGE_CAP = 6.0;

    /** A committed mace fall-slam ignores this fraction of the target's armour (the atonement made forceful). */
    private static final double FALL_SLAM_ARMOR_PIERCE = 0.50;

    /** How far the wielder must be falling for a hit to count as a fall-slam rather than a plain swing. */
    private static final float  FALL_SLAM_MIN_FALL = 1.5f;

    /** Weakness I on the struck foe when a mend procs — pressure without lifting the damage cap. 2s. */
    private static final int    MEND_WEAKNESS_TICKS = 40;

    /** How much health a mending proc restores — one heart. Clamped to max, never overheals. */
    private static final double HEAL_PER_HIT = 2.0;

    /** Flat per-hit chance to restore a little saturation/food. */
    private static final double SATURATION_CHANCE = 0.10;

    /** Ramping heal chance: starts here and resets here after every proc. */
    private static final double HEAL_CHANCE_BASE = 0.05;

    /** Each non-healing strike adds this to the ramping heal chance. */
    private static final double HEAL_CHANCE_STEP = 0.05;

    /** Per-player mending ramp. Grows by {@link #HEAL_CHANCE_STEP} per miss, resets to base on proc. */
    private final Map<UUID, Double> healChance = new ConcurrentHashMap<>();

    public PenitenceWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "penitence");
    }

    @Override
    public String id() {
        return "penitence";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.PENITENCE.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.PENITENCE.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.PENITENCE);

        item.setItemMeta(meta);
        return item;
    }

    // ---- gimmick: a weapon that heals but cannot harm --------------------------------

    /**
     * Melee hit landed. Penitence is not a real weapon: its outgoing damage is capped to a tiny value so
     * a mace fall-slam can never land a real blow. In exchange the strike may restore a little of the
     * wielder's saturation (flat 10%) and, on a ramping chance, mend a little of their health.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        // An ordinary swing only ever grazes — cap it. The exception is a committed mace fall-slam: it
        // still lands only the cap, but half the target's armour is ignored, so the slam is worth throwing
        // without turning Penitence into a mace. The slam scales the cap through the framework's pierceInput
        // on the event, keeping the mace's fall-slam knockback and sweep (no cancel, no re-deal).
        if (attacker.getFallDistance() > FALL_SLAM_MIN_FALL) {
            event.setDamage(plugin.weapons().pierceInput(victim, MACE_DAMAGE_CAP, FALL_SLAM_ARMOR_PIERCE));
        } else {
            event.setDamage(Math.min(event.getDamage(), MACE_DAMAGE_CAP));
        }

        // TODO(flavor): "Special: against any wielder of 'Paradise Lost', deals 50000% more damage."
        // Deliberately NOT implemented. When a Paradise Lost weapon/wielder exists, detect the victim
        // here and multiply the pre-cap damage (bypassing MACE_DAMAGE_CAP) instead of capping.

        boolean touched = false;

        // Saturation grace: a flat 10% chance to restore a little food + saturation.
        if (ThreadLocalRandom.current().nextDouble() < SATURATION_CHANCE) {
            if (restoreSaturation(attacker)) {
                attacker.sendActionBar(EgoHud.status("Penitence — comforted", WARM_HUD));
                touched = true;
            }
        }

        // Mending grace: a ramping chance. 5%, then +5% for every strike that does not heal; on a
        // proc the wielder mends a little and the ramp resets to 5%.
        UUID id = attacker.getUniqueId();
        double chance = healChance.getOrDefault(id, HEAL_CHANCE_BASE);
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            healChance.put(id, HEAL_CHANCE_BASE); // proc — reset the ramp
            // A mend-proc presses the struck foe: a short Weakness, so the grace has a little bite without
            // the damage cap ever rising.
            victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, MEND_WEAKNESS_TICKS, 0, false, true, true));
            if (mend(attacker)) {
                tollBell(attacker);
                traceCross(attacker, victim);
                if (!touched) attacker.sendActionBar(EgoHud.status("Penitence — mended", WARM_HUD));
            }
        } else {
            healChance.put(id, Math.min(1.0, chance + HEAL_CHANCE_STEP));
        }
    }

    /** Restore a couple of food + saturation. Returns true if anything actually changed. */
    private boolean restoreSaturation(Player attacker) {
        int food = Math.min(20, attacker.getFoodLevel() + 2);
        float sat = Math.min(food, attacker.getSaturation() + 2.0f);
        boolean changed = food != attacker.getFoodLevel() || sat != attacker.getSaturation();
        attacker.setFoodLevel(food);
        attacker.setSaturation(sat);
        return changed;
    }

    /** Mend a flat sip of health, clamped to max — never overheals. Returns true if any HP was restored. */
    private boolean mend(Player attacker) {
        AttributeInstance maxAttr = attacker.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        double current = attacker.getHealth();
        double healed = Math.min(maxHp, current + HEAL_PER_HIT);
        if (healed <= current) return false;
        attacker.setHealth(healed);
        return true;
    }

    /** Drop the per-player ramp when a wielder leaves. */
    @Override
    public void onQuit(UUID id) {
        healChance.remove(id);
    }

    /** A soft church-bell toll on the mend, pitch-jittered so repeated strikes don't drone. */
    private void tollBell(Player attacker) {
        float pitch = 0.85f + ThreadLocalRandom.current().nextFloat() * 0.3f; // ~0.85 - 1.15
        attacker.getWorld().playSound(attacker.getLocation(), Sound.BLOCK_BELL_USE, 0.55f, pitch);
    }

    /**
     * A faint white cross-glyph over the victim's upper body: a short vertical line crossed by a
     * short horizontal one, drawn in a plane facing the attacker. Low particle count, short-lived.
     */
    private void traceCross(Player attacker, LivingEntity victim) {
        World world = victim.getWorld();
        Location chest = victim.getLocation().add(0, 1.4, 0);

        // Horizontal axis: perpendicular to the attacker->victim look, so the cross faces the striker.
        Vector to = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        Vector right = to.lengthSquared() < 1.0e-6
                ? new Vector(1, 0, 0)
                : to.normalize().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();

        Particle.DustOptions dust = new Particle.DustOptions(GLYPH, 0.7f);

        // Vertical beam of the cross: from just below the chest up past the head.
        for (int i = -2; i <= 3; i++) {
            Location p = chest.clone().add(0, i * 0.22, 0);
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust);
        }
        // Horizontal beam, at chest height.
        for (int i = -2; i <= 2; i++) {
            if (i == 0) continue; // centre already drawn by the vertical beam
            Location p = chest.clone().add(right.clone().multiply(i * 0.22));
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust);
        }
    }

    // ---- lore ---------------------------------------------------------------------

    /** Primary — pale church gold. Display name, "How to use:", ability headers. */
    private static final TextColor GOLD  = TextColor.color(0xE8D9A0);
    /** Secondary — the grace/mending accent, already the weapon's own. The Abnormality title line. */
    private static final TextColor WARM  = TextColor.color(0xC9A94E);
    private static final TextColor WARM_HUD = WARM;                   // subtle action-bar cue on grace

    // Particle colour (kept apart from the lore palette so tuning one never disturbs the other).
    private static final Color GLYPH = Color.fromRGB(0xF3EEDC);       // the traced cross — near-white gold (dust)

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Penitence",
            "One Sin and Hundreds of Good Deeds",
            GOLD,
            WARM,
            List.of(
                    "Not made to kill, but to atone.",
                    "It comforts the one who wields it.",
                    "",
                    // Paradise Lost is flavour only — no such mechanic is implemented. It closed the old
                    // tooltip, below the moveset; the shared format has no room down there, so it sits at
                    // the foot of the flavour instead. The words are hers, untouched.
                    "vs. 'Paradise Lost': +50000% dmg."
            ),
            List.of(
                    new EgoLore.Ability("[Left Click] Damage Cap",
                            "Attacks deal at most 6 damage. A",
                            "committed mace fall-slam bites through",
                            "half the target's armor."),
                    new EgoLore.Ability("[Passive] Saturation Grace",
                            "Each hit has a 10% chance to restore",
                            "a little food and saturation."),
                    new EgoLore.Ability("[Passive] Mending Grace",
                            "Each hit has a chance to mend one",
                            "heart, never overhealing. Starts at",
                            "5% and climbs 5% per hit until it",
                            "procs, then resets to 5%. A proc also",
                            "briefly weakens the struck foe.")
            ));
}
