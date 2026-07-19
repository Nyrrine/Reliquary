package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Green Stem — Snow White's Apple. A WAW-tier Lobotomy Corp E.G.O Equipment: a thorned, apple-motif
 * blade — all splintered wood and a single red fruit, never green. The malice in it kills by hatred,
 * not by the sharpness of its tip.
 *
 * <p>A melee weapon. The vanilla IRON_SWORD swing deals its normal damage, uncancelled — the stem
 * only adds its venom. Every blow the wielder lands sinks a splinter into the struck body: a brief
 * {@link PotionEffectType#POISON POISON} laid on top of the vanilla hit (never a second
 * {@code damage()} call), while wooden thorns and a fleck of apple-red scatter.
 *
 * <p>Its grace is the <b>execute</b>: anything worn down to {@value #EXECUTE_FRACTION_PCT}% of its
 * max health within {@link #EXECUTE_RADIUS} blocks of the wielder is impaled by a wooden thorn spike
 * that erupts from the ground beneath it — a finishing blow. This fires both from a passive scan
 * ({@link #onTick}) and from a qualifying melee hit ({@link #onHit}). Only sub-threshold targets are
 * ever touched, and a short per-target cooldown keeps the same body from being speared twice.
 *
 * <p>The thorn is a non-vanilla hit, so it wears the blade a touch via
 * {@link EgoDurability#wearMainHand(Player)}. Its own {@code victim.damage(...)} re-enters
 * {@link #onHit}, so a {@link #ticking} fence stops the finisher from re-seeding venom or re-proccing
 * from inside its own blow. The weapon also keeps a small side grace: sneak + right-click takes a bite
 * of the poisoned fruit, mending the bearer on a short cooldown.
 *
 * <p>Per-player state — the bite cooldown — is cleared on quit.
 */
public final class GreenStemWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Green Stem. */
    private final NamespacedKey key;

    /** Bite cooldown per wielder: UUID -> epoch-millis when the next bite is allowed. */
    private final Map<UUID, Long> biteCd = new HashMap<>();

    /** Per-target thorn cooldown: victim UUID -> epoch-millis when it may be impaled again. */
    private final Map<UUID, Long> thornCd = new HashMap<>();

    /**
     * Victims whose thorn-execute damage is currently resolving. The finisher's
     * {@code victim.damage(...)} re-enters {@link #onHit}; while a UUID sits here, onHit refuses to
     * re-poison or re-proc from within its own blow.
     */
    private final Set<UUID> ticking = new HashSet<>();

    /** POISON dose applied on every landed melee hit: ~4 seconds, amplifier 0. */
    private static final int POISON_TICKS = 80;
    private static final int POISON_AMP = 0;

    /** The bite mends a chunk of the wielder's health — two hearts, clamped to their max. */
    private static final double BITE_HEAL = 4.0;

    /** How long the bite must rest between uses. */
    private static final long BITE_COOLDOWN_MS = 8_000L;

    // ---- execute tuning -----------------------------------------------------------

    /** Anything at or below this fraction of its max health is a candidate for the thorn. */
    private static final double EXECUTE_FRACTION = 0.05;
    private static final int EXECUTE_FRACTION_PCT = 5; // for the {@value} javadoc references only

    /** How near the wielder a low body must be for the thorn to reach it. */
    private static final double EXECUTE_RADIUS = 5.0;

    /**
     * Protection's ceiling. The server clamps the enchantment's protection factor to 20 and scales damage
     * by {@code 1 - factor/25}, so no legal armour set can shave off more than 80% — five times what is
     * needed to kill is therefore lethal through any of it. {@code GENERIC_KILL} is not tagged
     * {@code bypasses_enchantments}, so this is the one reduction the thorn still has to out-muscle.
     */
    private static final double PROTECTION_HEADROOM = 5.0;

    /**
     * Slack folded into the thorn before the headroom multiplies it. Covers the body's absorption hearts
     * and the damage cooldown: a blow landing inside the victim's invulnerability window has the previous
     * hit's damage subtracted from it, and when the thorn fires from {@link #onHit} the previous hit is
     * the sword blow that triggered it.
     */
    private static final double EXECUTE_MARGIN = 40.0;

    /** A speared body can't be re-speared for this long (mostly matters if a hit somehow fails to kill). */
    private static final long THORN_COOLDOWN_MS = 3_000L;

    /** onTick runs every 2 server ticks; scan for finishable prey once every this many pulses (~1s). */
    private static final int SCAN_INTERVAL = 10;

    /** Never impale more than a handful of bodies in a single scan. */
    private static final int MAX_EXECUTES_PER_SCAN = 3;

    public GreenStemWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "green_stem");
    }

    @Override
    public String id() {
        return "green_stem";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.GREEN_STEM.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.GREEN_STEM.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.GREEN_STEM);

        item.setItemMeta(meta);
        return item;
    }

    // ---- passive scan: the thorn hunts the dying -----------------------------------

    /**
     * Called every 2 server ticks while the player is an active wielder. Returns {@code true} while
     * the blade is held (keep ticking), {@code false} the moment it leaves the main hand (go idle).
     *
     * <p>Once every {@link #SCAN_INTERVAL} pulses it sweeps {@code getNearbyEntities(5,5,5)} for living
     * things at or below {@value #EXECUTE_FRACTION_PCT}% of their max health and impales up to
     * {@link #MAX_EXECUTES_PER_SCAN} of them with the wooden thorn.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false; // sheathed -> idle

        if (tick % SCAN_INTERVAL != 0) return true;

        long now = System.currentTimeMillis();
        int speared = 0;
        for (Entity entity : player.getNearbyEntities(EXECUTE_RADIUS, EXECUTE_RADIUS, EXECUTE_RADIUS)) {
            if (speared >= MAX_EXECUTES_PER_SCAN) break;
            if (entity == player || !(entity instanceof LivingEntity target)) continue;
            if (!isExecutable(target) || !thornReady(target.getUniqueId(), now)) continue;
            fireThorn(player, target, now);
            speared++;
        }
        return true;
    }

    // ---- gimmick: the splinter poisons, the thorn finishes --------------------------

    /**
     * Melee hit landed. The vanilla sword damage is left untouched; we sink a splinter into the victim
     * (a short POISON stacked on top of the vanilla blow — no second damage call) and scatter wooden
     * thorns. If the blow leaves the victim at or below the execute threshold, a thorn erupts to finish
     * it. Re-entrant calls from our own thorn (fenced by {@link #ticking}) do nothing.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (ticking.contains(victim.getUniqueId())) return; // our own thorn's damage re-entered; leave it be

        victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, POISON_TICKS, POISON_AMP, false, true, true));
        rustle(victim);
        scatterThorns(victim);

        long now = System.currentTimeMillis();
        if (isExecutable(victim) && thornReady(victim.getUniqueId(), now)) {
            fireThorn(attacker, victim, now);
        }
    }

    /** A body at or below {@link #EXECUTE_FRACTION} of its max health — and still alive. */
    private boolean isExecutable(LivingEntity target) {
        if (target.isDead() || target.getHealth() <= 0.0) return false;
        AttributeInstance maxAttr = target.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        return target.getHealth() <= maxHp * EXECUTE_FRACTION;
    }

    /**
     * The thorn's damage source: {@link DamageType#GENERIC_KILL}, attributed to the wielder.
     *
     * <p>{@code GENERIC_KILL} is the type vanilla's own {@code kill()} builds, and of the types the server
     * ships it is the one that actually delivers an execute: it is tagged {@code bypasses_armor},
     * {@code bypasses_resistance} and {@code bypasses_invulnerability} (and, because
     * {@code bypasses_shield} includes the whole {@code bypasses_armor} tag, a raised shield does not stop
     * it either). {@code MAGIC} bypasses armour but is still cut by Resistance, so it cannot promise a
     * kill.
     *
     * <p>The bypass is also what keeps the thorn honest: the server charges armour durability
     * (~damage/4 <em>per piece</em>) inside a branch it skips outright for a {@code bypasses_armor}
     * source, so however large this blow is, it costs the victim's gear nothing. An earlier build dealt a
     * plain overkill <em>attack</em> instead, which carries no such tag, and stripped a victim's whole
     * armour set in a hit or two. The damage type was the bug, not the magnitude.
     *
     * <p>The wielder is both the causing and the direct entity. The thorn would be the more natural direct
     * entity, but it is only particles — there is no entity to name — and the builder rejects a causing
     * entity without a direct one outright. That attribution is what names the wielder in the death
     * message and hands them the drops.
     */
    private static DamageSource executeSource(Player wielder) {
        return DamageSource.builder(DamageType.GENERIC_KILL)
                .withCausingEntity(wielder)
                .withDirectEntity(wielder)
                .build();
    }

    /**
     * What it takes to finish this body for certain: everything it has left to spend — health plus
     * absorption plus {@link #EXECUTE_MARGIN} of slack — multiplied by {@link #PROTECTION_HEADROOM}.
     *
     * <p>Derived rather than a fixed overkill number, so it stays lethal against a body with far more
     * health than vanilla grants without ever being an arbitrary magic constant.
     */
    private static double executeDamage(LivingEntity target) {
        return (target.getHealth() + target.getAbsorptionAmount() + EXECUTE_MARGIN) * PROTECTION_HEADROOM;
    }

    private boolean thornReady(UUID id, long now) {
        Long ready = thornCd.get(id);
        return ready == null || now >= ready;
    }

    /**
     * The finisher: a wooden thorn spike erupts from the ground beneath the target and impales it. The
     * killing blow is an armour-bypassing {@code damage()} credited to the wielder (see
     * {@link #executeSource}), fenced by {@link #ticking} so it doesn't re-seed venom or re-proc through
     * {@link #onHit}. Non-vanilla hit -> mild blade wear.
     *
     * <p>Going through {@code damage()} rather than straight to a kill is what marks the wielder as the
     * body's last attacker, and that mark is what the server reads afterwards to award XP and
     * player-kill drops. It matters most here: the passive scan spears bodies the wielder may never have
     * touched, so without the {@code damage()} call the kill would credit nobody at all.
     */
    private void fireThorn(Player wielder, LivingEntity target, long now) {
        eruptThorn(target.getWorld(), target.getLocation(), target);

        // Prune stale per-target cooldowns before writing a fresh one. Executed mobs never quit, so without
        // this the map would grow one dead entry per mob speared, forever; drop any whose ready-time passed.
        thornCd.entrySet().removeIf(e -> now >= e.getValue());

        UUID vid = target.getUniqueId();
        DamageSource source = executeSource(wielder);
        ticking.add(vid); // fence: the damage below re-enters onHit — don't let it loop
        // Armour-bypassing, credited to the wielder, and sized to be lethal outright.
        try {
            target.damage(executeDamage(target), source);
        } finally {
            ticking.remove(vid);
        }
        // Belt and braces. The thorn above is lethal on its own against anything the game can legally put
        // in its way, so reaching here means something outside this weapon intervened — another plugin
        // editing or cancelling the damage event, most plausibly. The execute is defined as a guaranteed
        // kill below the threshold, so honour that. kill() is setHealth(0) plus a death with our own
        // source, which is why it reads better than a bare setHealth(0): that dies to a *generic* source
        // and credits nobody.
        if (!target.isDead() && target.isValid() && target.getHealth() > 0.0) {
            target.kill(source);
        }

        thornCd.put(vid, now + THORN_COOLDOWN_MS);
        EgoDurability.wearMainHand(wielder); // non-vanilla hit — wears a touch
        wielder.sendActionBar(EgoHud.status("Thorn — impaled", APPLE));
    }

    /** A soft splintering rustle where the splinter bit, pitch-jittered so a flurry of hits doesn't drone. */
    private void rustle(LivingEntity victim) {
        float pitch = 0.85f + ThreadLocalRandom.current().nextFloat() * 0.3f; // ~0.85 - 1.15
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 0.55f, pitch);
    }

    /** A low scatter of wooden thorns and a fleck of apple-red over the struck body — no green. */
    private void scatterThorns(LivingEntity victim) {
        World world = victim.getWorld();
        Location chest = victim.getLocation().add(0, 1.1, 0);

        Particle.DustOptions bark = new Particle.DustOptions(THORN, 0.9f);
        Particle.DustOptions apple = new Particle.DustOptions(APPLE_TIP, 0.8f);
        world.spawnParticle(Particle.DUST, chest, 6, 0.3, 0.4, 0.3, 0.0, bark);
        world.spawnParticle(Particle.DUST, chest, 2, 0.28, 0.35, 0.28, 0.0, apple);
        world.spawnParticle(Particle.BLOCK, chest, 4, 0.25, 0.3, 0.25, 0.0, THORN_BLOCK);
    }

    /**
     * The finisher's spectacle: a whole thicket of wooden bramble spikes bursts up through the ground
     * around and through the target — a ring of tall, gnarled thorns leaning inward with one towering
     * central spike driven clean through the body, every point crowned apple-red. Splintered wood is
     * thrown up from the shattered ground, a hard crack of splitting timber and a meaty impale sound
     * ring out, and the target is briefly hoisted off its feet, pinned on the thorns. All wood-brown and
     * apple-red — never green.
     */
    private void eruptThorn(World world, Location base, LivingEntity target) {
        Particle.DustOptions bark     = new Particle.DustOptions(THORN, 1.3f);
        Particle.DustOptions barkDark = new Particle.DustOptions(THORN_DARK, 1.2f);
        Particle.DustOptions tip      = new Particle.DustOptions(APPLE_TIP, 1.2f);

        // A ring of tall thorns bursting up around the body, each leaning slightly inward.
        final int spikes = 6;
        final double ringRadius = 0.95;
        for (int s = 0; s < spikes; s++) {
            double ang = (Math.PI * 2.0 * s) / spikes;
            Location foot = base.clone().add(Math.cos(ang) * ringRadius, 0, Math.sin(ang) * ringRadius);
            double height = 2.0 + ThreadLocalRandom.current().nextDouble() * 0.7; // ~2.0 - 2.7 blocks
            greenStemSpike(world, foot, height, 0.7, bark, barkDark, tip);
        }
        // The central impaling spike — tallest of all, driven straight up through the target.
        greenStemSpike(world, base.clone(), 3.3, 1.0, bark, barkDark, tip);

        // Splintered wood torn up from the shattered ground and flung along the spikes as they break through.
        world.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.1, 0), 44, 0.95, 0.15, 0.95, 0.22, THORN_BLOCK);
        world.spawnParticle(Particle.BLOCK, base.clone().add(0, 1.1, 0), 26, 0.6, 0.9, 0.6, 0.28, THORN_BLOCK);

        // A hard crack of splitting timber, a meaty impale thud, and a woody snap.
        world.playSound(base, Sound.BLOCK_WOOD_BREAK, 1.5f, 0.5f);
        world.playSound(base, Sound.ITEM_TRIDENT_HIT, 1.3f, 0.65f);
        world.playSound(base, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.1f, 0.65f);
        world.playSound(base, Sound.BLOCK_BAMBOO_BREAK, 1.1f, 0.5f);

        // Briefly hoist the body up off its feet — pinned, impaled on the thorns.
        target.setVelocity(target.getVelocity().setY(0.55));
    }

    /**
     * One gnarled wooden thorn spike: a tapering column of bark-brown splinter-dust rising from
     * {@code foot} to {@code height}, wide and jagged at the base, drawn to a point and capped by a
     * sharp apple-red tip. {@code thickness} scales the base spread.
     */
    private void greenStemSpike(World world, Location foot, double height, double thickness,
                                Particle.DustOptions bark, Particle.DustOptions barkDark,
                                Particle.DustOptions tip) {
        int steps = 12;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double spread = (0.34 * (1.0 - t) + 0.02) * thickness; // gnarled base tapering to a point
            Location p = foot.clone().add(0, t * height, 0);
            world.spawnParticle(Particle.DUST, p, 2, spread, 0.03, spread, 0.0, (i & 1) == 0 ? bark : barkDark);
        }
        // The apple-red point crowning the spike.
        world.spawnParticle(Particle.DUST, foot.clone().add(0, height, 0), 4, 0.06, 0.06, 0.06, 0.0, tip);
    }

    // ---- side grace: the poisoned bite --------------------------------------------

    /**
     * Right-click routing. A plain click does nothing; sneak + click takes a bite of the fruit —
     * mending the wielder on a short cooldown, or reporting the remaining wait on the action bar.
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!sneaking) return;

        long now = System.currentTimeMillis();
        long ready = biteCd.getOrDefault(player.getUniqueId(), 0L);
        if (now < ready) {
            player.sendActionBar(EgoHud.cooldown("Bite", ready - now, FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 0.35f, 0.7f);
            return;
        }

        AttributeInstance maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        double current = player.getHealth();
        double healed = Math.min(maxHp, current + BITE_HEAL);
        if (healed > current) {
            player.setHealth(healed);
        }

        biteCd.put(player.getUniqueId(), now + BITE_COOLDOWN_MS);
        crunch(player);
        healBurst(player);
    }

    /** A crisp apple-bite crunch, pitch-jittered. */
    private void crunch(Player player) {
        float pitch = 0.9f + ThreadLocalRandom.current().nextFloat() * 0.25f; // ~0.9 - 1.15
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, pitch);
    }

    /** A wood-and-apple burst rising around the wielder as the bite mends them — no green. */
    private void healBurst(Player player) {
        World world = player.getWorld();
        Location core = player.getLocation().clone().add(0, 1.0, 0);

        Particle.DustOptions bark = new Particle.DustOptions(THORN, 1.0f);
        Particle.DustOptions apple = new Particle.DustOptions(APPLE_TIP, 0.9f);
        world.spawnParticle(Particle.DUST, core, 12, 0.35, 0.5, 0.35, 0.0, bark);
        world.spawnParticle(Particle.DUST, core, 5, 0.32, 0.45, 0.32, 0.0, apple);
        world.spawnParticle(Particle.BLOCK, core, 6, 0.3, 0.4, 0.3, 0.0, THORN_BLOCK);
    }

    @Override
    public void onQuit(UUID id) {
        biteCd.remove(id);
        thornCd.remove(id);
    }

    @Override
    public void onDisable() {
        biteCd.clear();
        thornCd.clear(); // drop the per-target execute cooldowns so nothing survives a reload
    }

    // ---- palette & lore -----------------------------------------------------------

    // No green anywhere: predominantly woody brown with hints of apple red. The blade is named for a stem
    // it does not have — it is splintered wood and one red fruit, and the palette says so on purpose.
    private static final TextColor WOOD  = TextColor.color(0x8A5A2C); // primary — wood brown
    private static final TextColor APPLE = TextColor.color(0xC5342C); // secondary — apple-red accent
    private static final TextColor FAINT = TextColor.color(0x8A7458); // action bar

    private static final Color THORN      = Color.fromRGB(0x8A5A2C); // wooden thorn splinters (dust)
    private static final Color THORN_DARK = Color.fromRGB(0x5E3A1E); // deeper bark shadow (dust)
    private static final Color APPLE_TIP  = Color.fromRGB(0xC5342C); // apple-red thorn tip (dust)
    private static final BlockData THORN_BLOCK = Material.STRIPPED_OAK_WOOD.createBlockData(); // splinter debris

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Green Stem",
            "Snow White's Apple",
            WOOD,
            APPLE,
            List.of(
                    "Once realizing that nobody would come,",
                    "stems and leaves blossomed as if by",
                    "magic. However, the inherent malice",
                    "caused all life to crumble as soon as",
                    "it bloomed.",
                    "",
                    "Those who come in contact die by the",
                    "deep-seated hatred rather than the",
                    "sharpness of its tip."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Poison On Hit",
                            "Every melee hit poisons the struck",
                            "body for 4 seconds."),
                    new EgoLore.Ability("[Passive] Thorn",
                            "Anything at or below 5% of its max",
                            "health within 5 blocks is impaled by a",
                            "wooden thorn — a kill that armour,",
                            "shields and Resistance cannot stop, and",
                            "that costs the body's gear nothing.",
                            "Fires both from your hits and on its",
                            "own while the blade is held."),
                    new EgoLore.Ability("[Shift + Right-click] Bite",
                            "Take a bite of the fruit to mend two",
                            "hearts. 8 second cooldown.")
            ));
}
