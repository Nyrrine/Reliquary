package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
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
 * Solitude — "Old Lady" (Lobotomy Corp E.G.O Equipment, TETH).
 *
 * <p>A rusty six-shooter that was old before anyone thought to pick it up. It is not a fast gun and it
 * was never meant to be one: the cylinder turns on its own patient schedule, one round every
 * {@value #SHOT_INTERVAL_MS}ms, and there is nothing the wielder can do to hurry it. What it fires is
 * not really a bullet. The round opens a <b>hole in the victim's soul rather than in their body</b> — no
 * shove, no stagger, just a hollow report and a small dark bloom where a person used to be whole.
 *
 * <ul>
 *   <li><b>Left-click (swing)</b> — <i>Bang. Bang.</i> Fire one chambered round straight down the aim
 *       line (hitscan, range {@value #RANGE}) for {@value #SHOT_DAMAGE} damage with <b>zero knockback</b>
 *       — the victim's velocity is captured and restored around the hit. The hammer then cycles for
 *       {@value #SHOT_INTERVAL_MS}ms before the next round will answer. The cylinder holds {@value #MAG}.
 *       An empty gun stays empty: it never auto-reloads, it only clicks.</li>
 *   <li><b>Right-click</b> — <i>Stories that Never Cease.</i> Gated on a <b>dry cylinder</b>; with rounds
 *       left the gun simply does not answer. The old woman talks and talks: {@value #STORY_SHOTS} hurried,
 *       wide, {@value #STORY_DAMAGE}-damage rounds every {@value #STORY_GAP_TICKS} ticks, and when the
 *       last one lands the cylinder is full again <b>instantly and for free</b> — that reload never pays
 *       the {@value #RELOAD_MS}ms reload wait. {@value #STORY_COOLDOWN_MS}ms ability cooldown, timed from
 *       the cast.</li>
 *   <li><b>Shift+right-click</b> — <i>Reload.</i> Swing the cylinder out at any point and thumb six rounds
 *       home over {@value #RELOAD_MS}ms. Firing is dead meanwhile; the trigger just clicks.</li>
 * </ul>
 *
 * <p><b>State &amp; safety.</b> All state is one UUID-&gt;cylinder map, dropped on quit. Every timer in it
 * is a wall-clock stamp, not a countdown, so a reload or the ability cooldown resolves correctly even
 * while the gun is stowed and this weapon is not ticking — {@link #onTick} disengages the instant the
 * revolver leaves the main hand and costs nothing until it comes back. Nothing is keyed by victim, so no
 * map can grow with the mob population. It spawns no entities at all — every shot is a raytrace plus a
 * short, capped particle draw — so there is nothing that can leak into the world and {@link #onDisable}
 * only has to cancel the ability burst. No world edits: damage is routed through
 * {@code victim.damage(...)} so other plugins can cancel it.
 */
public final class SolitudeWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;
    private final NamespacedKey key;

    /** Wielder -> their cylinder. The only per-player state this weapon keeps. */
    private final Map<UUID, Cyl> cylinders = new HashMap<>();

    /**
     * Bodies currently taking one of our own rounds. The manager dispatches {@link #onHit} for any hit
     * whose damager is a player holding this weapon, which every bullet is — so this fence tells a shot
     * apart from a swing. Held only across the single {@code damage()} call, in a try/finally, so it is
     * empty between rounds and can never accumulate a dead mob's id.
     */
    private final Set<UUID> shooting = new HashSet<>();

    // ---- tuning: a patient, rusty six-shooter ----------------------------------------

    private static final int    MAG               = 6;      // chambers in the cylinder — a six-shooter
    // Both halved after playtest — she liked the gun and wanted less waiting in it. The old woman is still
    // slow, she is just no longer testing anyone's patience: 750ms a round, 2.5s to fill the cylinder.
    private static final long   SHOT_INTERVAL_MS  = 750L;   // the hammer cycle between aimed rounds
    private static final long   RELOAD_MS         = 2500L;  // the on-demand shift+RC reload
    private static final double SHOT_DAMAGE       = 8.0;    // per aimed round — a netherite sword's hit, paid for by the 1.5s cycle

    // Practiced Thumbs (a vanilla enchant — the revolver holds Quick Charge at an anvil, so this needs no
    // catalogue entry): steadier hands thumb rounds home faster, cutting the shift+RC reload by 15% per
    // level, up to 45% at Quick Charge III. Utility only — it touches the manual reload wait and nothing
    // else; the hammer cycle between aimed rounds and the free Stories reload are both left alone.
    private static final double PRACTICED_THUMBS_PER_LEVEL = 0.15;
    private static final int    PRACTICED_THUMBS_CAP       = 3;
    private static final double SHOT_SPREAD       = 0.012;  // a hair of scatter so an aimed shot isn't a laser
    private static final double RANGE             = 30.0;   // hitscan reach, shared by both fire modes
    private static final double RAY_SIZE          = 0.5;    // entity ray fatness (forgiving aim)
    private static final double MUZZLE_FORWARD    = 0.7;    // how far down the aim line the muzzle sits

    // Stories that Never Cease — she talks and talks, and the gun is full again by the end of it.
    private static final String STORIES           = "Stories that Never Cease";
    private static final int    STORY_SHOTS       = 6;      // rounds in the burst
    private static final long   STORY_GAP_TICKS   = 2L;     // ticks between them — 6 rounds in ~0.5s
    private static final double STORY_DAMAGE      = 3.0;    // deliberately low; the free instant reload is the real payload
    private static final double STORY_SPREAD      = 0.06;   // hurried and unaimed — a much wider cone than a placed shot
    private static final long   STORY_COOLDOWN_MS = 45000L; // ability cooldown, timed from the cast

    // ---- palette --------------------------------------------------------------------

    private static final TextColor PRIMARY   = TextColor.color(0xB9A5D4); // dusty lilac — the name, the HUD
    private static final TextColor SECONDARY = TextColor.color(0x3A4A7A); // deep indigo — the Abnormality line
    private static final TextColor FAINT     = TextColor.color(0x7A7484); // conditions / trailing cooldowns

    private static final Color LILAC     = Color.fromRGB(0xB9, 0xA5, 0xD4); // the round leaving the barrel
    private static final Color INDIGO    = Color.fromRGB(0x3A, 0x4A, 0x7A); // what it has become by the time it lands
    private static final Color VOID_DARK = Color.fromRGB(0x14, 0x12, 0x1C); // the hole it leaves behind
    private static final Color RUST      = Color.fromRGB(0x6B, 0x4A, 0x3A); // flakes off the barrel with every shot

    /** The tracer fades lilac to indigo down its length — the shot goes cold on the way out. */
    private static final Particle.DustTransition TRACER    = new Particle.DustTransition(LILAC, INDIGO, 0.8f);
    private static final Particle.DustOptions    VOID_RING = new Particle.DustOptions(INDIGO, 1.0f);
    private static final Particle.DustOptions    VOID_CORE = new Particle.DustOptions(VOID_DARK, 1.4f);
    private static final Particle.DustOptions    RUST_FLAKE = new Particle.DustOptions(RUST, 0.7f);

    /** The void bloom's ring: motes placed evenly on a small circle around the impact. */
    private static final int    RING_MOTES  = 8;
    private static final double RING_RADIUS = 0.55;

    public SolitudeWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "solitude");
    }

    @Override
    public String id() {
        return "solitude";
    }

    // ---- Duet (dual-wield with Faint Aroma in the off hand) ---------------------------
    // When Faint Aroma is carried off-hand, the revolver and the crossbow play together, woven into Solitude's
    // FIRE: every 2nd aimed Bang auto-follows a real blossom, the reload becomes Faint-Aroma fire, aimed Bangs
    // feed the aroma charge and ride the petal bonus, and a loaded right-click at full bloom detonates
    // Magnificent End. Only the main-hand weapon ticks, so Solitude drives the merged HUD. Both stay fully
    // functional solo — every Duet path is gated on the off-hand partner being present.

    private static final int  DUET_BANGS_PER_BLOSSOM   = 2;    // every Nth aimed Bang auto-follows a blossom (PLACEHOLDER)
    private static final long DUET_BLOSSOM_DELAY_TICKS = 10L;  // 0.5s after the Bang (PLACEHOLDER)

    /** The Duet partner, fetched lazily from the registry the first time it is needed. */
    private FaintAromaWeapon partner;

    private FaintAromaWeapon partner() {
        if (partner == null && plugin.weapons().get("faint_aroma") instanceof FaintAromaWeapon fa) {
            partner = fa;
        }
        return partner;
    }

    /** True when Faint Aroma is in the off hand — the Duet condition. */
    private boolean duetActive(Player player) {
        FaintAromaWeapon fa = partner();
        return fa != null && fa.matches(player.getInventory().getItemInOffHand());
    }

    /**
     * Count one aimed Bang toward the auto-follow; on every {@value #DUET_BANGS_PER_BLOSSOM}th, schedule a
     * real Faint Aroma blossom {@value #DUET_BLOSSOM_DELAY_TICKS} ticks later. The delayed shot re-checks the
     * off hand (via {@code duetAutoBlossom}) so a partner swapped away mid-delay fires nothing.
     */
    private void duetCountBang(Player player) {
        Cyl cyl = cylinders.get(player.getUniqueId());
        if (cyl == null) return;
        if (++cyl.duetBangCount < DUET_BANGS_PER_BLOSSOM) return;
        cyl.duetBangCount = 0;
        FaintAromaWeapon fa = partner();
        if (fa == null) return;
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> { if (player.isOnline() && duetActive(player)) fa.duetAutoBlossom(player); },
                DUET_BLOSSOM_DELAY_TICKS);
    }

    /**
     * A wielder's cylinder. Every timer here is an absolute wall-clock stamp rather than a countdown, so
     * a reload and the ability cooldown both resolve on their own while the gun is stowed and this weapon
     * isn't ticking — nothing has to be driven to completion.
     */
    private static final class Cyl {
        int  rounds       = MAG;
        long lastShot     = 0L;  // stamp of the last AIMED round — the hammer cycle gate
        long reloadStart  = 0L;  // 0 = not reloading
        long storiesReady = 0L;  // stamp the ability comes back up
        BukkitTask burst  = null; // non-null while a Stories burst is running
        int  duetBangCount = 0;  // Duet: aimed Bangs counted toward the every-2nd auto-follow blossom

        boolean reloading() { return reloadStart != 0L; }
    }

    // ---- fire: Bang. Bang. ------------------------------------------------------------

    /**
     * A revolver is not a cudgel. Left-click is the trigger, so a shot taken at a body within arm's reach
     * would otherwise land a vanilla blow as well — and the blow, arriving first, stamps hurt-immunity that
     * swallows the round. The gun appears to stop working at exactly the range where it matters most.
     * Cancelling costs nothing: Solitude is a {@code ranged} model with no melee damage of its own.
     *
     * <p><b>The fence is not optional.</b> The manager dispatches this for <em>any</em> hit whose damager is
     * a player holding this weapon — and a bullet is precisely that. Every round we fire comes straight back
     * through here, so without {@link #shooting} the cancel would eat the shot it was meant to protect and
     * the revolver would deal nothing whatsoever.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (shooting.contains(victim.getUniqueId())) return; // our own round, not a swing
        event.setCancelled(true);
    }

    @Override
    public void onSwing(Player player) {
        // LEFT-click pulls the trigger. Driven only by the main-hand revolver.
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!matches(main)) return;

        Cyl cyl = cylinders.computeIfAbsent(player.getUniqueId(), k -> new Cyl());
        long now = System.currentTimeMillis();

        if (cyl.burst != null) return;                       // the burst owns the trigger while it talks
        // Duet: an empty OR reloading cylinder keeps firing through Faint Aroma's infinite blossoms
        // (cadence-gated, no ammo, and they build petals), so an empty Solitude never goes dead — it keeps
        // firing via Faint Aroma. Solo, both states stay a dry click. Shift-RC still refills the stronger
        // Bangs when she wants them back.
        if (cyl.reloading() || cyl.rounds <= 0) {
            if (duetActive(player)) partner().duetBlossom(player);
            else dryClick(player);
            renderBar(player, cyl);
            return;
        }
        // The hammer is still cycling; a click on every early pull would just be noise, so stay silent.
        if (now - cyl.lastShot < SHOT_INTERVAL_MS) return;

        cyl.lastShot = now;
        cyl.rounds--;
        // Duet: the revolver's own round rides Faint Aroma's petal bonus too, so blooms buff both weapons.
        double dmg = SHOT_DAMAGE;
        if (duetActive(player)) dmg *= partner().duetPetalMultiplier(player);
        loose(player, dmg, SHOT_SPREAD, true);
        EgoDurability.wearMainHand(player); // mild — one point per aimed round
        renderBar(player, cyl);
    }

    // ---- Stories that Never Cease / Reload --------------------------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!matches(main)) return;

        Cyl cyl = cylinders.computeIfAbsent(player.getUniqueId(), k -> new Cyl());
        long now = System.currentTimeMillis();

        if (sneaking) { manualReload(player, cyl, now); return; }

        // Stories that Never Cease. It only ever answers a DRY cylinder — with rounds left it does nothing
        // at all, by design. It is what the gun does when it has nothing left to say.
        if (cyl.burst != null) return;                       // never stack a second burst

        // Duet: Magnificent End takes the right-click at full bloom, at ANY cylinder state (loaded, empty, or
        // reloading) — priority over Stories. Gating it to the loaded RC meant she could never reach it once
        // empty; RC now means Magnificent End whenever it is charged. Below full bloom, the old behaviour
        // resumes just below (dry -> Stories, loaded -> the "petals not ready" cue).
        if (duetActive(player) && partner().duetMagnificentReady(player)) {
            partner().duetMagnificentEnd(player);
            renderBar(player, cyl);
            return;
        }

        if (cyl.reloading()) { dryClick(player); return; }
        if (cyl.rounds > 0) {                                // loaded, below full bloom
            // In Duet the loaded RC has no other job now — say why Magnificent End did not fire. Solo: dead.
            if (duetActive(player)) {
                player.sendActionBar(EgoHud.status("Magnificent End — petals not ready", FAINT));
                dryClick(player);
                return;
            }
            dryClick(player);
            return;
        }
        // dry cylinder, below full bloom -> Stories
        if (now < cyl.storiesReady) {
            dryClick(player);
            player.sendActionBar(EgoHud.cooldown(STORIES, cyl.storiesReady - now, FAINT));
            return;
        }
        beginStories(player, cyl);
    }

    /**
     * The burst: {@value #STORY_SHOTS} hurried rounds {@value #STORY_GAP_TICKS} ticks apart, then the free
     * instant reload. The ability cooldown is stamped at the cast, not at the end, and the wear is one
     * point for the whole ability rather than one per round.
     *
     * <p>If the wielder stows the gun or logs off mid-burst the runnable stops early but the free reload is
     * still handed back ({@link #endStories}) — the cooldown has already been paid, so an interruption must
     * never leave them holding a dead gun and a spent ability.
     */
    private void beginStories(Player player, Cyl cyl) {
        UUID id = player.getUniqueId();
        cyl.storiesReady = System.currentTimeMillis() + STORY_COOLDOWN_MS;
        EgoDurability.wearMainHand(player); // one point for the ability, not one per round
        storiesOpeningFx(player);

        cyl.burst = new BukkitRunnable() {
            int fired = 0;

            @Override
            public void run() {
                Player p = plugin.getServer().getPlayer(id);
                if (p == null || !matches(p.getInventory().getItemInMainHand())) {
                    endStories(p, cyl);   // interrupted — the free reload is still owed
                    cancel();
                    return;
                }
                // Hurried rounds don't touch lastShot: the burst is its own cadence and must not leave the
                // aimed hammer gate armed behind it.
                loose(p, STORY_DAMAGE, STORY_SPREAD, false);
                if (++fired >= STORY_SHOTS) {
                    endStories(p, cyl);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, STORY_GAP_TICKS);
    }

    /** The story ends and the cylinder is simply full again — instantly, and without paying the reload. */
    private void endStories(Player player, Cyl cyl) {
        cyl.burst = null;
        cyl.rounds = MAG;
        cyl.reloadStart = 0L;   // the free reload cancels any wait outright
        if (player != null) {
            reloadReadyFx(player);
            renderBar(player, cyl);
        }
    }

    /** Shift+right-click: swing the cylinder out and thumb rounds home over {@value #RELOAD_MS}ms. */
    private void manualReload(Player player, Cyl cyl, long now) {
        if (cyl.reloading() || cyl.burst != null) return;
        if (cyl.rounds >= MAG) { dryClick(player); return; } // already full — nothing to top up
        cyl.reloadStart = now;
        reloadStartFx(player);
        renderBar(player, cyl);
    }

    // ---- tick: reload finish + HUD ----------------------------------------------------

    @Override
    public boolean onTick(Player player, long tick) {
        // The revolver left the main hand — disengage immediately. There is nothing to drive to completion:
        // the reload and the ability cooldown are wall-clock stamps and resolve on their own.
        if (!matches(player.getInventory().getItemInMainHand())) return false;

        Cyl cyl = cylinders.computeIfAbsent(player.getUniqueId(), k -> new Cyl());

        if (cyl.reloading() && System.currentTimeMillis() - cyl.reloadStart >= reloadMs(player)) {
            cyl.rounds = MAG;
            cyl.reloadStart = 0L;
            reloadReadyFx(player);
        }

        renderBar(player, cyl);
        return true;
    }

    /**
     * The manual-reload time for the revolver held right now: the base wait cut by Practiced Thumbs (Quick
     * Charge, capped). The hammer cycle and the free Stories reload never read this.
     */
    private long reloadMs(Player player) {
        int qc = Math.min(PRACTICED_THUMBS_CAP,
                player.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.QUICK_CHARGE));
        return (long) (RELOAD_MS * (1.0 - PRACTICED_THUMBS_PER_LEVEL * qc));
    }

    /**
     * The held-weapon action bar. The base readout — the live chamber count {@code Chambers x/6} — is
     * ALWAYS shown; a state only ever APPENDS onto it, never replaces the line. At most one trailing state:
     * the reload while the cylinder is filling, otherwise the ability's state while the gun is dry (that is
     * when it matters). Everything reads in whole seconds.
     */
    private void renderBar(Player player, Cyl cyl) {
        long now = System.currentTimeMillis();
        Component bar = EgoHud.ammo(PRIMARY, "Chambers", cyl.rounds, MAG);
        if (cyl.reloading()) {
            bar = bar.append(plain("  ", FAINT))
                    .append(EgoHud.cooldown("Reloading", reloadMs(player) - (now - cyl.reloadStart), PRIMARY));
        } else if (cyl.rounds <= 0) {
            // Dry cylinder: show Stories' state — unless a charged Duet Magnificent End would take the RC
            // first, in which case the merged readout's ME-ready cue is the truthful one and a "Stories ready"
            // line here would only mislead about what the right-click does.
            boolean meTakesRc = duetActive(player) && partner().duetMagnificentReady(player);
            if (!meTakesRc) {
                bar = bar.append(plain("  ", FAINT)).append(now < cyl.storiesReady
                        ? EgoHud.cooldown(STORIES, cyl.storiesReady - now, FAINT)
                        : EgoHud.ready(STORIES, PRIMARY));
            }
        }
        // Duet: fold Faint Aroma's petal + aroma readout onto the same always-on line, so the merged HUD
        // reflects both weapons at once (Faint Aroma does not tick from the off hand — Solitude paints it).
        if (duetActive(player)) bar = EgoHud.row(bar, partner().duetReadout(player));
        player.sendActionBar(bar);
    }

    private static Component plain(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    // ---- ballistics -------------------------------------------------------------------

    /**
     * Loose exactly one round down the aim line: clip at the first real wall, then take the first living
     * body in the way. The hit deals {@code damage} with <b>no knockback</b> — the victim's velocity is
     * captured and restored around it, because this round is meant to leave a hole in them, not move them.
     *
     * @param aimed true for a placed left-click round (heavy, close report), false for a hurried burst round
     */
    private void loose(Player player, double damage, double spread, boolean aimed) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = scatter(eye.getDirection().normalize(), spread);
        Location muzzle = eye.clone().add(dir.clone().multiply(MUZZLE_FORWARD)).add(0, -0.12, 0);

        // Ignore passable blocks (grass, flowers, fluids) so only a real wall stops the round — the 3-arg
        // overload would let a grass tuft eat the shot.
        double maxDist = RANGE;
        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, RANGE, FluidCollisionMode.NEVER, true);
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
            // The burst's rounds land 2 ticks apart — inside the victim's i-frames, which would swallow
            // every one after the first. Clear them so all six actually register.
            le.setNoDamageTicks(0);
            // The round's own damage comes back to us: the manager dispatches every hit whose damager is
            // a player holding this weapon, and that is exactly what a bullet is. Without this fence
            // onHit — which exists to stop the revolver being swung as a club — would cancel the shot the
            // trigger just fired, and the gun would deal nothing at all.
            shooting.add(le.getUniqueId());
            try {
                le.damage(damage, player);
            } finally {
                shooting.remove(le.getUniqueId());
            }
            le.setVelocity(velocity);   // a void in the soul, not a wound — it never shoves them
            voidBloom(world, end);
            // Duet: an AIMED Bang that lands feeds Faint Aroma's aroma charge — gunfire building the scent
            // toward its Weakness. The petal-damage bonus was already applied at the trigger; the hurried
            // Stories rounds (aimed == false) never feed it.
            if (aimed && duetActive(player)) partner().duetFeedAroma(player, le);
        } else {
            end = eye.clone().add(dir.clone().multiply(maxDist));
        }

        reportFx(world, muzzle, aimed);
        drawTracer(world, muzzle, end);

        // Duet: every DUET_BANGS_PER_BLOSSOM aimed Bang fired (hit or miss) auto-follows a real Faint Aroma
        // blossom half a second later. Stories rounds are aimed == false, so they never count.
        if (aimed && duetActive(player)) duetCountBang(player);
    }

    /** Nudge a shot off true by a hair — a placed round barely, a hurried one noticeably. */
    private Vector scatter(Vector base, double spread) {
        if (spread <= 0.0) return base;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return base.clone().add(new Vector(
                rng.nextDouble(-spread, spread),
                rng.nextDouble(-spread, spread),
                rng.nextDouble(-spread, spread))).normalize();
    }

    // ---- presentation -----------------------------------------------------------------

    /**
     * The report: a low, hollow bark with a rusty mechanical edge and a thin crack tail, as if it were
     * fired in a much larger and much emptier room than the one the wielder is standing in. A placed round
     * lands heavy; a hurried burst round is quieter and higher so six of them read as chatter, not a din.
     */
    private void reportFx(World world, Location muzzle, boolean aimed) {
        float vol = aimed ? 1.0f : 0.55f;
        float pitch = aimed ? 0.65f : 0.95f;

        world.playSound(muzzle, Sound.ITEM_CROSSBOW_SHOOT, 0.9f * vol, pitch);            // the body of the shot
        world.playSound(muzzle, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f * vol, 0.5f);           // the hollow chest under it
        world.playSound(muzzle, Sound.ITEM_FIRECHARGE_USE, 0.35f * vol, 0.6f);            // old powder catching
        world.playSound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.16f * vol, 1.9f);         // the thin crack, far away
        world.playSound(muzzle, Sound.BLOCK_CHAIN_HIT, 0.30f * vol, 1.2f);                // the rusty hammer resetting

        // A little rust and old smoke shaken off the barrel — the gun is visibly falling apart as it works.
        world.spawnParticle(Particle.DUST, muzzle, 3, 0.05, 0.05, 0.05, 0, RUST_FLAKE);
        world.spawnParticle(Particle.SMOKE, muzzle, aimed ? 4 : 2, 0.06, 0.06, 0.06, 0.01);
    }

    /** The trigger falling on nothing: a dry mechanical click on tired, rusty parts. */
    private void dryClick(Player player) {
        Location at = player.getLocation();
        player.playSound(at, Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 1.5f);
        player.playSound(at, Sound.BLOCK_CHAIN_HIT, 0.35f, 0.6f);
    }

    /** The cylinder swings out — a rusty hinge and the slow business of thumbing rounds home. */
    private void reloadStartFx(Player player) {
        Location at = player.getLocation();
        player.playSound(at, Sound.ITEM_CROSSBOW_LOADING_START, 0.7f, 0.8f);
        player.playSound(at, Sound.BLOCK_CHAIN_HIT, 0.5f, 0.7f);
    }

    /** The cylinder snaps home: the one clean, satisfying noise this gun still makes. */
    private void reloadReadyFx(Player player) {
        Location at = player.getLocation();
        player.playSound(at, Sound.ITEM_CROSSBOW_LOADING_END, 0.7f, 0.9f);
        player.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.22f, 1.9f);
    }

    /** She starts talking: a low, lonely resonance that hangs under the burst that follows. */
    private void storiesOpeningFx(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6f, 0.6f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f);
        world.spawnParticle(Particle.SOUL, at, 6, 0.35, 0.5, 0.35, 0.01, null, true);
    }

    /**
     * The tracer: a fine line fading lilac-to-indigo down its length, with the odd wisp of soul peeling off
     * it. Forced so it still draws at the far end of a {@value #RANGE}-block shot, where the client would
     * otherwise cull it. The first metre is left bare so nothing bursts in first-person.
     */
    private void drawTracer(World world, Location from, Location to) {
        Vector step = to.toVector().subtract(from.toVector());
        double length = step.length();
        if (length < 1.0e-4) return;
        step.multiply(1.0 / length);

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int idx = 0;
        for (double d = 1.0; d < length; d += 0.6, idx++) {
            Location p = from.clone().add(step.clone().multiply(d));
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0, TRACER, true);
            if (idx % 4 == 0) {
                world.spawnParticle(Particle.SMOKE, p, 1, 0.02, 0.02, 0.02, 0.0, null, true);
            }
            if (rng.nextInt(10) == 0) { // a rare lonely wisp trailing the round out
                world.spawnParticle(Particle.SOUL, p, 1, 0.04, 0.04, 0.04, 0.0, null, true);
            }
        }
    }

    /**
     * The impact — the whole point of the weapon. Not a spray of blood: a small dark bloom that opens where
     * the round went in, a ring of indigo collapsing on a hole too dark to be a wound, and a soul or two
     * leaking out of it. One flat burst, no task, no entity, forced so it draws at range.
     */
    private void voidBloom(World world, Location at) {
        world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, VOID_CORE, true);              // the hole itself
        world.spawnParticle(Particle.DUST, at, 4, 0.10, 0.10, 0.10, 0, VOID_RING, true);     // its dark edge
        world.spawnParticle(Particle.SOUL, at, 3, 0.10, 0.12, 0.10, 0.02, null, true);       // what leaks out
        world.spawnParticle(Particle.SMOKE, at, 3, 0.08, 0.08, 0.08, 0.01, null, true);

        // The ring: motes placed evenly around the hit so the bloom reads as an opening, not a splatter.
        for (int i = 0; i < RING_MOTES; i++) {
            double a = (Math.PI * 2 * i) / RING_MOTES;
            Location p = at.clone().add(Math.cos(a) * RING_RADIUS, 0, Math.sin(a) * RING_RADIUS);
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, VOID_RING, true);
        }

        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);       // a hollow, unsatisfying thud
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.35f, 0.6f);   // and something ringing under it
    }

    // ---- item -------------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.SOLITUDE.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.SOLITUDE.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.SOLITUDE);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore -------------------------------------------------------------------------

    /** Built once: the display name is the weapon, the title line is the Abnormality. Never the reverse. */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Solitude",
            "Old Lady",
            PRIMARY,
            SECONDARY,
            List.of(
                    "A strong sense of loneliness still",
                    "lingers, even in the form of an E.G.O.",
                    "Its bullets create a void that cannot",
                    "be filled in the victim's soul, rather",
                    "than a wound upon their flesh and",
                    "bones. It was a rusty weapon from the",
                    "beginning."),
            List.of(
                    new EgoLore.Ability("[Left Click] Bang. Bang.",
                            "Shoot your revolver six times; there",
                            "is a 0.75-second cooldown in between",
                            "shots."),
                    new EgoLore.Ability("[Right Click] Stories that Never Cease",
                            "Only works when you have no bullets",
                            "left. Burst 6 fast, low-damage ranged",
                            "rounds, Instant reload after for free.",
                            "45-second ability cooldown."),
                    new EgoLore.Ability("[Shift+Right-Click] Reload",
                            "Reload your gun at any point; face a",
                            "2.5-second reload cooldown.")));

    // ---- lifecycle --------------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        Cyl cyl = cylinders.remove(id);
        if (cyl != null && cyl.burst != null) {
            cyl.burst.cancel();     // the burst's own guard would stop it next tick; don't wait
            cyl.burst = null;
        }
    }

    @Override
    public void onDisable() {
        // Nothing of this weapon is ever out in the world — no entities are spawned, so there is nothing to
        // sweep. The only thing that can outlive the plugin is a running burst.
        for (Cyl cyl : cylinders.values()) {
            if (cyl.burst != null) {
                cyl.burst.cancel();
                cyl.burst = null;
            }
        }
        cylinders.clear();
    }
}
