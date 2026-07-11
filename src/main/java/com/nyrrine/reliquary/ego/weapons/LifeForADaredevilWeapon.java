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
 * head: a modest, kill-credited finishing blow ({@link #EXECUTE_DAMAGE}) that {@code setHealth(0)} then
 * guarantees, wrapped in a clean slice, a burst of blood, and a wet crunch. True armor-bypass
 * ({@code DamageType}) is not on this compile classpath; earlier builds bought the kill with a
 * 10,000-point overkill, but that made the victim's armor lose ~2,500 durability per piece in a single hit
 * (durability toll scales with damage dealt), destroying armored gear instantly. The blow is now normal-
 * sized and the guaranteed kill comes from {@code setHealth(0)}, which takes no toll on armor at all.
 *
 * <p>That finishing blow is dealt with {@link LivingEntity#damage(double, org.bukkit.entity.Entity)},
 * which fires its own {@code EntityDamageByEntityEvent} and re-enters {@link #onHit}. A per-attacker
 * re-entrancy fence ({@link #executing}) makes the re-entrant call a no-op, so one swing lands exactly one
 * execute, never a loop.
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
     * The credited finishing blow. Kept modest on purpose: a landed {@code damage()} makes armour lose
     * durability proportional to the hit (~damage/4 per piece), so the old 10,000-point overkill shredded
     * a victim's whole armour set in a single execute. This lands a normal-sized, kill-credited blow; if a
     * heavily-armoured or Resistance-shielded target soaks it, {@link #decapitate} finishes the kill with
     * {@code setHealth(0)} — no overkill number, no armour-destroying durability blast.
     */
    private static final double EXECUTE_DAMAGE = 20.0;

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
     * the head with a huge raw finishing blow. The finishing blow re-enters this method (it fires its own
     * damage event); the {@link #executing} fence makes that re-entrant call a no-op.
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
     * Blink the wielder to directly behind the target and take the head: FX, a wet crunch, then a huge
     * raw finishing blow fenced against re-entrancy, and a point of non-vanilla wear on the blade.
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

        // The finishing blow. Guard re-entrancy: this damage() re-fires onHit for the same attacker.
        // A modest, credited hit (not a thousand-point overkill) keeps armour durability loss vanilla-sized.
        executing.add(aid);
        try {
            victim.damage(EXECUTE_DAMAGE, attacker);
        } finally {
            executing.remove(aid);
        }
        // Guarantee the execute even through heavy armour or Resistance, which could soak the modest blow.
        // setHealth(0) doesn't run the damage pipeline, so it takes no further toll on the victim's armour;
        // the recent damage() above still credits the kill to the wielder.
        if (!victim.isDead() && victim.isValid() && victim.getHealth() > 0.0) {
            victim.setHealth(0.0);
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
