package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
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
 * Wrist Cutter — Bloodbath. A TETH-tier Lobotomy Corp E.G.O weapon: a thin, keen blade wrought for
 * fast, shallow cuts rather than heavy blows.
 *
 * <p>A plain melee weapon — the vanilla IRON_SWORD swing deals its normal damage, uncancelled. The
 * gimmick lives in {@link #onHit}: every landed cut deepens an accumulating <b>bleed</b> on the
 * victim by {@link #STACKS_PER_HIT} stack(s), capped at {@link #MAX_STACKS}. Stacks do not each fire
 * their own timer — the victim carries a single per-second drain task that removes one stack per
 * second and deals that stack's small {@link #BLEED_TICK_DAMAGE}. So while the wielder keeps
 * skirmishing (landing hits), stacks build faster than the once-a-second drain can burn them; once
 * the hits stop, the accumulated wound bleeds out slowly — a long, gradual drain of {@code stacks}
 * seconds. The cap keeps a big skirmish's total ({@code MAX_STACKS * BLEED_TICK_DAMAGE}) inside the
 * netherite band: threatening, but a slow drain rather than an instant kill.
 *
 * <p>The drain re-enters {@link #onHit} (its tick calls {@code victim.damage(..., attacker)}), so a
 * re-entrancy flag ({@link #ticking}) makes onHit refuse to add a stack from inside its own tick; a
 * fresh melee strike still adds normally. Each victim carries at most one bleed state — a new cut
 * just bumps the stack counter on the existing state, never spawns a second task. State is cleared
 * when the victim dies/goes invalid, on quit, and on {@link #onDisable}.
 */
public final class WristCutterWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Wrist Cutter. */
    private final NamespacedKey key;

    /** Victim UUID -> their live bleed state (stack counter + per-second drain task). At most one per victim. */
    private final Map<UUID, WristCutterBleed> bleeds = new HashMap<>();

    /** Victims currently taking a bleed tick — guards {@link #onHit} against adding a stack from its own tick. */
    private final Set<UUID> ticking = new HashSet<>();

    // Bleed tuning — a stack accumulates per hit and drains one-per-second, so totals stay in the
    // netherite band even after a heavy skirmish (worst case MAX_STACKS * BLEED_TICK_DAMAGE).
    private static final double BLEED_TICK_DAMAGE = 1.0;  // one drained stack = half a heart
    private static final long   BLEED_PERIOD_TICKS = 20;  // drain one stack per second
    private static final int    STACKS_PER_HIT = 1;       // each landed cut deepens the wound by one stack
    private static final int    MAX_STACKS = 6;           // cap: <=6.0 accumulated, bled out over <=6s

    public WristCutterWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "wrist_cutter");
    }

    @Override
    public String id() {
        return "wrist_cutter";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.WRIST_CUTTER.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.WRIST_CUTTER.material());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Wrist Cutter").color(NAME).decoration(TextDecoration.ITALIC, false));
        meta.lore(LORE);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.WRIST_CUTTER);

        item.setItemMeta(meta);
        return item;
    }

    // ---- gimmick: fast light cuts that bleed --------------------------------------

    /**
     * Melee hit landed. Vanilla sword damage is left untouched; we add a bleed stack to the victim
     * (starting their drain task if this is the first stack) and flash the slice SFX/VFX. When the hit
     * is our own bleed tick re-entering (flagged in {@link #ticking}) we do nothing — the drain must
     * not feed itself stacks into a perpetual loop.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        if (ticking.contains(victim.getUniqueId())) return; // our own bleed tick — don't add a stack from within

        sliceFx(attacker, victim);
        addStack(attacker, victim);
    }

    /** Deepen the victim's bleed by one stack, spinning up their per-second drain task on the first stack. */
    private void addStack(Player attacker, LivingEntity victim) {
        UUID vid = victim.getUniqueId();
        WristCutterBleed bleed = bleeds.get(vid);
        if (bleed == null) {
            bleed = new WristCutterBleed(attacker.getUniqueId(), victim);
            bleeds.put(vid, bleed);
            bleed.runTaskTimer(plugin, BLEED_PERIOD_TICKS, BLEED_PERIOD_TICKS);
        } else {
            bleed.attackerId = attacker.getUniqueId(); // latest cutter takes the credit for the drain
        }
        bleed.addStacks();
    }

    /**
     * The accumulating wound. Each second it drains one stack and deals that stack's small damage
     * (attributed to the latest cutter while they remain online), trailing a thin blood mist on the
     * body each tick. It keeps running while stacks remain — a big skirmish's pile bleeds out slowly,
     * one stack per second — and ends once drained (or on death/invalid). Re-entrancy into
     * {@link #onHit} is fenced by the {@link #ticking} flag around the damage.
     */
    private final class WristCutterBleed extends BukkitRunnable {
        private UUID attackerId;
        private final LivingEntity victim;
        private int stacks = 0;

        private WristCutterBleed(UUID attackerId, LivingEntity victim) {
            this.attackerId = attackerId;
            this.victim = victim;
        }

        /** Add a hit's worth of stacks, clamped to the cap so accumulated bleed can't run away. */
        private void addStacks() {
            stacks = Math.min(MAX_STACKS, stacks + STACKS_PER_HIT);
        }

        @Override
        public void run() {
            if (stacks <= 0 || victim.isDead() || !victim.isValid()) {
                finish();
                return;
            }
            stacks--; // drain one stack this second

            UUID vid = victim.getUniqueId();
            Player attacker = plugin.getServer().getPlayer(attackerId);

            // The bleed is a pure damage-over-time, not a shove: capture the victim's velocity right
            // before the tick and restore it right after, so the damage event's knockback impulse is
            // undone. (The initial melee strike's own vanilla knockback happens on the real hit, not
            // here, and is left untouched.)
            Vector preVel = victim.getVelocity();

            ticking.add(vid); // fence: victim.damage below re-enters onHit; don't let it add a stack
            try {
                if (attacker != null && !attacker.equals(victim)) {
                    victim.damage(BLEED_TICK_DAMAGE, attacker);
                } else {
                    victim.damage(BLEED_TICK_DAMAGE);
                }
            } finally {
                ticking.remove(vid);
                victim.setVelocity(preVel); // undo the bleed tick's knockback
            }

            bloodTrail(victim);

            if (stacks <= 0) finish();
        }

        private void finish() {
            cancel();
            bleeds.remove(victim.getUniqueId());
        }
    }

    // ---- SFX / VFX ----------------------------------------------------------------

    /** A sharp, thin *shnk* slash and a spray of red droplets where the cut lands. */
    private void sliceFx(Player attacker, LivingEntity victim) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = victim.getWorld();
        Location hit = victim.getLocation().add(0, 1.0, 0);

        // Sharp, thin sword-slash — pitch-jittered high so rapid cuts stay crisp.
        float pitch = 1.6f + rng.nextFloat() * 0.35f; // ~1.6 - 1.95
        world.playSound(hit, Sound.ITEM_TRIDENT_RETURN, 0.7f, pitch);

        // Red droplet spray bursting off the wound.
        world.spawnParticle(Particle.DUST, hit, 8, 0.22, 0.22, 0.22, 0,
                new Particle.DustOptions(BLOOD, 1.0f));
    }

    /** A thin trail of red mist on the bleeding body plus a faint wet drip — per drain tick, low & short. */
    private void bloodTrail(LivingEntity victim) {
        Location body = victim.getLocation().add(0, 1.0, 0);
        // Small red dust with a touch of downward drift, like blood weeping off the wound.
        victim.getWorld().spawnParticle(Particle.DUST, body, 3, 0.18, 0.30, 0.18, 0,
                new Particle.DustOptions(BLOOD, 0.8f));
        // Faint squelch of the wound weeping — quiet & low-pitched so a long bleed-out never spams.
        victim.getWorld().playSound(body, Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.25f, 0.7f);
    }

    // ---- lifecycle ----------------------------------------------------------------

    @Override
    public void onQuit(UUID id) {
        // If the quitter was a bleeding victim, end their wound; drop any re-entrancy flag they held.
        WristCutterBleed bleed = bleeds.remove(id);
        if (bleed != null) bleed.cancel();
        ticking.remove(id);
    }

    @Override
    public void onDisable() {
        for (WristCutterBleed bleed : bleeds.values()) bleed.cancel();
        bleeds.clear();
        ticking.clear();
    }

    // ---- lore ---------------------------------------------------------------------

    private static final TextColor NAME  = TextColor.color(0xB3121B); // name — deep blood red
    private static final Color     BLOOD = Color.fromRGB(0x8A, 0x0B, 0x0B); // droplet / trail dust
    private static final TextColor PALE  = TextColor.color(0xD8C6C6); // base description
    private static final TextColor GORE  = TextColor.color(0xC0392B); // wound / bleed accent
    private static final TextColor FAINT = TextColor.color(0x8C7A7A); // conditions
    private static final TextColor QUOTE = TextColor.color(0x746262); // epithet

    private record Seg(String text, TextColor color, boolean italic) {
        Seg(String text, TextColor color) { this(text, color, false); }
    }

    private static final List<List<Seg>> LORE_SRC = List.of(
        List.of(new Seg("Bloodbath", NAME)),
        List.of(),
        List.of(new Seg("It bears bloodstains soaked in", PALE)),
        List.of(new Seg("ever-lasting red. Its keen blade", PALE)),
        List.of(new Seg("leaves a ", PALE), new Seg("wound that never heals", GORE), new Seg(".", PALE)),
        List.of(new Seg("How to use:", FAINT)),
        List.of(new Seg("Each cut stacks bleed on the foe.", FAINT)),
        List.of(new Seg("Stacks bleed out slowly over time.", FAINT)),
        List.of(),
        List.of(new Seg("\"You know, after joining Lobotomy", QUOTE, true)),
        List.of(new Seg("Corporation I really feel like I have", QUOTE, true)),
        List.of(new Seg("my life back together.\"", QUOTE, true))
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
