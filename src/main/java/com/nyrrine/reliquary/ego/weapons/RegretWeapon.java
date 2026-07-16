package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Regret — "Forsaken Murderer" (Lobotomy Corp E.G.O, TETH). A crude, ponderous HEAVY WARHAMMER.
 *
 * <p>It is a MACE and it is <b>meant</b> to be one. The material is not an accident of the fallback: the
 * roster bans MACE precisely because the fall-slam passive fights whatever gimmick sits on top of it (see
 * {@link EgoModels#LANTERN}), and Regret is one of only two deliberate exceptions. Penitence is the other,
 * and it is the mirror image — it <i>cancels</i> its mace-ness on purpose ({@code MACE_DAMAGE_CAP}, so a
 * fall-slam only grazes). Regret keeps its mace-ness, in full, on purpose.
 *
 * <p>Its soul is a slowly-recharging heavy hit. While the hammer is HELD a per-wielder CHARGE climbs from
 * empty to full over {@value #CHARGE_MS} ms (~2.5 s), driven from {@link #onTick}. This is <b>not</b> a
 * hold-to-charge input — right-click HOLD is not detectable on this platform, and there is deliberately no
 * {@code onInteract} here. The charge winds on its own for as long as the hammer is in the main hand.
 *
 * <p>On a landed melee hit ({@link #onHit}) the charge sets the weight of the blow — {@value #DMG_MAX} at
 * full, sloping down to a {@value #DMG_FLOOR} floor at empty — and then RESETS, so the wielder must wind up
 * again before the next heavy swing.
 *
 * <p><b>The blow is written additively, and this is the point.</b> {@code event.getDamage()} arrives here
 * (WeaponManager dispatches at HIGH priority) already carrying everything vanilla put on the swing: the
 * MACE fall-slam bonus above all, plus enchants, a critical, Strength. The charge only replaces the
 * weapon's own flat base attack ({@link #BASE_ATK}, 7.0) — everything above that baseline is preserved and
 * added back on top. A blanket {@code setDamage(blow)} would erase the fall-slam entirely, which is what
 * this weapon used to do and exactly what its tooltip had to apologise for ("Falling onto a target adds
 * nothing"). Dropping onto a target now lands the smash in full.
 *
 * <p>The wielder reads the charge on the action bar through {@link EgoHud#gauge} ({@code Hammer NN%}, or
 * {@code Hammer — ready} at full). A cue fires once the hammer tops out; a landed blow lands a layered
 * iron thud, a data-safe shockwave, and grit thrown up off the ground it was driven into, all scaled to how
 * hard it was wound up and how far it fell. Normal melee wears the weapon through the vanilla path.
 *
 * <p>Spawns no entities, and calls no {@code victim.damage()} — it only ever writes the damage of the one
 * vanilla event it was handed. So there is no i-frame juggling to do (one blow per body per event, never
 * two in a tick) and no re-entrancy fence to keep. Keep it that way. The only per-player state is the
 * charge clock and the "ready" flag, both dropped on quit.
 */
public final class RegretWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Regret. */
    private final NamespacedKey key;

    // Tuning — a heavy hammer you wind up between swings.
    //
    // CHARGE_MS was 4_500L. At 0.9 attack speed a full-strength swing comes round every ~1.11 s, so 4.5 s
    // asked the wielder to stand through four of them to earn one blow — longer than most openings last,
    // which is what made a "slow hammer" read as a punishing one. 2.5 s is ~2.25 swing-cooldowns: you skip
    // about two swings for the big one. That is a decision. Four was a chore.
    //
    // It does not touch the ceiling: DMG_MAX is unchanged, and the charge RESETS on every hit, so the
    // sustained rate stays capped by the reset, not by the clock. See the DPS note on onHit.
    private static final long   CHARGE_MS = 2_500L; // empty -> full recharge time while held (~2.5s)
    private static final double DMG_FLOOR = 3.0;    // damage at empty charge — a limp tap
    private static final double DMG_MAX   = 12.5;   // damage at full charge — deliberately over the band

    /**
     * The weapon's own flat base attack, as stamped by {@link EgoModels#stampWeapon} — the item modifier is
     * {@code atk - 1.0} over the player's base 1.0 attack damage, so an unenchanted, un-slammed, uncritical
     * swing reaches {@link #onHit} as exactly this number. Anything {@code event.getDamage()} carries ABOVE
     * it is what the swing earned on its own, and is preserved rather than overwritten. Read from the model
     * so a retune of the roster in EgoModels cannot silently desync this baseline.
     */
    private static final double BASE_ATK = EgoModels.REGRET.atk(); // 7.0

    /**
     * How much bonus damage marks a blow as a real fall-slam, for presentation only — never for damage.
     * Sharpness V adds a flat +3.0 and a critical on the bare base adds +3.5, so anything at or above 4.0 is
     * more than either can explain alone and is, in practice, the hammer coming down off a drop. A crit on a
     * Sharpness-V hammer would also trip it — that blow is genuinely enormous and has earned the heavy tell.
     */
    private static final double SLAM_TELL = 4.0;

    /** Epoch ms the current charge cycle began (charge = 0 at that instant) per wielder. */
    private final Map<UUID, Long> chargeSince = new ConcurrentHashMap<>();
    /** Wielders who have already heard the "ready" cue for the current charge (cleared on reset). */
    private final Set<UUID> readyCued = ConcurrentHashMap.newKeySet();

    public RegretWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "regret");
    }

    @Override
    public String id() {
        return "regret";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.REGRET.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.REGRET.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.REGRET);

        item.setItemMeta(meta);
        return item;
    }

    // ---- charge -------------------------------------------------------------------

    /** Current charge fraction [0,1] for this wielder — how far the heavy hit has wound up. */
    private double charge(UUID id) {
        Long since = chargeSince.get(id);
        if (since == null) return 0.0;
        long elapsed = System.currentTimeMillis() - since;
        if (elapsed <= 0) return 0.0;
        if (elapsed >= CHARGE_MS) return 1.0;
        return (double) elapsed / CHARGE_MS;
    }

    /** Start (or restart) the charge cycle from empty for this wielder. */
    private void resetCharge(UUID id) {
        chargeSince.put(id, System.currentTimeMillis());
        readyCued.remove(id);
    }

    /** Forget everything we track for this wielder. */
    private void clearCharge(UUID id) {
        chargeSince.remove(id);
        readyCued.remove(id);
    }

    // ---- gimmick: a slow, heavy hit -----------------------------------------------

    /** The wind-up: a laboured haul as the heavy head is dragged back for the swing. */
    @Override
    public void onSwing(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double charge = charge(player.getUniqueId());
        Location at = player.getLocation();
        World world = player.getWorld();

        // The heartbeat under the wind-up — louder and lower the more head there is to haul.
        world.playSound(at, Sound.ENTITY_WARDEN_HEARTBEAT,
                0.45f + (float) charge * 0.25f, 0.5f + rng.nextFloat() * 0.1f);

        // A wound-up swing drags the head through the air audibly; a limp tap has not earned the whoosh.
        // Gating on charge also keeps the common case (click-spam) at one sound per click, which is the
        // only reason a per-swing hook can afford a second layer at all.
        if (charge >= 0.5) {
            world.playSound(at, Sound.ITEM_MACE_SMASH_AIR,
                    0.3f + (float) charge * 0.3f, 0.55f + rng.nextFloat() * 0.08f);
        }
    }

    /**
     * A landed blow.
     *
     * <p>{@code event.getDamage()} is READ before it is written. It arrives carrying vanilla's whole swing:
     * this weapon's flat {@link #BASE_ATK} base, plus whatever the swing earned on its own — the MACE
     * fall-slam bonus, enchants, a critical, Strength. Subtracting the baseline isolates that earned part
     * and lets it ride on top of the charged blow instead of being erased. Only the weapon's own flat base
     * is replaced, which is the one number the charge is entitled to speak for.
     *
     * <p>Damage maths, at 0.9 attack speed (full-strength swing every ~1.11 s), against the band (a plain
     * netherite sword is 8/hit and 12.8 DPS; netherite + Sharpness V is ~11/hit and 17.6 DPS):
     * <ul>
     *   <li>Patient — wait the full 2.5 s: 12.5 a blow, 5.00 DPS (was 12.5 and 2.78 DPS at 4.5 s).</li>
     *   <li>Swing on cooldown, 1.11 s: charge 0.44, 7.22 a blow, 6.50 DPS (was 5.35 and 4.81 DPS).</li>
     *   <li>Click-spam into the 10-tick i-frame floor, 0.5 s: charge 0.20, 4.90 a blow, 9.80 DPS (was
     *       4.06 and 8.11 DPS). This is the true sustained ceiling and it is still under a BARE netherite
     *       sword's 12.8 — a little over half the Sharpness-V ceiling.</li>
     * </ul>
     * So the faster clock lifts sustained damage by ~35% and it does not leave the band: the reset, not the
     * charge clock, is what caps this weapon. Its claim is the single 12.5 burst, and that is unchanged.
     *
     * <p>Never {@code victim.damage()} — it would re-enter this dispatch. We write the one event we were
     * handed, so exactly one blow lands per body per event and there are no i-frames to clear.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID id = attacker.getUniqueId();
        double charge = charge(id);

        // Everything vanilla put on this swing above the weapon's own flat base. The MACE fall-slam lives
        // here — this is the same number PenitenceWeapon caps to defeat its own mace-ness. Regret keeps it.
        double bonus = Math.max(0.0, event.getDamage() - BASE_ATK);

        // The charge speaks for the base swing; the earned bonus rides on top rather than being erased.
        double blow = DMG_FLOOR + charge * (DMG_MAX - DMG_FLOOR);
        event.setDamage(blow + bonus);

        impact(attacker, victim.getLocation(), charge, bonus);

        // Spent — wind up again from empty.
        resetCharge(id);
    }

    /**
     * A live charge readout while Regret is held: {@code [▮▮▮▮▮▯▯▯▯▯]  Hammer 52%}, or
     * {@code Hammer — ready} at full. Drives the charge clock and fires the one-shot "ready" cue.
     * Returns true while the hammer is held, false the moment it leaves the hand (idle -> stop ticking).
     */
    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();
        if (!matches(player.getInventory().getItemInMainHand())) {
            clearCharge(id); // left the hand — drop the wind-up so charging only happens while held
            return false;
        }

        // First tick since drawing it — begin winding up from empty.
        if (!chargeSince.containsKey(id)) resetCharge(id);

        double frac = charge(id);
        if (frac >= 1.0) {
            if (readyCued.add(id)) readyCue(player); // once, the instant it tops out
            player.sendActionBar(EgoHud.gauge(IRON, 1.0, label("Hammer — ready")));
        } else {
            int pct = (int) Math.round(frac * 100);
            player.sendActionBar(EgoHud.gauge(IRON, frac, label("Hammer " + pct + "%")));
        }
        return true;
    }

    /** Drop this player's charge state. */
    @Override
    public void onQuit(UUID id) {
        clearCharge(id);
    }

    // ---- presentation --------------------------------------------------------------

    /**
     * The instant the hammer tops out: the head settling at the peak of the wind-up. A low iron clunk, not a
     * chime — the old cue rang at pitch 1.5, which reads bright and light and belongs to a smaller weapon.
     * The world hears the clunk (a wound-up Regret ought to be a tell others can read); the wielder gets
     * their own confirmation on top, one packet to one player.
     */
    private void readyCue(Player player) {
        Location at = player.getLocation();
        World world = player.getWorld();
        world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.3f, 0.55f);
        player.playSound(at, Sound.BLOCK_ANVIL_USE, 0.5f, 0.7f);
        world.spawnParticle(Particle.DUST, at.clone().add(0, 1.0, 0), 6, 0.25, 0.35, 0.25, 0, DUST);
    }

    /**
     * The impact of a landed blow — layered iron, a shockwave, and grit off the ground, all scaled to how
     * hard the hammer was wound up and how far it fell.
     *
     * <p>Budget: at most 19 particle calls on the very heaviest blow, against the old block's flat 20 on
     * every blow including the weakest. Nothing here is forced: a hit at a body's feet is for the people
     * standing near it, and forcing these out to 512 blocks for ~100 wielders is exactly the kind of spend
     * this server cannot make. Everything is a one-shot — no trailing tasks, no entities.
     */
    private void impact(Player attacker, Location at, double charge, double bonus) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = at.getWorld();
        boolean heavy = bonus >= SLAM_TELL; // presentation only — see SLAM_TELL

        // The hammer itself, in vanilla's own voice: the mace smash. A blow that came down off a drop gets
        // the heavy variant, which is the sound the game already uses for exactly this.
        float vol = 0.6f + (float) charge * 0.6f;
        world.playSound(at, heavy ? Sound.ITEM_MACE_SMASH_GROUND_HEAVY : Sound.ITEM_MACE_SMASH_GROUND,
                vol, 0.62f + rng.nextFloat() * 0.1f);

        // The iron head under it, pitched right down — the weight behind the smash.
        world.playSound(at, Sound.BLOCK_ANVIL_LAND, vol * 0.75f, 0.5f + rng.nextFloat() * 0.1f);

        // Screen-feel: the same blow again, dry and close, in the wielder's ears alone. It puts the hit in
        // your hands rather than over at the body. One packet, one player — the cheapest weight there is.
        // (There is no camera-shake API here, and faking one by nudging the attacker's velocity would move
        // a player mid-fight, which is not a thing a particle effect gets to do.)
        attacker.playSound(attacker.getLocation(),
                heavy ? Sound.ITEM_MACE_SMASH_GROUND_HEAVY : Sound.ITEM_MACE_SMASH_GROUND,
                0.45f + (float) charge * 0.35f, 0.55f);

        // A low dust shockwave ring at the struck body's feet — wider the harder it landed.
        shockwave(at, charge, bonus);

        // Grit thrown up out of the ground the body was driven into.
        if (charge >= 0.35 || heavy) groundBurst(at, heavy);

        // A crushing, well-charged blow erupts a small impact burst (Particle.EXPLOSION needs no data).
        if (charge >= 0.6) {
            world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.7f * (float) charge, 0.55f);
            world.spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.4, 0), 1);
        }
    }

    /**
     * A low dust shockwave ring at the struck body's feet, sized by the charge behind the blow and widened
     * by the fall behind it. Points scale with the charge too: a limp tap spends 8 calls where a full swing
     * spends 16, instead of the flat 18 the old block spent on everything.
     */
    private void shockwave(Location impact, double charge, double bonus) {
        World world = impact.getWorld();
        Location feet = impact.clone();
        final int points = 8 + (int) Math.round(charge * 8);
        // Bonus widens the ring, saturating at 12.0 — a ~3-block drop, where vanilla's slam curve leaves its
        // steep first stretch. Past that the ring is already as wide as it reads.
        final double radius = 1.2 + charge * 1.2 + Math.min(bonus, 12.0) / 12.0 * 0.8;
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            Location p = feet.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius);
            world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.02, 0.05, 0, DUST);
        }
        // A short puff of kicked-up grit at the very centre of the slam.
        world.spawnParticle(Particle.DUST, feet.clone().add(0, 0.2, 0), 6, 0.25, 0.1, 0.25, 0, DUST);
    }

    /**
     * Grit thrown up off the ground the body is standing on. DUST_PILLAR is the particle vanilla's own mace
     * smash throws, and it takes the struck ground's <b>BlockData</b> (NOT DustOptions — the wrong data
     * class here is a runtime crash, not a compile error), so a blow landed on stone throws stone and one
     * landed on sand throws sand. Skipped over air and liquid: nothing there to kick up.
     */
    private void groundBurst(Location at, boolean heavy) {
        Block ground = at.clone().subtract(0, 0.2, 0).getBlock();
        Material m = ground.getType();
        if (m.isAir() || !m.isSolid()) return; // skip air, liquids, non-solids
        BlockData data = ground.getBlockData();
        at.getWorld().spawnParticle(Particle.DUST_PILLAR, at.clone().add(0, 0.1, 0),
                heavy ? 8 : 4, 0.3, 0.05, 0.3, 0.0, data);
    }

    // ---- lore ----------------------------------------------------------------------

    /** Primary — the hammer's grim iron. Display name, "How to use:", ability headers, action bar. */
    private static final TextColor IRON  = TextColor.color(0x8A8F96);
    /**
     * Secondary — the crush/weight accent from this weapon's own palette, now carrying the Abnormality
     * title line. The old block set that line in {@link #IRON} like the name; the shared tooltip wants the
     * two distinct, and this is the accent already here that sits nearest the head of a maul.
     */
    private static final TextColor HEAVY = TextColor.color(0x6E7278);

    private static final Color GRIT = Color.fromRGB(0x70, 0x74, 0x7A); // the shockwave grit — cold iron-gray
    private static final Particle.DustOptions DUST = new Particle.DustOptions(GRIT, 1.4f);

    /** A small non-italic action-bar label in the hammer's iron tone. */
    private static Component label(String text) {
        return Component.text(text).color(IRON).decoration(TextDecoration.ITALIC, false);
    }

    // The moveset below is read off this file's code, not off the block it replaces. Three things worth
    // naming so they are never written back in:
    //   - the charge is NOT held down. There is no hold input on this platform; onTick winds it up on its
    //     own for as long as the hammer is in the main hand.
    //   - the ability that slammed the ground is gone, along with the mace it belonged to. Do not bring it
    //     or its name back.
    //   - the mace line below is now TRUE, and it was not before. The old text said "Falling onto a target
    //     adds nothing", and that was honest of the old code: it wrote the damage flat and erased vanilla's
    //     fall-slam. onHit now reads the earned bonus off the event and adds it back. The tooltip may say
    //     this weapon is a mace because the code makes it one — if that ever stops being true, this line
    //     comes out before the code does.
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Regret",
            "Forsaken Murderer",
            IRON,
            HEAVY,
            List.of(
                    "A heavy maul left behind by",
                    "the one the world forgot."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Mace Head",
                            "It is a mace, and swings as one.",
                            "Drop onto a target mid-fall and the",
                            "mace smash lands in full — the",
                            "farther the fall, the harder it hits.",
                            "Its bonus adds on top of the blow",
                            "below rather than replacing it."),
                    new EgoLore.Ability("[Passive] Charge Buildup",
                            "The hammer winds itself up while",
                            "held, reaching full charge in about",
                            "2.5 seconds. The bar reads out how",
                            "far along it is."),
                    new EgoLore.Ability("[Left Click] Charged Strike",
                            "A blow deals 3 damage from empty, up",
                            "to 12.5 at full charge, then spends",
                            "the charge and winds up again from",
                            "empty. Anything the swing earns on",
                            "its own — the mace smash, enchants,",
                            "a critical — lands on top.")
            ));
}
