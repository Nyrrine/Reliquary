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
     * can react when it isn't held (e.g. recalling a thrown weapon). Return true if handled.
     */
    default boolean onBareSwing(org.bukkit.entity.Player player) { return false; }

    default void onInteract(org.bukkit.entity.Player player, boolean sneaking) {}

    /**
     * The wielder landed a melee hit on a living entity with this relic in the main hand. Fired for
     * vanilla weapon damage so a relic can add an on-hit gimmick (heal, brand, bleed, bonus damage via
     * {@code event.setDamage(...)}, particles) without replacing the vanilla swing.
     *
     * <p>Damage dealt from in here does <b>not</b> come back to you: the manager marks this hook while it
     * runs, so a splash or a cleave struck from within is recognised as your own and never re-dispatched.
     * Damage dealt <b>later</b> is a different matter — a follow-up a scheduler delivers, a burst, a shot
     * fired from a trigger — because by then this hook has returned and the blow looks exactly like a fresh
     * swing again. Route those through {@code WeaponManager.dealing(...)} or keep a guard of your own.
     * Default: no-op.
     */
    default void onHit(org.bukkit.entity.Player attacker,
                       org.bukkit.entity.LivingEntity victim,
                       org.bukkit.event.entity.EntityDamageByEntityEvent event) {}

    /**
     * The wielder took damage with this relic in the main hand — the mirror of {@link #onHit}. Fired for
     * every cause (a mob or player strike, fall, fire, drowning, poison), and only for damage that actually
     * lands: it runs after every other listener has had its say, so a relic can count real hits without a
     * later cancel leaving a phantom in the tally. {@code event.getFinalDamage()} is the settled
     * post-armour figure here. Note a fully-blocked or absorbed strike still arrives with a final damage of
     * 0 — check it if "was struck" and "lost health" mean different things to your relic.
     *
     * <p>Read the event, don't write it. This is dispatched at monitor priority, so {@code setDamage} or
     * {@code setCancelled} here would mislead the listeners that already read their final values. To reach
     * the striker, narrow the event: {@code if (event instanceof EntityDamageByEntityEvent e)} then
     * {@code e.getDamager()}. Avoid calling {@code victim.damage()} in here — it re-enters this dispatch.
     * Default: no-op.
     */
    default void onDamaged(org.bukkit.entity.Player victim,
                           org.bukkit.event.entity.EntityDamageEvent event) {}

    /**
     * The wielder is about to take damage with this relic in the main hand — the <b>writable</b>, earlier
     * mirror of {@link #onDamaged}. Dispatched at HIGH priority, before the monitor {@link #onDamaged} pass.
     * Unlike onDamaged (read-only), you MAY reduce or zero the blow here via {@code event.setDamage(...)}.
     * Prefer {@code setDamage(0.0)} over {@code setCancelled}: a zeroed-but-live event still reaches the
     * monitor onDamaged dispatch, so a counting or countering relic still sees the strike; a cancelled one
     * would not. Default: no-op.
     */
    default void onIncomingDamage(org.bukkit.entity.Player wielder,
                                  org.bukkit.event.entity.EntityDamageEvent event) {}

    /**
     * The player pressed the swap-hands key (F). Dispatched to every weapon regardless of what's
     * held, so a relic can react even when its item has left the hand (e.g. recalling a thrown weapon).
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
