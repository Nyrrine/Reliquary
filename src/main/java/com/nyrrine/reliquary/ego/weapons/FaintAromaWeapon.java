package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
import com.nyrrine.reliquary.ego.EgoHud;
import com.nyrrine.reliquary.ego.EgoLore;
import com.nyrrine.reliquary.ego.EgoModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Faint Aroma — "Alriune" (Lobotomy Corp E.G.O Equipment, WAW).
 *
 * <p>A crossbow that smells of a forest that isn't there. The extraction took the Abnormality but left
 * the scent behind, so the thing hums with pollen and cold water light: lavender and cyan, all bloom and
 * no blade. Its arrowhead is <b>dull</b> — it does not pierce so much as <em>open</em>. Vivid flowers
 * sprout wherever it lands, on stone, on bark, on people. It is a beautiful weapon right up until the
 * moment it isn't.
 *
 * <ul>
 *   <li><b>[Passive] Unwithering Flower</b> — every strike mends the wielder
 *       ({@link #UNWITHERING_HEAL_PER_STRIKE}) and grows one petal. Each petal is
 *       +{@value #PETAL_DAMAGE_PER_STACK_PCT}% weapon damage, capped at {@value #PETAL_CAP} petals
 *       (= +{@value #PETAL_CAP}% at full bloom). At {@value #PETAL_CAP} petals, [Magnificent End]
 *       unlocks. The petal charge reads on the action bar.</li>
 *   <li><b>[Left Click] Blossoming Fragrance</b> — a lavender arrow every
 *       {@value #BLOSSOM_CADENCE_MS}ms, loosed the instant you click (the crossbow never draws or
 *       charges). A raised shield facing the shot swallows it whole.</li>
 *   <li><b>[Right-Click] Full Bloom</b> — {@value #FULL_BLOOM_ARROWS} heavier, faster arrows in rapid
 *       succession. Each one triggers the same passives as a Blossoming arrow. Rather than being stopped
 *       by a raised guard, Full Bloom <em>breaks</em> it: a blocking victim's shield is knocked out of
 *       action for {@value #SHIELD_DISABLE_TICKS} ticks.</li>
 *   <li><b>[Shift+Right-Click] Magnificent End</b> — refused below {@value #PETAL_CAP} petals. At full
 *       bloom, a single heavy bolt that opens on contact into a flower the size of a house, and spends
 *       every petal doing it.</li>
 * </ul>
 *
 * <p><b>Non-grief by construction.</b> [Magnificent End] "explodes upon contact", but nothing here ever
 * touches the world: the blast is a hand-rolled radius sweep ({@link Bolt#detonate}), never
 * {@code World.createExplosion}. No blocks are broken, no fire is lit, and the flowers it sprouts are
 * {@link Particle#ITEM} particles rather than placed blocks. Every point of damage goes through
 * {@code victim.damage(...)} so other plugins can cancel it.
 *
 * <p><b>Lifecycle.</b> This weapon spawns <b>no entities at all</b> — each arrow is a lightweight
 * {@link Bolt} runnable drawing particles along its own flight, the same trick Laetitia uses. There is
 * therefore nothing that can orphan into the world and nothing to sweep; the only cleanup is cancelling
 * the in-flight bolts ({@link #inFlight}) on disable. Per-player state is one UUID-keyed {@link Bloom}
 * map, dropped on quit. {@link #onTick} bails the moment the crossbow leaves the main hand.
 */
public final class FaintAromaWeapon implements Weapon {

    private final Reliquary plugin;
    private final NamespacedKey key;

    /** Wielder -> their petals, cadence gates and cooldowns. The only per-player state kept. */
    private final Map<UUID, Bloom> blooms = new HashMap<>();

    /** Every arrow currently in flight, so {@link #onDisable} can cancel the lot. No entities involved. */
    private final Set<Bolt> inFlight = new HashSet<>();

    // ---- [Passive] Unwithering Flower ----------------------------------------------

    /**
     * The mend laid on the wielder by every strike of [Passive] Unwithering Flower.
     *
     * <p><b>TODO: BLOCKED — pending a design ruling. This value is an unresolved PLACEHOLDER, not a
     * decision.</b> The spec reads "Heal half a bar of HP every strike", which parses two ways: half a
     * <em>heart</em> (1.0 HP) or half the health <em>bar</em> (10.0 HP). That is a 10x swing on a passive
     * that fires on every single arrow — at 10.0 a Full Bloom burst would full-heal the wielder three
     * times over, at 1.0 it is a light trickle. 1.0 sits here <b>only</b> so the passive compiles and runs
     * end-to-end; it is not the chosen number.
     *
     * <p>When the ruling lands this constant is the <b>only</b> thing that needs to change. The heal is
     * wired up completely through {@link #healWielder} and nothing else reads the value.
     */
    private static final double UNWITHERING_HEAL_PER_STRIKE = 1.0;

    /** Petals cap here; this is also the price of admission for [Magnificent End]. */
    private static final int PETAL_CAP = 30;

    /** Each petal is worth this much weapon damage: +1% per petal, so +30% at full bloom. */
    private static final double PETAL_DAMAGE_PER_STACK = 0.01;
    private static final int PETAL_DAMAGE_PER_STACK_PCT = 1; // the same number, for docs and the HUD

    // ---- faint aroma (the Weakness payload) ----------------------------------------

    /** The scent clings for 5 seconds. */
    private static final int FAINT_AROMA_WEAKNESS_TICKS = 100;
    private static final int FAINT_AROMA_WEAKNESS_SECONDS = 5; // the same number, for docs
    private static final int FAINT_AROMA_WEAKNESS_AMP = 0;     // Weakness I — a sapping, not a crippling

    // ---- [Left Click] Blossoming Fragrance -----------------------------------------

    /** The spec's cadence: one lavender arrow every 1.5 seconds. */
    private static final long BLOSSOM_CADENCE_MS = 1_500L;

    /** Per arrow, before petals. Modest on purpose — at a 1.5s cadence this is a steady poke, not burst. */
    private static final double BLOSSOM_DAMAGE = 6.0;

    /** Blocks/tick. Fast enough to read as instant in a fight, slow enough that Full Bloom can beat it. */
    private static final double BLOSSOM_SPEED = 2.2;

    /** Ticks of flight before the arrow gives up — about 35 blocks of reach. */
    private static final int BLOSSOM_LIFE_TICKS = 16;

    /**
     * Each ARM_SWING arms this much sustained fire, and {@link #onTick} drives the cadence while it lasts.
     * Deliberately <b>shorter</b> than {@link #BLOSSOM_CADENCE_MS}: a single tap therefore looses exactly
     * one arrow (the window lapses long before the next is due), while a held left-click — whose swings
     * keep re-arming it — sustains the 1.5s stream. The window exists because holding left-click in air
     * does not reliably repeat ARM_SWING, so onSwing alone can never sustain fire.
     */
    private static final long HOLD_WINDOW_MS = 800L;

    // ---- [Right-Click] Full Bloom ---------------------------------------------------

    /** "Three powerful lavender arrows in rapid succession." */
    private static final int FULL_BLOOM_ARROWS = 3;

    /** Ticks between the arrows of a burst — ~0.15s apart, i.e. rapid succession. */
    private static final long FULL_BLOOM_GAP_TICKS = 3L;

    /** "Higher in impact" — heavier than a Blossoming arrow, still under a Sharpness-V netherite hit. */
    private static final double FULL_BLOOM_DAMAGE = 7.0;

    /** "…and velocity" — visibly faster than {@link #BLOSSOM_SPEED}. */
    private static final double FULL_BLOOM_SPEED = 3.2;

    /** About 42 blocks of reach. */
    private static final int FULL_BLOOM_LIFE_TICKS = 13;

    /** The burst is the payoff, so it has to rest. */
    private static final long FULL_BLOOM_COOLDOWN_MS = 8_000L;

    /** How long a guard broken by Full Bloom / Magnificent End stays down — 4s, the vanilla axe-disable feel. */
    private static final int SHIELD_DISABLE_TICKS = 80;

    // ---- [Shift+Right-Click] Magnificent End ---------------------------------------

    /**
     * The bolt's direct hit. <b>FLAGGED:</b> this is the one number on the weapon that leaves the
     * balance band on purpose. A single Sharpness-V netherite hit is ~11; this is 24.0 raw, and since
     * [Magnificent End] can only ever fire at exactly {@value #PETAL_CAP} petals it always lands at
     * x1.30 — <b>31.2 on the body it strikes</b>. That buys: ~30 strikes of build-up (about 45s of
     * Blossoming Fragrance), the total loss of every petal, and the +30% damage that came with them.
     * It is a finisher that costs a whole fight's worth of accrual, so it is allowed to hurt.
     */
    private static final double MAGNIFICENT_END_IMPACT = 24.0;

    /**
     * The bloom around the impact, at the epicentre, falling off linearly to zero at
     * {@link #MAGNIFICENT_END_RADIUS}. Splash for bystanders only — see {@link Bolt#detonate} for why
     * the directly-struck body is deliberately exempt.
     */
    private static final double MAGNIFICENT_END_BLAST = 14.0;

    /** How wide the flower opens. */
    private static final double MAGNIFICENT_END_RADIUS = 4.5;

    /** Heavy and deliberate — it does not need to be the fastest thing it fires. */
    private static final double MAGNIFICENT_END_SPEED = 2.6;

    /** About 47 blocks of reach. */
    private static final int MAGNIFICENT_END_LIFE_TICKS = 18;

    // ---- flight ---------------------------------------------------------------------

    /** Sub-step per movement slice — the anti-tunnel granularity, well under a block. */
    private static final double BOLT_SUBSTEP = 0.4;

    /** How near an arrow's line a living body must be to be struck. The dull head is forgiving. */
    private static final double HIT_RADIUS = 1.0;

    /** Shot heading vs. a blocker's look: below this, they count as roughly facing the incoming arrow. */
    private static final double BLOCK_FACING_DOT = 0.2;

    /** Beyond ~32 blocks the client culls unforced particles, and these arrows outrange that easily. */
    private static final boolean FORCE_PARTICLES = true;

    // ---- palette: lavender and cyan, the colours of a forest that isn't there --------

    private static final TextColor LAVENDER = TextColor.color(0xB67ACE); // primary — name, ability headers
    private static final TextColor CYAN     = TextColor.color(0x6CF9F7); // secondary — the Abnormality line
    private static final TextColor FAINT    = TextColor.color(0x8A7A94); // muted readouts
    private static final TextColor WILT     = TextColor.color(0x6E5F76); // a refused Magnificent End
    private static final TextColor COUNT    = NamedTextColor.GRAY;       // counts, matching EgoHud

    private static final Color C_LAVENDER = Color.fromRGB(0xB6, 0x7A, 0xCE); // the arrow itself
    private static final Color C_CYAN     = Color.fromRGB(0x6C, 0xF9, 0xF7); // cold water light
    private static final Color C_PETAL    = Color.fromRGB(0xE7, 0xCB, 0xF5); // pale petal highlight
    private static final Color C_STEM     = Color.fromRGB(0x7A, 0x4E, 0x8E); // deeper violet, the shaft's core

    private static final Particle.DustOptions LAVENDER_DUST = new Particle.DustOptions(C_LAVENDER, 0.9f);
    private static final Particle.DustOptions CYAN_DUST     = new Particle.DustOptions(C_CYAN, 0.7f);
    private static final Particle.DustOptions PETAL_DUST    = new Particle.DustOptions(C_PETAL, 0.8f);
    private static final Particle.DustOptions STEM_DUST     = new Particle.DustOptions(C_STEM, 1.0f);

    /**
     * The signature: dust that starts lavender and fades to cyan as it hangs — the fragrance turning
     * colour in the air. DUST_COLOR_TRANSITION takes a {@link Particle.DustTransition}, never a plain
     * DustOptions; handing it the wrong data class is a runtime crash, not a compile error.
     */
    private static final Particle.DustTransition BLOOM_FADE =
            new Particle.DustTransition(C_LAVENDER, C_CYAN, 1.1f);

    /**
     * Vivid flowers sprout where the dull arrowhead lands. These are {@link Particle#ITEM} data — thrown
     * as particles, <b>never placed as blocks</b>: the weapon makes no permanent edit to the world.
     */
    private static final ItemStack[] FLOWERS = {
            new ItemStack(Material.ALLIUM),      // lavender
            new ItemStack(Material.CORNFLOWER),  // cyan
            new ItemStack(Material.PINK_TULIP),  // pale petal
            new ItemStack(Material.LILAC),       // a fuller bloom
    };

    public FaintAromaWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "faint_aroma");
    }

    @Override
    public String id() {
        return "faint_aroma";
    }

    /** Per-wielder bloom state: petals grown, plus the gates that pace the three inputs. */
    private static final class Bloom {
        int  petals      = 0;   // [Passive] Unwithering Flower — 0..PETAL_CAP
        long lastBlossom = 0L;  // epoch-ms of the last Blossoming Fragrance arrow (the 1.5s cadence gate)
        long holdUntil   = 0L;  // swing-refreshed auto-fire window; onTick looses arrows while it is live
        long fullBloomCd = 0L;  // epoch-ms when Full Bloom is ready again
    }

    /**
     * The three arrows Faint Aroma looses. One {@link Bolt} serves all three — the arrowhead is always
     * the same dull thing, only the weight, the speed and the bloom differ.
     */
    private enum Shot {
        /** [Left Click] Blossoming Fragrance — the light, constant shot. A raised shield swallows it. */
        BLOSSOM(BLOSSOM_DAMAGE, BLOSSOM_SPEED, BLOSSOM_LIFE_TICKS),
        /** [Right-Click] Full Bloom — heavier and faster; breaks a raised guard instead of being stopped. */
        FULL_BLOOM(FULL_BLOOM_DAMAGE, FULL_BLOOM_SPEED, FULL_BLOOM_LIFE_TICKS),
        /** [Shift+Right-Click] Magnificent End — the finisher. Opens on contact. */
        MAGNIFICENT(MAGNIFICENT_END_IMPACT, MAGNIFICENT_END_SPEED, MAGNIFICENT_END_LIFE_TICKS);

        private final double damage;    // before the petal multiplier
        private final double speed;     // blocks/tick
        private final int    lifeTicks; // ticks before it gives up

        Shot(double damage, double speed, int lifeTicks) {
            this.damage = damage;
            this.speed = speed;
            this.lifeTicks = lifeTicks;
        }
    }

    // ---- [Left Click] Blossoming Fragrance ------------------------------------------

    /**
     * Left-click looses a Blossoming Fragrance arrow, subject to the {@value #BLOSSOM_CADENCE_MS}ms
     * cadence, and arms the hold window so {@link #onTick} can sustain the stream (see
     * {@link #HOLD_WINDOW_MS} for why the window is needed at all).
     */
    @Override
    public void onSwing(Player player) {
        if (!matches(player.getInventory().getItemInMainHand())) return;

        Bloom bloom = blooms.computeIfAbsent(player.getUniqueId(), k -> new Bloom());
        long now = System.currentTimeMillis();
        bloom.holdUntil = now + HOLD_WINDOW_MS; // armed on EVERY swing, even a cadence-suppressed one
        fireBlossom(player, bloom, now);
    }

    /**
     * Loose exactly one Blossoming Fragrance arrow IF the 1.5s cadence gate allows. Shared by the
     * responsive onSwing click and the onTick hold continuation. True if an arrow actually left.
     */
    private boolean fireBlossom(Player player, Bloom bloom, long now) {
        if (now - bloom.lastBlossom < BLOSSOM_CADENCE_MS) return false;
        bloom.lastBlossom = now;

        launch(player, bloom, Shot.BLOSSOM);
        EgoDurability.wearMainHand(player); // a ranged shot never goes through a vanilla swing — wear it here

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 0.5f, 1.6f);
        world.playSound(eye, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.3f, 1.9f); // a soft floral chime, not a crack
        return true;
    }

    // ---- right-click routing --------------------------------------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (!matches(player.getInventory().getItemInMainHand())) return;

        Bloom bloom = blooms.computeIfAbsent(player.getUniqueId(), k -> new Bloom());
        if (sneaking) magnificentEnd(player, bloom);
        else fullBloom(player, bloom);
    }

    // ---- [Right-Click] Full Bloom ---------------------------------------------------

    /**
     * Three heavier, faster arrows a few ticks apart. Each is an ordinary {@link Shot#FULL_BLOOM} bolt,
     * so each one feeds [Passive] Unwithering Flower exactly like a Blossoming arrow does — the arrows
     * later in the burst therefore ride the petals the earlier ones just grew.
     */
    private void fullBloom(Player player, Bloom bloom) {
        long now = System.currentTimeMillis();
        if (now < bloom.fullBloomCd) {
            player.sendActionBar(EgoHud.cooldown("Full Bloom", bloom.fullBloomCd - now, FAINT));
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.4f, 1.7f);
            return;
        }
        bloom.fullBloomCd = now + FULL_BLOOM_COOLDOWN_MS;

        for (int i = 0; i < FULL_BLOOM_ARROWS; i++) {
            scheduleBloomArrow(player, i * FULL_BLOOM_GAP_TICKS);
        }
        EgoDurability.wearMainHand(player); // one point for the burst, not one per arrow
    }

    /**
     * Queue one arrow of the burst. The first goes now; the rest a few ticks out, each re-checking that
     * the crossbow is still in hand so a burst can't keep firing out of an empty grip.
     */
    private void scheduleBloomArrow(Player player, long delayTicks) {
        if (delayTicks <= 0L) {
            looseBloomArrow(player);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && matches(player.getInventory().getItemInMainHand())) {
                looseBloomArrow(player);
            }
        }, delayTicks);
    }

    /** One Full Bloom arrow, aimed down whatever line the wielder holds at the moment it leaves. */
    private void looseBloomArrow(Player player) {
        Bloom bloom = blooms.computeIfAbsent(player.getUniqueId(), k -> new Bloom());
        launch(player, bloom, Shot.FULL_BLOOM);

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 0.8f, 1.2f);
        world.playSound(eye, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.5f);
    }

    // ---- [Shift+Right-Click] Magnificent End ---------------------------------------

    /**
     * The finisher. Refused below {@value #PETAL_CAP} petals with a wilting cue; at full bloom it looses
     * one heavy bolt and spends every petal in the same breath.
     *
     * <p>The multiplier is snapshotted into the bolt at launch precisely because the petals are gone the
     * instant it leaves — the shot is paid for up front and carries what it bought.
     */
    private void magnificentEnd(Player player, Bloom bloom) {
        if (bloom.petals < PETAL_CAP) {
            player.sendActionBar(EgoHud.status(
                    "Magnificent End — " + bloom.petals + "/" + PETAL_CAP + " petals", WILT));
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.45f, 1.4f);
            return;
        }

        launch(player, bloom, Shot.MAGNIFICENT);
        bloom.petals = 0; // "Using this skill resets all stacks of your passive [Unwithering Flower]."
        EgoDurability.wearMainHand(player);

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.0f, 0.7f);
        world.playSound(eye, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.3f);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, eye, 14, 0.3, 0.3, 0.3, 0, BLOOM_FADE, FORCE_PARTICLES);
        player.sendActionBar(EgoHud.status("Magnificent End", CYAN));
    }

    // ---- [Passive] Unwithering Flower ------------------------------------------------

    /**
     * Every strike mends the wielder and grows one more petal (to {@link #PETAL_CAP}).
     *
     * <p>Fed by Blossoming Fragrance and Full Bloom strikes. Magnificent End deliberately does <b>not</b>
     * feed it: that skill is the passive's consumption, not its accrual — it resets the petals at cast, so
     * letting its own impact grow a fresh one (and heal) would be reading the spec against itself.
     */
    private void feedUnwitheringFlower(Player wielder) {
        Bloom bloom = blooms.computeIfAbsent(wielder.getUniqueId(), k -> new Bloom());
        healWielder(wielder);
        if (bloom.petals < PETAL_CAP) {
            bloom.petals++;
            if (bloom.petals == PETAL_CAP) fullBloomChime(wielder); // the moment Magnificent End unlocks
        }
    }

    /**
     * Mend the wielder by {@link #UNWITHERING_HEAL_PER_STRIKE}, <b>never past their max health</b> — the
     * cap is read off the live MAX_HEALTH attribute, so any other plugin's max-HP change is respected.
     */
    private void healWielder(Player player) {
        AttributeInstance maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        double current = player.getHealth();
        double healed = Math.min(maxHp, current + UNWITHERING_HEAL_PER_STRIKE);
        if (healed <= current) return; // already brimming — nothing to give

        player.setHealth(healed);
        World world = player.getWorld();
        Location chest = player.getLocation().add(0, 1.1, 0);
        world.spawnParticle(Particle.HEART, chest, 1, 0.25, 0.25, 0.25, 0);
        world.spawnParticle(Particle.DUST, chest, 3, 0.3, 0.35, 0.3, 0, PETAL_DUST);
    }

    /** The petal multiplier a shot rides: +1% weapon damage per petal, so x1.30 at full bloom. */
    private static double petalMultiplier(int petals) {
        return 1.0 + Math.min(petals, PETAL_CAP) * PETAL_DAMAGE_PER_STACK;
    }

    /**
     * Faint aroma: lay {@value #FAINT_AROMA_WEAKNESS_SECONDS} seconds of WEAKNESS on a struck body — the
     * scent of a forest that isn't there, sapping the strength out of whoever breathes it in.
     *
     * <p><b>Ready and complete, but not yet called from anywhere.</b> The payload is finished; the rule
     * that decides <em>when</em> it fires is blocked on a design ruling — see the TODO in
     * {@link Bolt#strike}. This is deliberate rather than an oversight: the effect is built as a
     * one-call helper so that wiring the trigger up once the rule is known is two lines, not a rewrite.
     *
     * <p><b>Note for whoever wires the trigger:</b> if the ruling needs a strike counter keyed by
     * <em>victim</em>, that map must be pruned <b>inline on every write</b> — drop entries whose stamp
     * has aged out, the way {@code GreenStemWeapon} prunes its per-target thorn cooldown. Mobs never fire
     * {@link #onQuit}, so a victim-keyed map that only cleans up on quit leaks one key per mob struck,
     * forever.
     */
    private void applyFaintAroma(LivingEntity victim) {
        victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, FAINT_AROMA_WEAKNESS_TICKS,
                FAINT_AROMA_WEAKNESS_AMP, false, true, true));

        World world = victim.getWorld();
        Location chest = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, chest, 12, 0.35, 0.45, 0.35, 0,
                BLOOM_FADE, FORCE_PARTICLES);
        world.playSound(chest, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 0.6f, 1.5f);
        world.playSound(chest, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.4f, 0.8f);
    }

    // ---- the lavender arrow ---------------------------------------------------------

    /** Loose one arrow of {@code shot}'s kind down the wielder's aim line, carrying the petals it bought. */
    private void launch(Player caster, Bloom bloom, Shot shot) {
        Bolt bolt = new Bolt(caster, shot, petalMultiplier(bloom.petals));
        bolt.runTaskTimer(plugin, 0L, 1L);
        inFlight.add(bolt);
    }

    /**
     * One lavender arrow in flight — a lightweight moving point, not an Arrow entity, so nothing can ever
     * orphan into the world. It advances along its heading each tick in {@value #BOLT_SUBSTEP}-block
     * sub-steps (anti-tunnel), drawing a lavender-to-cyan trail as it goes, and resolves on the first
     * solid block or the first living body it reaches. Always caps its lifetime and cancels itself.
     *
     * <p>The petal multiplier is a <b>snapshot taken at launch</b>, not a lookup at impact: Magnificent
     * End spends every petal the instant it fires, so a shot has to carry what it paid for.
     */
    private final class Bolt extends BukkitRunnable {

        private final UUID casterId;
        private final World world;
        private final Shot shot;
        private final Location point;      // the arrow's live position
        private final Vector dir;          // heading (unit)
        private final double multiplier;   // the petals this shot was bought with
        private int ticks = 0;
        private boolean done = false;

        Bolt(Player caster, Shot shot, double multiplier) {
            this.casterId = caster.getUniqueId();
            this.world = caster.getWorld();
            this.shot = shot;
            this.multiplier = multiplier;
            this.dir = caster.getEyeLocation().getDirection().normalize();
            this.point = caster.getEyeLocation().add(this.dir.clone().multiply(0.7)); // clear of the face
        }

        @Override
        public void run() {
            Player caster = plugin.getServer().getPlayer(casterId);
            if (caster == null || !caster.isOnline()) { stop(); return; }
            if (++ticks > shot.lifeTicks) { fizzle(); stop(); return; }

            // ONE broad-phase query per tick, then every sub-step tests against this snapshot. Querying
            // inside the sub-step loop would multiply the cost of the whole flight by the sub-step count.
            // The box is centred on this tick's start point and reaches everywhere the arrow can get to
            // during it: its full step, plus the contact radius, plus a block of slack.
            double reach = shot.speed + HIT_RADIUS + 1.0;
            List<LivingEntity> candidates = new ArrayList<>();
            for (Entity e : world.getNearbyEntities(point, reach, reach, reach)) {
                if (e.getUniqueId().equals(casterId)) continue;                 // never its own wielder
                if (e instanceof LivingEntity le && !le.isDead() && le.isValid()) candidates.add(le);
            }

            double moved = 0.0;
            while (moved < shot.speed) {
                double step = Math.min(BOLT_SUBSTEP, shot.speed - moved);
                point.add(dir.clone().multiply(step));

                // Only real walls stop it. Grass, flowers and fluids aren't solid, so a shot that grazes a
                // tuft flies on through rather than dying on it.
                if (point.getBlock().getType().isSolid()) {
                    land(caster);
                    stop();
                    return;
                }

                LivingEntity hit = nearest(candidates);
                if (hit != null) {
                    strike(caster, hit);
                    stop();
                    return;
                }

                trail();
                moved += step;
            }
        }

        /** The nearest live candidate within contact reach of the arrow's current point, else null. */
        private LivingEntity nearest(List<LivingEntity> candidates) {
            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (LivingEntity le : candidates) {
                if (le.isDead() || !le.isValid()) continue;
                double d = centre(le).subtract(point.toVector()).lengthSquared();
                if (d <= HIT_RADIUS * HIT_RADIUS && d < bestDist) { bestDist = d; best = le; }
            }
            return best;
        }

        /**
         * The arrow reaches a living body. The dull head doesn't pierce — it opens, and flowers come out.
         *
         * <p>Blossoming Fragrance can be stopped by a raised shield. Full Bloom and Magnificent End
         * instead break the guard: a blocking victim's shield is knocked out of action, and the arrow
         * lands anyway.
         */
        private void strike(Player caster, LivingEntity victim) {
            Location at = centre(victim).toLocation(world);

            // "[Blossoming Fragrance] Can be blocked by shields." A raised guard swallows it whole —
            // no damage, no petal, no heal: nothing was struck.
            if (shot == Shot.BLOSSOM && shieldFacing(victim)) {
                shieldClang(victim);
                return;
            }

            // "Any strike from Full Bloom & Magnificent end temporarily disables enemy shields if they
            // are blocking." The guard breaks; the arrow lands regardless.
            if (shot != Shot.BLOSSOM && victim instanceof Player blocker && blocker.isBlocking()) {
                breakGuard(blocker);
            }

            victim.damage(shot.damage * multiplier, caster); // routed through damage() so it stays cancellable
            flowerBurst(at);

            if (shot == Shot.MAGNIFICENT) {
                detonate(caster, at, victim); // "explodes upon contact"
                return;                       // the finisher consumes the passive — it never feeds it
            }

            feedUnwitheringFlower(caster);

            // TODO: BLOCKED — pending a design ruling. Deliberately NOT implemented rather than guessed.
            //
            // The spec reads: "Every 9th strike on any opponent you've been hitting triggers faint aroma
            // on their fourth strike, applying weakness to the enemy for 5 seconds."
            //
            // That names TWO different counters — a "9th strike" and a "fourth strike" — and does not say
            // what either one counts, or whose strikes they are. Open questions, all of which change the
            // implementation shape:
            //   * Is the 9th strike counted per-opponent, or across every opponent the wielder has hit?
            //     ("on any opponent you've been hitting" reads both ways.)
            //   * Whose "fourth strike" is the second counter? Four more of the wielder's strikes on that
            //     opponent after the 9th? The opponent's own fourth attack? A fourth strike by someone
            //     else entirely?
            //   * Does the 9th-strike count reset on trigger, or roll on every 9th forever?
            //   * Does a Full Bloom arrow advance the same counter as a Blossoming one? ("Each arrow
            //     triggers the same passives as blossoming fragrance" inherits this exact ambiguity, so
            //     Full Bloom is blocked on the same ruling — this branch is shared by both on purpose.)
            //
            // The payload is already done and waiting: once the rule is known, call
            //     applyFaintAroma(victim);
            // from here under whatever condition the ruling lands on. That is the whole wiring.
            //
            // If the ruling needs a strike counter keyed by VICTIM, prune it inline on every write —
            // mobs never fire onQuit, so a victim-keyed map leaks a key per mob struck, forever. See the
            // note on applyFaintAroma().
        }

        /** The arrow buries itself in a wall: flowers open out of the stone, and Magnificent End blooms. */
        private void land(Player caster) {
            Location at = point.clone();
            flowerBurst(at);
            if (shot == Shot.MAGNIFICENT) detonate(caster, at, null); // "explodes upon contact"
        }

        /**
         * [Magnificent End]'s bloom.
         *
         * <p><b>NON-GRIEF BY CONSTRUCTION.</b> This is a hand-rolled radius sweep, never
         * {@code World.createExplosion}: it breaks no blocks, lights no fire, and makes no permanent edit
         * of any kind. Every living body inside {@link #MAGNIFICENT_END_RADIUS} takes a linear-falloff
         * share of {@link #MAGNIFICENT_END_BLAST}, scaled by the petals that bought the shot, and every
         * point of it goes through {@code damage()} so it stays cancellable.
         *
         * <p>{@code directHit} — the body the arrow physically struck, or null on a wall hit — is
         * <b>exempt</b>. It has already eaten the far larger {@link #MAGNIFICENT_END_IMPACT}, and vanilla
         * invulnerability frames would swallow a second, smaller hit landed in the same tick anyway. The
         * blast is splash for everyone standing near it, and the exemption is honesty about that rather
         * than pretending the two numbers stack.
         */
        private void detonate(Player caster, Location at, LivingEntity directHit) {
            explodeFx(at);

            double r = MAGNIFICENT_END_RADIUS;
            for (Entity e : world.getNearbyEntities(at, r, r, r)) {
                if (e.getUniqueId().equals(casterId)) continue;                     // never its own wielder
                if (directHit != null && e.getUniqueId().equals(directHit.getUniqueId())) continue;
                if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;

                double dist = centre(le).toLocation(world).distance(at);
                if (dist > r) continue;                                             // the box is square, the bloom isn't
                double dmg = MAGNIFICENT_END_BLAST * (1.0 - dist / r) * multiplier; // linear falloff to zero
                if (dmg <= 0.0) continue;

                if (le instanceof Player blocker && blocker.isBlocking()) breakGuard(blocker);
                le.damage(dmg, caster);
            }
        }

        /** True when {@code victim} is a Player with a shield raised, roughly turned into the arrow. */
        private boolean shieldFacing(LivingEntity victim) {
            if (!(victim instanceof Player p) || !p.isBlocking()) return false;
            Vector look = p.getEyeLocation().getDirection();
            if (look.lengthSquared() < 1.0e-6) return false;
            // The arrow travels along dir; a blocker facing it looks back down that heading, so their look
            // opposes it. Perpendicular still counts as a rough face, hence a threshold just above zero.
            return look.normalize().dot(dir) < BLOCK_FACING_DOT;
        }

        // ---- presentation -----------------------------------------------------------

        /**
         * The arrow's trail: a violet core threaded with lavender, a cyan shimmer every other step, and the
         * odd mote of fragrance turning lavender-to-cyan as it hangs. Forced, because these arrows outrange
         * the client's ~32-block particle cull with room to spare.
         */
        private void trail() {
            world.spawnParticle(Particle.DUST, point, 1, 0.02, 0.02, 0.02, 0, STEM_DUST, FORCE_PARTICLES);
            world.spawnParticle(Particle.DUST, point, 1, 0.06, 0.06, 0.06, 0, LAVENDER_DUST, FORCE_PARTICLES);
            if ((ticks & 1) == 0) {
                world.spawnParticle(Particle.DUST, point, 1, 0.05, 0.05, 0.05, 0, CYAN_DUST, FORCE_PARTICLES);
            }
            if (ticks % 3 == 0) {
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point, 1, 0.05, 0.05, 0.05, 0,
                        BLOOM_FADE, FORCE_PARTICLES);
            }
            // The heavier shots carry more of the forest with them.
            if (shot != Shot.BLOSSOM && ticks % 2 == 0) {
                world.spawnParticle(Particle.END_ROD, point, 1, 0.03, 0.03, 0.03, 0.0, null, FORCE_PARTICLES);
            }
        }

        /** A shield swallows a Blossoming arrow: a clang and a puff of scattered pollen, no damage. */
        private void shieldClang(LivingEntity victim) {
            Location at = victim.getEyeLocation().add(dir.clone().multiply(-0.5)).add(0, -0.2, 0);
            world.playSound(at, Sound.ITEM_SHIELD_BLOCK, 0.9f, 1.1f);
            world.spawnParticle(Particle.CRIT, at, 8, 0.2, 0.2, 0.2, 0.08, null, FORCE_PARTICLES);
            world.spawnParticle(Particle.DUST, at, 6, 0.2, 0.25, 0.2, 0, LAVENDER_DUST, FORCE_PARTICLES);
        }

        /** Out of reach with nothing struck — the fragrance just thins out and is gone. */
        private void fizzle() {
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point, 5, 0.2, 0.2, 0.2, 0,
                    BLOOM_FADE, FORCE_PARTICLES);
            world.spawnParticle(Particle.DUST, point, 3, 0.15, 0.15, 0.15, 0, CYAN_DUST, FORCE_PARTICLES);
        }

        /** End this arrow exactly once: untrack it, then cancel its task. Safe to call twice. */
        private void stop() {
            if (done) return;
            done = true;
            inFlight.remove(this);
            cancel();
        }
    }

    // ---- shared presentation ---------------------------------------------------------

    /**
     * Vivid flowers sprout wherever the dull head strikes: a scatter of real flower items thrown as
     * {@link Particle#ITEM} data, a wash of lavender-to-cyan fragrance, and a soft green rustle. Nothing
     * is placed — the world is untouched the moment the particles fade.
     */
    private void flowerBurst(Location at) {
        World world = at.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // ITEM takes an ItemStack as its data — a DustOptions here would be a runtime crash.
        for (int i = 0; i < 3; i++) {
            ItemStack flower = FLOWERS[rng.nextInt(FLOWERS.length)];
            world.spawnParticle(Particle.ITEM, at, 3, 0.25, 0.3, 0.25, 0.06, flower, FORCE_PARTICLES);
        }
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 10, 0.3, 0.35, 0.3, 0,
                BLOOM_FADE, FORCE_PARTICLES);
        world.spawnParticle(Particle.DUST, at, 6, 0.3, 0.35, 0.3, 0, PETAL_DUST, FORCE_PARTICLES);
        world.spawnParticle(Particle.DUST, at, 4, 0.25, 0.3, 0.25, 0, CYAN_DUST, FORCE_PARTICLES);

        world.playSound(at, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 0.5f, 1.6f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.35f, 1.8f);
    }

    /**
     * [Magnificent End] opening: a flower the size of a house. A ring of petals sweeps outward at the
     * blast radius, the whole thing washed lavender-to-cyan, and it rings rather than booms — beautiful,
     * right up until you read the damage number.
     */
    private void explodeFx(Location at) {
        World world = at.getWorld();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0, null, FORCE_PARTICLES);
        world.spawnParticle(Particle.EXPLOSION, at, 6, 0.8, 0.8, 0.8, 0, null, FORCE_PARTICLES);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 60, 1.2, 1.2, 1.2, 0,
                BLOOM_FADE, FORCE_PARTICLES);

        // The petals of the bloom: a ring thrown out to the true blast radius, so the AoE reads honestly.
        final int petals = 28;
        for (int i = 0; i < petals; i++) {
            double ang = (Math.PI * 2.0 * i) / petals;
            for (double d = 0.8; d <= MAGNIFICENT_END_RADIUS; d += 0.9) {
                Location p = at.clone().add(Math.cos(ang) * d, 0.15 + d * 0.12, Math.sin(ang) * d);
                world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.05, 0.05, 0,
                        (i & 1) == 0 ? PETAL_DUST : CYAN_DUST, FORCE_PARTICLES);
            }
            ItemStack flower = FLOWERS[rng.nextInt(FLOWERS.length)];
            Location rim = at.clone().add(Math.cos(ang) * MAGNIFICENT_END_RADIUS, 0.4,
                    Math.sin(ang) * MAGNIFICENT_END_RADIUS);
            world.spawnParticle(Particle.ITEM, rim, 2, 0.2, 0.2, 0.2, 0.08, flower, FORCE_PARTICLES);
        }

        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.4f);   // pitched up: floral, not military
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.7f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 0.6f);
        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.6f);
    }

    /**
     * A guard broken by Full Bloom / Magnificent End. Putting the shield on cooldown is the Bukkit-level
     * equivalent of vanilla's shield-disable: it drops the raised block and locks the item out for
     * {@value #SHIELD_DISABLE_TICKS} ticks. Temporary — nothing is taken from them.
     */
    private void breakGuard(Player blocker) {
        blocker.setCooldown(Material.SHIELD, SHIELD_DISABLE_TICKS);

        World world = blocker.getWorld();
        Location at = blocker.getEyeLocation().add(0, -0.2, 0);
        world.playSound(at, Sound.ITEM_SHIELD_BREAK, 0.9f, 1.2f);
        world.spawnParticle(Particle.CRIT, at, 10, 0.25, 0.25, 0.25, 0.12, null, FORCE_PARTICLES);
        world.spawnParticle(Particle.DUST, at, 8, 0.25, 0.3, 0.25, 0, LAVENDER_DUST, FORCE_PARTICLES);
    }

    /** The chime as the thirtieth petal opens and [Magnificent End] comes off the leash. */
    private void fullBloomChime(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation().add(0, 1.0, 0);
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.6f);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 2.0f);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 16, 0.4, 0.6, 0.4, 0,
                BLOOM_FADE, FORCE_PARTICLES);
    }

    private static Vector centre(LivingEntity e) {
        return e.getLocation().add(0, e.getHeight() * 0.5, 0).toVector();
    }

    // ---- tick: hold-to-fire + the petal charge ---------------------------------------

    /**
     * Runs every 2 ticks while the wielder is engaged. Returns false the instant the crossbow is not in
     * the main hand — otherwise this player would tick forever.
     *
     * <p>While the swing-armed hold window is live, this drives the {@value #BLOSSOM_CADENCE_MS}ms
     * Blossoming Fragrance cadence; see {@link #HOLD_WINDOW_MS} for why the stream is driven from here
     * rather than from onSwing.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        if (!matches(player.getInventory().getItemInMainHand())) return false; // sheathed -> disengage

        Bloom bloom = blooms.computeIfAbsent(player.getUniqueId(), k -> new Bloom());

        long now = System.currentTimeMillis();
        if (now <= bloom.holdUntil) fireBlossom(player, bloom, now);

        renderBar(player, bloom);
        return true;
    }

    /**
     * The petal charge. The spec asks for it "in your hotbar"; the action bar is this roster's readout,
     * so it lives there: a gauge that fills as the petals grow, the live +N% it is worth, and the
     * unlock cue once [Magnificent End] is off the leash.
     */
    private void renderBar(Player player, Bloom bloom) {
        boolean bloomed = bloom.petals >= PETAL_CAP;
        TextColor fill = bloomed ? CYAN : LAVENDER;

        Component label = plain("Petals  " + bloom.petals + "/" + PETAL_CAP, COUNT)
                .append(plain("  +" + (bloom.petals * PETAL_DAMAGE_PER_STACK_PCT) + "%",
                        bloomed ? CYAN : FAINT));
        if (bloomed) {
            label = label.append(plain("  ", COUNT)).append(EgoHud.ready("Magnificent End", CYAN));
        }
        player.sendActionBar(EgoHud.gauge(fill, (double) bloom.petals / PETAL_CAP, label));
    }

    private static Component plain(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    // ---- item ------------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.FAINT_AROMA.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * The meta is stamped once, here, and never repainted — that is exactly why E.G.O weapons stay
     * enchantable. Nothing in this class touches item meta again after creation.
     */
    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.FAINT_AROMA.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.FAINT_AROMA);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore --------------------------------------------------------------------------

    /**
     * Built once at class-load and stamped in {@link #createItem}. The display name is the WEAPON
     * ("Faint Aroma"); the title line is the ABNORMALITY ("Alriune"). Never the other way round, and
     * never both.
     */
    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Faint Aroma",
            "Alriune",
            LAVENDER,
            CYAN,
            List.of(
                    "Even after the E.G.O was extracted, it",
                    "still carried the fragrance of the",
                    "archetype. Simply carrying it gives",
                    "the illusion that you're standing in a",
                    "forest in the middle of nowhere. The",
                    "arrowhead is dull and sprouts flowers",
                    "of vivid color wherever it strikes.",
                    "When flowers substitute all the desire",
                    "in everyone's hearts, this E.G.O. will",
                    "no longer be needed."
            ),
            List.of(
                    new EgoLore.Ability("[Passive] Unwithering Flower",
                            "Every strike mends you and grows a",
                            "petal. Each petal adds +1% damage, up",
                            "to 30. At 30 petals, Magnificent End",
                            "unlocks."),
                    new EgoLore.Ability("[Left Click] Blossoming Fragrance",
                            "A lavender arrow every 1.5s. A raised",
                            "shield blocks it."),
                    new EgoLore.Ability("[Right-Click] Full Bloom",
                            "Three heavier, faster arrows in rapid",
                            "succession. Breaks a raised guard."),
                    new EgoLore.Ability("[Shift+Right-Click] Magnificent End",
                            "A single bolt that blooms on contact",
                            "for devastating damage. Spends every",
                            "petal.")
            ));

    // ---- lifecycle ---------------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        blooms.remove(id); // petals, cadence gates and cooldowns all go with them
        // In-flight arrows need no reaping here: each Bolt checks its caster is still online every tick
        // and stops itself. Nothing of this weapon exists in the world for them to leave behind.
    }

    @Override
    public void onDisable() {
        // Cancel every arrow still in the air. Iterate a copy — stop() untracks as it goes.
        for (Bolt bolt : new ArrayList<>(inFlight)) bolt.stop();
        inFlight.clear();
        blooms.clear();
        // No entity sweep: this weapon spawns no entities at all. Its arrows are runnables drawing
        // particles, so there is nothing that can survive a reload or litter the world.
    }
}
