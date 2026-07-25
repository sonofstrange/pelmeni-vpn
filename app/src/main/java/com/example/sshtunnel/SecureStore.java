package com.example.sshtunnel;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String ALIAS = "ssh_tunnel_key";
    private static final String PREFS = "settings";
    private final SharedPreferences prefs;
    public SecureStore(Context c) { prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public void putPlain(String k, String v) { prefs.edit().putString(k, v).apply(); }
    public String getPlain(String k, String d) { return prefs.getString(k, d); }
    public void putBoolean(String k, boolean v) { prefs.edit().putBoolean(k, v).apply(); }
    public boolean getBoolean(String k, boolean d) { return prefs.getBoolean(k, d); }
    public boolean contains(String k) { return prefs.contains(k); }
    public void putLong(String k, long v) { prefs.edit().putLong(k, v).apply(); }
    public long getLong(String k, long d) { return prefs.getLong(k, d); }
    public void remove(String... keys) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : keys) editor.remove(key);
        editor.apply();
    }
    public void putEncrypted(String key, byte[] value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] encrypted = cipher.doFinal(value);
        prefs.edit()
                .putString("encrypted_" + key,
                        Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("encrypted_" + key + "_iv",
                        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }
    public byte[] getEncrypted(String key) {
        try {
            String encrypted = prefs.getString("encrypted_" + key, null);
            String iv = prefs.getString("encrypted_" + key + "_iv", null);
            if (encrypted == null || iv == null) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP));
        } catch (Exception ignored) {
            return null;
        }
    }
    public void removeEncrypted(String key) {
        prefs.edit().remove("encrypted_" + key)
                .remove("encrypted_" + key + "_iv").apply();
    }
    public void putSecret(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString("password", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).apply();
    }
    public String getSecret() {
        try {
            String e = prefs.getString("password", null), iv = prefs.getString("iv", null);
            if (e == null || iv == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(e, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception ex) { return ""; }
    }
    private SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return kg.generateKey();
    }
}
