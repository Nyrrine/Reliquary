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
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Solemn Lament — "The Funeral of the Dead Butterflies" (Lobotomy Corp E.G.O Equipment, WAW).
 *
 * <p>A matched pair of akimbo handguns — one grief for the dead, one lament for the living — that never
 * truly run dry. Both pistols live in the ONE main-hand model, so it is a single item with nothing ever
 * placed in the off-hand. Each pull barks ONE barrel, ALTERNATING side to side — left, right, left,
 * right — so the muzzle flash rocks between the twin barrels as they click. Every shot looses a short
 * shotgun cone of pellets and streams a flock of pale, fluttering butterflies OUT along the flight path,
 * scattering like ash down-range and at impact rather than bunching at the muzzle.
 *
 * <ul>
 *   <li><b>Left-click (swing)</b> — fire one pistol, alternating sides each click. The firing gun throws
 *       {@value #PELLETS_PER_GUN} hitscan pellets in a spread cone (range {@value #RANGE}). Each pellet
 *       that finds a living body deals {@value #PELLET_DAMAGE} with <b>zero knockback</b> — the victim's
 *       velocity is captured and restored around the hit. Each shot spends one from that gun's magazine.</li>
 *   <li><b>Right-click</b> — fast mag dump: unloads both pistols as fast as the trigger cycles, one shot
 *       per tick, alternating side to side until BOTH magazines run dry.</li>
 *   <li><b>Magazines</b> — each pistol holds {@value #MAG} shots, drained independently as you alternate.
 *       When BOTH run dry the guns AUTO-reload so it is never a dead gun — reload is automatic only.
 *       It never runs out for good — it only reloads.</li>
 * </ul>
 *
 * <p>State is a single UUID-&gt;magazine map cleared on quit. Nothing is ever placed in the off-hand: the
 * gun fires regardless of what the wielder holds off-hand. No world edits from firing: each volley is a
 * handful of raytraces plus short, capped particle bursts. The world entities it spawns are short-lived
 * flying images down the aim — small butterfly "fly" images for the base weapon, the big "True…" photos
 * for the admin variant — each non-persistent, tracked, and swept on quit/disable.
 */
public final class SolemnLamentWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;
    private final NamespacedKey key;      // marks the item as Solemn Lament
    private final NamespacedKey trueKey;  // marks the meme "True Solemn Lament" admin variant

    /** Wielder -> their twin magazines + reload timer. The only per-player state this weapon keeps. */
    private final Map<UUID, Mag> mags = new HashMap<>();

    /**
     * Bodies currently taking one of our own pellets. The manager dispatches {@link #onHit} for any hit
     * whose damager is a player holding this weapon, which every pellet is — so this fence is what tells a
     * shot apart from a swing. Held only across the single {@code damage()} call, in a try/finally, so it
     * is empty between shots and can never accumulate a dead mob's id.
     */
    private final Set<UUID> shooting = new HashSet<>();

    /** Live "True…" meme image displays, tracked so none can ever leak. Removed on expiry/quit/disable. */
    private final Set<ItemDisplay> memeDisplays = new HashSet<>();

    /** Scoreboard tag on every meme image display, for the belt-and-braces world sweep on disable. */
    private static final String TRUE_MEME_TAG = "solemn_true_meme";
    private static final int    MEME_LIFE_TICKS = 20;    // lifetime cap: each flying "True…" photo lives ~1s
    private static final int    MEME_COUNT      = 3;     // "True…" photos launched, fanned out, per shot
    private static final double MEME_SPEED      = 0.7;   // blocks/tick each photo flies down the aim line
    private static final double MEME_SPREAD     = 0.12;  // small angular fan on each photo's launch direction
    private static final double MEME_JITTER     = 0.35;  // small muzzle-position scatter per photo
    private static final float  MEME_SCALE      = 1.25f; // half the old 2.5 — a small flying card
    // The two flying-image variants share the same fly runnable / tag / tracking / cleanup; they differ
    // only in which pack-mapped texture they carry and how big they render. The base Solemn Lament throws
    // the little "fly" butterfly image; the True admin variant throws the big "True…" photo.
    private static final String TRUE_CMD        = "ego/true_solemn"; // pack CMD → flat "True…" photo model
    private static final String FLY_CMD         = "ego/fly";         // pack CMD → small butterfly "fly" image
    private static final int    FLY_COUNT       = 2;     // little fly images loosed per base-weapon shot
    private static final float  FLY_SCALE       = 0.27f; // ~3x smaller — a butterfly about the size of a particle

    // Tuning — a twin-pistol shotgun dirge that never truly empties.
    private static final int    MAG             = 12;    // shots per pistol
    private static final int    PELLETS_PER_GUN = 2;     // shotgun pellets each gun throws per trigger
    private static final double RANGE           = 16.0;  // "not too far" — a close-range reach
    private static final double PELLET_DAMAGE   = 0.55;  // per pellet at a near-empty mag; scaled down while full (see pelletDamage). Halved from 1.1 — the mag dump still hit like a truck.
    // A full mag dump was landing ~70 on a golem. The gun is now weakest with a full magazine and only bites
    // near its full pellet value as it empties, so a fresh two-mag dump is much softer than the tail of one.
    // Damage per pellet = PELLET_DAMAGE * (MIN_MAG_FACTOR .. 1.0), interpolated by how EMPTY the magazine is.
    private static final double MIN_MAG_FACTOR  = 0.40;  // a brimming pair of pistols hits at 40% of the pellet value
    private static final double CONE            = 0.10;  // shotgun spread (radians-ish scatter) — tightened so more pellets connect
    private static final double RAY_SIZE        = 0.5;   // entity ray fatness (forgiving aim)
    private static final double HAND_OFFSET     = 0.32;  // how far the muzzles sit off the aim line
    // Trigger cadence: one shot per ~100ms. The steady stream is driven by onTick (every 2 ticks ≈ 100ms)
    // while the hold window is live, so this gate lands ~8–10 shots/sec — a smooth mag-dump, not a same-tick
    // double. The first shot on a physical click still fires immediately from onSwing (subject to this gate).
    private static final long   FIRE_CADENCE_MS = 100L;  // steady onTick-driven stream cadence
    // Hold-to-spray window: each ARM_SWING re-arms this many ms of sustained fire, driven from onTick.
    // Bukkit LIMITATION: holding left-click in AIR does not reliably repeat ARM_SWING, so onSwing alone
    // can only fire once per physical click — the window lets onTick keep firing and trail off ~300ms
    // after the last swing, giving a real hold-to-mag-dump without depending on swing repeats.
    private static final long   HOLD_WINDOW_MS  = 300L;  // spray sustains ~300ms past the last swing
    private static final long   RELOAD_MS       = 1500L; // automatic dry reload (both mags empty)

    // Mourner's Stride (ego-enchant) — a NON-damage handling perk. Each shot refreshes a brief Speed I so the
    // wielder keeps pace while spraying/dumping; levels only make that momentum LINGER longer past the last
    // shot, never a faster tier. Touches movement alone — no mag, pellet, spread, range, cadence or damage.
    private static final int    STRIDE_MAX_LVL     = 3;
    private static final int    STRIDE_BASE_TICKS  = 12; // ~0.6s of Speed I refreshed on each shot
    private static final int    STRIDE_LEVEL_TICKS = 8;  // +0.4s of lingering momentum per level

    // Palette — funereal white and black. The body text and the epithet used to have their own entries
    // here (bone white, and a dimmer grey); EgoLore owns both of those colours now, so they are gone.
    private static final TextColor NAME  = TextColor.color(0xEDEDF2); // pallbearer white (name)
    private static final TextColor CREPE = TextColor.color(0x1C1C22); // funeral black accent
    private static final TextColor FAINT = TextColor.color(0x74747E); // conditions / controls
    private static final TextColor FRAME = NamedTextColor.DARK_GRAY;   // brackets, matching EgoHud
    private static final TextColor COUNT = NamedTextColor.GRAY;        // ammo count text
    private static final TextColor EMPTY = NamedTextColor.RED;         // an emptied pistol goes red

    // Butterfly palette — pale whites, soft iridescent pastels, and funeral black.
    private static final Color C_WHITE  = Color.fromRGB(0xEC, 0xEC, 0xF0);
    private static final Color C_BLACK  = Color.fromRGB(0x14, 0x14, 0x18);
    private static final Color C_PINK   = Color.fromRGB(0xF2, 0xD7, 0xE4);
    private static final Color C_BLUE   = Color.fromRGB(0xD7, 0xE2, 0xF2);
    private static final Color C_VIOLET = Color.fromRGB(0xE2, 0xD7, 0xF2);
    private static final Particle.DustOptions WHITE_WING = new Particle.DustOptions(C_WHITE, 0.9f);
    private static final Particle.DustOptions BLACK_WING = new Particle.DustOptions(C_BLACK, 0.9f);
    private static final Particle.DustOptions WHITE_THIN = new Particle.DustOptions(C_WHITE, 0.6f);
    private static final Particle.DustOptions BLACK_THIN = new Particle.DustOptions(C_BLACK, 0.6f);

    // Reload spinner frames — a rotating cylinder, no milliseconds.
    private static final String[] SPIN = {"◐", "◓", "◑", "◒"};

    public SolemnLamentWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "solemn_lament");
        this.trueKey = new NamespacedKey(plugin, "solemn_lament_true");
    }

    @Override
    public String id() {
        return "solemn_lament";
    }

    /** Per-wielder twin magazines. Both drain together; when both hit zero the guns auto-reload. */
    private static final class Mag {
        int     right       = MAG;
        int     left        = MAG;
        boolean nextLeft    = true;  // which pistol fires next — flips every click for L,R,L,R…
        long    lastFire    = 0L;
        long    sprayUntil  = 0L;    // hold-to-spray: onTick keeps firing while now <= this (re-armed each swing)
        long    reloadStart = 0L;    // 0 = not reloading
        boolean dumping     = false; // a right-click fast mag-dump runnable is currently active

        boolean reloading() { return reloadStart != 0L; }
    }

    // ---- fire ---------------------------------------------------------------------

    /**
     * Pistols are not for pistol-whipping. Left-click is the trigger, so firing at a body close enough to
     * touch would otherwise land a vanilla blow as well — and the blow, arriving first, stamps
     * hurt-immunity that swallows the pellets. Mourners at close quarters found the gun simply stopped
     * working. Cancelling costs nothing: Solemn Lament is a {@code ranged} model with no melee damage of
     * its own.
     *
     * <p><b>The fence is not optional.</b> The manager dispatches this for <em>any</em> hit whose damager is
     * a player holding this weapon, and every pellet is exactly that — so our own shot arrives back here.
     * Without {@link #shooting} the cancel would eat the pellets it exists to protect, and the pistols would
     * deal nothing at all.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (shooting.contains(victim.getUniqueId())) return; // our own pellet, not a swing
        event.setCancelled(true);
    }

    @Override
    public void onSwing(Player player) {
        // LEFT-click (arm swing) FIRES. Driven ONLY by the main-hand Solemn Lament — nothing else.
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!matches(main)) return;

        UUID id = player.getUniqueId();
        Mag mag = mags.computeIfAbsent(id, k -> new Mag());

        // Mid-reload: firing is disabled, the trigger just clicks empty.
        if (mag.reloading()) {
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.35f, 1.6f);
            return;
        }

        // Arm the hold-to-spray window on EVERY swing (even a cadence-suppressed one). Bukkit does not
        // reliably repeat ARM_SWING while left-click is HELD in air, so onSwing cannot sustain fire by
        // itself — instead each swing re-arms this window and onTick keeps firing until it lapses.
        long now = System.currentTimeMillis();
        mag.sprayUntil = now + HOLD_WINDOW_MS;

        // Responsive first shot: fire immediately if the cadence gate allows (de-dupes same-tick doubles).
        if (fireOnce(player, mag, now)) {
            renderBar(player, mag, 0);
        }
    }

    /**
     * Fire exactly one shot IF the {@link #FIRE_CADENCE_MS} gate allows. Shared by the responsive onSwing
     * click and the onTick hold-to-spray continuation. Returns true if a shot actually fired.
     */
    private boolean fireOnce(Player player, Mag mag, long now) {
        if (now - mag.lastFire < FIRE_CADENCE_MS) return false;
        mag.lastFire = now;
        loose(player, mag);
        return true;
    }

    /**
     * Loose exactly one alternating shot, no cadence gate: pick the side (skipping a spent one), throw that
     * pistol's cone/flock, drain its mag, flip sides, wear, and auto-reload if both are now dry. Used by the
     * gated {@link #fireOnce} and by the ungated right-click fast mag-dump.
     */
    private void loose(Player player, Mag mag) {
        // Alternating trigger: each shot fires exactly ONE pistol, flipping sides every pull.
        boolean left = mag.nextLeft;
        if (left && mag.left <= 0 && mag.right > 0) left = false;        // don't dry-fire a spent side
        else if (!left && mag.right <= 0 && mag.left > 0) left = true;

        fireShot(player, left);                      // one pistol, its cone, its flock, its ding
        if (left) mag.left  = Math.max(0, mag.left - 1);
        else      mag.right = Math.max(0, mag.right - 1);
        mag.nextLeft = !left;                        // alternate for the next shot
        EgoDurability.wearMainHand(player);          // mild — one point per shot
        applyMournersStride(player);                 // keep pace while firing (ego-enchant, movement only)

        // Safety fallback: if both mags are now empty, auto-reload so it is never a dead gun.
        if (mag.right <= 0 && mag.left <= 0) beginReload(player, mag);
    }

    @Override
    public void onInteract(Player player, boolean sneaking) {
        // RIGHT-click = EXTREMELY FAST MAG DUMP. Kick off a short self-cancelling runnable that looses one
        // alternating shot EVERY tick, draining BOTH pistols to empty as fast as the trigger can cycle.
        // When both hit zero the auto-reload fallback fires; reload is now automatic-only, never manual.
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!matches(main)) return;

        UUID id = player.getUniqueId();
        Mag mag = mags.computeIfAbsent(id, k -> new Mag());

        // Guards: never stack a second dump, and never dump while reloading or already empty.
        if (mag.dumping || mag.reloading()) return;
        if (mag.left <= 0 && mag.right <= 0) return;             // nothing to dump — auto-reload handles it

        beginDump(id, mag);
    }

    /**
     * The right-click fast mag-dump: a self-cancelling {@link BukkitRunnable} that looses one alternating
     * shot every tick (~20/sec) with NO cadence gate, blowing through both full mags in well under a second.
     * It ends the moment both pistols are empty (whereupon {@link #loose} has already armed the auto-reload),
     * the wielder switches off the weapon, or they log off. The {@code dumping} flag stops a second dump from
     * stacking on top of this one.
     */
    private void beginDump(UUID id, Mag mag) {
        mag.dumping = true;
        new BukkitRunnable() {
            @Override
            public void run() {
                Player p = plugin.getServer().getPlayer(id);
                // Stop if the wielder is gone, switched off the guns, or a reload took over mid-dump.
                if (p == null || !matches(p.getInventory().getItemInMainHand()) || mag.reloading()) {
                    mag.dumping = false;
                    cancel();
                    return;
                }
                loose(p, mag);
                renderBar(p, mag, 0);
                // Both empty → the dump is spent; loose() has already triggered the auto-reload.
                if (mag.left <= 0 && mag.right <= 0) {
                    mag.dumping = false;
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** A single shot: one pistol fires its shotgun cone, streams a flock down-range, and dings. */
    /**
     * The per-pellet damage for the shot being fired now: weakest with a full pair of magazines, climbing to
     * the full {@link #PELLET_DAMAGE} as they empty. So a mag dump opens soft and only sharpens at its tail,
     * and — since the mags auto-reload to full — sustained fire never sits at full damage.
     */
    private double pelletDamage(Player player) {
        Mag mag = mags.get(player.getUniqueId());
        int total = mag == null ? 0 : mag.right + mag.left;
        double emptiness = 1.0 - Math.min(1.0, total / (double) (2 * MAG)); // 0 = brimming, 1 = dry
        return PELLET_DAMAGE * (MIN_MAG_FACTOR + (1.0 - MIN_MAG_FACTOR) * emptiness);
    }

    private void fireShot(Player player, boolean left) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector rightV = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (rightV.lengthSquared() < 1e-6) rightV = new Vector(1, 0, 0); // straight up/down
        rightV.normalize();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        fireGun(player, world, eye, dir, rightV, rng, left, pelletDamage(player));
        shotDing(world, eye, rng);

        // Every shot ALSO throws flying billboarded images down the aim line. Which images depends on the
        // main-hand variant: the True admin variant belches the big "True…" photos; the base Solemn Lament
        // throws a couple of smaller butterfly "fly" images instead. Same fly/tag/tracking/cleanup for both.
        if (isTrueVariant(player.getInventory().getItemInMainHand())) {
            spawnFlyingImages(player, eye, dir, TRUE_CMD, MEME_SCALE, MEME_COUNT);
        } else {
            spawnFlyingImages(player, eye, dir, FLY_CMD, FLY_SCALE, FLY_COUNT);
        }
    }

    /**
     * Launch a small burst of billboarded flying images OUT from the muzzle down the shot's aim line — flat
     * images that always face the viewer as they travel, like slow visual projectiles. {@code count} are
     * loosed per shot, each fanned out a little (a slight angular spread on the launch direction plus a small
     * muzzle-position jitter). Each is a PAPER display item mapped by the pack ({@code cmd}) to the flat
     * model, scaled to {@code scale}, full-bright, non-persistent, tagged {@link #TRUE_MEME_TAG}, and tracked
     * in {@link #memeDisplays}. A per-image self-cancelling runnable teleports it forward {@value #MEME_SPEED}
     * blocks/tick and removes it at the {@value #MEME_LIFE_TICKS}-tick lifetime cap — ~14 blocks at this
     * speed, always short of the {@link #RANGE}-block cap, which stands as an unreachable backstop —
     * untracking it on removal so the set can never grow unbounded, and it can never leak on quit/disable.
     *
     * <p>General over BOTH image types: the base Solemn Lament calls this with the small {@link #FLY_CMD}
     * butterfly image, the True admin variant with the big {@link #TRUE_CMD} "True…" photo. The only per-type
     * differences are {@code cmd}, {@code scale}, and {@code count}; the fly/tag/tracking/cleanup are shared.
     *
     * <p>The image renders via the client-side resource pack (PAPER + a custom-model-data mapping). The pack
     * on this server is installed client-side, not server-sent, so the server can't detect who has it and the
     * image is shown to everyone; a per-player "invisible for pack-less" hide is only possible with a
     * server-sent pack.
     */
    private void spawnFlyingImages(Player shooter, Location muzzle, Vector dir, String cmd, float scale, int count) {
        World world = shooter.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = muzzle.clone().add(dir.clone().multiply(1.6)); // a bit forward of the eye, at head height

        for (int i = 0; i < count; i++) {
            // Alternate the base between black and white wool so the pack-less fallback reads as a funereal
            // black-and-white swarm; the pack maps either wool + this CMD to the flat image.
            final ItemStack image = flyingImageItem(i % 2 == 0 ? Material.BLACK_WOOL : Material.WHITE_WOOL, cmd);
            // Fan this image out: perturb the aim a touch and jitter its launch point a touch, so the burst
            // sprays rather than stacks on one line.
            Vector aim = dir.clone().add(new Vector(
                    rng.nextDouble(-MEME_SPREAD, MEME_SPREAD),
                    rng.nextDouble(-MEME_SPREAD, MEME_SPREAD),
                    rng.nextDouble(-MEME_SPREAD, MEME_SPREAD))).normalize();
            Location at = base.clone().add(
                    rng.nextDouble(-MEME_JITTER, MEME_JITTER),
                    rng.nextDouble(-MEME_JITTER, MEME_JITTER),
                    rng.nextDouble(-MEME_JITTER, MEME_JITTER));

            ItemDisplay disp = world.spawn(at, ItemDisplay.class, d -> {
                d.setItemStack(image);
                d.setBillboard(Display.Billboard.CENTER);           // always faces the viewer — reads as a flat image
                d.setBrightness(new Display.Brightness(15, 15));    // full-bright, ignores world light
                d.setTransformation(new Transformation(
                        new Vector3f(), new Quaternionf(),
                        new Vector3f(scale, scale, scale),          // per-type size (fly 0.27, True 1.25)
                        new Quaternionf()));
                d.setPersistent(false);                             // a crash can never leave it on disk
                d.addScoreboardTag(TRUE_MEME_TAG);
            });
            memeDisplays.add(disp);

            // NOTE: no per-player hide. The resource pack here is installed CLIENT-SIDE (not server-sent),
            // so the server never receives a PlayerResourcePackStatusEvent and can't tell who has it — every
            // player is assumed to have the pack (they do on this server), and the image always renders. A
            // true "invisible for pack-less" fallback is only possible if the pack is server-sent.

            // Fly it forward each tick like a slow visual projectile; self-cancels — and UNTRACKS itself —
            // once it has flown its range or hit the lifetime cap, whichever comes first.
            final Vector stepVec = aim.multiply(MEME_SPEED);
            new BukkitRunnable() {
                int    age       = 0;
                double travelled = 0.0;

                @Override
                public void run() {
                    if (!disp.isValid() || age++ >= MEME_LIFE_TICKS || travelled >= RANGE) {
                        disp.remove();
                        memeDisplays.remove(disp);
                        cancel();
                        return;
                    }
                    disp.teleport(disp.getLocation().add(stepVec));
                    travelled += MEME_SPEED;
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }
    }

    /** A flying-image display item: a black/white WOOL stack whose CMD the pack maps to the flat image model.
     *  Pack-less clients see the vanilla black/white wool cube — a themed swarm fallback, not a paper card. */
    private ItemStack flyingImageItem(Material mat, String cmd) {
        ItemStack image = new ItemStack(mat);
        ItemMeta meta = image.getItemMeta();
        var cmdComp = meta.getCustomModelDataComponent();
        cmdComp.setStrings(List.of(cmd));
        meta.setCustomModelDataComponent(cmdComp);
        image.setItemMeta(meta);
        return image;
    }

    /**
     * One pistol's shot: a short-range hitscan shotgun cone — instant pellets, each drawing its own tracer
     * line with the odd butterfly bit lingering beside it. Nothing bursts in the wielder's face.
     */
    private void fireGun(Player player, World world, Location eye, Vector dir, Vector rightV,
                         ThreadLocalRandom rng, boolean left, double damage) {
        boolean black = left;                              // left barrel = lament for the living = dark wings
        double side = left ? -HAND_OFFSET : HAND_OFFSET;   // left barrel flashes left, right barrel flashes right
        Location muzzle = eye.clone()
                .add(dir.clone().multiply(0.7))
                .add(rightV.clone().multiply(side))
                .add(0, -0.15, 0);

        for (int i = 0; i < PELLETS_PER_GUN; i++) {
            resolvePellet(player, world, eye, coneDir(rng, dir), muzzle, black, RANGE, damage);
        }
    }

    /** A random direction inside the shotgun cone around {@code base}. */
    private Vector coneDir(ThreadLocalRandom rng, Vector base) {
        return base.clone().add(new Vector(
                rng.nextDouble(-CONE, CONE),
                rng.nextDouble(-CONE, CONE),
                rng.nextDouble(-CONE, CONE))).normalize();
    }

    /**
     * One hitscan pellet: clip at the first wall, then hit the first living body — dealing a small share
     * of damage with NO knockback (velocity captured and restored). Draws a faint tracer either way.
     */
    private void resolvePellet(Player player, World world, Location eye, Vector dir, Location muzzle,
                               boolean black, double range, double damage) {
        double maxDist = range;
        // Ignore passable blocks (tall/short grass, flowers, fluids) so only real walls clip the shot —
        // otherwise a shot that grazes a grass tuft dies on the grass. Same fix as Beak/Crimson.
        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        if (blockHit != null && blockHit.getHitPosition() != null) {
            maxDist = eye.toVector().distance(blockHit.getHitPosition());
        }

        RayTraceResult entHit = world.rayTraceEntities(
                eye, dir, maxDist, RAY_SIZE,
                e -> e instanceof LivingEntity && !e.getUniqueId().equals(player.getUniqueId()));

        Location end;
        if (entHit != null && entHit.getHitEntity() instanceof LivingEntity le) {
            end = entHit.getHitPosition().toLocation(world);
            Vector velocity = le.getVelocity();
            // A shot is three pellets arriving together, and vanilla only lets a body be hurt once every
            // ten ticks — so without this the first pellet stamped hurt-immunity and the other two were
            // swallowed. A point-blank shot landed 3.8 of its 11.4 and had done since the day it was
            // written; the pellets were never weak, they were never arriving.
            le.setNoDamageTicks(0);
            shooting.add(le.getUniqueId());
            try {
                le.damage(damage, player);
            } finally {
                shooting.remove(le.getUniqueId());
            }
            le.setVelocity(velocity);              // no knockback — the shot never moves the target
            impactFx(world, end, black);
        } else {
            end = eye.clone().add(dir.clone().multiply(maxDist));
        }
        drawTracer(world, muzzle, end, black);
    }

    /**
     * Mourner's Stride (ego-enchant): a handling perk, not a damage one. Each shot refreshes a brief Speed I so
     * the wielder keeps pace while spraying and mag-dumping; higher levels only make that momentum LINGER longer
     * past the last shot, never a faster tier. Strictly non-damage — it touches movement alone, so it cannot lift
     * this weapon's already-tuned effective damage.
     */
    private void applyMournersStride(Player player) {
        int lvl = Math.min(EgoEnchants.level(player.getInventory().getItemInMainHand(), "mourners_stride"),
                STRIDE_MAX_LVL);
        if (lvl <= 0) return;
        int ticks = STRIDE_BASE_TICKS + STRIDE_LEVEL_TICKS * lvl;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 0, false, false, true)); // Speed I only
    }

    // ---- reload --------------------------------------------------------------------

    /** Begin the auto-reload: a minimal cylinder-spin sound cue — no screen-clogging swirl at the feet. */
    private void beginReload(Player player, Mag mag) {
        if (mag.reloading()) return;
        mag.reloadStart = System.currentTimeMillis();

        World world = player.getWorld();
        Location feet = player.getLocation().add(0, 0.1, 0);
        world.playSound(feet, Sound.ITEM_CROSSBOW_LOADING_START, 0.7f, 1.2f);
        world.playSound(feet, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.7f);         // a soft spin tick
    }

    /** The mags refill: a bright "ready" ding — sound only, no particle sparkle in the wielder's face. */
    private void reloadReady(Player player) {
        World world = player.getWorld();
        Location loc = player.getLocation().add(0, 1.0, 0);
        world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 2.0f);
        world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.9f);
        world.playSound(loc, Sound.ITEM_CROSSBOW_LOADING_END, 0.6f, 1.4f);
    }

    // ---- tick: reload finish + hold-to-spray + HUD ---------------------------------

    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!matches(main)) return false;   // guns left the hand — disengage, nothing to clean up

        Mag mag = mags.computeIfAbsent(id, k -> new Mag());

        // Finish an in-flight reload.
        if (mag.reloading() && System.currentTimeMillis() - mag.reloadStart >= RELOAD_MS) {
            mag.right = MAG;
            mag.left = MAG;
            mag.reloadStart = 0L;
            reloadReady(player);
        }

        // Hold-to-spray continuation. onTick runs every 2 ticks (~100ms) for active wielders; while the
        // spray window armed by onSwing is still live and firing is allowed, keep loosing shots at the
        // FIRE_CADENCE_MS rate. This is what turns a held left-click (which does NOT reliably repeat
        // ARM_SWING in air) into a sustained ~8–10 shots/sec mag-dump that trails off ~300ms after the
        // last swing. Never fires while reloading.
        if (!mag.reloading()) {
            long now = System.currentTimeMillis();
            if (now <= mag.sprayUntil) {
                fireOnce(player, mag, now);
            }
        }

        renderBar(player, mag, tick);
        return true;
    }

    /** The held-weapon action bar: a filling reload gauge, or the dual twin-magazine readout. */
    private void renderBar(Player player, Mag mag, long tick) {
        if (mag.reloading()) {
            long elapsed = System.currentTimeMillis() - mag.reloadStart;
            double frac = Math.min(1.0, (double) elapsed / RELOAD_MS);
            String spin = SPIN[(int) (Math.floorMod(tick, SPIN.length))];
            Component label = Component.text("Reloading ", NAME).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(spin, FAINT));
            player.sendActionBar(EgoHud.gauge(NAME, frac, label));
        } else {
            player.sendActionBar(dualReadout(mag));
        }
    }

    /** {@code [L] 12/12   [R] 12/12} — funereal white, the empty side flushing red. */
    private Component dualReadout(Mag mag) {
        return sidePart("L", mag.left)
                .append(plain("   ", FRAME))
                .append(sidePart("R", mag.right))
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component sidePart(String tag, int cur) {
        TextColor tagColor = cur <= 0 ? EMPTY : NAME;
        TextColor cntColor = cur <= 0 ? EMPTY : COUNT;
        return plain("[", FRAME)
                .append(plain(tag, tagColor))
                .append(plain("] ", FRAME))
                .append(plain(cur + "/" + MAG, cntColor));
    }

    private static Component plain(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    // ---- presentation --------------------------------------------------------------

    /**
     * The satisfying Lobotomy-Corp bell — no gunshot crack at all. This is the bright bell "ding" borrowed
     * from the reload-ready cue (the wielder loved it), now sounded on every shot: a clean high bell layered
     * with a soft chime, kept at a modest volume so a fast mag-dump stays a shimmering peal, not a din.
     */
    private void shotDing(World world, Location at, ThreadLocalRandom rng) {
        // Every shot now sounds IDENTICAL to the reload-ready cue: the exact same bell + chime + crossbow
        // layer at the same volumes and pitches as reloadReady(), so a shot and a finished reload ring alike.
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 2.0f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.9f);
        world.playSound(at, Sound.ITEM_CROSSBOW_LOADING_END, 0.6f, 1.4f);
    }

    /**
     * The tracer LINE — the star of the shot. A fine, twin-toned white/black spine runs the pellet's flight
     * path with an occasional ethereal shimmer node, and every so often a small butterfly bit peels off and
     * LINGERS briefly beside the line (sparse short-lived motes — not a cloud). The near-muzzle metre is left
     * bare so nothing crowds first-person.
     */
    private void drawTracer(World world, Location from, Location to, boolean black) {
        Particle.DustOptions core    = black ? BLACK_THIN : WHITE_THIN;
        Particle.DustOptions counter = black ? WHITE_THIN : BLACK_THIN; // the funereal twin tone
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Vector step = to.toVector().subtract(from.toVector());
        double length = step.length();
        if (length < 1.0e-4) return;
        step.multiply(1.0 / length);
        int idx = 0;
        for (double d = 1.0; d < length; d += 0.75, idx++) {   // start ~1 block out — keep it off the face
            Location p = from.clone().add(step.clone().multiply(d));
            // Prettier core: a fine white/black spine, twin-toned along its length…
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, (idx & 1) == 0 ? core : counter);
            // …with an occasional pale shimmer node threaded through it.
            if (idx % 5 == 0) {
                world.spawnParticle(Particle.END_ROD, p, 1, 0.0, 0.0, 0.0, 0.0);
            }
            // Sparse butterfly bits that LINGER a moment beside the line — a single fluttering wing each.
            if (rng.nextInt(9) == 0) {
                flock(world, p, black, 1);
            }
        }
    }

    /** A small mark where a pellet lands — a couple of wing-motes at the line's end, no cloud or swirl. */
    private void impactFx(World world, Location at, boolean black) {
        world.spawnParticle(Particle.DUST, at, 2, 0.08, 0.08, 0.08, 0, black ? BLACK_WING : WHITE_WING);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.3f, 2.0f);
    }

    /** Loose a small, capped set of lingering, fluttering butterflies from a point. Self-cancelling. */
    private void flock(World world, Location origin, boolean black, int count) {
        new Flock(world, origin, black, Math.min(count, 6)).runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * A cheap, self-cancelling butterfly (or small handful): each "wing" starts on the tracer line, then
     * sways, barely lofts, and drifts a little to the side before fading — a mote that lingers briefly
     * BESIDE the line rather than a burst. Each wing draws a two-dot butterfly (a flapping pair of coloured
     * motes) per tick. Capped in both count and lifetime so a volley never floods particles.
     */
    private final class Flock extends BukkitRunnable {
        private static final int LIFE = 14; // ticks — they linger only briefly
        private final World world;
        private final Wing[] wings;
        private int age = 0;

        Flock(World world, Location o, boolean black, int count) {
            this.world = world;
            this.wings = new Wing[count];
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < count; i++) {
                Wing w = new Wing();
                w.x = o.getX(); w.y = o.getY(); w.z = o.getZ();
                double ang = r.nextDouble(0, Math.PI * 2);
                double sp = 0.02 + r.nextDouble() * 0.03;   // a slow sideways drift beside the line
                w.vx = Math.cos(ang) * sp;
                w.vz = Math.sin(ang) * sp;
                w.vy = 0.02 + r.nextDouble() * 0.03;         // barely lofts — it lingers, doesn't fly off
                w.phase = r.nextDouble(0, Math.PI * 2);
                w.color = pickColor(r, black);
                wings[i] = w;
            }
        }

        @Override
        public void run() {
            if (age++ >= LIFE) { cancel(); return; }
            double t = age;
            for (Wing w : wings) {
                // Sway perpendicular to travel — the flutter.
                double flutter = Math.sin(t * 0.9 + w.phase) * 0.035;
                w.x += w.vx + (-w.vz) * flutter;
                w.z += w.vz + (w.vx) * flutter;
                w.y += w.vy;
                w.vy *= 0.9; // the loft eases off as it lingers

                // Butterfly wings: two motes flapping open/shut, offset perpendicular to travel.
                double flap = 0.02 + Math.abs(Math.sin(t * 1.2 + w.phase)) * 0.12;
                double px = -w.vz, pz = w.vx;
                double plen = Math.sqrt(px * px + pz * pz);
                if (plen > 1e-6) { px /= plen; pz /= plen; }
                Particle.DustOptions dust = new Particle.DustOptions(w.color, 0.9f);
                world.spawnParticle(Particle.DUST, new Location(world, w.x + px * flap, w.y, w.z + pz * flap),
                        1, 0, 0, 0, 0, dust);
                world.spawnParticle(Particle.DUST, new Location(world, w.x - px * flap, w.y, w.z - pz * flap),
                        1, 0, 0, 0, 0, dust);
            }
        }
    }

    /** One butterfly's live state. */
    private static final class Wing {
        double x, y, z, vx, vy, vz, phase;
        Color color;
    }

    /** Weighted pick: black guns lean dark; white guns lean pale, both dusted with soft pastels. */
    private static Color pickColor(ThreadLocalRandom r, boolean black) {
        double d = r.nextDouble();
        if (black) {
            if (d < 0.55) return C_BLACK;
            if (d < 0.80) return C_WHITE;
        } else {
            if (d < 0.50) return C_WHITE;
            if (d < 0.62) return C_BLACK;
        }
        double p = r.nextDouble();
        return p < 0.34 ? C_PINK : p < 0.67 ? C_BLUE : C_VIOLET;
    }

    // ---- item ----------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.SOLEMN_LAMENT.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * True if this stack is the meme "True Solemn Lament" admin variant: it is a Solemn Lament (matches)
     * that carries the extra {@code solemn_lament_true} marker byte. It fires exactly like the base weapon
     * — plus the per-shot "True…" image.
     */
    public boolean isTrueVariant(ItemStack item) {
        if (!matches(item)) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(trueKey, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.SOLEMN_LAMENT.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.SOLEMN_LAMENT);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * The meme admin variant, "True Solemn Lament". Built OFF {@link #createItem()} so it keeps the base
     * CROSSBOW, the {@code solemn_lament} PDC byte, AND the {@code ego/solemn_lament} custom-model-data —
     * the pack maps that CMD to the new gun model, so the True variant must render the SAME gun (do NOT
     * change the CMD). We only ADD a distinguishing {@code solemn_lament_true} marker byte and rename it.
     * {@code matches()} already accepts it (it carries the base byte), so {@code /reliquary admin
     * solemn_lament} hands it out with no Reliquary change.
     */
    @Override
    public ItemStack adminVariant() {
        ItemStack item = createItem();
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("True Solemn Lament").color(NAME)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(trueKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ----------------------------------------------------------------------

    // The title line is the Abnormality and needs a secondary distinct from the near-white name, so it is
    // set in FAINT — the grey this weapon's own controls text has always read in. CREPE is the palette's
    // nominal accent and the thematically obvious pick, but at #1C1C22 it is near-black: a tooltip's
    // background is near-black too, and the line would be all but invisible. FAINT is the readable member
    // of the same funereal palette, so nothing is invented here.
    //
    // The moveset below is written from the code in this file, not from the how-to block it replaces:
    // onSwing fires and onInteract dumps both mags. Reload is automatic-only — there is no manual reload
    // path anywhere in this class.
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Solemn Lament",
            "The Funeral of the Dead Butterflies",
            NAME,
            FAINT,
            List.of(
                    "The somber design is a reminder that not",
                    "a sliver of frivolity is allowed for the",
                    "minds of those who mourn."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Twin Magazines",
                            "Each pistol holds 12 shots, drained",
                            "independently as you alternate. When",
                            "both run dry they reload automatically",
                            "(1.5 seconds), and cannot fire until",
                            "the reload finishes."),
                    new EgoLore.Ability("[Left Click] Alternating Fire",
                            "Fire one pistol, alternating sides",
                            "each click — 3 pellets in a short cone",
                            "out to 16 blocks, with no knockback.",
                            "Hold to keep firing."),
                    new EgoLore.Ability("[Right Click] Fast Mag Dump",
                            "Unloads both pistols as fast as the",
                            "trigger cycles, one shot per tick,",
                            "alternating sides until both",
                            "magazines run dry.")
            ));

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        mags.remove(id);
        // Reap any live "True…" meme images — they are ephemeral and not player-attributed, so a quit is a
        // safe moment to clear the tracked set (belt-and-braces against a leaked display).
        clearTrackedMemes();
    }

    @Override
    public void onDisable() {
        // Plugin shutting down: remove every tracked "True…" meme image, then sweep all worlds for any
        // stray tagged display. They are setPersistent(false), so none can be saved to disk regardless.
        clearTrackedMemes();
        sweepMemeOrphans();
    }

    /** Remove every currently-tracked "True…" meme display and empty the tracking set. */
    private void clearTrackedMemes() {
        for (ItemDisplay d : memeDisplays) {
            if (d != null && d.isValid()) d.remove();
        }
        memeDisplays.clear();
    }

    /** Belt-and-braces: reap any display carrying our meme tag across all loaded worlds. */
    private void sweepMemeOrphans() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) {
                if (e.getScoreboardTags().contains(TRUE_MEME_TAG)) e.remove();
            }
        }
    }
}
