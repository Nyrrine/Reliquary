package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import com.nyrrine.reliquary.ego.EgoHud;
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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Regret — "Forsaken Murderer" (Lobotomy Corp E.G.O, TETH). A crude, ponderous HEAVY HAMMER.
 *
 * <p>A melee E.G.O Equipment that rides the vanilla MACE swing (never cancelled), but it is <b>not</b>
 * played as a mace. Its soul is a slowly-recharging heavy hit: while the hammer is HELD a per-wielder
 * CHARGE climbs from empty to full over about {@value #CHARGE_MS} ms (~4.5 s), driven from
 * {@link #onTick}. On a landed melee hit ({@link #onHit}) the final damage is written directly from the
 * current charge via {@code event.setDamage(...)} — a crushing {@value #DMG_MAX} at full (on par with a
 * Sharpness VII netherite sword), sloping down to a {@value #DMG_FLOOR} floor at empty — and then the
 * charge RESETS to zero, so the wielder must wind up again before the next heavy blow.
 *
 * <p>Because the blow's damage is fully driven by {@code setDamage} here, the vanilla MACE fall-slam
 * bonus is neutralized (the final number is overwritten no matter how the blow landed) — it deliberately
 * cannot be abused as a mace. The item stays MACE material so it reads and swings like a hammer; the
 * {@link EgoModels#REGRET} base attack number is irrelevant since {@code onHit} overwrites the damage.
 *
 * <p>The wielder reads the charge on the action bar through {@link EgoHud#gauge} ({@code Hammer NN%},
 * or {@code Hammer — ready} at full). A subtle cue fires once the hammer reaches full charge; a landed
 * charged blow lands a weighty thud and a data-safe impact shockwave scaled to how hard it was wound up.
 * Normal melee wears the weapon through the vanilla path. The only per-player state is the charge clock
 * and the "ready" flag, both dropped on quit.
 */
public final class RegretWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Regret. */
    private final NamespacedKey key;

    // Tuning — a slow, heavy hammer you wind up between swings.
    private static final long   CHARGE_MS = 4_500L; // empty -> full recharge time while held (~4.5s)
    private static final double DMG_FLOOR = 3.0;    // damage at empty charge — a limp tap
    private static final double DMG_MAX   = 12.5;   // damage at full charge — ~Sharpness VII netherite sword

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

        meta.displayName(Component.text("Regret").color(IRON).decoration(TextDecoration.ITALIC, false));
        meta.lore(LORE);
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

    /** The wind-up: a laboured heave as the heavy head is hauled back for the swing. */
    @Override
    public void onSwing(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        player.getWorld().playSound(player.getLocation(),
                Sound.ENTITY_WARDEN_HEARTBEAT, 0.55f, 0.55f + rng.nextFloat() * 0.1f);
    }

    /**
     * A landed blow. The final damage is written straight from the current charge — a crushing hit at
     * full, a limp tap at empty — via {@code event.setDamage} (never {@code victim.damage()}, which would
     * re-enter this dispatch). This overwrites the vanilla mace number entirely, so the fall-slam bonus
     * is neutralized. After the hit the charge resets: the wielder must wind up again.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID id = attacker.getUniqueId();
        double charge = charge(id);
        double damage = DMG_FLOOR + charge * (DMG_MAX - DMG_FLOOR);
        event.setDamage(damage);

        impact(victim.getLocation(), charge);

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

    /** A subtle cue the instant the hammer reaches full charge — a soft chime and a little puff. */
    private void readyCue(Player player) {
        Location at = player.getLocation();
        World world = player.getWorld();
        world.playSound(at, Sound.BLOCK_ANVIL_USE, 0.45f, 1.5f);
        world.spawnParticle(Particle.DUST, at.clone().add(0, 1.0, 0), 6, 0.25, 0.35, 0.25, 0, DUST);
    }

    /**
     * The impact of a landed blow — a weighty thud and a data-safe shockwave, both scaled to how hard the
     * hammer was wound up. At high charge it also erupts a small explosion burst to sell the heavy hit.
     */
    private void impact(Location at, double charge) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = at.getWorld();

        // A heavy metal CLANG — anvil + iron-golem, pitched down; louder the harder it landed.
        float vol = 0.6f + (float) charge * 0.6f;
        world.playSound(at, Sound.BLOCK_ANVIL_LAND, vol, 0.5f + rng.nextFloat() * 0.15f);
        world.playSound(at, Sound.ENTITY_IRON_GOLEM_ATTACK, vol, 0.5f + rng.nextFloat() * 0.15f);

        // A low dust shockwave ring at the struck body's feet — wider the harder it landed.
        shockwave(at, charge);

        // A crushing, well-charged blow erupts a small impact burst (Particle.EXPLOSION needs no data).
        if (charge >= 0.6) {
            world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.7f * (float) charge, 0.6f);
            world.spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.4, 0), 1);
        }
    }

    /** A low dust shockwave ring at the struck body's feet, sized by the charge behind the blow. */
    private void shockwave(Location impact, double charge) {
        World world = impact.getWorld();
        Location feet = impact.clone();
        final int points = 18;
        final double radius = 1.2 + charge * 1.2;
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            Location p = feet.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius);
            world.spawnParticle(Particle.DUST, p, 1, 0.05, 0.02, 0.05, 0, DUST);
        }
        // A short puff of kicked-up grit at the very centre of the slam.
        world.spawnParticle(Particle.DUST, feet.clone().add(0, 0.2, 0), 6, 0.25, 0.1, 0.25, 0, DUST);
    }

    // ---- lore ----------------------------------------------------------------------

    private static final TextColor IRON  = TextColor.color(0x8A8F96); // name / grim iron-gray
    private static final TextColor STEEL = TextColor.color(0x9AA0A8); // base description
    private static final TextColor HEAVY = TextColor.color(0x6E7278); // crush / weight accent
    private static final TextColor FAINT = TextColor.color(0x63666C); // conditions / controls

    private static final Color GRIT = Color.fromRGB(0x70, 0x74, 0x7A); // the shockwave grit — cold iron-gray
    private static final Particle.DustOptions DUST = new Particle.DustOptions(GRIT, 1.4f);

    /** A small non-italic action-bar label in the hammer's iron tone. */
    private static Component label(String text) {
        return Component.text(text).color(IRON).decoration(TextDecoration.ITALIC, false);
    }

    private record Seg(String text, TextColor color, boolean italic) {
        Seg(String text, TextColor color) { this(text, color, false); }
    }

    private static final List<List<Seg>> LORE_SRC = List.of(
        List.of(new Seg("Forsaken Murderer", IRON)),
        List.of(),
        List.of(new Seg("A ", STEEL), new Seg("heavy", HEAVY), new Seg(" maul left behind by", STEEL)),
        List.of(new Seg("the one the world forgot.", STEEL)),
        List.of(),
        List.of(new Seg("How to use:", FAINT)),
        List.of(new Seg("Hold to charge a heavy hit —", FAINT)),
        List.of(new Seg("shown on the bar. Strike at", FAINT)),
        List.of(new Seg("full for a crushing blow.", FAINT)),
        List.of(),
        List.of(new Seg("E.G.O Equipment", FAINT, true))
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
