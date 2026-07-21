package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoEnchants;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Crimson Scar — "Little Red Riding Hooded Mercenary" (Lobotomy Corp E.G.O, HE).
 *
 * <p>A trick weapon that the wielder — not the RNG — reconfigures. Crimson Scar exists in two item
 * forms and <b>SHIFT + right-click</b> snaps between them:
 *
 * <ul>
 *   <li><b>Melee form</b> — a woodsman's axe ({@link EgoModels#CRIMSON_SCAR}, NETHERITE_AXE 7.0/1.2). The
 *       vanilla chop is left uncancelled; on-hit it squelches wetly and, below half HP, goes
 *       <b>blood-drunk</b>: the chop hits harder and splashes everything near the wielder (allies and
 *       other players included — the frenzy is indiscriminate, and that is the drawback).</li>
 *   <li><b>Pistol form</b> — a flintlock: a hard-coded CROSSBOW item (no melee attributes) stamped with
 *       the same PDC key plus a "pistol" marker byte. A plain <b>right-click</b> fires one STRONG but
 *       SLOW hitscan bullet down the eye line, then a LONG reload locks the trigger. While reloading the
 *       action bar shows {@code EgoHud.cooldown("Reload", …)} and a hint to swap back to steel.</li>
 * </ul>
 *
 * <p><b>Resource-pack / model note (no code change):</b> the melee form's in-game model (CMD
 * {@code "ego/crimson_scar"}) is meant to render as a <b>SICKLE</b>; the pistol form (CMD
 * {@code "ego/crimson_scar_pistol"}) renders as the flintlock. Both are plain vanilla items with no pack.
 *
 * <p>State: a per-wielder {@code firing} re-entrancy fence around the flintlock's {@code victim.damage(...)}
 * (so the bullet can't chain), and a {@code lastFire} reload-clock map. Both cleared on quit. No world
 * edits; nothing runs for non-wielders.
 */
public final class CrimsonScarWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Crimson Scar (present on BOTH forms). */
    private final NamespacedKey key;

    /** PDC marker byte present ONLY on the pistol (flintlock) form — distinguishes it from the axe. */
    private final NamespacedKey formKey;

    /**
     * Wielders whose flintlock shot is currently resolving. {@code victim.damage(...)} from the shot
     * re-enters onHit for the same wielder; while their id is in here the melee gimmick is skipped, so a
     * bullet can never trigger a chop's on-hit. Cleared per shot (and on quit).
     */
    private final Set<UUID> firing = new HashSet<>();

    /** Wielder -> epoch-millis of their last flintlock shot; drives the reload lock. Cleared on quit. */
    private final Map<UUID, Long> lastFire = new HashMap<>();

    /** Wielder -> count of properly-spaced melee strikes toward the 3rd-strike lunging stab. Cleared on quit. */
    private final Map<UUID, Integer> strikeCount = new HashMap<>();

    /** Wielder -> epoch-millis of their last COUNTED melee strike; the mash guard for the combo. Cleared on quit. */
    private final Map<UUID, Long> lastCountedHit = new HashMap<>();

    /** Victim -> their live lunging-stab bleed task; refreshed (cancel + replace), self-clearing, cancelled on quit/disable. */
    private final Map<UUID, BukkitTask> bleeds = new HashMap<>();

    /**
     * Wielders whose lunging-stab bleed tick is currently resolving. That {@code victim.damage(...)} re-enters
     * {@link #onHit}; while their id is in here the melee gimmick (and the combo count) is skipped, so a bleed
     * tick can never re-arm the combo or re-open another bleed. Cleared per tick (and on quit/disable).
     */
    private final Set<UUID> ticking = new HashSet<>();

    // Flintlock tuning — a strong single ball, then a long wait. Shots cannot be fast.
    private static final double FLINTLOCK_DAMAGE   = 9.0;    // 4.5 hearts — a decisive strike
    private static final double FLINTLOCK_RANGE    = 30.0;   // hitscan reach
    private static final double FLINTLOCK_RAY_SIZE = 0.5;    // entity ray fatness (forgiving aim)
    private static final long   RELOAD_MS          = 4000L;  // long reload — swap back to steel meanwhile

    // Ram the Powder (a custom enchant — id "ram_the_powder"): the melee form is an axe, which can't hold
    // Quick Charge at an anvil, so a vanilla read is impossible — this is an ego-enchant that cuts the
    // flintlock's reload lock by 12% per level, up to 36% at level 3. Cadence only — never the ball's blow.
    private static final double RAM_THE_POWDER_PER_LEVEL = 0.12;
    private static final int    RAM_THE_POWDER_CAP       = 3;

    // Lunging-stab combo tuning — every 3rd properly-spaced chop lunges in and opens a bleed.
    private static final long   MIN_HIT_INTERVAL_MS = 350L; // hits closer than this are mash — they don't count
    private static final int    LUNGE_STRIKE        = 3;    // the 3rd counted strike is the lunging stab
    private static final double LUNGE_SPEED         = 0.95; // forward dash impulse into the target
    private static final double LUNGE_UP            = 0.12; // little upward pop so terrain doesn't eat the dash
    // The bleed opened by the stab — a short, modest DoT with no knockback.
    private static final double STAB_BLEED_DAMAGE      = 1.5;  // ~0.75 hearts per tick
    private static final long   STAB_BLEED_PERIOD_TICKS = 10L; // every ~0.5s
    private static final int    STAB_BLEED_TICKS        = 3;   // three ticks -> ~4.5 over ~1.5s

    // Blood-drunk tuning — the below-half-HP frenzy (unchanged; "the effects are good").
    private static final double BLOOD_DRUNK_FRAC     = 0.50;  // below 50% max HP
    private static final int    BLOOD_DRUNK_FRAC_PCT = 50;    // (for the javadoc/tuning readout)
    private static final double BLOOD_DRUNK_MULT     = 1.5;   // damage buff on the chop
    private static final double SPLASH_RADIUS        = 3.0;   // blades reach out this far
    private static final int    SPLASH_CAP           = 6;     // at most this many caught in the spray
    private static final double SPLASH_KNOCKBACK     = 0.45;  // horizontal shove on the splashed
    private static final double SPLASH_KNOCKBACK_UP  = 0.22;  // little upward pop

    /**
     * What the spray takes out of each body it catches. Deliberately a third of the chop that threw it:
     * the frenzy's weight is the ×{@value #BLOOD_DRUNK_MULT} on the blow itself, and the spray is the
     * blood coming off it — everyone nearby is cut, nobody nearby is executed. Six of these at once is
     * {@value #SPLASH_CAP} × 2.0 spread across six different bodies, never stacked on one, so no single
     * instance goes anywhere near the netherite band's ceiling.
     */
    private static final double SPLASH_DAMAGE        = 2.0;

    // Palette — crimson red, blood on snow.

    /** Primary — crimson. Display name, "How to use:", the ability headers, and the form-swap action bar. */
    private static final TextColor NAME  = TextColor.color(0xC01823);
    /**
     * Secondary — the blood accent, a shade brighter than the crimson above it. The Abnormality title line.
     * It used to tint the flavour block; the shared tooltip sets flavour in its own off-white, so the accent
     * carries the title rather than letting it repeat the name's colour.
     */
    private static final TextColor BLOOD = TextColor.color(0xD8323C);
    private static final TextColor STEEL = TextColor.color(0xB8BCC6); // steel / reload accent
    private static final TextColor FAINT = TextColor.color(0x8A6A6C); // conditions / controls / last line

    private static final Color RED_DEEP = Color.fromRGB(0xC0, 0x18, 0x23);
    private static final Color RED_BRIGHT = Color.fromRGB(0xF0, 0x3A, 0x44);
    private static final Particle.DustOptions BLOOD_DUST = new Particle.DustOptions(RED_DEEP, 1.2f);
    private static final Particle.DustOptions AURA_DUST  = new Particle.DustOptions(RED_BRIGHT, 1.4f);
    private static final Particle.DustOptions TRACER_DUST = new Particle.DustOptions(RED_BRIGHT, 0.8f);
    private static final Particle.DustOptions BLEED_DUST  = new Particle.DustOptions(RED_DEEP, 0.8f);

    public CrimsonScarWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "crimson_scar");
        this.formKey = new NamespacedKey(plugin, "crimson_scar_pistol");
    }

    @Override
    public String id() {
        return "crimson_scar";
    }

    /** matches() accepts BOTH forms: the axe (melee) and the crossbow (flintlock), both stamped with {@link #key}. */
    @Override
    public boolean matches(ItemStack item) {
        if (item == null) return false;
        Material t = item.getType();
        if (t != EgoModels.CRIMSON_SCAR.material() && t != Material.CROSSBOW) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** A stack is the pistol (flintlock) form iff it carries the {@link #formKey} marker byte. */
    private boolean isPistol(ItemStack item) {
        if (item == null) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(formKey, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        return buildMeleeForm();
    }

    // ---- item forms ---------------------------------------------------------------

    /** The MELEE form: the NETHERITE_AXE (its resource model is a SICKLE), full E.G.O melee stats. */
    private ItemStack buildMeleeForm() {
        ItemStack item = new ItemStack(EgoModels.CRIMSON_SCAR.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        // CMD "ego/crimson_scar" + the shared melee curve.
        EgoModels.stampWeapon(meta, EgoModels.CRIMSON_SCAR);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * The PISTOL (flintlock) form: a hard-coded CROSSBOW carrying the same {@link #key} plus the
     * {@link #formKey} marker. No melee attributes — its bite comes from the fired ball. Its resource
     * model keys off CMD {@code "ego/crimson_scar_pistol"} (set in-code; no EgoModels entry needed).
     */
    private ItemStack buildPistolForm() {
        ItemStack item = new ItemStack(Material.CROSSBOW);
        ItemMeta meta = item.getItemMeta();

        PISTOL_TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(formKey, PersistentDataType.BYTE, (byte) 1);
        // Hard-coded crossbow: CMD stamp only, no melee attribute modifiers.
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("ego/crimson_scar_pistol"));
        meta.setCustomModelDataComponent(cmd);

        item.setItemMeta(meta);
        return item;
    }

    /** Carry enchantments (vanilla and ego) and durability (by wear-fraction) from one form to the other. */
    private void carryOver(ItemStack from, ItemStack to) {
        ItemMeta tm = to.getItemMeta();
        if (tm == null) return;
        for (var e : from.getEnchantments().entrySet()) {
            tm.addEnchant(e.getKey(), e.getValue(), true);
        }
        // Ego-enchants live in a PDC sub-container, not the vanilla map — carry them too, or a swap to the
        // flintlock would drop Ram the Powder (and any future Crimson ego-enchant) the axe was holding.
        for (var e : EgoEnchants.all(from).entrySet()) {
            EgoEnchants.set(tm, e.getKey(), e.getValue());
        }
        ItemMeta fm = from.getItemMeta();
        if (fm instanceof Damageable fd && tm instanceof Damageable td) {
            int fromMax = from.getType().getMaxDurability();
            int toMax = to.getType().getMaxDurability();
            if (fromMax > 0 && toMax > 0 && fd.getDamage() > 0) {
                double frac = Math.min(1.0, (double) fd.getDamage() / fromMax);
                td.setDamage(Math.min(toMax - 1, (int) Math.round(frac * toMax)));
            }
        }
        to.setItemMeta(tm);
    }

    // ---- input: swap forms, fire, lunge -------------------------------------------

    /**
     * SHIFT + right-click toggles between the axe and the flintlock. A plain right-click fires the
     * flintlock (pistol form) or does a light cosmetic lunge (melee form).
     */
    @Override
    public void onInteract(Player player, boolean sneaking) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!matches(held)) return;

        if (sneaking) {
            toggleForm(player, held);
            return;
        }
        if (isPistol(held)) {
            fireFlintlock(player);
        }
        // Plain right-click in melee (steel) form does nothing — shift-right-click swaps to the flintlock.
    }

    /** Snap the held item to the other form, carrying enchants/durability, and sell the reconfigure. */
    private void toggleForm(Player player, ItemStack current) {
        boolean toPistol = !isPistol(current);
        ItemStack next = toPistol ? buildPistolForm() : buildMeleeForm();
        carryOver(current, next);
        player.getInventory().setItemInMainHand(next);

        reconfigureFx(player, player.getEyeLocation());
        // A form-swap is a non-vanilla use — wear the new main-hand item a mild point.
        EgoDurability.wearMainHand(player);

        renderBar(player); // the composed line already names the form we just snapped into
    }

    /**
     * The always-on composed readout, branching on the form in hand. The flintlock reads its reload gate;
     * the steel sickle reads its lunging-stab combo and, below half HP, its blood-drunk stance — every state
     * the wielder is carrying, on ONE line via {@link EgoHud#row}. Every path that used to flash a lone form
     * name or reload clock now sends this, so no single state replaces the line as the wielder acts.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false;
        renderBar(player);
        return true;
    }

    private void renderBar(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (isPistol(held)) {
            player.sendActionBar(EgoHud.row(formReadout(true), reloadReadout(player)));
        } else {
            player.sendActionBar(EgoHud.row(formReadout(false), lungeReadout(player), bloodReadout(player)));
        }
    }

    /** Which form is in hand: the flintlock, or the steel sickle. */
    private Component formReadout(boolean pistol) {
        return pistol ? EgoHud.status("Flintlock", STEEL) : EgoHud.status("Steel", NAME);
    }

    /** The flintlock reload for the pistol held right now: the base lock cut by its Ram the Powder bonus. */
    private long reloadMs(Player player) {
        int lvl = Math.min(RAM_THE_POWDER_CAP,
                EgoEnchants.level(player.getInventory().getItemInMainHand(), "ram_the_powder"));
        return (long) (RELOAD_MS * (1.0 - RAM_THE_POWDER_PER_LEVEL * lvl));
    }

    /** The flintlock half: its reload gate counting down, else ready to fire. */
    private Component reloadReadout(Player player) {
        Long last = lastFire.get(player.getUniqueId());
        long rem = last == null ? 0L : reloadMs(player) - (System.currentTimeMillis() - last);
        return rem > 0 ? EgoHud.cooldown("Reload", rem, STEEL) : EgoHud.ready("Reload", STEEL);
    }

    /** The steel half: the strike pips climbing toward the third-strike lunging stab. */
    private Component lungeReadout(Player player) {
        int n = Math.min(strikeCount.getOrDefault(player.getUniqueId(), 0), LUNGE_STRIKE);
        return EgoHud.pips("Lunge", BLOOD, n, LUNGE_STRIKE);
    }

    /** The blood-drunk stance, shown only while the wielder is below the frenzy threshold, else dropped. */
    private Component bloodReadout(Player player) {
        return isBloodDrunk(player) ? EgoHud.status("Blood-Drunk", BLOOD) : null;
    }

    /**
     * Fire one strong, slow flintlock ball down the eye line. Locked while reloading (the action bar shows
     * the reload clock + a swap hint). The ball's {@code victim.damage} re-enters onHit — the {@link #firing}
     * fence makes that a no-op so it cannot chain.
     */
    private void fireFlintlock(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long last = lastFire.get(id);
        if (last != null) {
            long remaining = reloadMs(player) - (now - last);
            if (remaining > 0) {
                renderBar(player); // the composed line already shows the reload counting down
                player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 1.1f);
                return;
            }
        }
        lastFire.put(id, now);

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // Clip at the first wall so the tracer stops where it should.
        double maxDist = FLINTLOCK_RANGE;
        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, FLINTLOCK_RANGE, FluidCollisionMode.NEVER, true);
        if (blockHit != null && blockHit.getHitPosition() != null) {
            maxDist = eye.toVector().distance(blockHit.getHitPosition());
        }

        // First living body (not the shooter) along the ray.
        RayTraceResult entHit = world.rayTraceEntities(
                eye, dir, maxDist, FLINTLOCK_RAY_SIZE,
                e -> e instanceof LivingEntity && !e.getUniqueId().equals(id));

        Location muzzle = eye.clone().add(dir.clone().multiply(0.6));
        Location end;
        LivingEntity target = null;
        if (entHit != null && entHit.getHitEntity() instanceof LivingEntity le) {
            target = le;
            end = entHit.getHitPosition().toLocation(world);
        } else {
            end = eye.clone().add(dir.clone().multiply(maxDist));
        }

        reconfigureFx(player, muzzle);
        drawTracer(world, muzzle, end);

        if (target != null) {
            // Fence the re-entrant onHit around this one damage call.
            firing.add(id);
            try {
                target.damage(FLINTLOCK_DAMAGE, player);
            } finally {
                firing.remove(id);
            }
            bulletImpactFx(world, end);
        }

        // A flintlock shot is a non-vanilla use — mild wear.
        EgoDurability.wearMainHand(player);

        renderBar(player); // reflect the fresh reload on the composed line at once
    }

    // ---- melee on-hit: wet chops + a blood-drunk frenzy (preserved) ----------------

    /**
     * Melee hit landed. Only the axe form carries the gimmick. The vanilla chop stays intact; it squelches
     * wetly, and below half HP the wielder goes blood-drunk — the chop hits harder and splashes everything
     * nearby, allies included. The flintlock ball's re-entry is fenced by {@link #firing}.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID id = attacker.getUniqueId();

        // Re-entrant call from our own flintlock ball or our own stab-bleed tick: let the vanilla damage
        // through, run no gimmick (and don't let it re-arm the combo).
        if (firing.contains(id) || ticking.contains(id)) {
            return;
        }
        // The melee gimmick belongs to the axe; the flintlock's incidental melee does nothing extra.
        if (isPistol(attacker.getInventory().getItemInMainHand())) {
            return;
        }

        wetSlash(victim);

        // Combo: only properly-spaced chops count (mash inside MIN_HIT_INTERVAL_MS is ignored). Every third
        // counted strike is a lunging stab that dashes into the target and opens a bleed, then the count resets.
        long now = System.currentTimeMillis();
        Long lastCounted = lastCountedHit.get(id);
        if (lastCounted == null || now - lastCounted >= MIN_HIT_INTERVAL_MS) {
            lastCountedHit.put(id, now);
            int n = strikeCount.getOrDefault(id, 0) + 1;
            if (n >= LUNGE_STRIKE) {
                strikeCount.put(id, 0);
                lungingStab(attacker, victim);
            } else {
                strikeCount.put(id, n);
            }
        }

        if (isBloodDrunk(attacker)) {
            event.setDamage(event.getDamage() * BLOOD_DRUNK_MULT);
            bloodDrunkBurst(attacker);
            splash(attacker, victim);
        }
    }

    /** True while the wielder is below the blood-drunk HP threshold. */
    private boolean isBloodDrunk(Player attacker) {
        AttributeInstance maxAttr = attacker.getAttribute(Attribute.MAX_HEALTH);
        double max = maxAttr != null ? maxAttr.getValue() : 20.0;
        if (max <= 0.0) return false;
        return attacker.getHealth() < max * BLOOD_DRUNK_FRAC;
    }

    /**
     * The blood-drunk splash: sweep once for living bodies near the wielder, cap the count, and cut and
     * knock every one of them — allies and other players included. No ally check; that is the drawback.
     */
    private void splash(Player attacker, LivingEntity struck) {
        int hit = 0;
        for (var entity : attacker.getNearbyEntities(SPLASH_RADIUS, SPLASH_RADIUS, SPLASH_RADIUS)) {
            if (hit >= SPLASH_CAP) break;
            if (entity == attacker || entity == struck) continue;   // the wielder & the main target excepted
            if (!(entity instanceof LivingEntity other)) continue;

            Vector away = other.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1.0e-6) {
                away = attacker.getEyeLocation().getDirection().setY(0);
            }
            if (away.lengthSquared() < 1.0e-6) {
                away = new Vector(1, 0, 0);
            }
            away.normalize().multiply(SPLASH_KNOCKBACK).setY(SPLASH_KNOCKBACK_UP);

            // The spray cuts, and it did not used to. This method only ever shoved, while its own javadoc
            // and the class docs both said it "cut every one of them" — the wound the text promised was
            // never in the code. On a weapon called Blood-drunk, carried by Little Red Riding Hooded
            // Mercenary, a spray that politely creates space is the story backwards: Red wades in.
            //
            // The shove stays, because being struck and reeling is the drama. Each body is cut once; the
            // wielder's own chop victim is skipped above, so nothing here is hit twice in a swing and no
            // i-frames need clearing.
            other.damage(SPLASH_DAMAGE, attacker);
            other.setVelocity(other.getVelocity().add(away));

            Location at = other.getLocation().add(0, 1.0, 0);
            other.getWorld().spawnParticle(Particle.DUST, at, 6, 0.25, 0.3, 0.25, 0, BLOOD_DUST);
            other.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0.0, 0.0, 0.0, 0);
            hit++;
        }
    }

    // ---- 3rd-strike lunging stab + bleed -------------------------------------------

    /**
     * The 3rd counted strike: a short forward dash into the target and a stab that opens a bleed. The dash
     * is a pure impulse on the wielder; the stab's damage is the bleed DoT (short, no knockback). The bleed's
     * {@code victim.damage} re-enters {@link #onHit} — the {@link #ticking} fence makes that a no-op.
     */
    private void lungingStab(Player attacker, LivingEntity victim) {
        // Lunge toward the target (fall back to eye-line, then a default, if they're stacked on us).
        Vector into = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (into.lengthSquared() < 1.0e-6) into = attacker.getEyeLocation().getDirection().setY(0);
        if (into.lengthSquared() < 1.0e-6) into = new Vector(1, 0, 0);
        into.normalize().multiply(LUNGE_SPEED).setY(LUNGE_UP);
        attacker.setVelocity(attacker.getVelocity().add(into));

        stabFx(attacker, victim);
        startBleed(attacker, victim);

        // A lunging stab is a non-vanilla flourish — wear the main-hand item a mild point.
        EgoDurability.wearMainHand(attacker);
    }

    /** Cancel any live stab-bleed on the victim and seed a fresh one — refresh, not stack. */
    private void startBleed(Player wielder, LivingEntity victim) {
        UUID vid = victim.getUniqueId();
        BukkitTask old = bleeds.remove(vid);
        if (old != null) old.cancel();
        BukkitTask task = new BleedTask(wielder.getUniqueId(), victim)
                .runTaskTimer(plugin, STAB_BLEED_PERIOD_TICKS, STAB_BLEED_PERIOD_TICKS);
        bleeds.put(vid, task);
    }

    /**
     * The stab wound. A small damage tick attributed to the wielder (while online), a fixed number of times,
     * trailing blood each tick. The tick's own knockback is undone so the bleed is a pure DoT, and its
     * {@code victim.damage} re-entry into {@link #onHit} is fenced by {@link #ticking}.
     */
    private final class BleedTask extends BukkitRunnable {
        private final UUID wielderId;
        private final LivingEntity victim;
        private int ticksLeft = STAB_BLEED_TICKS;

        private BleedTask(UUID wielderId, LivingEntity victim) {
            this.wielderId = wielderId;
            this.victim = victim;
        }

        @Override
        public void run() {
            if (ticksLeft <= 0 || victim.isDead() || !victim.isValid()) {
                finish();
                return;
            }
            ticksLeft--;

            Player wielder = plugin.getServer().getPlayer(wielderId);
            Vector preVel = victim.getVelocity();

            if (wielder != null) ticking.add(wielderId); // fence: this damage re-enters onHit
            try {
                if (wielder != null && !wielder.equals(victim)) {
                    victim.damage(STAB_BLEED_DAMAGE, wielder);
                } else {
                    victim.damage(STAB_BLEED_DAMAGE);
                }
            } finally {
                if (wielder != null) ticking.remove(wielderId);
                victim.setVelocity(preVel); // pure DoT — undo the bleed tick's knockback
            }

            bleedTrail(victim);
            if (ticksLeft <= 0) finish();
        }

        private void finish() {
            cancel();
            bleeds.remove(victim.getUniqueId());
        }
    }

    // ---- presentation --------------------------------------------------------------

    /** A wet, squelchy chop — a spray of blood at the wound. */
    private void wetSlash(LivingEntity victim) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = victim.getWorld();
        Location impact = victim.getLocation().add(0, 1.0, 0);

        world.spawnParticle(Particle.DUST, impact, 10, 0.3, 0.3, 0.3, 0, BLOOD_DUST);
        world.spawnParticle(Particle.CRIT, impact, 3, 0.25, 0.25, 0.25, 0.05);

        float pitch = 0.85f + rng.nextFloat() * 0.25f;
        world.playSound(impact, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, pitch);       // meaty hit
        world.playSound(impact, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.7f, 0.6f + rng.nextFloat() * 0.2f); // wet squelch
    }

    /** The lunging stab lands: a driven crimson thrust — a spray of blood, a sweep arc, and a meaty run-through. */
    private void stabFx(Player attacker, LivingEntity victim) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = victim.getWorld();
        Location impact = victim.getLocation().add(0, 1.0, 0);

        world.spawnParticle(Particle.DUST, impact, 14, 0.25, 0.35, 0.25, 0.0, BLOOD_DUST);
        world.spawnParticle(Particle.SWEEP_ATTACK, impact, 1, 0.0, 0.0, 0.0, 0);
        world.spawnParticle(Particle.CRIT, impact, 6, 0.2, 0.2, 0.2, 0.2);

        world.playSound(impact, Sound.ITEM_TRIDENT_RIPTIDE_2, 0.7f, 1.3f + rng.nextFloat() * 0.15f); // the dash
        world.playSound(impact, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.8f);                        // the run-through
        world.playSound(impact, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.7f, 0.5f + rng.nextFloat() * 0.15f); // wet stab
    }

    /** A thin trail of red mist off the bleeding stab wound — low count, short-lived. */
    private void bleedTrail(LivingEntity victim) {
        victim.getWorld().spawnParticle(Particle.DUST, victim.getLocation().add(0, 1.0, 0),
                4, 0.18, 0.30, 0.18, 0.0, BLEED_DUST);
    }

    /** The snap-to-pistol reconfigure / flintlock crack: a steel flash, a muzzle burst, and a heavy report. */
    private void reconfigureFx(Player attacker, Location muzzle) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = attacker.getWorld();
        float jitter = 1.0f + rng.nextFloat() * 0.2f;

        // The metallic snap of the trick weapon reconfiguring.
        world.playSound(muzzle, Sound.BLOCK_PISTON_EXTEND, 0.6f, 1.6f + rng.nextFloat() * 0.2f);
        world.playSound(muzzle, Sound.ITEM_TRIDENT_RETURN, 0.4f, 1.8f);
        // A heavy flintlock report.
        world.playSound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.1f * jitter);
        world.playSound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.45f, 1.5f * jitter);

        // Muzzle flash — fire + smoke + spark. (No Particle.FLASH: it requires a Color in 26.1.2 and
        // throws "missing required data class org.bukkit.Color" when spawned without one.)
        world.spawnParticle(Particle.FLAME, muzzle, 4, 0.05, 0.05, 0.05, 0.02);
        world.spawnParticle(Particle.SMOKE, muzzle, 5, 0.06, 0.06, 0.06, 0.01);
        world.spawnParticle(Particle.CRIT, muzzle, 3, 0.05, 0.05, 0.05, 0.1);
    }

    /** A short crimson tracer along the ball's flight — low count, drawn once. */
    private void drawTracer(World world, Location from, Location to) {
        Vector step = to.toVector().subtract(from.toVector());
        double length = step.length();
        if (length < 1.0e-4) return;
        step.multiply(1.0 / length);

        double spacing = 0.6;
        for (double d = 0.0; d < length; d += spacing) {
            Location p = from.clone().add(step.clone().multiply(d));
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, TRACER_DUST);
        }
    }

    /** A burst of blood where the ball lands. */
    private void bulletImpactFx(World world, Location at) {
        world.spawnParticle(Particle.DUST, at, 8, 0.15, 0.15, 0.15, 0, BLOOD_DUST);
        world.spawnParticle(Particle.CRIT, at, 4, 0.12, 0.12, 0.12, 0.1);
        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.6f, 1.5f);
    }

    /** The red aura + a low heartbeat/growl when a blood-drunk chop lands below half HP. */
    private void bloodDrunkBurst(Player attacker) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = attacker.getWorld();
        Location around = attacker.getLocation().add(0, 1.0, 0);

        // A ring of red aura around the wielder.
        world.spawnParticle(Particle.DUST, around, 16, 0.5, 0.7, 0.5, 0, AURA_DUST);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, around, 8, 0.5, 0.6, 0.5, 0,
                new Particle.DustTransition(RED_BRIGHT, RED_DEEP, 1.3f));

        // A heavy heartbeat / growl edge under the frenzy — kept low so a sustained blood-drunk fight
        // stays an undertone, not a wall of sound every swing (playtest §1.3: "tone down the sound fx a bunch").
        world.playSound(attacker.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.3f, 0.6f + rng.nextFloat() * 0.1f);
        world.playSound(attacker.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.15f, 1.4f + rng.nextFloat() * 0.2f);
    }

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        // Wielder state.
        firing.remove(id);
        lastFire.remove(id);
        strikeCount.remove(id);
        lastCountedHit.remove(id);
        ticking.remove(id);

        // If the quitter was a bleeding victim, end their wound.
        BukkitTask task = bleeds.remove(id);
        if (task != null) task.cancel();
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : bleeds.values()) task.cancel();
        bleeds.clear();
        firing.clear();
        lastFire.clear();
        strikeCount.clear();
        lastCountedHit.clear();
        ticking.clear();
    }

    // ---- lore ----------------------------------------------------------------------

    // Crimson Scar is the roster's one two-form weapon, so it builds two tooltips off a single body: same
    // Abnormality, same flavour, same moveset, and only the display name differs — the flintlock still says
    // so on the tin, exactly as it always has. Both forms carry the WHOLE moveset on purpose: the wielder
    // reading the flintlock's tooltip is precisely the one who needs to know steel is a shift-right-click
    // away, and vice versa. Every ability line therefore names the form it belongs to.

    /** The flavour block, carried over word for word from the weapon's original tooltip. */
    private static final List<String> DESC = List.of(
            "With steel in one hand and",
            "gunpowder in the other, there's",
            "nothing to fear in this place."
    );

    /**
     * The moveset, read off the code rather than the old tooltip — which listed three terse controls and
     * left the blood-drunk frenzy off the item entirely.
     */
    private static final List<EgoLore.Ability> MOVES = List.of(
            new EgoLore.Ability("[Passive] Blood-Drunk",
                    "Below half health, steel-form chops",
                    "deal 1.5x damage and throw a spray that",
                    "cuts everything nearby for 2 and shoves",
                    "it back — allies and other players",
                    "included."),
            new EgoLore.Ability("[Left Click] Lunging Stab",
                    "Every 3rd steel-form chop dashes you",
                    "into the target and opens a bleed —",
                    "1.5 damage every 0.5s, three times.",
                    "Chops landed too fast don't count."),
            new EgoLore.Ability("[Right Click] Flintlock Shot",
                    "In flintlock form, fires one ball down",
                    "your eye line — 9 damage, 30 blocks.",
                    "Then a 4 second reload locks the",
                    "trigger. Does nothing in steel form."),
            new EgoLore.Ability("[Shift + Right-click] Form Swap",
                    "Swaps between the steel sickle and the",
                    "flintlock. Enchantments and wear carry",
                    "across both forms.")
    );

    /** The melee (steel) form's tooltip — the item {@link #createItem()} hands out. */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Crimson Scar",
            "Little Red Riding Hooded Mercenary",
            NAME,
            BLOOD,
            DESC,
            MOVES);

    /** The flintlock form's tooltip: the same lore under the name the pistol form has always carried. */
    private static final EgoLore.Tooltip PISTOL_TOOLTIP = EgoLore.egoLore(
            "Crimson Scar — Flintlock",
            "Little Red Riding Hooded Mercenary",
            NAME,
            BLOOD,
            DESC,
            MOVES);
}
