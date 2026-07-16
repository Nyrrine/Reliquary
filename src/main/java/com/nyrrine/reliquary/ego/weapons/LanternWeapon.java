package com.nyrrine.reliquary.ego.weapons;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lantern — "Meat Lantern" (Lobotomy Corp E.G.O, TETH). A heavy, slow-swung lump of glowing offal.
 *
 * <p>The fantasy is an anglerfish's lure grown to arm's length: a luminous organ on a haft that throws a
 * warm, wet, inviting light — and a scatter of teeth along its length for whatever the light brings close.
 * It is patient rather than fast. It glows, it waits, and then it eats.
 *
 * <p><b>[Passive] Om Nom Nom.</b> Every {@value #DIGEST_EVERY}rd instance of damage the wielder takes is
 * swallowed rather than simply suffered: {@value #DIGEST_HEAL_PERCENT}% of that instance's damage is
 * returned as health {@link #DIGEST_DELAY_TICKS ticks} later (three seconds — the lantern chews slowly).
 * The tally is per-wielder and fed by {@link #onDamaged}, which counts every cause the wielder suffers
 * while the Lantern is drawn; see it for the two blows that don't count.
 *
 * <p><b>[Left Click] I Shall Nibble Thee!</b> Ordinary slow strikes ride the vanilla swing untouched, but
 * every {@value #NIBBLE_EVERY}th landed strike is a real bite: the teeth close, and the wielder is healed
 * for the <i>full</i> damage that strike dealt ({@code event.getFinalDamage()} — what the victim actually
 * takes after their armour, so a well-armoured foe feeds the lantern less). The strike counter rides the
 * action bar as pips so the wielder can see the bite coming.
 *
 * <p><b>On the fallback item.</b> The Lantern was a MACE and is now a NETHERITE_HOE — a haft with a head,
 * which is the right silhouette for a lantern on a pole and, more to the point, one of the few vanilla
 * items carrying no combat passive at all. A mace's fall-slam had to be clamped out of every blow; there
 * is nothing to clamp now, so an ordinary strike passes through exactly as vanilla intends and its
 * enchants land. See {@link EgoModels#LANTERN}.
 *
 * <p>Nothing here spawns an entity and nothing edits the world, so there are no orphans to sweep. The only
 * state is per-wielder (digest tally, nibble tally) plus the short-lived digest tasks, all dropped in
 * {@link #onQuit} and {@link #onDisable}.
 */
public final class LanternWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key marking an ItemStack as Lantern. */
    private final NamespacedKey key;

    // ---- tuning -----------------------------------------------------------------------

    /** Om Nom Nom: only every Nth instance of damage taken is swallowed and digested. */
    private static final int DIGEST_EVERY = 3;

    /** Om Nom Nom: fraction of the swallowed instance's damage returned as health (15%). */
    private static final double DIGEST_HEAL_FRAC = 0.15;

    /** The same figure as a whole number, for the class/tooltip docs. */
    private static final int DIGEST_HEAL_PERCENT = 15;

    /** Om Nom Nom: the lantern digests slowly — the heal lands three seconds after the blow. */
    private static final long DIGEST_DELAY_TICKS = 60L;

    /** I Shall Nibble Thee!: every Nth landed strike is the bite that heals. */
    private static final int NIBBLE_EVERY = 5;

    /** Ambient lure glow cadence, in onTick dispatches (onTick fires every 2 server ticks -> ~1s). */
    private static final int LURE_PERIOD = 10;

    /**
     * How long a one-off cue ("nibbled", "om nom nom") holds the action bar. Without this the pip readout,
     * repainted every 2 ticks, would stomp the cue before the wielder could ever read it.
     */
    private static final long CUE_MS = 1_200L;

    // ---- per-wielder state ------------------------------------------------------------

    /** Instances of damage taken since the last digest, per wielder. Rolls over at {@link #DIGEST_EVERY}. */
    private final Map<UUID, Integer> damageTally = new ConcurrentHashMap<>();

    /** Landed strikes since the last bite, per wielder. Rolls over at {@link #NIBBLE_EVERY}. */
    private final Map<UUID, Integer> nibbleTally = new ConcurrentHashMap<>();

    /** Digest heals waiting out their three seconds. Cancelled on quit/disable so none outlive the plugin. */
    private final Set<Digest> digesting = ConcurrentHashMap.newKeySet();

    /** A one-off action-bar cue and the instant it stops outranking the pips. */
    private record Cue(String text, long until) {}

    /** Live cues per wielder. Pruned inline in {@link #onTick} the moment one expires. */
    private final Map<UUID, Cue> cues = new ConcurrentHashMap<>();

    public LanternWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "lantern");
    }

    @Override
    public String id() {
        return "lantern";
    }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != EgoModels.LANTERN.material()) return false;
        ItemMeta m = item.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(EgoModels.LANTERN.material());
        ItemMeta meta = item.getItemMeta();

        TOOLTIP.applyTo(meta);
        meta.setEnchantmentGlintOverride(false);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        EgoModels.stampWeapon(meta, EgoModels.LANTERN);

        item.setItemMeta(meta);
        return item;
    }

    // ---- [Left Click] I Shall Nibble Thee! ---------------------------------------------

    /**
     * A landed strike, and the teeth that ride it. Every {@value #NIBBLE_EVERY}th one bites, and the
     * wielder is healed for the full damage that strike deals — read via {@code getFinalDamage()}, so the
     * figure is what the victim actually takes once their armour has had its say, and a well-armoured foe
     * feeds the lantern less.
     *
     * <p>Nothing here re-deals damage ({@code victim.damage()} is never called, only the attacker is
     * healed), so this hook cannot re-enter itself and needs no re-entrancy fence. Wear is left to the
     * vanilla swing that carried the hit — the bite is not a separate use.
     *
     * <p>This used to open by clamping a MACE fall-slam out of the blow. The Lantern is no longer a mace
     * (see {@link EgoModels#LANTERN}), so there is no slam bonus to neutralise and the clamp is gone with
     * it — the blow now passes through exactly as vanilla and the enchants land it.
     */
    @Override
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent event) {
        UUID id = attacker.getUniqueId();
        int strikes = bumpTally(nibbleTally, id);

        if (strikes < NIBBLE_EVERY) {
            toothFx(victim);
            return;
        }
        nibbleTally.put(id, 0); // the teeth have closed — start counting toward the next bite

        // What the victim actually takes, post-clamp and post-armour, is what the lantern gets to swallow.
        double dealt = event.getFinalDamage();
        biteFx(attacker, victim);
        if (dealt > 0 && heal(attacker, dealt)) cue(attacker, "Lantern — nibbled");
    }

    // ---- [Passive] Om Nom Nom -----------------------------------------------------------

    /**
     * Drives the lure glow and the nibble pips. Returns false — disengaging this player from the tick loop
     * — the instant the Lantern is not in the main hand. Om Nom Nom does its own counting through
     * {@link #onDamaged} and needs nothing from here.
     */
    @Override
    public boolean onTick(Player player, long tick) {
        UUID id = player.getUniqueId();
        if (!matches(player.getInventory().getItemInMainHand())) return false;

        // A fresh cue outranks the readout for a moment; otherwise show progress toward the bite:
        // [Nibble ◆ ◆ ◇ ◇ ◇] — 0..4, resetting the moment the teeth close.
        Cue cue = cues.get(id);
        if (cue != null && System.currentTimeMillis() < cue.until()) {
            player.sendActionBar(EgoHud.status(cue.text(), LURE_TEXT));
        } else {
            if (cue != null) cues.remove(id); // expired — prune inline rather than let it sit
            player.sendActionBar(EgoHud.pips("Nibble", LURE_TEXT, nibbleTally.getOrDefault(id, 0), NIBBLE_EVERY));
        }

        if (tick % LURE_PERIOD == 0) lureGlow(player);
        return true;
    }

    /**
     * Om Nom Nom's eyes and ears: one instance of damage taken, from any cause the wielder suffers while
     * the Lantern is in the main hand — a strike, a fall, fire, poison. The lantern isn't fussy.
     *
     * <p>Two blows are turned away at the door. A <b>fully blocked or absorbed</b> strike arrives here with
     * a final damage of 0: the wielder was struck but took nothing, and there is nothing to swallow — it
     * must not burn a place in the tally, or a shield would starve the passive while appearing to feed it.
     * A <b>killing blow</b> is likewise not counted; nothing is left to digest, and the mouthful would land
     * three seconds into a corpse. Damage is still pending at this point in the chain, so the blow is fatal
     * exactly when it meets or exceeds the health the wielder is still standing on.
     */
    @Override
    public void onDamaged(Player victim, EntityDamageEvent event) {
        double taken = event.getFinalDamage();
        if (taken <= 0.0) return;                  // struck, but nothing got through — a shield, or absorption
        if (taken >= victim.getHealth()) return;   // the last blow; there is no after in which to chew it

        swallow(victim, victim.getUniqueId(), taken);
    }

    /**
     * An instance of damage taken. Every {@value #DIGEST_EVERY}rd one is swallowed: a portion of it is
     * booked to come back as health three seconds later, once the lantern has chewed it over.
     */
    private void swallow(Player player, UUID id, double lost) {
        int taken = bumpTally(damageTally, id);
        if (taken < DIGEST_EVERY) return;
        damageTally.put(id, 0); // swallowed — start counting toward the next mouthful

        double heal = lost * DIGEST_HEAL_FRAC;
        if (heal <= 0.0) return;

        swallowFx(player);
        Digest digest = new Digest(id, heal);
        digesting.add(digest);
        digest.runTaskLater(plugin, DIGEST_DELAY_TICKS);
    }

    /**
     * A mouthful working its way down. Three seconds after the blow it returns its share of health, then
     * drops itself from {@link #digesting}. A wielder who has gone offline (or died) in the meantime simply
     * gets nothing — the task still clears itself either way.
     */
    private final class Digest extends BukkitRunnable {
        private final UUID owner;
        private final double amount;

        private Digest(UUID owner, double amount) {
            this.owner = owner;
            this.amount = amount;
        }

        @Override
        public void run() {
            digesting.remove(this);
            Player player = plugin.getServer().getPlayer(owner);
            if (player == null || !player.isOnline() || player.isDead()) return;
            if (heal(player, amount)) digestFx(player);
        }
    }

    // ---- shared helpers -----------------------------------------------------------------

    /** Bump a per-wielder tally by one and return the new value. */
    private static int bumpTally(Map<UUID, Integer> tally, UUID id) {
        return tally.merge(id, 1, Integer::sum);
    }

    /**
     * Flash a one-off cue on the action bar and hold it there briefly, so the pip readout (repainted every
     * 2 ticks) doesn't wipe it before it can be read. Sent immediately too, in case the wielder is no longer
     * holding the Lantern and so is no longer being ticked at all.
     */
    private void cue(Player player, String text) {
        cues.put(player.getUniqueId(), new Cue(text, System.currentTimeMillis() + CUE_MS));
        player.sendActionBar(EgoHud.status(text, LURE_TEXT));
    }

    /**
     * Feed the wielder, never past their maximum — the lantern gives back what it ate, not more. Returns
     * true if any health was actually restored.
     */
    private boolean heal(Player player, double amount) {
        AttributeInstance maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
        double current = player.getHealth();
        double healed = Math.min(maxHp, current + amount);
        if (healed <= current) return false;
        player.setHealth(healed);
        return true;
    }

    // ---- lifecycle ------------------------------------------------------------------------

    /** Drop everything we hold for this wielder, including any mouthful still going down. */
    @Override
    public void onQuit(UUID id) {
        damageTally.remove(id);
        nibbleTally.remove(id);
        cues.remove(id);
        for (Iterator<Digest> it = digesting.iterator(); it.hasNext(); ) {
            Digest digest = it.next();
            if (digest.owner.equals(id)) {
                digest.cancel();
                it.remove();
            }
        }
    }

    /** Shutting down: no entities to reap, but no digest may outlive the plugin. */
    @Override
    public void onDisable() {
        for (Digest digest : digesting) digest.cancel();
        digesting.clear();
        damageTally.clear();
        nibbleTally.clear();
        cues.clear();
    }

    // ---- SFX / VFX ------------------------------------------------------------------------

    /** The lure at rest: a slow, warm pulse of organic light breathing off the organ. */
    private void lureGlow(Player player) {
        Location at = player.getLocation().add(0, 1.1, 0);
        player.getWorld().spawnParticle(Particle.DUST, at, 2, 0.35, 0.35, 0.35, 0, LURE_DUST);
    }

    /** An ordinary strike — a light, wet nip of the teeth. Quiet, since these land constantly. */
    private void toothFx(LivingEntity victim) {
        Location hit = victim.getLocation().add(0, 1.0, 0);
        victim.getWorld().playSound(hit, Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.25f, 1.5f);
        victim.getWorld().spawnParticle(Particle.DUST, hit, 3, 0.2, 0.2, 0.2, 0, TOOTH_DUST);
    }

    /**
     * The bite. Teeth close with a wet chewing crunch on the victim, and the light flares warm on the
     * wielder as the mouthful goes down.
     */
    private void biteFx(Player attacker, LivingEntity victim) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World world = victim.getWorld();
        Location hit = victim.getLocation().add(0, 1.0, 0);

        // Wet crunch: a slimy tear under a chewing gnash, both pitched low so it reads as meat, not metal.
        world.playSound(hit, Sound.BLOCK_SLIME_BLOCK_BREAK, 0.8f, 0.6f + rng.nextFloat() * 0.1f);
        world.playSound(hit, Sound.ENTITY_GENERIC_EAT, 0.9f, 0.7f + rng.nextFloat() * 0.15f);

        // A spray of torn flesh at the wound...
        world.spawnParticle(Particle.DUST, hit, 12, 0.28, 0.28, 0.28, 0, MEAT_DUST);
        // ...and the organ's light swelling on the one holding it.
        world.spawnParticle(Particle.DUST, attacker.getLocation().add(0, 1.1, 0),
                8, 0.3, 0.4, 0.3, 0, LURE_DUST);
    }

    /** A third hit taken: the lantern gulps it down. The heal is now three seconds out. */
    private void swallowFx(Player player) {
        Location at = player.getLocation().add(0, 1.1, 0);
        player.getWorld().playSound(at, Sound.ENTITY_GENERIC_DRINK, 0.5f, 0.6f);
        player.getWorld().spawnParticle(Particle.DUST, at, 5, 0.25, 0.3, 0.25, 0, MEAT_DUST);
    }

    /** Three seconds on: digested. A small satisfied burp, and the health comes back. */
    private void digestFx(Player player) {
        Location at = player.getLocation().add(0, 1.1, 0);
        player.getWorld().playSound(at, Sound.ENTITY_PLAYER_BURP, 0.45f, 1.2f);
        player.getWorld().spawnParticle(Particle.HEART, at, 3, 0.3, 0.3, 0.3, 0); // HEART takes no data
        cue(player, "Lantern — om nom nom");
    }

    // ---- lore ------------------------------------------------------------------------------

    private static final TextColor PRIMARY   = TextColor.color(0xE5D7D7); // name — pale, waxy flesh-white
    private static final TextColor SECONDARY = TextColor.color(0x56BC20); // title — the sickly lure-glow green
    private static final TextColor LURE_TEXT = SECONDARY;                 // action-bar cues share the glow

    private static final Color LURE = Color.fromRGB(0x56, 0xBC, 0x20); // the luminous organ's green light
    private static final Color MEAT = Color.fromRGB(0x8A, 0x2B, 0x2B); // torn flesh sprayed off a bite
    private static final Color BONE = Color.fromRGB(0xE5, 0xD7, 0xD7); // the teeth themselves

    private static final Particle.DustOptions LURE_DUST  = new Particle.DustOptions(LURE, 1.1f);
    private static final Particle.DustOptions MEAT_DUST  = new Particle.DustOptions(MEAT, 1.0f);
    private static final Particle.DustOptions TOOTH_DUST = new Particle.DustOptions(BONE, 0.7f);

    /** The flavour block, pre-wrapped to ~38 characters a line. */
    private static final List<String> DESCRIPTION = List.of(
        "The luminous organ shines brilliantly,",
        "making it useful for lighting up the",
        "dark. It's also great as a lure.",
        "Though teeth sticking out of some",
        "spots of the equipment is a rather",
        "frightening sight."
    );

    /** The moveset, in the order it reads on the item. */
    private static final List<EgoLore.Ability> MOVESET = List.of(
        new EgoLore.Ability("[Passive] Om Nom Nom",
            "Every 3rd instance of damage taken",
            "is digested: heals 15% of that",
            "damage after three seconds."),
        new EgoLore.Ability("[Left Click] I Shall Nibble Thee!",
            "Slow strikes. Every 5th strike bites",
            "deep and heals you for the full",
            "damage that strike dealt.")
    );

    private static final EgoLore.Tooltip TOOLTIP =
        EgoLore.egoLore("Lantern", "Meat Lantern", PRIMARY, SECONDARY, DESCRIPTION, MOVESET);
}
