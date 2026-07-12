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

    /** The station type at a block, or {@code null} if it's an ordinary block. */
    public StationType typeAt(Block block) {
        StationType t = placed.get(key(block.getLocation()));
        // Guard against a registry that outlived its block (explosion, piston, WorldEdit, fluid): if the
        // block no longer matches the station's base material, it isn't a station — don't honor a ghost.
        if (t != null && block.getType() != t.base()) return null;
        return t;
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
