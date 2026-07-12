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
        Catalysts.Recipe rec = Catalysts.forWeapon(weaponId);

        Map<String, Integer> refined = rec != null ? CatalystCost.refinedTax(spec) : Map.of();
        Map<Material, Integer> grind = rec != null ? CatalystCost.components(rec, spec.grade()) : Map.of();
        int enkNeed = rec != null ? CatalystCost.enkephalin(rec, spec.grade()) : 0;

        out.add(Component.text("◆ QUEST — Extract " + spec.display() + " (" + spec.grade().display() + ")",
                NamedTextColor.GOLD));
        // The single most useful next action — the quest arrow.
        out.add(Component.text("→ NEXT: ", NamedTextColor.YELLOW)
                .append(nextStep(player, spec, refined, grind, enkNeed)));

        // What to aim the brew at.
        StringBuilder aim = new StringBuilder();
        for (Sin s : dominantSins(spec, 3)) aim.append(s.display()).append("  ");
        out.add(Component.text("Aim your cogito at: " + aim.toString().trim(), NamedTextColor.GRAY));

        // Refined-reagent checklist.
        if (!refined.isEmpty()) {
            out.add(Component.text("Refined reagents:", NamedTextColor.GRAY));
            for (var e : refined.entrySet()) {
                int have = countRefined(player, e.getKey());
                boolean done = have >= e.getValue();
                Reagent rr = Reagents.byId(e.getKey());
                out.add(Component.text((done ? "  ✔ " : "  • ") + (rr != null ? rr.display() : e.getKey())
                        + "  " + have + "/" + e.getValue(), done ? NamedTextColor.GREEN : NamedTextColor.AQUA));
            }
        }
        // Catalyst grind checklist.
        if (!grind.isEmpty()) {
            out.add(Component.text("Catalyst grind:", NamedTextColor.GRAY));
            for (var e : grind.entrySet()) {
                int have = countType(player, e.getKey());
                boolean done = have >= e.getValue();
                out.add(Component.text((done ? "  ✔ " : "  • ") + prettyMat(e.getKey())
                        + "  " + have + "/" + e.getValue(), done ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            int enk = countEnkephalin(player);
            out.add(Component.text((enk >= enkNeed ? "  ✔ " : "  • ") + "Enkephalin  " + enk + "/" + enkNeed,
                    enk >= enkNeed ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        }
        return out;
    }

    /** The quest arrow — the first unmet milestone, phrased as a beginner-friendly instruction. */
    private Component nextStep(Player player, WeaponSpec spec, Map<String, Integer> refined,
                              Map<Material, Integer> grind, int enkNeed) {
        if (!hasVial(player)) {
            return Component.text("Make a Cogito vial — feed emotion items to the Font, then right-click the "
                    + "Alembic to render the Raw Cogito into a vial.", NamedTextColor.WHITE);
        }
        for (var e : refined.entrySet()) {
            int have = countRefined(player, e.getKey());
            if (have >= e.getValue()) continue;
            Sin s = sinOfRefined(e.getKey());
            Reagent rr = Reagents.byId(e.getKey());
            String raw = s != null ? prettyMat(SinConcentrate.rawFor(s)) : "raw sin items";
            return Component.text("Craft " + (e.getValue() - have) + "× more "
                    + (rr != null ? rr.display() : e.getKey()) + " — gather " + raw
                    + ", craft 8 → a Concentrate, then 4 Concentrate + Amethyst → a Pure at a crafting table.",
                    NamedTextColor.WHITE);
        }
        for (var e : grind.entrySet()) {
            int have = countType(player, e.getKey());
            if (have < e.getValue()) {
                return Component.text("Gather " + (e.getValue() - have) + " more " + prettyMat(e.getKey())
                        + " for the catalyst grind.", NamedTextColor.WHITE);
            }
        }
        int enk = countEnkephalin(player);
        if (enk < enkNeed) {
            return Component.text("Render " + (enkNeed - enk) + " more Enkephalin — sneak-right-click the "
                    + "Alembic holding Raw Cogito.", NamedTextColor.WHITE);
        }
        List<Sin> top = dominantSins(spec, 1);
        String topSin = top.isEmpty() ? "its sins" : top.get(0).display();
        return Component.text("You have everything! Forge the catalyst at the Crucible, brew a cogito heavy in "
                + topSin + " (buffer with Amethyst, distill last), then sneak-pour at the Well.",
                NamedTextColor.GREEN);
    }

    private boolean hasVial(Player player) {
        for (ItemStack it : player.getInventory().getContents()) if (Cogito.matches(it)) return true;
        return false;
    }

    private int countRefined(Player player, String reagentId) {
        int c = 0;
        for (ItemStack it : player.getInventory().getContents())
            if (it != null && reagentId.equals(RefinedReagent.idOf(it))) c += it.getAmount();
        return c;
    }

    private int countEnkephalin(Player player) {
        int c = 0;
        for (ItemStack it : player.getInventory().getContents()) if (Enkephalin.matches(it)) c += it.getAmount();
        return c;
    }

    private Sin sinOfRefined(String reagentId) {
        for (Sin s : Sin.values())
            if (reagentId.equals(RefinedReagent.pureId(s)) || reagentId.equals(RefinedReagent.standardId(s))) return s;
        return null;
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
