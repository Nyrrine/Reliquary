package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Assay's "track a weapon" guide (§35). A player tells the Assay which weapon they're chasing; the tracker
 * then tells them <i>what to fetch</i> — the catalyst grind still outstanding, and which refined reagents to
 * climb for the sins that weapon leans on — and makes matching dropped items in the world glow with a tag so
 * the ingredients they need jump out. It never solves the brew for them (stability, taints, when to distill
 * are still the player's problem) — it only points at the shopping list.
 */
public final class WeaponTracker {

    private final Plugin plugin;
    private final Map<UUID, String> tracked = new java.util.HashMap<>();

    public WeaponTracker(Plugin plugin) {
        this.plugin = plugin;
        startGlowTask();
    }

    public void track(UUID player, String weaponId) { tracked.put(player, weaponId); }
    public void clear(UUID player) { tracked.remove(player); }
    public String tracked(UUID player) { return tracked.get(player); }

    /** The catalyst grind components for a tracked weapon (what a full catalyst forge needs). */
    public Map<Material, Integer> neededComponents(String weaponId) {
        Catalysts.Recipe rec = Catalysts.forWeapon(weaponId);
        return rec == null ? Map.of() : rec.components();
    }

    /**
     * The fetch list for the tracked weapon: the catalyst components with how many the player still needs,
     * plus the refined-reagent ladder to climb for the weapon's dominant sins. Pure guidance, no answers.
     */
    public List<Component> shoppingList(Player player, String weaponId) {
        List<Component> out = new ArrayList<>();
        WeaponSpec spec = WeaponSignatures.byId(weaponId);
        if (spec == null) { out.add(Component.text("Unknown weapon.", NamedTextColor.RED)); return out; }

        out.add(Component.text("Tracking " + spec.display() + " (" + spec.grade().display() + ")",
                NamedTextColor.AQUA));

        // The sins it leans on → which refined reagent to climb.
        out.add(Component.text("Lean into:", NamedTextColor.GRAY));
        for (Sin s : dominantSins(spec, 3)) {
            String pure = RefinedReagent.pureId(s), std = RefinedReagent.standardId(s);
            String climb = std != null ? std : pure;
            Reagent r = climb != null ? Reagents.byId(climb) : null;
            out.add(Component.text("  • " + s.display() + " → " + (r != null ? r.display() : "raw " + s.display()),
                    s.color()));
        }

        // Catalyst grind still outstanding (for a guaranteed pull) — the real, grade-scaled cost.
        Catalysts.Recipe rec = Catalysts.forWeapon(weaponId);
        if (rec != null) {
            out.add(Component.text("Catalyst grind (for a guaranteed pull):", NamedTextColor.GRAY));
            for (var e : CatalystCost.components(rec, spec.grade()).entrySet()) {
                int have = countType(player, e.getKey());
                boolean done = have >= e.getValue();
                out.add(Component.text((done ? "  ✔ " : "  • ") + prettyMat(e.getKey())
                        + "  " + have + "/" + e.getValue(),
                        done ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            for (var e : CatalystCost.refinedTax(spec).entrySet()) {
                Reagent rr = Reagents.byId(e.getKey());
                out.add(Component.text("  • " + (rr != null ? rr.display() : e.getKey())
                        + " (refined) ×" + e.getValue(), NamedTextColor.AQUA));
            }
            out.add(Component.text("  + " + CatalystCost.enkephalin(rec, spec.grade()) + " Enkephalin",
                    NamedTextColor.GRAY));
        }
        return out;
    }

    /** The weapon's biggest-share sins (up to {@code n}). */
    public List<Sin> dominantSins(WeaponSpec spec, int n) {
        List<Sin> sins = new ArrayList<>(List.of(Sin.values()));
        sins.sort((a, b) -> Double.compare(spec.signature().get(b), spec.signature().get(a)));
        List<Sin> top = new ArrayList<>();
        for (Sin s : sins) { if (spec.signature().get(s) <= 0.5) break; top.add(s); if (top.size() >= n) break; }
        return top;
    }

    private int countType(Player player, Material m) {
        int c = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == m) c += it.getAmount();
        }
        return c;
    }

    private String prettyMat(Material m) {
        String[] parts = m.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return sb.toString().trim();
    }

    /** Every second, glow-tag nearby dropped items that the tracking player still needs. */
    private void startGlowTask() {
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    String weaponId = tracked.get(p.getUniqueId());
                    if (weaponId == null) continue;
                    Map<Material, Integer> need = neededComponents(weaponId);
                    if (need.isEmpty()) continue;
                    for (var e : p.getNearbyEntities(14, 8, 14)) {
                        if (!(e instanceof Item drop)) continue;
                        Material m = drop.getItemStack().getType();
                        if (!need.containsKey(m)) continue;
                        drop.setGlowing(true);
                        drop.customName(Component.text("↑ " + prettyMat(m) + " (needed)", NamedTextColor.AQUA));
                        drop.setCustomNameVisible(true);
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, 20L);
    }
}
