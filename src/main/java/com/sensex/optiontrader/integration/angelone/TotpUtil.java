package com.sensex.optiontrader.integration.angelone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;

/**
 * RFC 6238 TOTP generator using HMAC-SHA1. No external dependencies — uses JDK crypto.
 * <p>
 * Accepts secrets in multiple formats (auto-detected):
 * <ul>
 *   <li>Base32 — standard for Google Authenticator (only A-Z, 2-7)</li>
 *   <li>Hex — e.g. Angel One sometimes provides hex strings (0-9, A-F)</li>
 *   <li>Raw ASCII — used as-is as the HMAC key bytes</li>
 * </ul>
 */
final class TotpUtil {

    private static final int DIGITS = 6;
    private static final int TIME_STEP_SECONDS = 30;
    private static final String HMAC_ALGO = "HmacSHA1";
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpUtil() {}

    static String generateTotp(String secret) {
        byte[] key = decodeSecret(secret);
        long counter = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS;
        byte[] counterBytes = ByteBuffer.allocate(8).putLong(counter).array();

        byte[] hash;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            hash = mac.doFinal(counterBytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }

        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        int otp = binary % (int) Math.pow(10, DIGITS);
        return String.format("%0" + DIGITS + "d", otp);
    }

    /**
     * Auto-detects the secret encoding and returns raw key bytes.
     * Priority: Base32 if all chars are valid Base32 → hex if all chars are hex → raw UTF-8 bytes.
     */
    private static byte[] decodeSecret(String secret) {
        String cleaned = secret.replaceAll("[\\s=-]", "");

        if (isBase32(cleaned)) {
            return base32Decode(cleaned);
        }
        if (isHex(cleaned)) {
            return hexDecode(cleaned);
        }
        return cleaned.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isBase32(String s) {
        String upper = s.toUpperCase(Locale.ROOT);
        for (char c : upper.toCharArray()) {
            if (BASE32_CHARS.indexOf(c) < 0) return false;
        }
        return !s.isEmpty();
    }

    private static boolean isHex(String s) {
        return s.matches("[0-9a-fA-F]+") && s.length() % 2 == 0;
    }

    private static byte[] base32Decode(String encoded) {
        String s = encoded.toUpperCase(Locale.ROOT);
        int outLen = s.length() * 5 / 8;
        byte[] out = new byte[outLen];
        int buffer = 0, bitsLeft = 0, idx = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | BASE32_CHARS.indexOf(c);
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[idx++] = (byte) ((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out;
    }

    private static byte[] hexDecode(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    | Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
