package me.perdomo.dpc;

import android.util.Base64;

import java.security.MessageDigest;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Verifies the maintenance passcode against the PBKDF2 hash in {@link Policy}.
 *
 * <p>Hashed rather than compared in the clear because the APK is served over
 * the network during QR provisioning - assume anyone who can enrol a device
 * can also read the binary.</p>
 */
public final class Passcode {

    private Passcode() {}

    public static boolean verify(char[] input) {
        try {
            byte[] salt = Base64.decode(Policy.PASSCODE_SALT_B64, Base64.NO_WRAP);
            byte[] expected = Base64.decode(Policy.PASSCODE_HASH_B64, Base64.NO_WRAP);
            if (expected.length == 0) {
                return false;
            }
            PBEKeySpec spec = new PBEKeySpec(
                    input, salt, Policy.PASSCODE_ITERATIONS, expected.length * 8);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            spec.clearPassword();
            return MessageDigest.isEqual(actual, expected);
        } catch (Exception e) {
            return false;
        }
    }

    /** True when Policy still holds the placeholder hash. */
    public static boolean isUnconfigured() {
        return "REPLACE_ME".equals(Policy.PASSCODE_HASH_B64);
    }
}
