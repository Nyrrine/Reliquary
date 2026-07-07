# Reliquary

A Paper 26.1.2 plugin — a vault of unique, lore-driven "relic" weapons for Minecraft. Each relic is a self-contained weapon with its own item, identity, and hand-crafted behaviour, all routed through a small shared framework. The first relic is **Arayashiki**, the concept-severing blade of the House of Spiders.

## The Relics

### Arayashiki

A star-forged blade that severs things not just from the body but from memory itself.

- **White swoop slashes** — every left-click swing throws one big randomized white sword-swoop that arcs around you, damaging everything in a wide forward cone.
- **Memory / use-time cooldown** — the blade is drawn from the wielder's own memory. Holding it drains its use-time; sheathing it lets the memory regenerate. As it drains, the blade's own name erodes letter by letter into blank space (and its lore frays with it). Fully hollow, it can no longer cut.
- **Muga feet-smoke aura** — a subtle dark smoke with a faint red ember tint curls low around the wielder's feet while the blade is held.
- **Fade-to-white erasure kills** — anything slain by Arayashiki dissolves upward into bright white as it is erased from existence, dropping nothing (mobs) or leaving a custom "erased from existence" death message (players).
- **Right-click directional dash** — a Tracer-style dash exactly where you look, with 3 stored charges that each recharge on their own timer, cutting through every entity it passes.
- **Sneak + hold-shift channel** — sneak + right-click begins a sustained ball-of-slashes "nova" that keeps storming while you hold shift, firing real swoops in every direction and shredding everything nearby while it rapidly burns memory.

## Build

Requires **JDK 25** (Paper 26.1.2 targets Java 25).

```bash
export JAVA_HOME="$HOME/jdk25/jdk-25.0.3+9"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$HOME/arayashiki"
./gradlew build --no-daemon --console=plain
```

The jar lands at `build/libs/Reliquary-0.1.0.jar`. Drop it into your server's `plugins/` folder and restart.

## Adding a new relic

1. Implement the `Weapon` interface (`com.nyrrine.reliquary.core.Weapon`) in a new package under `com/nyrrine/reliquary/weapons/`.
2. Register it in `Reliquary.onEnable` via `weapons.register(new YourWeapon(this));`.

The `WeaponManager` handles the shared swing guard, the right-click routing, the mob/player death hooks, and the per-player tick loop, dispatching each event to whichever relic the player is holding.

## Controls

| Input | Action |
| --- | --- |
| Left-click | Swing (white swoop slash) |
| Right-click | Directional dash (3 charges) |
| Sneak + right-click, then hold shift | Nova (channeled ball-of-slashes) |
