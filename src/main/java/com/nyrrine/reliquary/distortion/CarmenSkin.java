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
 * {@code textures.minecraft.net} and declares which body model wears it. {@link #SIGNATURE} is
 * Mojang's SHA1withRSA signature over it.
 *
 * <p><b>The model is part of the signed payload, not a setting.</b> Her PNG is drawn <b>slim</b>
 * (alex): the arms are 3px wide and the 4th column is transparent, so a manifest that declares
 * classic renders her with Steve's thick arms and a seam where the texture runs out. The declaration
 * lives inside the signature, so it cannot be edited after the fact — changing the model means
 * re-signing the PNG and replacing both strings together. A manifest with no {@code metadata} block
 * at all means classic <i>by omission</i>, which is the quiet way to get this wrong.
 *
 * <p>What was checked before trusting these, rather than assumed:
 * <ul>
 *   <li>The signature verifies against Mojang's published profile-property keys (valid under the
 *       first published RSA-4096 key). Genuinely Mojang-signed, not merely well-formed.</li>
 *   <li>The manifest declares {@code model: slim}, matching how the PNG is actually painted —
 *       verified by reading the arm columns out of the image, not by eye.</li>
 *   <li>The texture the manifest points at was downloaded and compared pixel-for-pixel against the
 *       source PNG — identical, 64x64.</li>
 * </ul>
 *
 * <p>Pinning beats fetching: no signing service in the hot path, no API key, no rate limit, no
 * network call on transform, and nothing that can rot while the server is running. The texture URL
 * is permanent, so these strings stay good.
 *
 * <p>Known assumption: the manifest is bound to the profile id of the account that generated it, not
 * to the wearer's. Vanilla clients don't enforce that binding on texture properties — which is why
 * any of this works. Confirmed rendering correctly in her playtest.
 *
 * <p>To swap Carmen's skin later: re-sign the new PNG <i>with the model its arms are drawn for</i>
 * and replace both strings. They are a matched pair — a value with the wrong signature renders
 * nothing at all, and the right signature over the wrong model renders the wrong body.
 */
final class CarmenSkin {

    /** The name the profile wears in form. Profile names cap at 16 characters. */
    static final String NAME = "Carmen";

    /** Base64 texture manifest (the signed payload) — declares the texture URL and {@code model: slim}. */
    static final String VALUE =
            "ewogICJ0aW1lc3RhbXAiIDogMTczOTU0NTk5NTcwMSwKICAicHJvZmlsZUlkIiA6ICI0OWIzODUyNDdhMWY0NTM3"
            + "YjBmN2MwZTFmMTVjMTc2NCIsCiAgInByb2ZpbGVOYW1lIiA6ICJiY2QyMDMzYzYzZWM0YmY4IiwKICAic2lnbmF0"
            + "dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6"
            + "ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2RhMjY5MzIxOWFlZmJjY2U0Y2RiMTBlYmMy"
            + "YjRlMTkyMTQ3ODBkNDg0NWViNmY5YTQyZWM3ZmRlMmNmMzBjN2EiLAogICAgICAibWV0YWRhdGEiIDogewogICAg"
            + "ICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==";

    /** Mojang's signature over {@link #VALUE}. The pair only works together. */
    static final String SIGNATURE =
            "ZXeGoqlV4/1Ql93UxgMJPCJSdaYUOdWjCbSk/gd3p8sldXvjEICAFeJ4okvnU1P1XNzNV3fa3PpWjt+QCN+MWsFX"
            + "42C3EleaFBl+Jp5b0DGjdBZGCdcQdImmeVhy5c/CuwtujUcXYg2BvpXBU71Hq1PccnwZE4EgU0JE7CjmmFK4GwEm"
            + "KfTzsVxZHfnxxlGUOfhHXUNuQJPw6oHcrbChGHMtSlT5N7GXoQP+iNaiYAL2DKBn75AKsH1bpbv9nl0gX0f8MF1N"
            + "tzBGgYjaVjnHx1scuFEMryEDZ0XsfX+bUyi9BGXPV61Y3ixk+yqT7FNfQ+lv39bhi3MH416AmpQtZup6SqLH+WgC"
            + "CR/TLJZxU01ivD6zwXykzZgUJUYhwDkb37vOyz5berpYFOly76U2D/QTtc2F0bQqiIwSEllB4OGaUoHpEXQQcrT/"
            + "88nIfbEkrau0a9wHwuFYpH8XKcScDBV0JwEA3zCWRMxIKzlNzJs+KLiXr9HzqBb+RN4JSgPbcrWT66bL5GlTQ5aN"
            + "ssTYtZrdJIeecIJDjh7tEBIeisHAp2ZNyIKOUwy8qatv6ZCpZRxx7OSfOrmRPxDWjetdlgOm+LvsTAidxKlfsuRo"
            + "A86tLwCjo53nIQr4Zocf/nkJlzIh1XdETDjT3QiDavUixkBKw6me5+NJ/YV8YTZTMp0=";

    private CarmenSkin() {
    }
}
