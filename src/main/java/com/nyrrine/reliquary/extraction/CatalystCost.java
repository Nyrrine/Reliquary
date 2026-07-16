package com.nyrrine.reliquary.extraction;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a catalyst <i>actually</i> costs to forge (§35 — "catalysts should be way more expensive"). A catalyst
 * is the endgame guarantee, so its price is deliberately steep and scales hard with the weapon's grade:
 *
 * <ul>
 *   <li><b>Vanilla grind</b> — the {@link Catalysts.Recipe} components, multiplied by {@link #gradeMult}.</li>
 *   <li><b>Enkephalin</b> — the recipe's Enkephalin, same multiplier.</li>
 *   <li><b>Refined-reagent tax</b> — the deep chain: you must burn {@link #refinedTax Pure reagents of the
 *       weapon's own dominant sins} into the forge, so a catalyst can't be reached without first climbing the
 *       raw→Concentrate→Pure ladder. A WAW catalyst wants 16 Pure reagents (~512 raw) on top of its grind;
 *       an ALEPH wants its top 5 sins at 5 Pures each (25) — the ceiling of the grind.</li>
 * </ul>
 *
 * All values are STARTER tuning — cheap to move once playtested.
 */
public final class CatalystCost {

    private CatalystCost() {}

    /** Multiplier on the vanilla grind + Enkephalin, by grade. */
    public static int gradeMult(EgoGrade g) {
        return switch (g) {
            case ZAYIN -> 2;
            case TETH  -> 3;
            case HE    -> 4;
            case WAW   -> 5;
            case ALEPH -> 6;
        };
    }

    /** Grade tier 1..5 (ZAYIN..ALEPH) — drives how many sins / how many Pures the refined tax demands. */
    public static int gradeTier(EgoGrade g) { return g.ordinal() + 1; }

    /** The scaled vanilla component grind (base × {@link #gradeMult}). */
    public static Map<Material, Integer> components(Catalysts.Recipe rec, EgoGrade g) {
        int m = gradeMult(g);
        Map<Material, Integer> out = new LinkedHashMap<>();
        for (var e : rec.components().entrySet()) out.put(e.getKey(), e.getValue() * m);
        return out;
    }

    /** The scaled Enkephalin cost. */
    public static int enkephalin(Catalysts.Recipe rec, EgoGrade g) {
        return rec.enkephalin() * gradeMult(g);
    }

    /**
     * The refined-reagent tax: the weapon's top {@code tier} dominant sins, each demanding {@code tier} of that
     * sin's <b>Pure</b> reagent (tier = 1..4 by grade). Reagent id → count. Empty if a sin has no Pure form.
     */
    public static Map<String, Integer> refinedTax(WeaponSpec w) {
        int tier = gradeTier(w.grade());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Sin s : dominantSins(w, tier)) {
            String pure = RefinedReagent.pureId(s);
            if (pure != null) out.put(pure, tier);
        }
        return out;
    }

    /** The weapon's biggest-share sins (up to {@code n}, share &gt; 0.5%). */
    public static List<Sin> dominantSins(WeaponSpec w, int n) {
        List<Sin> sins = new ArrayList<>(List.of(Sin.values()));
        sins.sort((a, b) -> Double.compare(w.signature().get(b), w.signature().get(a)));
        List<Sin> top = new ArrayList<>();
        for (Sin s : sins) {
            if (w.signature().get(s) <= 0.5) break;
            top.add(s);
            if (top.size() >= n) break;
        }
        return top;
    }
}
