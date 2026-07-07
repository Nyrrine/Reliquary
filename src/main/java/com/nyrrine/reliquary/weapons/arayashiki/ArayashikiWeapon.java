package com.nyrrine.reliquary.weapons.arayashiki;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Arayashiki — the concept-severing relic blade. Owns all of the blade's state:
 * its item, the memory/use-time charge system, the name/lore erosion, the
 * erased-kill marks, and the action-bar mute. Behaviour is delegated to the four
 * helper classes (combat, skills, wielder tick, erasure).
 */
public final class ArayashikiWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key that marks an ItemStack as the Arayashiki blade. */
    private final NamespacedKey bladeKey;

    /** The blade's canonical name — the thing that gets "erased" as a cooldown later. */
    public static final String BLADE_NAME = "Arayashiki";

    /** Entities recently struck by Arayashiki -> time, so death can be recognised as erasure. */
    private final Map<UUID, Long> erasedMarks = new HashMap<>();
    private static final long ERASE_MARK_MS = 6000L;

    /**
     * The wielder's remaining "use time", in ticks. The blade is drawn from the
     * memory of whoever holds it (per the lore): holding it drains this toward 0,
     * sheathing it lets it regenerate. At 0 the blade is fully erased and won't cut.
     */
    public static final int MAX_USE_TICKS = 3600; // 3 minutes of continuous use
    private final Map<UUID, Integer> useTicks = new HashMap<>();

    /** A fixed, scattered order in which the name's letters decay into blank space. */
    private static final int[] EROSION_ORDER = buildErosionOrder(BLADE_NAME.length());

    private final ArayashikiCombat combat;
    private final ArayashikiSkills skills;
    private final ArayashikiWielder wielder;
    private final ArayashikiErasure erasure;

    public ArayashikiWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.bladeKey = new NamespacedKey(plugin, "arayashiki_blade");
        this.combat = new ArayashikiCombat(plugin, this);
        this.skills = new ArayashikiSkills(plugin, this);
        this.wielder = new ArayashikiWielder(plugin, this);
        this.erasure = new ArayashikiErasure(plugin, this);
    }

    // ---- Weapon interface ----------------------------------------------------------

    @Override
    public String id() {
        return "arayashiki";
    }

    @Override
    public void onSwing(Player player) {
        combat.onSwing(player);
    }

    @Override
    public void onInteract(Player player, boolean sneaking) {
        skills.onInteract(player, sneaking);
    }

    @Override
    public void onTick(Player player, long tick) {
        wielder.tick(player, tick);
    }

    @Override
    public void onEntityDeath(EntityDeathEvent event) {
        erasure.onEntityDeath(event);
    }

    @Override
    public void onPlayerDeath(PlayerDeathEvent event) {
        erasure.onPlayerDeath(event);
    }

    // ---- use-time / erasure charge -------------------------------------------------

    public int useTicksOf(UUID id) {
        return useTicks.getOrDefault(id, MAX_USE_TICKS); // starts full
    }

    public void setUseTicks(UUID id, int value) {
        useTicks.put(id, Math.max(0, Math.min(MAX_USE_TICKS, value)));
    }

    /** True while the blade still has enough of itself left to cut. */
    public boolean hasCharge(UUID id) {
        return useTicksOf(id) > 0;
    }

    /** Temporarily silence the memory bar (e.g. so a dash's charge pips stay visible). */
    private final Map<UUID, Long> actionBarMuteUntil = new HashMap<>();

    public void muteActionBar(UUID id, long ms) {
        actionBarMuteUntil.put(id, System.currentTimeMillis() + ms);
    }

    public boolean isActionBarMuted(UUID id) {
        Long t = actionBarMuteUntil.get(id);
        return t != null && System.currentTimeMillis() < t;
    }

    /** How many letters are currently erased for the given remaining use-time. */
    public int erosionLevel(int remainingTicks) {
        double erosion = 1.0 - (double) remainingTicks / MAX_USE_TICKS;
        return (int) Math.round(erosion * EROSION_ORDER.length);
    }

    /** The blade's name for a given remaining use-time — letters decayed into blanks. */
    public Component erodedName(int remainingTicks) {
        char[] chars = BLADE_NAME.toCharArray();
        int toErase = erosionLevel(remainingTicks);
        for (int k = 0; k < toErase && k < EROSION_ORDER.length; k++) {
            chars[EROSION_ORDER[k]] = ' ';
        }
        NamedTextColor color = toErase >= EROSION_ORDER.length
                ? NamedTextColor.GRAY : NamedTextColor.WHITE;
        return Component.text(new String(chars))
                .color(color)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static int[] buildErosionOrder(int n) {
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        // Fixed seed -> the decay pattern is scattered but identical every time.
        java.util.Random r = new java.util.Random(0x5EEDL);
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            Integer tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp;
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = idx[i];
        return out;
    }

    /** 0.0 = pristine, 1.0 = fully erased. */
    public double erosionFraction(int remainingTicks) {
        return 1.0 - (double) remainingTicks / MAX_USE_TICKS;
    }

    /** Blanks out a scattered fraction of a string's non-space characters (stable per string). */
    public String eraseText(String s, double fraction) {
        if (fraction <= 0) return s;
        char[] c = s.toCharArray();
        List<Integer> pos = new ArrayList<>();
        for (int i = 0; i < c.length; i++) if (c[i] != ' ') pos.add(i);
        int n = pos.size();
        // A per-string seed keeps each line's decay scattered but identical across updates.
        java.util.Random r = new java.util.Random(0x5EEDL ^ (long) s.hashCode() * 2654435761L);
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            Integer tmp = pos.get(i); pos.set(i, pos.get(j)); pos.set(j, tmp);
        }
        int k = (int) Math.round(fraction * n);
        for (int i = 0; i < k; i++) c[pos.get(i)] = ' ';
        return new String(c);
    }

    // ---- lore (colored, and mutilated by memory erasure) ---------------------------

    // A cool cold-steel palette with ember + crimson accents.
    private static final TextColor GOLD  = TextColor.color(0xE8C87A); // the name
    private static final TextColor PALE  = TextColor.color(0xC9C9D6); // base description
    private static final TextColor STEEL = TextColor.color(0xF2F4FA); // sever / erase
    private static final TextColor EMBER = TextColor.color(0xDA5A2C); // burning wounds
    private static final TextColor CRIM  = TextColor.color(0xC22035); // the cost
    private static final TextColor FAINT = TextColor.color(0x83838E); // conditions
    private static final TextColor QUOTE = TextColor.color(0x6E6E78); // the epithet

    /** One colored (optionally italic) span of a lore line. */
    private record Seg(String text, TextColor color, boolean italic) {
        Seg(String text, TextColor color) { this(text, color, false); }
    }

    private static final List<List<Seg>> LORE = List.of(
        List.of(new Seg("天殺  ", CRIM), new Seg("Tiansha Star's Blade", GOLD)),
        List.of(),
        List.of(new Seg("A star-forged, translucent-pale ōdachi", PALE)),
        List.of(new Seg("whose cuts ", PALE), new Seg("can never be made whole", STEEL), new Seg(".", PALE)),
        List.of(),
        List.of(new Seg("Its wounds ", PALE), new Seg("burn", EMBER), new Seg(" — reigniting and", PALE)),
        List.of(new Seg("reopening in its presence, undoing", PALE)),
        List.of(new Seg("what flesh was ever replaced.", PALE)),
        List.of(),
        List.of(new Seg("At full power it ", PALE), new Seg("severs a thing from", STEEL)),
        List.of(new Seg("reality entire", STEEL), new Seg(", leaving no memory it was.", PALE)),
        List.of(),
        List.of(new Seg("Only one who perceives the flow of", FAINT, true)),
        List.of(new Seg("time may draw it and remain whole.", FAINT, true)),
        List.of(),
        List.of(new Seg("It mutilates the memories of its wielder", CRIM, true)),
        List.of(new Seg("— the most precious, taken first.", CRIM, true)),
        List.of(),
        List.of(new Seg("\"Erasing me, erasing you.\"", QUOTE, true))
    );

    /** The lore for a given remaining use-time — its text decays as the memory is cut. */
    public List<Component> erodedLore(int remainingTicks) {
        double f = erosionFraction(remainingTicks);
        List<Component> out = new ArrayList<>(LORE.size());
        for (List<Seg> line : LORE) {
            if (line.isEmpty()) { out.add(Component.empty()); continue; }
            Component c = Component.empty().decoration(TextDecoration.ITALIC, false);
            for (Seg seg : line) {
                c = c.append(Component.text(eraseText(seg.text(), f))
                        .color(seg.color())
                        .decoration(TextDecoration.ITALIC, seg.italic()));
            }
            out.add(c);
        }
        return out;
    }

    /** Records that this entity was just cut by Arayashiki. */
    public void markErased(LivingEntity entity) {
        erasedMarks.put(entity.getUniqueId(), System.currentTimeMillis());
        // Cheap housekeeping so the map can't grow without bound.
        if (erasedMarks.size() > 256) {
            long cutoff = System.currentTimeMillis() - ERASE_MARK_MS;
            erasedMarks.values().removeIf(t -> t < cutoff);
        }
    }

    /** True (once) if the entity died recently enough to a cut from Arayashiki. */
    public boolean consumeErasedMark(UUID id) {
        Long t = erasedMarks.remove(id);
        return t != null && System.currentTimeMillis() - t < ERASE_MARK_MS;
    }

    public NamespacedKey bladeKey() {
        return bladeKey;
    }

    /** True if this stack is an Arayashiki blade. */
    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_SWORD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(bladeKey, PersistentDataType.BYTE);
    }

    /** Builds a fresh Arayashiki blade. */
    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(erodedName(MAX_USE_TICKS));
        meta.lore(erodedLore(MAX_USE_TICKS));

        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(bladeKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }
}
