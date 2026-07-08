package com.nyrrine.reliquary.core;

/**
 * A single relic weapon in the vault. Each relic owns its item, its identity, and
 * its behaviour; the {@link WeaponManager} routes the shared game events to it.
 */
public interface Weapon {
    String id();

    org.bukkit.inventory.ItemStack createItem();

    /** An admin/debug variant of this relic (e.g. maxed/god mode), or null if it has none. */
    default org.bukkit.inventory.ItemStack adminVariant() { return null; }

    boolean matches(org.bukkit.inventory.ItemStack item);

    default void onSwing(org.bukkit.entity.Player player) {}

    /**
     * The player left-clicked (swung) with an empty main hand. Dispatched to every weapon so a relic
     * can react when it isn't held (e.g. Gungnir recalling its spear). Return true if handled.
     */
    default boolean onBareSwing(org.bukkit.entity.Player player) { return false; }

    default void onInteract(org.bukkit.entity.Player player, boolean sneaking) {}

    /**
     * The player pressed the swap-hands key (F). Dispatched to every weapon regardless of what's
     * held, so a relic can react even when its item has left the hand (e.g. Gungnir's recall).
     */
    default void onSwapHands(org.bukkit.entity.Player player,
                             org.bukkit.event.player.PlayerSwapHandItemsEvent event) {}

    /** Called every 2 ticks while the player is an active wielder. Return false to disengage. */
    default boolean onTick(org.bukkit.entity.Player player, long tick) { return false; }

    default void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {}

    default void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {}

    /** A player joined — a chance to restore anything owed to them (e.g. a relic held mid-flight). */
    default void onJoin(org.bukkit.entity.Player player) {}

    /** A player left — drop any per-player state you keep for them. */
    default void onQuit(java.util.UUID id) {}

    /** The plugin is shutting down — a chance to return any relic that's out in the world. */
    default void onDisable() {}

    /** Debug lines for any relics this weapon has out in the world (in flight), shown by /reliquary track. */
    default java.util.List<String> outstandingReport() { return java.util.List.of(); }

    /** True to cancel this player's fall damage right now (e.g. they just dashed). */
    default boolean cancelsFallDamage(java.util.UUID id) { return false; }
}
