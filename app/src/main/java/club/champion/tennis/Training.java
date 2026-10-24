package club.champion.tennis;

import org.json.*;
import java.time.*;
import java.util.*;

public final class Training {
    public final long id;
    public final String title, court;
    public final LocalDateTime date;
    public final int duration, capacity, remaining, priceMin, priceMax;
    public final boolean online;
    public final JSONObject source;

    public Training(JSONObject j) throws JSONException {
        source = j;
        id = j.getLong("id");
        date = LocalDateTime.parse(j.getString("date").replace(' ', 'T'));
        JSONObject service = j.getJSONObject("service");
        title = service.getString("title");
        court = j.getJSONObject("staff").getString("name").replace("Корт(", "Корт · ").replace(")", "");
        duration = j.optInt("length", 3600) / 60;
        capacity = j.getInt("capacity");
        remaining = Math.max(0, capacity - j.getInt("records_count"));
        priceMin = service.getInt("price_min");
        priceMax = service.getInt("price_max");
        online = service.optBoolean("is_online_booking_available", false) && !j.optBoolean("is_deleted", false);
    }
    public boolean bookable() { return online && remaining > 0 && date.isAfter(LocalDateTime.now(YclientsApi.CLUB_ZONE)); }
    public String price() { return priceMin == priceMax ? priceMin + " ₽" : priceMin + "–" + priceMax + " ₽"; }
    public String time() { return date.toLocalTime().toString(); }
    public String bookingUrl() { return YclientsApi.BASE + "/company/1538880/activity/info/" + id + "?o=act" + date.toLocalDate() + "aid" + id + "c1"; }
    public static List<Training> parse(JSONArray array) throws JSONException {
        List<Training> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject j = array.getJSONObject(i);
            if (!j.optBoolean("is_deleted", false)) result.add(new Training(j));
        }
        result.sort(Comparator.comparing(t -> t.date));
        return result;
    }
}
