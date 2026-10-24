package club.champion.tennis;

import org.json.*;

/** Consumer account returned by phone/SMS authorization. Password and SMS code are never stored. */
public final class UserSession {
    public final long id;
    public final String token, name, phone, email;
    public UserSession(JSONObject response) throws JSONException {
        JSONObject data = response.optJSONObject("data");
        JSONObject user = data != null ? data : response;
        id = user.optLong("id"); token = user.optString("user_token");
        name = user.optString("name"); phone = user.optString("phone"); email = user.optString("email");
        if (id <= 0 || token.isEmpty() || token.matches("(?s).*[\\s,].*") || !new ClientProfile("", "", phone, "").validPhone())
            throw new JSONException("YCLIENTS не вернул данные аккаунта. Повторите вход.");
    }
    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("id",id).put("user_token",token).put("name",name).put("phone",phone).put("email",email);
    }
    public String identity() { return id + ":" + token; }
}
