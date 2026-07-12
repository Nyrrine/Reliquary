package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Assay's "track a weapon" hint system (§35). A player tells the Assay which weapon they're chasing; the
 * tracker then tells them <i>what to fetch next</i> — the catalyst grind still outstanding, and which refined
 * reagents to climb for the sins that weapon leans on. It never solves the brew for them (stability, taints,
 * when to distill are still the player's problem) — it only points at the next step.
 */
public final class WeaponTracker {

    private final Plugin plugin;
    private final Map<UUID, String> tracked = new java.util.HashMap<>();

    public WeaponTracker(Plugin plugin) {
        this.plugin = plugin;
    }

    public void track(UUID player, String weaponId) { tracked.put(player, weaponId); }
    public void clear(UUID player) { tracked.remove(player); }
    public String tracked(UUID player) { return tracked.get(player); }

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

        out.add(Component.text("◆ Chasing " + spec.display() + " (" + spec.grade().display() + ")",
                NamedTextColor.GOLD));

        // If you're holding a brewed cogito, read IT against the target — arrows + colours for what to fix.
        PotState held = Cogito.read(player.getInventory().getItemInMainHand());
        if (held != null && !held.isBlank()) {
            out.add(Component.text("HINT: ", NamedTextColor.YELLOW).append(cogitoHint(held, spec)));
            out.addAll(analyzeCogito(held, spec));
            return out;
        }

        // Otherwise you're still gathering — the fetch checklist.
        out.add(Component.text("HINT: ", NamedTextColor.YELLOW)
                .append(nextStep(player, spec, refined, grind, enkNeed)));
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

    /** The one biggest thing wrong with the held cogito, phrased as an instruction. */
    private Component cogitoHint(PotState st, WeaponSpec spec) {
        SinProfile cog = st.profile(), tgt = spec.signature();
        if (st.titer() + 1e-9 < spec.grade().minVolume())
            return Component.text("Blend more vials — you need volume " + (int) spec.grade().minVolume()
                    + " (have " + (int) st.titer() + ").", NamedTextColor.WHITE);
        if (st.grade().ordinal() < spec.grade().minCogito().ordinal())
            return Component.text("Distill to raise purity toward " + spec.grade().minCogito().display()
                    + " (you're " + fmt(st.purity()) + "%).", NamedTextColor.WHITE);
        Sin worstLow = null, worstHigh = null; double lowGap = 0, highGap = 0;
        for (Sin s : Sin.values()) {
            double d = cog.get(s) - tgt.get(s);
            if (tgt.get(s) >= 3 && d < lowGap) { lowGap = d; worstLow = s; }
            if (d > highGap) { highGap = d; worstHigh = s; }
        }
        if (worstLow != null && -lowGap >= 4)
            return Component.text("Add more " + worstLow.display() + " (have " + (int) cog.get(worstLow)
                    + "%, aim " + (int) tgt.get(worstLow) + "%).", NamedTextColor.WHITE);
        if (worstHigh != null && highGap >= 6)
            return Component.text("Too much " + worstHigh.display() + " — dilute it (have "
                    + (int) cog.get(worstHigh) + "%, aim " + (int) tgt.get(worstHigh) + "%).", NamedTextColor.WHITE);
        return Component.text("Balanced! Insert the catalyst (sneak the Crucible) and pour at the Well.",
                NamedTextColor.GREEN);
    }

    /** The full read of the held cogito vs the target — arrows (↑ more / ↓ less / ✔ ok) + colours per axis. */
    private List<Component> analyzeCogito(PotState st, WeaponSpec spec) {
        List<Component> out = new ArrayList<>();
        SinProfile cog = st.profile(), tgt = spec.signature();
        double match = spec.matchOf(cog);
        out.add(Component.text("Match " + Math.round(match * 100) + "%   Volume " + (int) st.titer()
                + "   " + st.grade().display() + " " + fmt(st.purity()) + "%", matchColor(match)));

        if (st.titer() + 1e-9 < spec.grade().minVolume())
            out.add(Component.text("  ↑ Volume " + (int) st.titer() + "/" + (int) spec.grade().minVolume()
                    + " — blend more vials", NamedTextColor.YELLOW));
        if (st.grade().ordinal() < spec.grade().minCogito().ordinal())
            out.add(Component.text("  ↑ Purity — distill toward " + spec.grade().minCogito().display(),
                    NamedTextColor.YELLOW));

        for (Sin s : Sin.values()) {
            double have = cog.get(s), want = tgt.get(s);
            if (have < 3 && want < 3) continue;
            double d = have - want;
            String stat = String.format(java.util.Locale.ROOT, "%s %d%% (aim %d%%)",
                    s.display(), Math.round(have), Math.round(want));
            if (want >= 3 && d < -3) out.add(Component.text("  ↑ " + stat + " — more", s.color()));
            else if (d > 3) out.add(Component.text("  ↓ " + stat + " — less", s.color()));
            else out.add(Component.text("  ✔ " + stat, s.color()));
        }
        return out;
    }

    private NamedTextColor matchColor(double match) {
        if (match >= 0.85) return NamedTextColor.GREEN;
        if (match >= 0.60) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }

    private static String fmt(double d) { return String.format(java.util.Locale.ROOT, "%.0f", d); }

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
}
