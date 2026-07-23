package com.nyrrine.reliquary.extraction;

import com.nyrrine.reliquary.Reliquary;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ShapelessRecipe;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks which placed blocks are extraction stations, so only crafted-and-placed ones act as stations while
 * every ordinary block of the same type stays vanilla. The location→type map persists to {@code stations.yml}
 * in the plugin data folder (survives restarts). Also registers the station crafting recipes.
 */
public final class Stations {

    private final Reliquary plugin;
    private final Map<String, StationType> placed = new HashMap<>();
    private final File file;

    public Stations(Reliquary plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stations.yml");
    }

    private static String key(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    /** The world-locations of every placed Carmen's Brain (a placed WELL), for the idle VFX manager. */
    public java.util.List<Location> wells() {
        java.util.List<Location> out = new java.util.ArrayList<>();
        for (Map.Entry<String, StationType> e : placed.entrySet()) {
            if (e.getValue() != StationType.WELL) continue;
            String[] p = e.getKey().split(",");
            if (p.length != 4) continue;
            org.bukkit.World w = plugin.getServer().getWorld(p[0]);
            if (w == null) continue;
            try {
                out.add(new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
            } catch (NumberFormatException ignored) { /* malformed key */ }
        }
        return out;
    }

    public void register(Block block, StationType type) {
        placed.put(key(block.getLocation()), type);
        save();
    }

    public void unregister(Block block) {
        if (placed.remove(key(block.getLocation())) != null) save();
    }

    // ---- persistence ---------------------------------------------------------------

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String k : cfg.getKeys(false)) {
            try {
                placed.put(k, StationType.valueOf(cfg.getString(k, "")));
            } catch (IllegalArgumentException ignored) { /* stale/renamed type */ }
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, StationType> e : placed.entrySet()) cfg.set(e.getKey(), e.getValue().name());
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save stations.yml: " + e.getMessage());
        }
    }

    // ---- recipes -------------------------------------------------------------------

    /** Register (or re-register) the shapeless crafting recipe for each station. */
    public void registerRecipes() {
        for (StationType t : StationType.values()) {
            NamespacedKey rk = new NamespacedKey(plugin, "station_" + t.name().toLowerCase());
            plugin.getServer().removeRecipe(rk); // idempotent — safe across /reload
            ShapelessRecipe recipe = new ShapelessRecipe(rk, t.createItem());
            for (Material m : t.ingredients()) recipe.addIngredient(m);
            plugin.getServer().addRecipe(recipe);
        }
    }
}
