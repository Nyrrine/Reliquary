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

### Gungnir

**Status:** Complete · **Give:** `/reliquary give gungnir [player]`

| Input | Effect |
| --- | --- |
| Left click | Loose a straight bolt that flies fast with aim-assist and buries into the first target it meets |
| Right click | Loose a ricochet bolt that homes onto nearby mobs and bounces off the world, ping-ponging and racking up hits until recalled |
| Empty-hand left click, or press F | Recall the spear at will |
| Passive (buried) | A spear sunk into a body makes every strike on it land ~50% harder while it stays lodged |

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

## Contributing

Want to build a relic or work on the code? See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Source-available under the [Reliquary License](LICENSE): free for personal, non-commercial fan use. It may **not** be monetized or sold without permission, and may **not** be used to train AI/ML models. See [LICENSE](LICENSE) for the full terms.
