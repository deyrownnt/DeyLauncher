package com.deylauncher.auth;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.prefs.Preferences;

/** Small local vault for a Microsoft refresh token. The encrypted payload can safely live in
 * profile.json; the per-user AES key is held separately in the OS Java preferences store. */
public final class TokenVault {
    private static final Preferences PREFS = Preferences.userNodeForPackage(TokenVault.class);
    private static final String KEY_NAME = "refresh-token-key-v1";
    private TokenVault() {}

    public static String encrypt(String value) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] output = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, output, 0, iv.length);
        System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(output);
    }

    public static String decrypt(String encoded) throws Exception {
        byte[] input = Base64.getDecoder().decode(encoded);
        if (input.length <= 12) throw new IllegalArgumentException("Invalid saved session");
        byte[] iv = java.util.Arrays.copyOfRange(input, 0, 12);
        byte[] payload = java.util.Arrays.copyOfRange(input, 12, input.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
    }

    private static SecretKey key() throws Exception {
        String encoded = PREFS.get(KEY_NAME, null);
        if (encoded != null) return new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(encoded), "AES");
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        PREFS.put(KEY_NAME, Base64.getEncoder().encodeToString(key.getEncoded()));
        return key;
    }
}
