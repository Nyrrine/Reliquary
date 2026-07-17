# Reliquary

Reliquary adds custom relic weapons to a Minecraft server. It's a Paper plugin, so it's server-side only; players use the relics with a vanilla client, no mods required.

## Requirements

- Paper 26.1.2 (or a compatible fork)
- Java 25

## Install

1. Put `Reliquary-x.x.x.jar` in your server's `plugins/` folder.
2. Restart the server.
3. As an operator, run `/reliquary give arayashiki` in-game.

## Commands

All commands are **operator only** (permission `reliquary.admin`).

| Command | Effect |
| --- | --- |
| `/reliquary help` | Show command help |
| `/reliquary list` | List loaded relic ids |
| `/reliquary give <id> [player]` | Give a relic to yourself or another player |
| `/reliquary giveall <relics\|egoequipment> [player]` | Give every weapon of a category |
| `/reliquary admin <id> [player]` | Give an admin or debug variant, if the weapon has one |
| `/reliquary track` | List every relic in play and who's holding it |
| `/reliquary purge <player>` | Remove all relics from a player |

---

## Relics

### Arayashiki

**Status:** Complete · **Give:** `/reliquary give arayashiki [player]`

| Input | Effect |
| --- | --- |
| Left click | Wide sweeping slash around you |
| Right click | Dash where you're looking. 3 charges, each recharging ~3s; cuts what you pass through; cancels fall damage |
| Shift + hold right click | Sustained slash storm around you while held |
| On kill | Erases the target (no drops) and refills all dashes |
| Passive | Holding it drains a 3-minute meter; it erodes the item's own name as it drops, becomes unusable at empty, and refills (~45s) while sheathed |

---

### Lævateinn

**Status:** Complete · **Give:** `/reliquary give laevateinn [player]`

| Input | Effect |
| --- | --- |
| Left click (sealed) | A 3-step heavy combo: overhead blunt, slashing sweep, then a beyblade spin-dash. Cooldown-gated, slower while sealed and faster as the seals break |
| Left click (True Form) | One big orange slash with a lingering burn (~7 damage over a second). Fast but not spammable |
| Double-jump, then left click in air | Ground Slam: leap, aim, and dive to that spot for a large Mace-style crater with block debris and burning ground |
| Right click | Gut Stab: a short dash that blasts a temporary hole through walls (restores in ~7s, no drops), impales the first enemy, and unleashes an afterimage slash flurry. 2s cooldown at True Form |
| Shift + right click (True Form) | Complete and Total Extermination: hurl the flaming blade into a locked target, kick off the ground and rocket to it, then rip the sword out for heavy damage (90s cooldown) |
| Passive (Heat) | A segmented action-bar gauge with inline ability cooldowns; out of combat it cools and re-seals a form at a time |
| Passive (True Form) | A silent fire-nova chips nearby hostiles. Self-burn starts after 1 minute and ramps; at half health the seals force shut one by one and lock the blade |

---

### Roadmap

#### Vespas' Hwando & Gungnir

| | |
| --- | --- |
| Status | 🔜 Planned |

#### Vergilius' Gladius

| | |
| --- | --- |
| Status | 🔜 Planned |

#### Helm of Mambrino

| | |
| --- | --- |
| Status | 🔜 Planned |

#### The Eye of Odin

| | |
| --- | --- |
| Status | 🔜 Planned |

---

### Special: Project Moon

#### An Arbiter's Power

| | |
| --- | --- |
| Status | 🔜 Planned |

#### Mimicry

| | |
| --- | --- |
| Status | 🔜 Planned |

#### Fpoon

| | |
| --- | --- |
| Status | 🔜 Planned |

---

## E.G.O Equipment

E.G.O weapons from Lobotomy Corporation, ZAYIN through WAW. Each is a plain vanilla item carrying a CustomModelData tag, so it works bare and a resource pack can swap in the real model. Unlike the relics, these are enchantable and wear with use. Give one with `/reliquary give <id>`, or a whole set with `/reliquary giveall egoequipment`.

Controls below use left click for the melee or primary attack, right click for the skill, and sneak plus right click for the special.

### Penitence

**Give:** `/reliquary give penitence [player]`

| Input | Effect |
| --- | --- |
| Left click | A weak blunt hit. It cannot be used as a mace, so no slam damage |
| Passive | Each hit has a flat chance to restore your saturation and a rising chance to heal you a little |

---

### Soda

**Give:** `/reliquary give soda [player]`

| Input | Effect |
| --- | --- |
| Sneak + right click | Charge the can with a shaking fizz. It takes a moment to fill |
| Right click (charged) | Spray a purple soda cone that heals allies it hits and gives them Speed and Regeneration. It never affects the wielder |

---

### Fourth Match Flame

**Give:** `/reliquary give fourth_match_flame [player]`

| Input | Effect |
| --- | --- |
| Right click | Fire a wide, chaotic cannon-shotgun cone of flame that ignites what it hits, then a long cooldown. Also throws a visual physics fire block |
| Sneak + right click (with a Magma Block) | Adds a molten blast to the shot and consumes one magma block |

---

### Red Eyes

**Give:** `/reliquary give red_eyes [player]`

| Input | Effect |
| --- | --- |
| Passive | A small movement-speed boost while held |
| Every 4th strike | Stuns the target briefly |
| Right click (Penitence in the off hand) | Serious Skullbuster: leap and ground-slam for an area stun, 3-minute cooldown, and inherit Penitence's on-hit healing |

---

### Regret

**Give:** `/reliquary give regret [player]`

| Input | Effect |
| --- | --- |
| Passive | A heavy hammer that charges to full over about 4.5 seconds while held, shown on the action bar |
| Left click | A hit deals damage scaled by charge, up to a Sharpness VII netherite sword at full, then the charge resets |

---

### Beak

**Give:** `/reliquary give beak [player]`

| Input | Effect |
| --- | --- |
| Right click | Peck: fire one bullet from a 12-round magazine with no knockback |
| Reload | Automatic over 5 seconds when the magazine runs dry |
| Enchants | Multishot fires a rapid three-round burst per trigger. Piercing punches through targets |

---

### Logging

**Give:** `/reliquary give logging [player]`

| Input | Effect |
| --- | --- |
| Left click | Hitting a player builds an implant charge on them, shown on the action bar |
| Right click (at full charge) | Rip Their Heart: tear out the most-recent foe's heart for heavy damage, a bleed, and 9 seconds of Slowness and Weakness |

---

### Wrist Cutter

**Give:** `/reliquary give wrist_cutter [player]`

| Input | Effect |
| --- | --- |
| Left click | Each hit adds a bleed stack with no knockback |
| Passive | When you stop hitting, the accumulated bleed drains slowly, one stack per second |

---

### Christmas

**Give:** `/reliquary give christmas [player]`

| Input | Effect |
| --- | --- |
| Left click | A slow, heavy club with a bone-crunch impact |
| On hit | May gift you a fleeting buff and may gift the target a fleeting effect |

---

### Grinder Mk.4

**Give:** `/reliquary give grinder_mk4 [player]`

| Input | Effect |
| --- | --- |
| Left click | A burst of six grinding hits with no knockback |
| Shift + right click | Toggle a sustained grind: mine a 3x3 area slowly and deal steady tick damage to what is in front |

---

### Crimson Scar

**Give:** `/reliquary give crimson_scar [player]`

| Input | Effect |
| --- | --- |
| Left click | A melee combo. The third strike lunges in and opens a bleed |
| Shift + right click | Swap between the axe and a flintlock pistol |
| Right click (pistol form) | Fire a strong, slow shot, then a long reload |

---

### Cobalt Scar

**Give:** `/reliquary give cobalt_scar [player]`

| Input | Effect |
| --- | --- |
| Left click | Fast, full-damage hits with no attack cooldown for 1.8-style combos. Short reach, and each hit applies bleed |
| Right click | A short dash, 7-second cooldown |
| Passive | The off hand is kept empty while it is held |

---

### Our Galaxy

**Give:** `/reliquary give our_galaxy [player]`

| Input | Effect |
| --- | --- |
| Right click | Fire a homing comet, 3 charges. It can be blocked by a shield or parried by striking the comet |
| Sneak + right click | Short blink in the direction you face. It cannot pass through walls, 25-second cooldown |

---

### Harvest

**Give:** `/reliquary give harvest [player]`

| Input | Effect |
| --- | --- |
| Every 3rd strike | A slow scythe slash. Only this strike heals you |
| On kill | The kill's drops and XP go straight to your inventory |
| Right click (ripe crop) | Harvest and replant it, with a 50% chance of a bonus yield |

---

### Life for a Daredevil

**Give:** `/reliquary give life_for_a_daredevil [player]`

| Input | Effect |
| --- | --- |
| On hit | Execute a low-health foe within 9 blocks by teleporting behind it and decapitating. Thresholds are 25% for mobs, 10% for players, 5% for bosses |
| Passive | Holding it costs you 2 hearts of maximum health |

---

### Laetitia

**Give:** `/reliquary give laetitia [player]`

| Input | Effect |
| --- | --- |
| Right click | Fire an auto-aiming playmate bolt that seeks a chosen target. It can be blocked by a shield |

---

### Lamp

**Give:** `/reliquary give lamp [player]`

| Input | Effect |
| --- | --- |
| Passive | A warm aura grants nearby allies Resistance |
| Sneak + right click | Gaze: copy the debuffs on you onto a marked target |
| Right click | Slam for heavy knockback and little damage, 3-second cooldown |

---

### Magic Bullet

**Give:** `/reliquary give magic_bullet [player]`

| Input | Effect |
| --- | --- |
| Left click | Charge a musket shot with a long, dramatic wind-up, then fire a hitscan that never misses. About 7 seconds to reload between shots |
| Right click | Mark the foe you look at so shots lock onto them |
| Shift + right click (after 6 shots) | The Seventh Bullet: a massive homing orb that gouges a temporary trench, flings block debris with gravity, and deals devastating area damage. If it kills anyone, it strikes you back for heavy self-damage. A 15-second downtime follows |

---

### Solemn Lament

**Give:** `/reliquary give solemn_lament [player]`

| Input | Effect |
| --- | --- |
| Left click | Fire, alternating the two barrels. A short shotgun cone with no knockback |
| Right click | Dump the whole magazine rapidly |
| Reload | Automatic when both magazines empty. 12 rounds per barrel |

---

### In the Name of Love and Hate

**Give:** `/reliquary give love_and_hate [player]`

| Input | Effect |
| --- | --- |
| Left click | Cycle between Love and Hate forms |
| Right click (Love) | Fire a mending bolt that heals allies it touches |
| Right click (Hate) | Summon homing bolts that chase and damage foes |
| Shift + right click (Love) | Minor Arcana Slave: a ricocheting beam that grants Regeneration, 2-minute cooldown |
| Shift + right click (Hate) | Reverse Arcana Slave: a massive follow-laser that tears a temporary path through blocks, 5-minute cooldown |

---

### Sword With Sharpened Tears

**Give:** `/reliquary give sword_of_tears [player]`

| Input | Effect |
| --- | --- |
| Passive | Four rapiers float behind you |
| On hit | A rapier darts in to strike alongside you for piercing follow-up damage |
| Shift + right click | Recharge spent rapiers, 45-second cooldown |

---

### Green Stem

**Give:** `/reliquary give green_stem [player]`

| Input | Effect |
| --- | --- |
| On hit | Applies Poison |
| Passive | A foe at or below 5% health within 5 blocks is impaled on thorns and executed |

---

### Screaming Wedge

**Give:** `/reliquary give screaming_wedge [player]`

| Input | Effect |
| --- | --- |
| Right click | Fire a slow, homing hair-strand bolt, once every 5 seconds |
| On hit | 30% chance to root the target. Each shot has a 50% chance to cost you saturation |

---

### Heaven

**Give:** `/reliquary give heaven [player]`

| Input | Effect |
| --- | --- |
| On hit | Striking a foe that is looking at you deals 10% more damage and can lock it in a brief stasis |

---

## Contributing

Want to build a relic or work on the code? See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Source-available under the [Reliquary License](LICENSE): free for personal, non-commercial fan use. It may **not** be monetized or sold without permission, and may **not** be used to train AI/ML models. See [LICENSE](LICENSE) for the full terms.
