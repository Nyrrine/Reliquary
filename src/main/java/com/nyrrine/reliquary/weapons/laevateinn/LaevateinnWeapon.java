package com.nyrrine.reliquary.weapons.laevateinn;

import com.nyrrine.reliquary.Reliquary;
import com.nyrrine.reliquary.core.Weapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lævateinn — Matthias's sealed relic. A heavy <b>greatsword</b> while sealed, whose
 * seals break as you fight and whose final layer wakes a fast <b>true sword</b>.
 *
 * <p>Heat is a <b>live combat gauge</b>, not a one-way meter: landing hits stokes it and
 * crosses the thresholds that unbox the blade (forms 0 → 3); once you fall out of combat
 * it cools and the seals close again, one form at a time. Off-hand the blade is inert and
 * cools. There are NO status mechanics — every strike is a flat, per-form value; the fire
 * and embers are pure VFX. Behaviour is delegated to the helpers (combat, skills, tick).
 */
public final class LaevateinnWeapon implements Weapon {

    private final Reliquary plugin;

    /** PDC key that marks an ItemStack as the Lævateinn blade. */
    private final NamespacedKey bladeKey;
    /** Key for the per-form attack-speed modifier (so the swing bar matches the M1 pace). */
    private final NamespacedKey atkSpeedKey;
    /** Admin/debug "Worthy" variant: always True Form, never self-burns. */
    private final NamespacedKey worthyKey;

    // ---- Heat / unseal tuning ------------------------------------------------------
    /** The four forms: 0 Sealed Greatsword, 1 First Seal, 2 Second Seal, 3 True Form. */
    public static final int MAX_FORM = 3;
    /** Heat needed to reach form 1, 2, 3. Crossing up unseals; falling below re-seals. */
    private static final int[] HEAT_TO_FORM = {60, 160, 300};
    /** Heat is capped a little above the true-form line so it has a sliver of buffer. */
    public static final int HEAT_MAX = 320;
    /** Re-seal only once heat drops this far below a break, so it can't flicker on the line. */
    private static final int HYSTERESIS = 15;
    /**
     * Ground Slam cooldown — also gates the double-jump (a leap starts it), so the whole leap→dive
     * package is on a long leash. A slow, heavy signature move, not a mobility toy. 45s across forms.
     */
    public long slamCdMs(int form) {
        // 5s off every form (45 -> 40); True Form (max) drops all the way to 20s.
        return form >= MAX_FORM ? 20000L : 40000L;
    }
    /** You count as "in combat" for this long after landing a hit; heat holds, then cools. */
    public static final long COMBAT_WINDOW_MS = 15000L;
    /** Heat gained per hit (a touch slower to unbox than before). */
    public static final int HEAT_PER_HIT = 7;
    /** One attack grants at most this many hits' worth of heat, so AoE can't over-stoke. */
    private static final int HEAT_HIT_CAP = 2;

    // ---- per-wielder state (all UUID-keyed, all cleared in onQuit) ------------------
    private final Map<UUID, Integer> heat = new HashMap<>();       // 0..HEAT_MAX, the live gauge
    private final Map<UUID, Integer> form = new HashMap<>();       // 0..3, derived w/ hysteresis
    private final Map<UUID, Long> lastCombatAt = new HashMap<>();  // last time a hit landed (ms)
    private final Map<UUID, Long> gutstabReadyAt = new HashMap<>();// Rising Gutstab off-cooldown at
    private final Map<UUID, Long> slamReadyAt = new HashMap<>();   // Ground Slam off-cooldown at
    private final Map<UUID, Long> ultReadyAt = new HashMap<>();    // Extermination off-cooldown at
    private final Map<UUID, Long> lastPulseAt = new HashMap<>();   // True-Form pulse clock (ms)
    private final Map<UUID, Long> pulseActiveUntil = new HashMap<>();// keeps the tick alive through a pulse
    private final Map<UUID, Long> comboBusyUntil = new HashMap<>();// wielder rooted in the ultimate combo
    private final Map<UUID, Long> airSlamArmedUntil = new HashMap<>();// a double-jump armed an air slam
    private final Map<UUID, Long> m1ReadyAt = new HashMap<>();     // sealed M1 combo off-cooldown at
    private final Map<UUID, Integer> comboStep = new HashMap<>();  // sealed M1 combo step (0,1,2)
    private final Map<UUID, Long> lastM1At = new HashMap<>();      // last sealed M1 hit (for combo reset)
    private final Map<UUID, Long> fallGraceUntil = new HashMap<>();// no fall damage while dashing/leaping/slamming
    private final Map<UUID, Long> trueFormSince = new HashMap<>(); // when this wielder entered True Form (self-burn ramp)

    private final LaevateinnCombat combat;
    private final LaevateinnSkills skills;
    private final LaevateinnWielder wielder;

    public LaevateinnWeapon(Reliquary plugin) {
        this.plugin = plugin;
        this.bladeKey = new NamespacedKey(plugin, "laevateinn_blade");
        this.atkSpeedKey = new NamespacedKey(plugin, "laevateinn_atkspeed");
        this.worthyKey = new NamespacedKey(plugin, "laevateinn_worthy");
        this.combat = new LaevateinnCombat(plugin, this);
        this.skills = new LaevateinnSkills(plugin, this);
        this.wielder = new LaevateinnWielder(plugin, this);
    }

    // ---- Weapon interface ----------------------------------------------------------

    @Override public String id() { return "laevateinn"; }

    @Override
    public void onSwing(Player player) {
        if (comboBusy(player.getUniqueId())) return; // rooted mid-combo — ignore input
        combat.onSwing(player);
    }

    @Override
    public void onInteract(Player player, boolean sneaking) {
        if (comboBusy(player.getUniqueId())) return;
        skills.onInteract(player, sneaking);
    }

    @Override
    public boolean onTick(Player player, long tick) {
        return wielder.tick(player, tick);
    }

    // ---- sealed M1 combo -----------------------------------------------------------

    public long m1ReadyAt(UUID id) { return m1ReadyAt.getOrDefault(id, 0L); }
    public void setM1ReadyAt(UUID id, long at) { m1ReadyAt.put(id, at); }

    public int comboStep(UUID id) { return comboStep.getOrDefault(id, 0); }
    public void setComboStep(UUID id, int step) { comboStep.put(id, step); }

    public long lastM1At(UUID id) { return lastM1At.getOrDefault(id, 0L); }
    public void setLastM1At(UUID id, long at) { lastM1At.put(id, at); }

    // ---- Heat / form ---------------------------------------------------------------

    public int heatOf(UUID id) { return heat.getOrDefault(id, 0); }

    public void setHeat(UUID id, int value) {
        heat.put(id, Math.max(0, Math.min(HEAT_MAX, value)));
    }

    /** Landing a hit stokes the blade (capped) and refreshes the combat window. */
    public void addHeat(UUID id, int amount) {
        setHeat(id, heatOf(id) + amount);
        stampCombat(id);
    }

    /** Add heat for an attack that struck {@code hits} targets, capped so AoE can't over-stoke. */
    public void addHeatForHits(UUID id, int hits) {
        if (hits <= 0) return;
        addHeat(id, Math.min(hits, HEAT_HIT_CAP) * HEAT_PER_HIT);
    }

    // ---- damage gating (only the blade's own moves deal damage — no free vanilla melee) --------

    /** True while the plugin is applying its own damage, so the melee guard can tell it from a raw swing. */
    private boolean dealing = false;

    public boolean isDealing() { return dealing; }

    /** Apply a plugin-sourced hit (clears i-frames, flags it as ours so the vanilla-melee guard lets it through). */
    public void dealDamage(LivingEntity target, double amount, Player source) {
        // Counterplay: a raised shield facing the wielder eats the hit outright — Lævateinn never
        // forces damage through a guard. Works for every one of the blade's moves (they all route here).
        if (shieldBlocks(target, source)) {
            target.getWorld().playSound(target.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.85f);
            return;
        }
        dealing = true;
        try {
            target.setNoDamageTicks(0);
            target.damage(amount, source);
        } finally {
            dealing = false;
        }
    }

    /** True if {@code target} is a player actively raising a shield toward the source's side (front arc). */
    private boolean shieldBlocks(LivingEntity target, Player source) {
        if (!(target instanceof Player p) || !p.isBlocking()) return false;
        Vector toSource = source.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
        if (toSource.lengthSquared() < 1.0e-6) return true;   // right on top of them — count as guarded
        Vector look = p.getEyeLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1.0e-6) return false;
        return toSource.normalize().dot(look.normalize()) > 0.0; // source is in front of the shield
    }

    public int formOf(UUID id) { return form.getOrDefault(id, 0); }
    public void setForm(UUID id, int f) { form.put(id, Math.max(0, Math.min(MAX_FORM, f))); }

    /** Heat needed to be at (the low edge of) the given form; 0 for form 0. */
    public int heatForForm(int f) {
        if (f < 1) return 0;
        if (f > MAX_FORM) f = MAX_FORM;
        return HEAT_TO_FORM[f - 1];
    }

    /**
     * The form the current heat implies, stepping up at each break and down only once
     * heat has fallen a hysteresis band below it (so it can't rattle on a threshold).
     */
    public int deriveForm(UUID id) {
        int h = heatOf(id);
        int f = formOf(id);
        while (f < MAX_FORM && h >= HEAT_TO_FORM[f]) f++;          // rise at the break
        while (f > 0 && h < HEAT_TO_FORM[f - 1] - HYSTERESIS) f--; // fall once well below it
        return f;
    }

    // ---- combat window -------------------------------------------------------------

    public void stampCombat(UUID id) { lastCombatAt.put(id, System.currentTimeMillis()); }

    public boolean inCombat(UUID id) {
        Long t = lastCombatAt.get(id);
        return t != null && System.currentTimeMillis() - t < COMBAT_WINDOW_MS;
    }

    // ---- skill cooldowns (durations live in the helpers that spend them) ------------

    public long gutstabReadyAt(UUID id) { return gutstabReadyAt.getOrDefault(id, 0L); }
    public void setGutstabReadyAt(UUID id, long at) { gutstabReadyAt.put(id, at); }

    public long slamReadyAt(UUID id) { return slamReadyAt.getOrDefault(id, 0L); }
    public void setSlamReadyAt(UUID id, long at) { slamReadyAt.put(id, at); }

    public long ultReadyAt(UUID id) { return ultReadyAt.getOrDefault(id, 0L); }
    public void setUltReadyAt(UUID id, long at) { ultReadyAt.put(id, at); }

    // ---- True-Form pulse clock -----------------------------------------------------

    public long lastPulseAt(UUID id) { return lastPulseAt.getOrDefault(id, 0L); }
    public void setLastPulseAt(UUID id, long at) { lastPulseAt.put(id, at); }

    public void markPulseActive(UUID id, long ms) {
        pulseActiveUntil.put(id, System.currentTimeMillis() + ms);
    }

    public boolean pulseActive(UUID id) {
        Long t = pulseActiveUntil.get(id);
        return t != null && System.currentTimeMillis() < t;
    }

    // ---- ultimate root + air-slam arming -------------------------------------------

    /** Root the wielder for the duration of the six-beat combo (inputs ignored meanwhile). */
    public void setComboBusy(UUID id, long ms) { comboBusyUntil.put(id, System.currentTimeMillis() + ms); }

    public boolean comboBusy(UUID id) {
        Long t = comboBusyUntil.get(id);
        return t != null && System.currentTimeMillis() < t;
    }

    /** A double-jump leap arms an air slam; a left-click while armed crashes down. */
    public void armAirSlam(UUID id, long ms) { airSlamArmedUntil.put(id, System.currentTimeMillis() + ms); }

    public boolean airSlamArmed(UUID id) {
        Long t = airSlamArmedUntil.get(id);
        return t != null && System.currentTimeMillis() < t;
    }

    public void disarmAirSlam(UUID id) { airSlamArmedUntil.remove(id); }

    // ---- True-Form clock (self-burn ramps the longer you stay unsealed) -------------

    public void enterTrueForm(UUID id) { trueFormSince.putIfAbsent(id, System.currentTimeMillis()); }
    public void leaveTrueForm(UUID id) { trueFormSince.remove(id); }
    /** Seconds spent continuously in True Form, or 0 if not. */
    public double trueFormSeconds(UUID id) {
        Long t = trueFormSince.get(id);
        return t == null ? 0 : (System.currentTimeMillis() - t) / 1000.0;
    }

    // ---- fall immunity (dash / double-jump / ground slam) --------------------------

    public void grantFallGrace(UUID id, long ms) { fallGraceUntil.put(id, System.currentTimeMillis() + ms); }

    @Override
    public boolean cancelsFallDamage(UUID id) {
        Long t = fallGraceUntil.get(id);
        return t != null && System.currentTimeMillis() < t;
    }

    // ---- grief / friendly-fire soft-check ------------------------------------------

    /**
     * True if the wielder may harm this target with an AoE. Never hits the wielder's
     * own tamed pets; soft-checks WorldGuard / GriefPrevention (reflection only, no
     * hard dependency) and skips entities where damage is denied. Fails OPEN.
     */
    public boolean canHarm(Player wielder, Entity target) {
        if (target == null || target.equals(wielder)) return false;
        if (target instanceof Tameable t && t.isTamed()
                && t.getOwner() != null && wielder.getUniqueId().equals(t.getOwner().getUniqueId())) {
            return false; // your own pet is never caught in your fire
        }
        Location loc = target.getLocation();
        return !gpDenies(wielder, loc) && !wgDenies(loc);
    }

    // GriefPrevention: reflectively resolved once, then a cheap per-call claim lookup.
    private Boolean gpPresent;
    private Object gpDataStore;
    private Method gpGetClaimAt;

    private boolean gpDenies(Player wielder, Location loc) {
        try {
            if (gpPresent == null) {
                var gp = plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
                if (gp == null || !gp.isEnabled()) { gpPresent = false; return false; }
                gpDataStore = gp.getClass().getField("dataStore").get(gp);
                gpGetClaimAt = gpDataStore.getClass().getMethod("getClaimAt",
                        Location.class, boolean.class, Class.forName("me.ryanhamshire.GriefPrevention.Claim"));
                gpPresent = true;
            }
            if (!gpPresent) return false;
            Object claim = gpGetClaimAt.invoke(gpDataStore, loc, true, null);
            if (claim == null) return false;
            Object owner = claim.getClass().getField("ownerID").get(claim);
            if (owner == null) return true;                       // admin claim -> deny
            return !owner.equals(wielder.getUniqueId());          // someone else's claim -> deny
        } catch (Throwable ignored) {
            return false; // fail open
        }
    }

    // WorldGuard: reflectively resolved once; per-call MOB_DAMAGE flag query.
    private Boolean wgPresent;
    private Object wgRegionContainer;
    private Object wgMobDamageFlag;
    private Object wgStateDeny;
    private Method wgAdapt;
    private Method wgCreateQuery;
    private Method wgTestState;

    private boolean wgDenies(Location loc) {
        try {
            if (wgPresent == null) {
                var wg = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
                if (wg == null || !wg.isEnabled()) { wgPresent = false; return false; }
                Object instance = Class.forName("com.sk89q.worldguard.WorldGuard")
                        .getMethod("getInstance").invoke(null);
                Object platform = instance.getClass().getMethod("getPlatform").invoke(instance);
                wgRegionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);
                wgCreateQuery = wgRegionContainer.getClass().getMethod("createQuery");
                wgAdapt = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
                        .getMethod("adapt", Location.class);
                Class<?> flags = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
                wgMobDamageFlag = flags.getField("MOB_DAMAGE").get(null);
                Class<?> stateFlag = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");
                Class<?> state = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag$State");
                wgStateDeny = state.getField("DENY").get(null); // the DENY enum constant
                Object query = wgCreateQuery.invoke(wgRegionContainer);
                wgTestState = query.getClass().getMethod("testState",
                        Class.forName("com.sk89q.worldedit.util.Location"),
                        Class.forName("com.sk89q.worldguard.protection.association.RegionAssociable"),
                        java.lang.reflect.Array.newInstance(stateFlag, 0).getClass());
                wgPresent = true;
            }
            if (!wgPresent) return false;
            Object weLoc = wgAdapt.invoke(null, loc);
            Object query = wgCreateQuery.invoke(wgRegionContainer);
            Object flagArray = java.lang.reflect.Array.newInstance(wgMobDamageFlag.getClass(), 1);
            java.lang.reflect.Array.set(flagArray, 0, wgMobDamageFlag);
            Object result = wgTestState.invoke(query, weLoc, null, flagArray);
            return wgStateDeny.equals(result);
        } catch (Throwable ignored) {
            return false; // fail open
        }
    }

    // ---- identity ------------------------------------------------------------------

    public NamespacedKey bladeKey() { return bladeKey; }
    public NamespacedKey atkSpeedKey() { return atkSpeedKey; }

    @Override
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_SWORD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(bladeKey, PersistentDataType.BYTE);
    }

    /** Builds a fresh, sealed Lævateinn (form 0). */
    @Override
    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(formName(0));
        meta.lore(formLore(0));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(bladeKey, PersistentDataType.BYTE, (byte) 1);
        // Stamp the form-0 model up front so it shows correctly before the wielder's first repaint.
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(java.util.List.of("laev0"));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    /** True if this stack is the admin "Worthy" variant (always True Form, no self-burn). */
    public boolean isWorthy(ItemStack item) {
        if (!matches(item)) return false;
        return item.getItemMeta().getPersistentDataContainer().has(worthyKey, PersistentDataType.BYTE);
    }

    /** The admin/debug "Worthy Lævateinn": pinned to True Form, never burns its wielder. */
    @Override
    public ItemStack adminVariant() {
        ItemStack item = createItem();
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Worthy Lævateinn").color(TextColor.color(0xFF7A18))
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(worthyKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // ---- per-form name / lore ------------------------------------------------------

    // Sealed is purple + white; orange is reserved for the True Form alone.
    private static final TextColor T_SEAL0 = TextColor.color(0xA9A2C0); // sealed, dim lavender
    private static final TextColor T_SEAL1 = TextColor.color(0x9A3CE0); // first seal — purple
    private static final TextColor T_SEAL2 = TextColor.color(0xC08CFF); // second seal — bright violet
    private static final TextColor T_TRUE  = TextColor.color(0xFF7A18); // true form — orange (reserved)
    private static final TextColor PURPLE  = TextColor.color(0x9A3CE0); // combat accent
    private static final TextColor WHITE   = TextColor.color(0xE9E4F5); // base text
    private static final TextColor LAV     = TextColor.color(0xB8A8E0); // the quote
    private static final TextColor FAINT   = TextColor.color(0x9A93A8); // conditions
    private static final TextColor OR_LT   = TextColor.color(0xFFB050); // true-form accent (reserved)

    static TextColor tierColor(int form) {
        return switch (form) {
            case 1 -> T_SEAL1;
            case 2 -> T_SEAL2;
            case 3 -> T_TRUE;
            default -> T_SEAL0;
        };
    }

    /** The blade's name for a given form — a chained greatsword climbing to the true sword. */
    public Component formName(int form) {
        String text = switch (form) {
            case 1 -> "⛓ Lævateinn — First Seal Broken";
            case 2 -> "Lævateinn — Second Seal Broken";
            case 3 -> "Lævateinn";
            default -> "⛓ Sealed Greatsword ⛓";
        };
        return Component.text(text).color(tierColor(form)).decoration(TextDecoration.ITALIC, false);
    }

    private record Seg(String text, TextColor color, boolean italic) {
        Seg(String text, TextColor color) { this(text, color, false); }
    }

    /** The blade's lore for a given form — same shard, described hotter each layer, with its moves. */
    public List<Component> formLore(int form) {
        TextColor fc = tierColor(form);
        List<List<Seg>> lines = new ArrayList<>();

        // Matthias's line.
        lines.add(List.of(new Seg("\"I don't need some stupid crutch", LAV, true)));
        lines.add(List.of(new Seg("like that \"Shin (心)\" crap. I just", LAV, true)));
        lines.add(List.of(new Seg("gotta pull as much power as I can", LAV, true)));
        lines.add(List.of(new Seg("from these tattoos and grit my teeth,", LAV, true)));
        lines.add(List.of(new Seg("and I'll overpower your fancy", LAV, true)));
        lines.add(List.of(new Seg("little tricks no problem.\"", LAV, true)));
        lines.add(List.of());

        switch (form) {
            case 0 -> {
                lines.add(List.of(new Seg("Sealed. A heavy greatsword — its heat", WHITE)));
                lines.add(List.of(new Seg("chained away. Strike to build Heat.", WHITE)));
                lines.add(List.of());
                lines.add(List.of(new Seg("Left-click", PURPLE, true), new Seg(": 3-hit heavy combo.", FAINT, true)));
                lines.add(List.of(new Seg("Double-jump, left-click", PURPLE, true),
                        new Seg(": ground slam.", FAINT, true)));
                lines.add(List.of(new Seg("Right-click", PURPLE, true), new Seg(": gut stab.", FAINT, true)));
            }
            case 1 -> {
                lines.add(List.of(new Seg("First seal broken.", fc)));
                lines.add(List.of(new Seg("The greatsword strikes heavier.", FAINT, true)));
            }
            case 2 -> {
                lines.add(List.of(new Seg("Second seal broken.", fc)));
                lines.add(List.of(new Seg("Heavier still; the slams crack deeper.", FAINT, true)));
            }
            case 3 -> {
                lines.add(List.of(new Seg("Final seal broken — True Form.", fc)));
                lines.add(List.of(new Seg("The greatsword becomes a swift blade.", FAINT, true)));
                lines.add(List.of(new Seg("Shift-right-click", T_TRUE, true),
                        new Seg(": Complete and Total", T_TRUE, true)));
                lines.add(List.of(new Seg("Extermination — hurl the blade,", T_TRUE, true)));
                lines.add(List.of(new Seg("then rocket in after it.", T_TRUE, true)));
                lines.add(List.of());
                lines.add(List.of(new Seg("Its heat now burns the hand that holds it.", OR_LT, true)));
            }
        }
        lines.add(List.of());
        lines.add(List.of(new Seg("Passive — ", FAINT, true), new Seg("Ridiculous Grit", PURPLE, true)));
        lines.add(List.of());
        lines.add(List.of(new Seg("Out of the fight, its seals close again.", FAINT, true)));

        List<Component> out = new ArrayList<>(lines.size());
        for (List<Seg> line : lines) {
            if (line.isEmpty()) { out.add(Component.empty()); continue; }
            Component c = Component.empty().decoration(TextDecoration.ITALIC, false);
            for (Seg seg : line) {
                c = c.append(Component.text(seg.text()).color(seg.color())
                        .decoration(TextDecoration.ITALIC, seg.italic()));
            }
            out.add(c);
        }
        return out;
    }

    /**
     * Plugin shutdown / {@code /reload} / crash: return anything the blade has out in the world.
     * The flight runnables and timed block-restores are cancelled by the scheduler before they can
     * clean up after themselves, so we flush both here — restore outstanding temp-carved blocks and
     * remove any thrown molten swords still in flight. Purely cleanup; no combat state is touched.
     */
    @Override
    public void onDisable() {
        skills.onDisable();
    }

    /** Drop a player's per-player state when they leave, so the maps don't grow over time. */
    @Override
    public void onQuit(UUID id) {
        heat.remove(id);
        form.remove(id);
        lastCombatAt.remove(id);
        gutstabReadyAt.remove(id);
        slamReadyAt.remove(id);
        ultReadyAt.remove(id);
        lastPulseAt.remove(id);
        pulseActiveUntil.remove(id);
        comboBusyUntil.remove(id);
        airSlamArmedUntil.remove(id);
        m1ReadyAt.remove(id);
        comboStep.remove(id);
        lastM1At.remove(id);
        fallGraceUntil.remove(id);
        trueFormSince.remove(id);
        skills.clear(id);
        wielder.clear(id);
    }
}
