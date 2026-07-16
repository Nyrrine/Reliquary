package com.nyrrine.reliquary.distortion;

/**
 * Carmen's skin, pinned as a Mojang-signed textures property.
 *
 * <p>A PNG on its own can't be worn. Applying a skin at runtime means handing the player's profile a
 * {@code textures} property, and the game only accepts one that carries Mojang's signature — which
 * only Mojang's session servers can mint. So the skin was pushed through a skin-signing service
 * (MineSkin) once, and the two strings it minted are pinned right here.
 *
 * <p>{@link #VALUE} is the base64 texture manifest — it names the texture's permanent home on
 * {@code textures.minecraft.net}. {@link #SIGNATURE} is Mojang's SHA1withRSA signature over it.
 *
 * <p>What was checked before trusting them, rather than assumed:
 * <ul>
 *   <li>The signature verifies against Mojang's live profile-property keys (valid under the first
 *       published RSA-4096 key). It is genuinely Mojang-signed, not merely well-formed.</li>
 *   <li>The texture the manifest points at was downloaded and compared pixel-for-pixel against the
 *       source PNG — identical, 64x64, classic (non-slim) model.</li>
 * </ul>
 *
 * <p>Pinning beats fetching: no signing service in the hot path, no API key, no rate limit, no
 * network call on transform, and nothing that can rot while the server is running. The texture URL
 * is permanent, so these strings stay good.
 *
 * <p>Known assumption, worth knowing before debugging a skin that won't render: the manifest is
 * bound to the profile id of the account that generated it, not to the wearer's. Vanilla clients
 * don't enforce that binding on texture properties — which is the only reason any skin plugin works
 * — but it is the one link in the chain that can't be proven without a client actually looking at it.
 *
 * <p>To swap Carmen's skin later: re-sign the new PNG the same way and replace both strings. They
 * are a matched pair — a value with the wrong signature renders nothing.
 */
final class CarmenSkin {

    /** The name the profile wears in form. Profile names cap at 16 characters. */
    static final String NAME = "Carmen";

    /** Base64 texture manifest (the signed payload). */
    static final String VALUE =
            "ewogICJ0aW1lc3RhbXAiIDogMTc0NDcyMjQ2NTU5NywKICAicHJvZmlsZUlkIiA6ICJjY2MxNGM2ZDUwMDE0MjBm"
            + "YmMxYjkyMTM2Y2JmOWU4MSIsCiAgInByb2ZpbGVOYW1lIiA6ICJab25lX1gwODE1IiwKICAic2lnbmF0dXJlUmVx"
            + "dWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRw"
            + "Oi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2RhMjY5MzIxOWFlZmJjY2U0Y2RiMTBlYmMyYjRlMTky"
            + "MTQ3ODBkNDg0NWViNmY5YTQyZWM3ZmRlMmNmMzBjN2EiCiAgICB9CiAgfQp9";

    /** Mojang's signature over {@link #VALUE}. The pair only works together. */
    static final String SIGNATURE =
            "YmlJKYcY/jHv//9s+P6hj7Q6sFMqHurvnjtlmY0ZJO5TFn6vTX5MmAoAGLejuHCJZ40KOIKkI22GqAHhot8GMB7H"
            + "U2XX/iQJfONAeK0ofeK0DZczX39OisWq9JWZsJJdE8oR4c96mbWt9h8IimBXwvj9EiMPgFaps3tE3m9KDy3/qj0w"
            + "Kxu186QnRGSr1QFUXDPGBhNb3XFPk7tIXyMfTz1J8+ZMrwVzdM1OG24pQ9I0H80InQgSaSVsd1a4vgCish1OuL3E"
            + "1lvXgPKRwCtc04Qi66vI50kx2vO8kJZiD9dCoKSOqNATYCTkQbGAfOhaFNERsniNzcDGYs50HotgcXNml0W9qUna"
            + "tU/Lah31wCSgAErT3IQZ4641PgNCaB1YnzPjO+l0mDvVxQPqDVeRXF8ktoI+PoYMnzKqH05g5ARnMdVfogdmhSoY"
            + "gvie6k4Ybet5ZIVDZa8Zf930DxPKZWu2O7c1bJxYzZ52mV+wb6B+59QFSe+cGz0HARoqG/AyhqvUG17eWaNMPItF"
            + "YCoIf6i+3nbZA4R6wtYp1LCECDniDV3V/DNMgmV4Q41yrBcElBxvn7XDg5GcJhABYP2Yyb64FcoK2GVuaFfZNeYD"
            + "l5zt4ImSBu110Jl7KVWnwjLw2bC6LNrFQQllPkxV6gXCpRAzKs6oBYLxK+F8F0EXfIY=";

    private CarmenSkin() {
    }
}
