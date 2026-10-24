package club.champion.tennis;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/** Official online booking API. A developer-issued Partner Token is required. */
public class NativeBookingApi {
    public static final String API = AppEnvironment.apiBase();
    public JSONObject settings() throws Exception {
        HttpURLConnection c = AppEnvironment.open(AppEnvironment.LOCAL ? API+"/settings" : "https://platform.yclients.com/api/v1/b2c/channel/widget/location/1538880/?forBooking=1");
        try {
            c.setConnectTimeout(12000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) throw new IOException("Не удалось проверить настройки клуба");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[4096]; int n; while ((n=in.read(b))!=-1) { if(out.size()>1048576)throw new IOException("Слишком большой ответ");out.write(b,0,n); }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { c.disconnect(); }
    }
    public static JSONObject payload(Training t, ClientProfile p, String comment, boolean consent) throws JSONException {
        if (!consent || p.name.isEmpty() || !p.validPhone()) throw new IllegalArgumentException("Проверьте имя, телефон и согласие");
        JSONObject appointment = new JSONObject().put("activityId", t.id).put("activityType", 2).put("id", 0)
            .put("staff_id", t.source.getLong("staff_id")).put("services",new JSONArray().put(t.source.getLong("service_id")))
            .put("datetime", t.date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")))
            .put("clients_count",1).put("events", new JSONArray()).put("custom_fields",new JSONObject());
        return new JSONObject().put("phone",p.normalizedPhone()).put("fullname",p.name).put("surname",p.surname)
            .put("email",p.email).put("comment",comment).put("bookform_id",1724922).put("isMobile",true)
            .put("notify_by_sms",0).put("notify_by_email",0).put("is_newsletter_allowed",false).put("is_yc_newsletter_allowed",false)
            .put("is_personal_data_processing_allowed",true).put("is_yc_personal_data_processing_allowed",true)
            .put("appointments",new JSONArray().put(appointment));
    }
    public JSONObject book(long activityId, JSONObject payload, String token) throws Exception {
        return book(activityId,payload,token,null);
    }
    public JSONObject book(long activityId, JSONObject payload, String token, String userToken) throws Exception {
        if (token == null || token.trim().isEmpty() || token.contains("\n") || token.contains("\r")) throw new Rejected(NetworkMessage.ERROR);
        HttpURLConnection connection = AppEnvironment.open(API + "/activity/1538880/" + activityId + "/book");
        try {
            connection.setConnectTimeout(12000); connection.setReadTimeout(25000); connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization",UserAccountApi.authorization(token.trim(),userToken));
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            connection.setRequestProperty("Accept","application/vnd.yclients.v2+json");
            connection.setRequestProperty("User-Agent","ChampionTennis/1.2 Android");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            String body;
            try (InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in != null) {
                    byte[] buffer = new byte[4096]; int n;
                    while ((n = in.read(buffer)) != -1) { if (out.size() > 1048576) throw new IOException("Слишком большой ответ"); out.write(buffer,0,n); }
                }
                body = out.toString(StandardCharsets.UTF_8.name());
            }
            if(status==401||status==403)throw new Rejected(NetworkMessage.ERROR);
            JSONObject response;
            try { response = new JSONObject(body); }
            catch (JSONException e) {
                if (status >= 400 && status < 500) throw new Rejected("YCLIENTS отклонил запрос (" + status + ")");
                throw new IOException("Сервер не прислал распознаваемое подтверждение",e);
            }
            if (status >= 400 && status < 500) {
                String message = error(response);
                throw new Rejected(message);
            }
            if (status < 200 || status >= 300) throw new IOException("Не получено подтверждение сервера (" + status + ")");
            if (!response.optBoolean("success")) throw new Rejected(error(response));
            return receipt(response);
        } finally { connection.disconnect(); }
    }
    public static JSONObject receipt(JSONObject response) throws JSONException, IOException {
        if (!response.optBoolean("success")) throw new IOException("Сервер не подтвердил создание записи");
        Object data = response.opt("data");
        JSONObject record = data instanceof JSONArray && ((JSONArray)data).length() == 1 ? ((JSONArray)data).getJSONObject(0) : data instanceof JSONObject ? (JSONObject)data : null;
        if (record == null || record.optLong("id") <= 0) throw new IOException("Не получен номер записи от YCLIENTS");
        return new JSONObject(record.toString());
    }
    static String error(JSONObject response) {
        JSONObject meta = response.optJSONObject("meta");
        return meta != null && !meta.optString("message").isEmpty() ? meta.optString("message") : "YCLIENTS отклонил запись. Проверьте данные и доступность занятия.";
    }
    public static class Rejected extends IOException { public Rejected(String message) { super(NetworkMessage.safe(message)); } }
}
