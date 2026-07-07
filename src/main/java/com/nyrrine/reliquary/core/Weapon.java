package com.nyrrine.reliquary.core;

/**
 * A single relic weapon in the vault. Each relic owns its item, its identity, and
 * its behaviour; the {@link WeaponManager} routes the shared game events to it.
 */
public interface Weapon {
    String id();

    org.bukkit.inventory.ItemStack createItem();

    boolean matches(org.bukkit.inventory.ItemStack item);

    default void onSwing(org.bukkit.entity.Player player) {}

    default void onInteract(org.bukkit.entity.Player player, boolean sneaking) {}

    default void onTick(org.bukkit.entity.Player player, long tick) {}

    default void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {}

    default void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {}
}
