package com.nyrrine.reliquary.distortion;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A beautiful voice — the distortion system's way in and out.
 *
 * <p>Everything the system needs is wired from here so the plugin's main class only has to say
 * "enable" and "disable". Nothing else in Reliquary needs to know this package exists.
 */
public final class Distortion {

    private final JavaPlugin plugin;
    private final CarmenForm form = new CarmenForm();

    public Distortion(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        DistortionCommand command = new DistortionCommand(form);
        bind("abeautifulvoice", command);
        bind("carmen", command);

        plugin.getServer().getPluginManager().registerEvents(form, plugin);
        plugin.getServer().getPluginManager().registerEvents(new CarmenChat(form), plugin);
    }

    /**
     * Hands everyone back their own face.
     *
     * <p>Matters most on a reload, where players stay connected across the swap: without this they'd
     * be left wearing a Carmen the server no longer has any way to take off.
     */
    public void disable() {
        form.restoreAll();
    }

    private void bind(String name, DistortionCommand command) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd == null) return; // not declared in plugin.yml — nothing to bind
        cmd.setExecutor(command);
        cmd.setTabCompleter(command);
    }
}
