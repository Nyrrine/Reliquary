# Reliquary

Reliquary adds custom relic weapons to a Minecraft server. It's a Paper plugin, so it's server-side only — players use the relics with a vanilla client, no mods required.

First relic done: **Arayashiki**. More are planned.

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
| Right click | Dash where you're looking — 3 charges, each recharging ~3s; cuts what you pass through; cancels fall damage |
| Shift + hold right click | Sustained slash storm around you while held |
| On kill | Erases the target (no drops) and refills all dashes |
| Passive | Holding it drains a 3-minute meter; it erodes the item's own name as it drops, becomes unusable at empty, and refills (~45s) while sheathed |

---

### Roadmap

#### Helm of Mambrino

| | |
| --- | --- |
| Status | 🔜 Planned |

#### Gungnir

| | |
| --- | --- |
| Status | 🔜 Planned |

#### The Eye of Odin

| | |
| --- | --- |
| Status | 🔜 Planned |

#### Lævateinn

| | |
| --- | --- |
| Status | 🔜 Planned |

---

### Special — Project Moon

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
