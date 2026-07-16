package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Boss;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Life for a Daredevil — the abnormality <b>Crumbling Armor</b>. A Lobotomy Corp E.G.O Equipment: an
 * ancient sword whose archetype "desired" that it be useless in the hands of the frightened.
 *
 * <p>The blade is a plain NETHERITE_SWORD swing — vanilla damage, uncancelled — with a single, brutal
 * gimmick in {@link #onHit}: a <b>decapitation execute</b>. When a landed blow finds a foe already at the
 * end of its rope (a normal mob under 25% HP, a player under 10%, a boss under 5%) and that foe is within
 * {@link #EXECUTE_RANGE} blocks, the wielder blinks to <em>directly behind</em> the target and takes the
 * head, wrapped in a clean slice, a burst of blood, and a wet crunch.
 *
 * <h2>How the head comes off</h2>
 * The finisher is a real, armour-bypassing blow: a {@link DamageSource} built on
 * {@link DamageType#GENERIC_KILL} and credited to the wielder, sized by {@link #executeDamage} to be
 * lethal through the worst mitigation the target can legally stack.
 *
 * <p>{@code GENERIC_KILL} is the type vanilla's own {@code kill()} builds, and of the types the server
 * ships it is the one that actually delivers an execute: it is tagged {@code bypasses_armor},
 * {@code bypasses_resistance} and {@code bypasses_invulnerability} (and, because {@code bypasses_shield}
 * includes the whole {@code bypasses_armor} tag, a raised shield does not stop it either). {@code MAGIC}
 * bypasses armour but is still cut by Resistance, so it cannot promise a kill.
 *
 * <p>The armour bypass is also what keeps the hit honest. The server charges armour durability
 * (~damage/4 <em>per piece</em>) inside a branch it skips outright when the source is tagged
 * {@code bypasses_armor} — so the size of this blow costs the victim's gear nothing. An earlier build
 * dealt a plain 10,000-point <em>attack</em> instead, which carries no such tag; that stripped ~2,500
 * durability off every piece the victim wore, in one hit. Sizing the number down was a workaround for
 * the wrong problem: the damage type was the bug, not the magnitude.
 *
 * <p>That finishing blow is dealt with {@link LivingEntity#damage(double, DamageSource)}, which fires its
 * own {@code EntityDamageByEntityEvent} and re-enters {@link #onHit}. A per-attacker re-entrancy fence
 * ({@link #executing}) makes the re-entrant call a no-op, so one swing lands exactly one execute, never a
 * loop. Going through {@code damage()} rather than straight to a kill is deliberate: it is what marks the
 * wielder as the last attacker, which is what the server later reads to award XP and player-kill drops.
 *
 * <p><b>Drawback.</b> While the sword is held the wielder is diminished: a {@code -4.0} (two-heart)
 * {@link Attribute#MAX_HEALTH} modifier, keyed by {@link #HEALTH_KEY}, is applied in {@link #onTick} and
 * stripped the moment the blade leaves the hand (the tick then returns {@code false} to disengage). The
 * keyed modifier is remove-before-add and can exist at most once per player; it is also cleared on quit,
 * on death, on join (defensively), and on plugin disable, so it can never stack or leak.
 */
public final class LifeForADaredevilWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Life for a Daredevil. */
    private final NamespacedKey key;

    /** Key for the two-heart MAX_HEALTH burden applied while the blade is held. */
    private final NamespacedKey healthKey;

    /** How far a target may be and still be blinked-to and executed. */
    private static final double EXECUTE_RANGE = 9.0;
    private static final double EXECUTE_RANGE_SQ = EXECUTE_RANGE * EXECUTE_RANGE;

    /** HP-fraction thresholds below which a struck target qualifies for the decapitation. */
    private static final double THRESH_MOB    = 0.25; // a normal mob under a quarter
    private static final double THRESH_PLAYER = 0.10; // a player under a tenth
    private static final double THRESH_BOSS   = 0.05; // a boss under a twentieth

    /**
     * Protection's ceiling. The server clamps the enchantment's protection factor to 20 and scales damage
     * by {@code 1 - factor/25}, so no legal armour set can shave off more than 80% — five times what is
     * needed to kill is therefore lethal through any of it. {@code GENERIC_KILL} is not tagged
     * {@code bypasses_enchantments}, so this is the one reduction the execute still has to out-muscle.
     */
    private static final double PROTECTION_HEADROOM = 5.0;

    /**
     * Slack folded into the finisher before the headroom multiplies it. Covers the target's absorption
     * hearts and the damage cooldown: a blow landing inside the victim's invulnerability window has the
     * previous hit's damage subtracted from it, and the previous hit here is the sword swing that
     * triggered the execute in the first place.
     */
    private static final double EXECUTE_MARGIN = 40.0;

    /** The two-heart burden borne while the blade is held. */
    private static final double HEALTH_PENALTY = -4.0;

    /** Bosses by type. An entity that {@code instanceof Boss} (carries a boss bar) also counts. */
    private static final Set<EntityType> BOSS_TYPES = EnumSet.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN, EntityType.ELDER_GUARDIAN);

    /** Attackers currently inside their own execute damage() call — the fence against re-entrant onHit. */
    private final Set<UUID> executing = new HashSet<>();

    /** Players currently carrying the two-heart burden, so disable/cleanup is precise. */
    private final Set<UUID> burdened = new HashSet<>();

    public LifeForADaredevilWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "life_for_a_daredevil");
        this.healthKey = new NamespacedKey(plugin, "life_for_a_daredevil_burden");
    }

    @Override
    public String id() {
        return "life_for_a_daredevil";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.LIFE_FOR_A_DAREDEVIL.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.LIFE_FOR_A_DAREDEVIL.material());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Life for a Daredevil").color(NAME).decoration(TextDecoration.ITALIC, false));
        meta.lore(LORE);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.LIFE_FOR_A_DAREDEVIL);

        item.setItemMeta(meta);
        return item;
    }

    // ---- gimmick: a decapitation execute on a faltering foe -------------------------

    /**
     * Melee hit landed. The vanilla sword damage is left intact. If the struck target is within
     * {@link #EXECUTE_RANGE} and already below its HP threshold, the wielder blinks behind it and takes
     * the head with an armour-bypassing finishing blow. The finishing blow re-enters this method (it fires
     * its own damage event); the {@link #executing} fence makes that re-entrant call a no-op.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID aid = attacker.getUniqueId();
        if (executing.contains(aid)) return;                 // our own execute re-entering — never recurse

        if (victim.isDead() || !victim.isValid()) return;
        if (victim.getWorld() != attacker.getWorld()) return;
        if (attacker.getLocation().distanceSquared(victim.getLocation()) > EXECUTE_RANGE_SQ) return;

        AttributeInstance maxAttr = victim.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        if (maxHp <= 0.0) return;
        double frac = victim.getHealth() / maxHp;
        if (frac >= thresholdFor(victim)) return;            // not faltering enough — an ordinary sword blow

        decapitate(attacker, victim);
    }

    /**
     * The execute's damage source: {@link DamageType#GENERIC_KILL}, attributed to the wielder.
     *
     * <p>Both the causing and the direct entity are the wielder — they are stood at the target's back
     * swinging the blade themselves, with nothing in between. That attribution is what names them in the
     * death message and hands them the drops.
     */
    private static DamageSource executeSource(Player attacker) {
        return DamageSource.builder(DamageType.GENERIC_KILL)
                .withCausingEntity(attacker)
                .withDirectEntity(attacker)
                .build();
    }

    /**
     * What it takes to finish this target for certain: everything it has left to spend — health plus
     * absorption plus {@link #EXECUTE_MARGIN} of slack — multiplied by {@link #PROTECTION_HEADROOM}.
     *
     * <p>Derived rather than a fixed overkill number, so it stays lethal against a target with far more
     * health than vanilla grants without ever being an arbitrary magic constant. It costs the victim's
     * armour nothing however large it gets — see the class notes on {@code bypasses_armor}.
     */
    private static double executeDamage(LivingEntity victim) {
        return (victim.getHealth() + victim.getAbsorptionAmount() + EXECUTE_MARGIN) * PROTECTION_HEADROOM;
    }

    /** The HP fraction below which this target may be executed. */
    private double thresholdFor(LivingEntity victim) {
        if (isBoss(victim)) return THRESH_BOSS;
        if (victim instanceof Player) return THRESH_PLAYER;
        return THRESH_MOB;
    }

    /** Boss = one of a small set of boss types, or any entity carrying a boss bar. */
    private boolean isBoss(LivingEntity victim) {
        return BOSS_TYPES.contains(victim.getType()) || victim instanceof Boss;
    }

    /**
     * Blink the wielder to directly behind the target and take the head: FX, a wet crunch, then the
     * armour-bypassing finishing blow fenced against re-entrancy, and a point of non-vanilla wear on the
     * blade.
     */
    private void decapitate(Player attacker, LivingEntity victim) {
        UUID aid = attacker.getUniqueId();

        // Step behind the target — one step past its back, turned to look at the nape.
        Location vLoc = victim.getLocation();
        Vector facing = vLoc.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0e-6) {
            facing = attacker.getLocation().toVector().subtract(vLoc.toVector()).setY(0);
        }
        if (facing.lengthSquared() < 1.0e-6) facing = new Vector(0, 0, 1);
        facing.normalize();
        Location behind = vLoc.clone().subtract(facing.clone().multiply(1.2));
        behind.setDirection(facing);                          // face the same way the victim does = at its back
        attacker.teleport(behind);

        decapFx(attacker, victim);

        // The finishing blow: armour-bypassing, credited to the wielder, and sized to be lethal outright.
        // Guard re-entrancy — this damage() re-fires onHit for the same attacker.
        DamageSource source = executeSource(attacker);
        executing.add(aid);
        try {
            victim.damage(executeDamage(victim), source);
        } finally {
            executing.remove(aid);
        }
        // Belt and braces. The blow above is lethal on its own against anything the game can legally put in
        // its way, so reaching here means something outside this weapon intervened — another plugin editing
        // or cancelling the damage event, most plausibly. An execute is defined as a guaranteed kill below
        // the threshold, so honour that. kill() is setHealth(0) plus a death with our own source, which is
        // why it reads better than a bare setHealth(0): that dies to a *generic* source and credits nobody.
        if (!victim.isDead() && victim.isValid() && victim.getHealth() > 0.0) {
            victim.kill(source);
        }

        // Teleport + kill is a non-vanilla action, so wear the blade a point beyond the vanilla swing.
        EgoDurability.wearMainHand(attacker);
    }

    // ---- drawback: two hearts, while borne ------------------------------------------

    /**
     * Ticked only while this player is an engaged wielder. Apply the two-heart burden when the blade is in
     * the main hand; strip it and disengage ({@code return false}) the moment it is not.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (matches(player.getInventory().getItemInMainHand())) {
            applyBurden(player);
            return true;                                      // still held — keep ticking
        }
        clearBurden(player);
        return false;                                         // blade is away — drop the burden, stop ticking
    }

    /** Apply the keyed two-heart MAX_HEALTH modifier if it is not already present (remove-before-add safe). */
    private void applyBurden(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        for (AttributeModifier m : inst.getModifiers()) {
            if (healthKey.equals(m.getKey())) { burdened.add(player.getUniqueId()); return; } // already borne
        }
        inst.addModifier(new AttributeModifier(healthKey, HEALTH_PENALTY, AttributeModifier.Operation.ADD_NUMBER));
        burdened.add(player.getUniqueId());
    }

    /** Strip the keyed burden from a live player, if present. */
    private void clearBurden(Player player) {
        burdened.remove(player.getUniqueId());
        AttributeInstance inst = player.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;
        for (AttributeModifier m : inst.getModifiers()) {
            if (healthKey.equals(m.getKey())) inst.removeModifier(m);
        }
    }

    @Override
    public void onJoin(Player player) {
        clearBurden(player);                                  // defensive: never inherit a stale burden on login
    }

    @Override
    public void onPlayerDeath(PlayerDeathEvent event) {
        clearBurden(event.getEntity());                       // don't carry the burden through respawn
    }

    @Override
    public void onQuit(UUID id) {
        executing.remove(id);
        burdened.remove(id);
        Player p = plugin.getServer().getPlayer(id);
        if (p != null) clearBurden(p);                        // still online at quit-time — strip it clean
    }

    @Override
    public void onDisable() {
        for (UUID id : new HashSet<>(burdened)) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) clearBurden(p);
        }
        burdened.clear();
    }

    // ---- SFX / VFX ------------------------------------------------------------------

    /**
     * The decapitation: a clean bright slice drawn horizontally across the nape, a low burst of blood, a
     * short upward spray, and a two-part sound — a crisp sweep and a wet crunch, pitch-jittered.
     */
    private void decapFx(Player attacker, LivingEntity victim) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = victim.getWorld();
        Location neck = victim.getLocation().add(0, victim.getHeight() * 0.85, 0);

        // Sound: a clean sweep followed by a heavy, wet crunch.
        world.playSound(neck, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.9f + (rng.nextFloat() - 0.5f) * 0.1f);
        world.playSound(neck, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.7f + (rng.nextFloat() - 0.5f) * 0.1f);
        world.playSound(neck, Sound.BLOCK_ANVIL_LAND, 0.35f, 1.4f + (rng.nextFloat() - 0.5f) * 0.1f);

        // The clean slice — a bright near-white line laid horizontally across the neck, facing the striker.
        Vector dir = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 1.0e-6) dir = attacker.getEyeLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0e-6) dir = new Vector(1, 0, 0);
        dir.normalize();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) right = new Vector(1, 0, 0);
        right.normalize();

        Particle.DustOptions slice = new Particle.DustOptions(SLICE, 1.1f);
        for (int i = -3; i <= 3; i++) {
            Location p = neck.clone().add(right.clone().multiply(i * 0.16));
            world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, slice);
        }

        // Blood: a low burst at the neck and a short spray driven upward from the cut.
        Particle.DustOptions blood = new Particle.DustOptions(BLOOD, 1.3f);
        world.spawnParticle(Particle.DUST, neck, 22, 0.22, 0.14, 0.22, 0.0, blood);
        world.spawnParticle(Particle.DUST, neck.clone().add(0, 0.15, 0), 8, 0.05, 0.02, 0.05, 0.12, blood);
    }

    // ---- lore -----------------------------------------------------------------------

    private static final TextColor NAME  = TextColor.color(0x8FA6BE); // name / abnormality title — pale gray-blue
    private static final Color     SLICE = Color.fromRGB(0xED, 0xEF, 0xF2); // the clean slice — near-white
    private static final Color     BLOOD = Color.fromRGB(0x8A, 0x0F, 0x12); // decapitation blood — dark red
    private static final TextColor PALE  = TextColor.color(0xC6CFD8); // base description
    private static final TextColor STEEL = TextColor.color(0x9FB8D8); // mechanic accent
    private static final TextColor FAINT = TextColor.color(0x7C8794); // conditions / epithet

    private record Seg(String text, TextColor color, boolean italic) {
        Seg(String text, TextColor color) { this(text, color, false); }
    }

    private static final List<List<Seg>> LORE_SRC = List.of(
        List.of(new Seg("Crumbling Armor", NAME)),
        List.of(),
        List.of(new Seg("An ancient sword.", PALE)),
        List.of(new Seg("Just as its archetype desired, it", FAINT, true)),
        List.of(new Seg("will be useless in the hands of", FAINT, true)),
        List.of(new Seg("the frightened", FAINT, true)),
        List.of(),
        List.of(new Seg("E.G.O Equipment", FAINT)),
        List.of(),
        List.of(new Seg("How to use:", FAINT)),
        List.of(new Seg("Blink behind a faltering foe within", PALE)),
        List.of(new Seg("9 blocks", STEEL), new Seg(" and take its head.", PALE)),
        List.of(new Seg("Executes ", FAINT), new Seg("mob <25%", STEEL), new Seg(", ", FAINT), new Seg("player <10%", STEEL), new Seg(",", FAINT)),
        List.of(new Seg("boss <5%", STEEL), new Seg(" HP.", FAINT)),
        List.of(new Seg("Holding it costs you ", FAINT), new Seg("2 hearts", STEEL), new Seg(".", FAINT))
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
