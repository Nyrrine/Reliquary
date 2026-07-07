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

    /** Called every 2 ticks while the player is an active wielder. Return false to disengage. */
    default boolean onTick(org.bukkit.entity.Player player, long tick) { return false; }

    default void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {}

    default void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {}

    /** A player left — drop any per-player state you keep for them. */
    default void onQuit(java.util.UUID id) {}

    /** True to cancel this player's fall damage right now (e.g. they just dashed). */
    default boolean cancelsFallDamage(java.util.UUID id) { return false; }
}
