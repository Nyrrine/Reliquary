package com.nyrrine.reliquary.distortion;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves Carmen's pinned skin is really Mojang-signed, without a server or a network.
 *
 * <p>This exists because the skin is the one part of the form that can't be checked by reading it.
 * The strings are opaque base64; a corrupted, truncated or re-signed pair looks exactly as plausible
 * as a good one, and the only other way to find out is a player logging in and seeing no skin. So
 * the signature is verified here instead, the same way the game verifies it.
 *
 * <p>The key below is Mojang's published profile-property key — the one that actually signed this
 * value. Key and signature are pinned together as a frozen pair, so this never touches the network
 * and never cares whether Mojang rotates keys later: an old signature stays valid under the key that
 * made it.
 *
 * <p>If this test fails, the skin is broken and would have rendered as nothing in-game. Replacing
 * the skin means replacing value, signature, and — if it was signed under a newer key — this key
 * too. Don't delete the test to make it pass.
 */
class CarmenSkinTest {

    /** Mojang's profile-property public key that signed {@link CarmenSkin#SIGNATURE}. */
    private static final String MOJANG_PROFILE_PROPERTY_KEY =
            "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAylB4B6m5lz7jwrcFz6Fd/fnfUhcvlxsTSn5kIK/2aGG1"
            + "C3kMy4VjhwlxF6BFUSnfxhNswPjh3ZitkBxEAFY25uzkJFRwHwVA9mdwjashXILtR6OqdLXXFVyUPIURLOSWqGNB"
            + "tb08EN5fMnG8iFLgEJIBMxs9BvF3s3/FhuHyPKiVTZmXY0WY4ZyYqvoKR+XjaTRPPvBsDa4WI2u1zxXMeHlodT3l"
            + "nCzVvyOYBLXL6CJgByuOxccJ8hnXfF9yY4F0aeL080Jz/3+EBNG8RO4ByhtBf4Ny8NQ6stWsjfeUIvH7bU/4zCYc"
            + "YOq4WrInXHqS8qruDmIl7P5XXGcabuzQstPf/h2CRAUpP/PlHXcMlvewjmGU6MfDK+lifScNYwjPxRo4nKTGFZf/"
            + "0aqHCh/EAsQyLKrOIYRE0lDG3bzBh8ogIMLAugsAfBb6M3mqCqKaTMAf/VAjh5FFJnjS+7bE+bZEV0qwax1CEoPP"
            + "JL1fIQjOS8zj086gjpGRCtSy9+bTPTfTR/SJ+VUB5G2IeCItkNHpJX2ygojFZ9n5Fnj7R9ZnOM+L8nyIjPu3aePv"
            + "tcrXlyLhH/hvOfIOjPxOlqW+O5QwSFP4OEcyLAUgDdUgyW36Z5mB285uKW/ighzZsOTevVUG2QwDItObIV6i8RCx"
            + "FbN2oDHyPaO5j1tTaBNyVt8CAwEAAQ==";

    @Test
    void signatureIsGenuinelyMojangSigned() throws Exception {
        PublicKey mojang = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(MOJANG_PROFILE_PROPERTY_KEY)));

        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initVerify(mojang);
        // Mojang signs the base64 text itself, not the json inside it.
        signature.update(CarmenSkin.VALUE.getBytes(StandardCharsets.US_ASCII));

        assertTrue(signature.verify(Base64.getDecoder().decode(CarmenSkin.SIGNATURE)),
                "Carmen's textures signature does not verify against Mojang's key — the skin would not render");
    }

    @Test
    void valueDescribesASkinTexture() {
        String manifest = new String(Base64.getDecoder().decode(CarmenSkin.VALUE), StandardCharsets.UTF_8);

        assertTrue(manifest.contains("\"SKIN\""),
                "the manifest carries no SKIN texture: " + manifest);
        assertTrue(manifest.contains("textures.minecraft.net/texture/"),
                "the skin must be hosted by Mojang or the client won't fetch it: " + manifest);
    }

    @Test
    void nameFitsAProfile() {
        // Profile names are capped at 16 characters; a longer one is rejected on the way in.
        assertTrue(CarmenSkin.NAME.length() <= 16, "profile name too long: " + CarmenSkin.NAME);
        assertEquals("Carmen", CarmenSkin.NAME);
    }
}
