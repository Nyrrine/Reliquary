# Contributing to Reliquary

Reliquary is built to make adding weapons easy. Here's what you need to work on it or build a relic.

## Getting the code

```bash
git clone https://github.com/Nyrrine/Reliquary.git
cd Reliquary
```

## Building

You need **JDK 25**. The Gradle wrapper is included, so you don't need Gradle installed separately.

```bash
./gradlew build
```

The plugin lands at `build/libs/Reliquary-x.x.x.jar`. Drop it into a Paper 26.1.2 test server's `plugins/` folder to try it.

## Project layout

```
src/main/java/com/nyrrine/reliquary/
├── Reliquary.java          # plugin entry point — registers relics, handles /commands
├── core/
│   ├── Weapon.java         # the interface every relic implements
│   └── WeaponManager.java  # registry + shared event wiring + the tick loop
└── weapons/
    └── arayashiki/         # one relic, fully self-contained
```

`core/` is the framework, `weapons/<name>/` is a relic. `WeaponManager` owns all the Bukkit event listeners and the per-tick loop, works out which relic a player is holding, and calls the matching method on it. A relic never touches an event listener directly — it just implements behavior.

## Adding a relic

1. Make a package under `weapons/`, e.g. `weapons/gungnir/`.
2. Write a class that implements `Weapon`:

   ```java
   public final class GungnirWeapon implements Weapon {
       public String id() { return "gungnir"; }
       public ItemStack createItem() { /* build the item */ }
       public boolean matches(ItemStack item) { /* is this my relic? */ }

       // override only the hooks you need:
       // onSwing, onInteract, onTick, onEntityDeath, onPlayerDeath,
       // cancelsFallDamage, onQuit
   }
   ```

3. Register it in `Reliquary.onEnable`:

   ```java
   weapons.register(new GungnirWeapon(this));
   ```

It'll show up in `/reliquary give gungnir` and `/reliquary list`.

## Good to know

- **`matches(ItemStack)`** is how a relic claims an item. Tag your item with a `PersistentDataContainer` key and check it here. Don't rely on the item's name or lore — those change at runtime.
- **`onTick(player, tick)` returns a boolean.** Return `true` while the player is actively using the relic (or something's still running, like a cooldown), `false` when they're idle. The manager only ticks "engaged" players, so a single relic on a 100-player server costs about one player's worth of work. Don't do heavy per-tick scans for players who aren't using your relic.
- **Everything runs on the main server thread.** Keep per-tick work light; schedule effects with Bukkit's scheduler.
- Clean up per-player state in **`onQuit(uuid)`** so nothing leaks over long uptimes.

## Performance

The tick loop only touches active wielders, event handlers bail out cheaply for players not holding a relic, and there's no I/O or off-thread work. For a heavily loaded server, run it with a tuned JVM (Aikar's flags) and a sensible heap — that's server config, not a plugin concern.
