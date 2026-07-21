package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.EgoWeapon;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoDurability;
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
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Soda — an E.G.O Equipment reworked into an ally-side HEALER.
 *
 * <p>A grape-purple pistol that vents carbonated pressure. It never helps the person holding it: everything
 * it does is aimed <b>outward</b>, at friends caught in the spray.
 *
 * <ul>
 *   <li><b>Sneak + right-click</b> — <i>charge up</i>. Keep sneaking and the can shakes and fizzes, the
 *       pitch rising as pressure builds; the action bar shows a purple charge gauge ("Fizzing" → "Charged").
 *       A full charge takes {@value #CHARGE_MS}ms — a couple of seconds — because the payoff is strong.
 *       Let go of sneak before it tops out and the pressure fizzles harmlessly away.</li>
 *   <li><b>Right-click</b> (not sneaking) — <i>fire the fizz shot</i>, but only if a charge is banked.
 *       No charge means a soft, flat fizzle and nothing else. Firing spends the charge and wears the can a
 *       little.</li>
 * </ul>
 *
 * <p>The fizz shot erupts a fat Coke-and-Mentos fountain of purple soda (see {@link SodaEruption}). Any
 * <b>ally</b> caught in the fan — any other player, and any non-hostile creature — is drenched and
 * <b>healed</b> as hard as an Instant Health I potion (~4 HP), plus a short Speed I and Regeneration. The
 * spray never touches the wielder: they are explicitly excluded from the cone, so a Soda-bearer can only
 * ever heal <i>others</i>.
 *
 * <p>State is a single in-memory UUID-&gt;charge map, cleared on quit. The only scheduled work is the brief
 * self-cancelling eruption animation on each shot.
 */
public final class SodaWeapon implements EgoWeapon {

    /** The base E.G.O tooltip, exposed so the enchant renderer can append applied enchants beneath it. */
    @Override public EgoLore.Tooltip egoTooltip() { return TOOLTIP; }

    private final Reliquary plugin;
    private final NamespacedKey key;

    /** Wielder -> their charge state. The only state this weapon keeps. */
    private final Map<UUID, Charge> charges = new HashMap<>();

    // Tuning — a slow-charge, strong-payoff support tool.
    private static final long   CHARGE_MS   = 2200L; // sneak-hold time to a full charge
    private static final double SPRAY_RANGE = 9.0;   // reach of the healing fan
    private static final double CONE_COS    = 0.55;  // ~57° half-angle — a wide, fat spray cone

    // Ally buffs on a hit (never applied to the wielder).
    private static final int HEAL_AMP    = 0;        // Instant Health I ≈ 4 HP
    private static final int SPEED_TICKS = 120;      // Speed I, 6s
    private static final int SPEED_AMP   = 0;
    private static final int REGEN_TICKS = 100;      // Regeneration I, 5s
    private static final int REGEN_AMP   = 0;

    // Palette — refreshing grape/blueberry plastic.
    private static final TextColor NAME  = TextColor.color(0x9B6BE8); // grape purple (name, charge gauge)
    private static final TextColor FIZZ  = TextColor.color(0x5FB8FF); // carbonated blue accent
    private static final TextColor FAINT = TextColor.color(0x7A7A90); // conditions / controls

    private static final Color PURPLE = Color.fromRGB(0x9B, 0x6B, 0xE8);
    private static final Color LILAC  = Color.fromRGB(0xC9, 0xA6, 0xFF);
    private static final Color BLUE   = Color.fromRGB(0x5F, 0xB8, 0xFF);
    private static final Particle.DustOptions SPRAY  = new Particle.DustOptions(PURPLE, 0.9f);
    private static final Particle.DustOptions FOAM   = new Particle.DustOptions(LILAC, 0.8f);
    private static final Particle.DustOptions BUBBLE = new Particle.DustOptions(BLUE, 0.7f);

    public SodaWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "soda");
    }

    @Override
    public String id() {
        return "soda";
    }

    /** Per-wielder pressure state: whether it's actively fizzing up, whether a shot is banked, timers. */
    private static final class Charge {
        boolean charging = false; // holding sneak, pressure rising
        boolean charged  = false; // a full shot is banked and ready to fire
        long    start    = 0L;    // epoch-millis the current charge began
        int     fxFrame  = 0;     // throttle for the rising-fizz cue
    }

    // ---- input --------------------------------------------------------------------

    @Override
    public void onInteract(Player player, boolean sneaking) {
        Charge c = charges.computeIfAbsent(player.getUniqueId(), k -> new Charge());

        if (sneaking) {
            // Sneak + right-click = begin charging (unless we're already charging or already loaded).
            if (c.charged) {                 // already have a shot banked — just reaffirm it
                player.sendActionBar(EgoHud.gauge(NAME, 1.0, EgoHud.status("Charged", NAME)));
                return;
            }
            if (c.charging) return;          // already fizzing up
            c.charging = true;
            c.start = System.currentTimeMillis();
            c.fxFrame = 0;
            startChargeFx(player);
            return;
        }

        // Plain right-click = fire the fizz shot, but only if a charge is banked.
        if (c.charged) {
            c.charged = false;
            fireFizz(player);
            EgoDurability.wearMainHand(player); // mild — once per shot
        } else {
            emptyFizzle(player);
        }
    }

    @Override
    public boolean onTick(Player player, long tick) {
        boolean held = matches(player.getInventory().getItemInMainHand());
        Charge c = charges.get(player.getUniqueId());

        if (!held) {
            // Stowed the can mid-charge: the pressure escapes and is lost.
            if (c != null && c.charging) {
                c.charging = false;
                fizzleOut(player);
            }
            return false; // idle and not held — stop ticking
        }

        if (c == null) return true; // held, nothing brewing yet — keep ticking to catch a charge

        if (c.charging) {
            // Charging only continues while sneak is held; release early and it fizzles away.
            if (!player.isSneaking()) {
                c.charging = false;
                fizzleOut(player);
                return true;
            }
            double frac = Math.min(1.0, (double) (System.currentTimeMillis() - c.start) / CHARGE_MS);
            chargingFx(player, frac, c);
            if (frac >= 1.0) {
                c.charging = false;
                c.charged = true;
                readyFx(player);
                player.sendActionBar(EgoHud.gauge(NAME, 1.0, EgoHud.status("Charged", NAME)));
            } else {
                player.sendActionBar(EgoHud.gauge(NAME, frac, EgoHud.status("Fizzing", FIZZ)));
            }
            return true;
        }

        if (c.charged) {
            player.sendActionBar(EgoHud.gauge(NAME, 1.0, EgoHud.status("Charged", NAME)));
        }
        return true;
    }

    // ---- fire ---------------------------------------------------------------------

    /** Vent the banked charge: erupt the soda fountain and drench every ally in the fan. */
    private void fireFizz(Player player) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location muzzle = eye.clone().add(dir.clone().multiply(0.8));

        popFizzSound(world, muzzle);
        new SodaEruption(plugin, world, muzzle, dir).start();
        healAllies(player, eye, dir);
    }

    /**
     * Drench every ally in the spray cone. An ally is any living entity that is <b>not</b> the wielder and
     * <b>not</b> a hostile {@link Enemy} — so other players and passive/tamed creatures qualify, while
     * zombies, skeletons, the Ender Dragon and every other hostile are ignored. ({@code Enemy} is the wider
     * net: it catches bosses like the dragon that are hostile without being {@code Monster}.) The wielder is
     * excluded by UUID, so Soda can never heal or buff its own bearer even standing in its own spray.
     */
    private void healAllies(Player wielder, Location eye, Vector dir) {
        World world = wielder.getWorld();
        UUID self = wielder.getUniqueId();
        for (Entity e : world.getNearbyEntities(eye, SPRAY_RANGE, SPRAY_RANGE, SPRAY_RANGE)) {
            if (!(e instanceof LivingEntity ally)) continue;
            if (ally.getUniqueId().equals(self)) continue;   // NEVER the wielder
            if (ally instanceof Enemy) continue;             // hostiles are not allies

            Vector to = ally.getLocation().add(0, ally.getHeight() * 0.5, 0).toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist < 0.05 || dist > SPRAY_RANGE) continue;
            if (dir.dot(to.multiply(1.0 / dist)) < CONE_COS) continue; // outside the fan

            healOne(ally);
        }
    }

    /** Heal + buff a single ally, and splash a cosmetic burst of soda over them (no real damage is dealt). */
    private void healOne(LivingEntity ally) {
        // Instant Health I ≈ 4 HP; the effect brings its own heal-sparkle flash. Then a short kit of support.
        ally.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, HEAL_AMP, true, true));
        ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, SPEED_TICKS, SPEED_AMP, true, true));
        ally.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, REGEN_TICKS, REGEN_AMP, true, true));

        World world = ally.getWorld();
        Location at = ally.getLocation().add(0, ally.getHeight() * 0.6, 0);
        world.spawnParticle(Particle.DUST, at, 14, 0.35, 0.5, 0.35, 0, SPRAY);
        world.spawnParticle(Particle.DUST, at, 8, 0.32, 0.46, 0.32, 0, FOAM);
        world.spawnParticle(Particle.SPLASH, at, 10, 0.32, 0.4, 0.32, 0.06);
        world.spawnParticle(Particle.HEART, at, 3, 0.28, 0.4, 0.28, 0.0);
        world.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.6f);
        world.playSound(at, Sound.ENTITY_GENERIC_DRINK, 0.4f, 1.7f);
    }

    // ---- presentation --------------------------------------------------------------

    /** The shake that kicks off a charge — a low, wet gurgle building in the can. */
    private void startChargeFx(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation();
        world.playSound(at, Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 0.6f);
        world.playSound(at, Sound.ENTITY_GENERIC_SPLASH, 0.25f, 0.7f);
    }

    /** While charging: a rising-pitch fizz and a curl of grape bubbles at the hand, throttled per frame. */
    private void chargingFx(Player player, double frac, Charge c) {
        World world = player.getWorld();
        Location hand = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.6));
        hand.subtract(0, 0.25, 0);

        if ((c.fxFrame++ & 1) == 0) {
            float pitch = 0.6f + (float) frac * 1.2f; // pressure rising
            world.playSound(hand, Sound.BLOCK_FIRE_EXTINGUISH, 0.22f, pitch);
        }
        int n = 2 + (int) (frac * 3);
        world.spawnParticle(Particle.DUST, hand, n, 0.10, 0.10, 0.10, 0, SPRAY);
        world.spawnParticle(Particle.BUBBLE_POP, hand, 2, 0.08, 0.08, 0.08, 0.01);
    }

    /** The satisfying "topped-off" cue when a charge finishes — a bright little pop. */
    private void readyFx(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation();
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f, 1.9f);
        world.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.25f, 2.0f);
        world.spawnParticle(Particle.DUST, player.getEyeLocation(), 8, 0.2, 0.2, 0.2, 0, FOAM);
    }

    /** The big pop/fizz of the shot itself — cork pop, gush, and a bubbling wash. */
    private void popFizzSound(World world, Location muzzle) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        world.playSound(muzzle, Sound.ENTITY_GENERIC_EXPLODE, 0.45f, 1.7f + rng.nextFloat() * 0.2f); // muffled pop
        world.playSound(muzzle, Sound.BLOCK_FIRE_EXTINGUISH, 0.9f, 1.1f);                              // big fizz
        world.playSound(muzzle, Sound.ENTITY_PLAYER_SPLASH, 0.7f, 1.2f);                               // gush
        world.playSound(muzzle, Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 1.4f);                           // bubbling
    }

    /** No charge banked: a soft, flat fizzle and a status nudge to charge first. */
    private void emptyFizzle(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.3f, 0.5f);
        player.sendActionBar(EgoHud.status("Flat — hold sneak to charge the fizz", FAINT));
    }

    /** Charge abandoned (released sneak early or stowed the can): the pressure escapes, lost. */
    private void fizzleOut(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.35f, 0.45f);
        player.sendActionBar(EgoHud.status("Fizzled out", FAINT));
    }

    // ---- item ----------------------------------------------------------------------

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.SODA.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.SODA.material());
        ItemMeta meta = item.getItemMeta();
        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.SODA);
        item.setItemMeta(meta);
        return item;
    }

    // ---- lore ----------------------------------------------------------------------

    // Primary stays the grape purple the name has always been. Secondary is FIZZ — the carbonated blue
    // already in this weapon's own palette, and the accent the charge gauge reads "Fizzing" in — so the
    // Abnormality title line and the action bar agree on the same blue. The old block set that title line
    // in NAME; under the shared format the title needs a colour of its own, and grape-on-grape would not
    // have been one. Nothing here is invented: both colours were already in the palette above.

    private static final EgoLore.Tooltip TOOLTIP = EgoLore.egoLore(
            "Soda",
            "Opened Can of WellCheers",
            NAME,
            FIZZ,
            List.of(
                    "Extracted by an employee who",
                    "particularly loved shrimp.",
                    "A pistol in a refreshing purple;",
                    "in use, a faint scent of grapes."
            ),
            List.of(
                    new EgoLore.Ability("[Shift + Right-click] Charge Fizz",
                            "Start shaking the can. Keep sneaking",
                            "for 2.2 seconds to bank a shot.",
                            "Release sneak or stow the can early",
                            "and the pressure is lost."),
                    new EgoLore.Ability("[Right Click] Fizz Shot",
                            "Spends a banked charge to erupt a",
                            "9-block spray cone. Every ally in it",
                            "is healed (Instant Health I), plus",
                            "Speed I for 6s and Regeneration I",
                            "for 5s. Hostile mobs are ignored,",
                            "and the spray never touches you.")
            ));

    // ---- lifecycle -----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        charges.remove(id);
    }
}
