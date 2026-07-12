package com.nyrrine.reliquary.extraction;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Assay as the game's interactive wiki (§35). The lectern opens a written book whose pages <b>link to each
 * other</b> — click a topic to jump, click a weapon to <i>track</i> it. Content is generated from the live
 * registries (every taint + its cure, every sin's affinity ladder, the whole weapon roster), so it never goes
 * stale. It explains the systems and lists the ingredients; it deliberately does <b>not</b> solve a brew for
 * you (when to buffer, when to distill, which taint to chase is yours to read off the vial).
 */
public final class GuideBook {

    private final WeaponTracker tracker;

    public GuideBook(WeaponTracker tracker) { this.tracker = tracker; }

    private static final TextColor HEAD = TextColor.color(0x8FE6DA);
    private static final TextColor BODY = TextColor.color(0xC8CCD4);
    private static final TextColor FAINT = TextColor.color(0x8A8F9A);

    /** Open a topic (default "index"). Topics: index, loop, vial, sins, taints, affinities, refining,
     *  catalysts, weapons, tips. */
    public void open(Player player, String topic) {
        List<Component> pages = switch (topic == null ? "index" : topic.toLowerCase(Locale.ROOT)) {
            case "loop"       -> loop();
            case "vial"       -> vial();
            case "sins"       -> sins();
            case "taints", "afflictions", "cures" -> taints();
            case "affinities", "reagents" -> affinities();
            case "refining", "grades" -> refining();
            case "catalysts"  -> catalysts();
            case "weapons"    -> weapons(player);
            case "tips"       -> tips();
            default            -> index();
        };
        player.openBook(Book.book(Component.text("The Assay"), Component.text("Reliquary"), pages));
    }

    // ---- pages ---------------------------------------------------------------------

    private List<Component> index() {
        Component p = header("THE ASSAY")
                .append(text("\nYou don't craft a weapon — you distill a mind and pour it into the Well.\n\n"))
                .append(text("Chapters:\n", FAINT))
                .append(link("The Loop", "loop")).append(nl())
                .append(link("Reading a Vial", "vial")).append(nl())
                .append(link("The 7 Sins", "sins")).append(nl())
                .append(link("Afflictions & Cures", "taints")).append(nl())
                .append(link("Sin Affinities", "affinities")).append(nl())
                .append(link("Refining & Grades", "refining")).append(nl())
                .append(link("Catalysts", "catalysts")).append(nl())
                .append(link("Weapons (track one)", "weapons")).append(nl())
                .append(link("Tips", "tips"));
        return List.of(p);
    }

    private List<Component> loop() {
        return paged("THE LOOP", List.of(
                text("1. Font — feed emotions → Raw Cogito.\n"),
                text("2. Alembic — Raw → a vial (click) or → Enkephalin (sneak).\n"),
                text("3. Censer — titrate reagents into the vial.\n"),
                text("4. Centrifuge — distill (purity up, volume down).\n"),
                text("5. Manifold — blend vials (but few! see Tips).\n"),
                text("6. Crucible — forge a catalyst.\n"),
                text("7. Well — peer in (preview), sneak to pour.\n")), "index");
    }

    private List<Component> vial() {
        return paged("READING A VIAL", List.of(
                text("Hover a vial — it reads itself out, no Assay needed.\n\n"),
                text("Purity/Grade — Crude→Certified; gates weapon tier.\n"),
                text("Stability — hits 0 and the pot ruptures. Buffer with Amethyst.\n"),
                text("Volume — bigger pours reach rarer weapons.\n"),
                text("Afflictions — a live mind sickens; the vial recolours and the tooltip names the fault + timer.\n")),
                "index");
    }

    private List<Component> sins() {
        Component p = header("THE 7 SINS").append(text("\n"));
        for (Sin s : Sin.values()) {
            p = p.append(Component.text(s.display(), s.color()))
                    .append(text(" (" + s.cluster().name().toLowerCase(Locale.ROOT) + ")\n", FAINT));
        }
        p = p.append(text("\nOpposed pairs bleed stability fast:\n", FAINT));
        for (Sin.Bridge b : Sin.bridges()) {
            p = p.append(Component.text(b.a().display(), b.a().color()))
                    .append(text(" ✕ ", FAINT))
                    .append(Component.text(b.b().display() + "\n", b.b().color()));
        }
        p = p.append(text("Steady a cross-axis brew with Honeycomb.\n", BODY)).append(back("index"));
        return List.of(p);
    }

    private List<Component> taints() {
        List<Component> entries = new ArrayList<>();
        for (Taint t : Taint.values()) {
            Reagent cure = t.cureId() == null ? null : Reagents.byId(t.cureId());
            Component e = Component.text(t.display(), t.color()).decoration(TextDecoration.BOLD, true)
                    .append(text((t.blocksDistill() ? " (blocks distilling)" : "") + "\n", FAINT))
                    .append(text(t.symptom() + "\n", BODY))
                    .append(text("Cure: " + (cure != null ? cure.display() : "time / scars") + "\n\n", HEAD));
            entries.add(e);
        }
        return paged("AFFLICTIONS & CURES", entries, "index");
    }

    private List<Component> affinities() {
        List<Component> entries = new ArrayList<>();
        for (Sin s : Sin.values()) {
            String pure = RefinedReagent.pureId(s), std = RefinedReagent.standardId(s);
            Reagent pr = pure == null ? null : Reagents.byId(pure);
            Reagent sr = std == null ? null : Reagents.byId(std);
            Component e = Component.text(s.display(), s.color()).decoration(TextDecoration.BOLD, true)
                    .append(text("\nraw " + pretty(SinConcentrate.rawFor(s)) + " ×8 → Concentrate\n", FAINT))
                    .append(text("→ " + (pr != null ? pr.display() : "?") + " (Pure)\n", BODY))
                    .append(text("→ " + (sr != null ? sr.display() : "?") + " (Standard)\n", BODY));
            if (pr != null) e = e.append(text(pr.effectSummary() + "\n\n", FAINT));
            entries.add(e);
        }
        return paged("SIN AFFINITIES", entries, "index");
    }

    private List<Component> refining() {
        return paged("REFINING & GRADES", List.of(
                text("Raw sin items are weak and dirty. Climb the ladder:\n\n"),
                text("8 raw → 1 Concentrate (crafting table).\n"),
                text("4 Concentrate + Amethyst → a Pure reagent.\n"),
                text("4 Pure + a gated item → a Standard reagent.\n\n"),
                text("A Standard is ~2 stacks of raw and ~21 crafts — but it's the only way to Analytical grade, and so to WAW.\n"),
                text("Pure/Standard reagents are low-noise scalpels at ANY tier.\n")), "index");
    }

    private List<Component> catalysts() {
        return paged("CATALYSTS", List.of(
                text("A per-weapon key that GUARANTEES the exact weapon — but only if your pour already matches it closely (≥85%). It cuts the luck, not the skill.\n\n"),
                text("1. Track the weapon (Weapons chapter).\n"),
                text("2. Gather its long grind.\n"),
                text("3. Forge it at the Crucible.\n"),
                text("4. Carry it, sneak-pour at the Well.\n")), "index");
    }

    private List<Component> weapons(Player player) {
        List<Component> entries = new ArrayList<>();
        for (EgoGrade g : EgoGrade.values()) {
            for (WeaponSpec w : WeaponSignatures.all()) {
                if (w.grade() != g) continue;
                Component e = Component.text("• " + w.display(), BODY)
                        .append(text(" [" + g.display() + "] ", FAINT))
                        .append(Component.text("» track", NamedTextColor.GOLD)
                                .clickEvent(ClickEvent.runCommand("/cogito track " + w.id()))
                                .hoverEvent(HoverEvent.showText(Component.text("Track " + w.display()))))
                        .append(text("\n"));
                entries.add(e);
            }
        }
        return paged("WEAPONS — click to track", entries, "index");
    }

    private List<Component> tips() {
        return paged("TIPS", List.of(
                text("• Fewest, cleanest touches win — every add dirties the pot.\n"),
                text("• Buffer BEFORE a big add (Pure ~1 Amethyst, Standard ~2).\n"),
                text("• Blend few vials — merging many is punished hard (noise + faults).\n"),
                text("• Keep a chest of your sins + cures near the stations; afflictions tick even in your bag.\n"),
                text("• Distill last; over-distilling shrinks volume below the gate.\n")), "index");
    }

    // ---- building blocks -----------------------------------------------------------

    private Component header(String title) {
        return Component.text(title, HEAD).decoration(TextDecoration.BOLD, true);
    }

    private Component text(String s) { return Component.text(s, BODY); }
    private Component text(String s, TextColor c) { return Component.text(s, c); }
    private Component nl() { return Component.text("\n"); }

    private String pretty(org.bukkit.Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        return sb.toString().trim();
    }

    private Component link(String label, String topic) {
        return Component.text("» " + label, TextColor.color(0x64C8E6))
                .clickEvent(ClickEvent.runCommand("/cogito guide " + topic))
                .hoverEvent(HoverEvent.showText(Component.text("Open: " + label)));
    }

    private Component back(String topic) {
        return Component.text("\n« Contents", FAINT)
                .clickEvent(ClickEvent.runCommand("/cogito guide " + topic));
    }

    /** Chunk a header + entries into book pages (~5 entries/page), each with a Contents link. */
    private List<Component> paged(String title, List<Component> entries, String backTopic) {
        List<Component> pages = new ArrayList<>();
        int per = 5;
        for (int i = 0; i < entries.size(); i += per) {
            Component page = header(title).append(text("\n"));
            for (int j = i; j < Math.min(i + per, entries.size()); j++) page = page.append(entries.get(j));
            page = page.append(back(backTopic));
            pages.add(page);
        }
        if (pages.isEmpty()) pages.add(header(title).append(back(backTopic)));
        return pages;
    }
}
