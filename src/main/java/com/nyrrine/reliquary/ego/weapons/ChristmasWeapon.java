package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Christmas — Rudolta of the Sleigh. A Lobotomy Corp E.G.O weapon extracted from the abnormality
 * "Rudolta of the Sleigh": a slow, heavy, leather-patched club that comes bearing gifts.
 *
 * <p>A plain melee weapon — the vanilla swing deals its normal damage, uncancelled — but it swings
 * <em>slow</em>, like a club (its low attack speed lives in {@link EgoModels}), and every landed blow
 * lands with a heavy {@code THWACK}. Its charm is the gimmick in {@link #onHit}: each hit rolls
 * <b>two</b> independent {@value #GIFT_CHANCE_PERCENT}% "gift" chances — one may hand the
 * <em>wielder</em> a brief, combat-convenient blessing, and one may hand the <em>struck enemy</em> a
 * gift of their own (never called a debuff — the sleigh gives to everyone). Sleigh bells jingle,
 * snowflakes drift, and ribbon-bright sparkles curl around whoever just received something.
 *
 * <p>Every gift is a fleeting potion effect, never a dropped item, so nothing can be farmed or
 * littered on a busy server. Holds no per-player state, so there is nothing to clear on quit.
 */
public final class ChristmasWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Christmas. */
    private final NamespacedKey key;

    /**
     * Chance, per landed melee hit, that a gift is given. Rolled independently for the wielder's buff
     * and the enemy's gift. Kept small so it stays a delight, not a crutch.
     */
    private static final int GIFT_CHANCE_PERCENT = 11;

    public ChristmasWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "christmas");
    }

    @Override
    public String id() {
        return "christmas";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.CHRISTMAS.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.CHRISTMAS.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.CHRISTMAS);

        item.setItemMeta(meta);
        return item;
    }

    // ---- gimmick: a heavy club that comes bearing gifts ---------------------------

    /**
     * Melee hit landed. The vanilla damage is left untouched. Every landed blow lands with a heavy
     * {@code THWACK}; then two independent gift rolls fire — one may bless the wielder, one may gift
     * the struck enemy. The wielder is told on the action bar what they received and/or gifted.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        thwack(victim); // heavy club thud on every landed hit

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        SelfBuff self = null;
        if (rng.nextInt(100) < GIFT_CHANCE_PERCENT) {
            self = SelfBuff.VALUES[rng.nextInt(SelfBuff.VALUES.length)];
            attacker.addPotionEffect(self.effect());
            jingleBells(attacker);
            giftVfx(attacker.getLocation().add(0, 1.0, 0), attacker.getWorld());
        }

        EnemyGift enemy = null;
        if (rng.nextInt(100) < GIFT_CHANCE_PERCENT) {
            enemy = EnemyGift.VALUES[rng.nextInt(EnemyGift.VALUES.length)];
            victim.addPotionEffect(enemy.effect());
            jingleBells(attacker);
            giftVfx(victim.getLocation().add(0, 1.0, 0), victim.getWorld());
        }

        announce(attacker, self, enemy);
    }

    /** Fleeting, combat-convenient blessings for the one who swings the club. */
    private enum SelfBuff {
        SWIFTNESS("Swiftness",   PotionEffectType.SPEED,       100, 0), // Speed I, 5s
        MENDING("Regeneration",  PotionEffectType.REGENERATION, 80, 1), // Regeneration II, 4s
        CUSHION("Absorption",    PotionEffectType.ABSORPTION,  160, 0), // Absorption I, 8s
        MIGHT("Strength",        PotionEffectType.STRENGTH,    100, 0), // Strength I, 5s
        GUARD("Resistance",      PotionEffectType.RESISTANCE,  100, 0), // Resistance I, 5s
        SPRING("a Spring in your step", PotionEffectType.JUMP_BOOST, 120, 1); // Jump Boost II, 6s

        static final SelfBuff[] VALUES = values();

        final String label;
        final PotionEffectType type;
        final int ticks;
        final int amplifier;

        SelfBuff(String label, PotionEffectType type, int ticks, int amplifier) {
            this.label = label;
            this.type = type;
            this.ticks = ticks;
            this.amplifier = amplifier;
        }

        PotionEffect effect() {
            return new PotionEffect(type, ticks, amplifier);
        }
    }

    /** Fleeting gifts for the struck enemy — always a "gift", never a "debuff". */
    private enum EnemyGift {
        SLUGGISHNESS("Sluggishness", PotionEffectType.SLOWNESS,        80, 1), // Slowness II, 4s
        WEAKNESS("Weakness",         PotionEffectType.WEAKNESS,       100, 0), // Weakness I, 5s
        DIZZINESS("Dizziness",       PotionEffectType.NAUSEA,         100, 0), // Nausea, 5s
        PRICKLE("a Prickle",         PotionEffectType.POISON,          80, 0), // Poison I, 4s
        HEAVY_HANDS("Heavy Hands",   PotionEffectType.MINING_FATIGUE, 100, 1), // Mining Fatigue II, 5s
        LIFTOFF("Weightlessness",    PotionEffectType.LEVITATION,      40, 0); // Levitation I, 2s

        static final EnemyGift[] VALUES = values();

        final String label;
        final PotionEffectType type;
        final int ticks;
        final int amplifier;

        EnemyGift(String label, PotionEffectType type, int ticks, int amplifier) {
            this.label = label;
            this.type = type;
            this.ticks = ticks;
            this.amplifier = amplifier;
        }

        PotionEffect effect() {
            return new PotionEffect(type, ticks, amplifier);
        }
    }

    /** Festive action-bar notice of what the wielder received and/or gifted the enemy. */
    private void announce(Player attacker, SelfBuff self, EnemyGift enemy) {
        Component bar = null;
        if (self != null) {
            bar = EgoHud.status("A gift! You received " + self.label, GREEN);
        }
        if (enemy != null) {
            Component g = EgoHud.status("You gifted them " + enemy.label, GOLD);
            bar = (bar == null) ? g : bar.append(EgoHud.status("   ", WHITE)).append(g);
        }
        if (bar != null) attacker.sendActionBar(bar);
    }

    /**
     * The heavy club THWACK on every landed hit — the wet crunch of bones giving way under a slow,
     * blunt swing: a dry bone-block crack over a deep bone knock, weighted by a low, dulled crit-impact,
     * pitch-jittered a touch so repeated swings don't drone. No metal — it lands like meat and bone, not
     * an anvil.
     */
    private void thwack(LivingEntity victim) {
        World world = victim.getWorld();
        Location at = victim.getLocation();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float jitter = rng.nextFloat() * 0.1f;
        world.playSound(at, Sound.BLOCK_BONE_BLOCK_BREAK, 1.1f, 0.55f + jitter); // dry bone crunch
        world.playSound(at, Sound.BLOCK_BONE_BLOCK_HIT, 1.0f, 0.5f + jitter);    // deep bone knock
        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.7f, 0.5f + jitter); // low, weighty impact
    }

    /** A bright sleigh-bell jingle — a couple of note-block bell tones, pitch-jittered so it lilts. */
    private void jingleBells(Player attacker) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = attacker.getWorld();
        Location at = attacker.getLocation();
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 1.3f + rng.nextFloat() * 0.4f);
        world.playSound(at, Sound.BLOCK_BELL_USE, 0.5f, 1.5f + rng.nextFloat() * 0.3f);
    }

    /**
     * A festive burst around a point: drifting snowflakes, a few ribbon-bright dust motes in Christmas
     * colours, and a scatter of happy-villager cheer. Low counts, short-lived.
     */
    private void giftVfx(Location center, World world) {
        world.spawnParticle(Particle.SNOWFLAKE, center, 12, 0.45, 0.6, 0.45, 0.01);

        Color[] ribbon = { Color.fromRGB(0xD7263D), Color.fromRGB(0x2E8B57), Color.fromRGB(0xF1C40F) };
        for (Color c : ribbon) {
            world.spawnParticle(Particle.DUST, center, 3, 0.4, 0.5, 0.4, 0,
                    new Particle.DustOptions(c, 1.0f));
        }

        world.spawnParticle(Particle.HAPPY_VILLAGER, center, 5, 0.4, 0.5, 0.4, 0);
    }

    // ---- lore ---------------------------------------------------------------------

    /** Primary — holly red. Display name, "How to use:", ability headers. */
    private static final TextColor RED   = TextColor.color(0xD7263D); // name / holly red
    /** Secondary — the pine green already in the sleigh's colours. The Abnormality title line. */
    private static final TextColor GREEN = TextColor.color(0x2E8B57); // pine green accent
    private static final TextColor GOLD  = TextColor.color(0xF1C40F); // gift / ribbon gold
    private static final TextColor WHITE = TextColor.color(0xF5F5F5); // snow-white base

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Christmas",
            "Rudolta of the Sleigh",
            RED,
            GREEN,
            List.of(
                    "Patched with heavy leather of unknown",
                    "origin — its colors recall a festive",
                    "holiday now long forgotten."
            ),
            List.of(
                    new EgoLore.Ability("[Left Click] Heavy Club Hit",
                            "A slow, heavy club. Every landed blow",
                            "lands with a bone-crunching thwack."),
                    new EgoLore.Ability("[Passive] Self Buff Roll",
                            "Each landed hit has an 11% chance to",
                            "bless you with a random fleeting gift:",
                            "Swiftness, Regeneration, Absorption,",
                            "Strength, Resistance, or a Spring in",
                            "your step."),
                    new EgoLore.Ability("[Passive] Enemy Gift Roll",
                            "A separate 11% roll per hit gifts the",
                            "struck enemy Sluggishness, Weakness,",
                            "Dizziness, a Prickle, Heavy Hands or",
                            "Weightlessness. One hit can do both.")
            ));
}
