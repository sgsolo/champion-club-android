package club.champion.tennis;

import org.json.*;

/** Only contact details entered by the user. No passwords, cookies or consent flags. */
public final class ClientProfile {
    public final String name, surname, phone, email;
    public ClientProfile(String name, String surname, String phone, String email) {
        this.name = clean(name); this.surname = clean(surname);
        this.phone = clean(phone); this.email = clean(email);
    }
    private static String clean(String s) { return s == null ? "" : s.trim(); }
    public static ClientProfile empty() { return new ClientProfile("", "", "", ""); }
    public static ClientProfile fromJson(JSONObject j) {
        return new ClientProfile(j.optString("name"), j.optString("surname"), j.optString("phone"), j.optString("email"));
    }
    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("name", name).put("surname", surname).put("phone", phone).put("email", email);
    }
    public String normalizedPhone() {
        String digits = phone.replaceAll("[^0-9]", "");
        if(phone.startsWith("+"))return digits;
        if (digits.length() == 11 && digits.startsWith("8")) return "7" + digits.substring(1);
        if (digits.length() == 10) return "7" + digits;
        return digits;
    }
    public boolean validPhone() { String n = normalizedPhone(); return n.startsWith("7")?n.length()==11:n.length()>=10&&n.length()<=15; }
}
