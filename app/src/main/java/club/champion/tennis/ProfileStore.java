package club.champion.tennis;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** AES-GCM ciphertext in private preferences; the key stays in Android Keystore. */
public final class ProfileStore {
    private static final String ALIAS = "champion_contact_profile_v1";
    private final SharedPreferences preferences;
    private final String integrationKey;
    public ProfileStore(Context context) { this(context,AppEnvironment.partnerToken()); }
    ProfileStore(Context context,String integrationKey) { preferences = context.getSharedPreferences("private_contact_profile", Context.MODE_PRIVATE);this.integrationKey=integrationKey; }
    public boolean enabled() { return preferences.getBoolean("remember", true); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }
    private JSONObject read(String field) throws Exception {
        String saved = preferences.getString(field, "");
        if (saved.isEmpty()) return new JSONObject();
        String[] parts = saved.split(":");
        if (parts.length != 3 || !parts[0].equals("1")) throw new GeneralSecurityException("Неизвестный формат профиля");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)));
        byte[] plain = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP));
        return new JSONObject(new String(plain, StandardCharsets.UTF_8));
    }
    private void write(String field, JSONObject data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        String value = "1:" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!preferences.edit().putString(field, value).commit()) throw new java.io.IOException("Не удалось сохранить данные");
    }
    public synchronized ClientProfile load() throws Exception { return enabled() ? ClientProfile.fromJson(read("contact")) : ClientProfile.empty(); }
    public synchronized void save(ClientProfile profile, boolean remember) throws Exception {
        if (!remember) { if (!preferences.edit().remove("contact").putBoolean("remember", false).commit()) throw new java.io.IOException("Не удалось сохранить настройки"); return; }
        write("contact", profile.toJson()); preferences.edit().putBoolean("remember", true).apply();
    }
    public synchronized String token() throws Exception { return integrationKey; }
    public synchronized UserSession session() throws Exception {
        JSONObject user=read("account_session"); return user.length()==0?null:new UserSession(user);
    }
    public synchronized void session(UserSession user) throws Exception { write("account_session",user.toJson()); }
    public synchronized void logout() throws java.io.IOException {
        if(!preferences.edit().remove("account_session").commit())throw new java.io.IOException("Не удалось выйти из аккаунта");
    }
    public synchronized long smsAfter(String phone) throws Exception { return read("sms_cooldown").optLong(phone); }
    public synchronized void smsAfter(String phone,long time) throws Exception {
        JSONObject gates=read("sms_cooldown");gates.put(phone,time);write("sms_cooldown",gates);
    }
    public synchronized JSONObject attempts() throws Exception { return read("booking_attempts"); }
    public synchronized void attempt(long activityId, JSONObject attempt) throws Exception {
        JSONObject all = attempts();
        if (attempt == null) all.remove(Long.toString(activityId)); else all.put(Long.toString(activityId), attempt);
        write("booking_attempts", all);
    }
    public synchronized void clear() { preferences.edit().remove("contact").apply(); }
}
