package club.champion.tennis;

import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** A server record, including visits created on the website or by the club. */
public final class UserRecord {
    public final JSONObject source;
    public final long id;
    public final String title, staff, address;
    public final ZonedDateTime date;
    public final int minutes, attendance;
    public final boolean deleted;
    UserRecord(JSONObject o) throws Exception {
        source=new JSONObject(o.toString());
        id=o.getLong("id"); if(id<=0)throw new JSONException("Нет номера записи");
        JSONObject company=o.getJSONObject("company");
        // The documented `date` is the company's local time, unlike datetime which may use another offset.
        String local=o.optString("date");
        if(!local.isEmpty())date=LocalDateTime.parse(local.replace(' ','T')).atZone(YclientsApi.CLUB_ZONE);
        else {
            String iso=o.getString("datetime").replaceFirst("([+-]\\d{2})(\\d{2})$","$1:$2");
            date=OffsetDateTime.parse(iso).atZoneSameInstant(YclientsApi.CLUB_ZONE);
        }
        List<String> titles=new ArrayList<>();JSONArray services=o.getJSONArray("services");
        for(int i=0;i<services.length();i++){String t=services.getJSONObject(i).optString("title");if(!t.isEmpty())titles.add(t);}
        title=titles.isEmpty()?"Занятие в Champion Club":String.join(" · ",titles);
        JSONObject specialist=o.optJSONObject("staff");staff=specialist==null?"":specialist.optString("name").replace("Корт(","Корт · ").replace(")","");
        address=company.optString("address"); minutes=Math.max(0,o.optInt("length")/60);
        deleted=o.optBoolean("deleted");attendance=o.optInt("visit_attendance",o.optInt("attendance"));
    }
    public boolean upcoming(Instant now){return !date.plusMinutes(minutes).toInstant().isBefore(now);}
    public boolean cancellable(Instant now){return !deleted&&attendance!=1&&attendance!=-1&&date.toInstant().isAfter(now);}
    public String status(){return deleted?"Отменена":attendance==1?"Посещено":attendance==-1?"Не состоялось · неявка":attendance==2?"Подтверждена":AppEnvironment.LOCAL?"Тестовая запись":"Запись в YCLIENTS";}
    public String dateLabel(){return date.format(DateTimeFormatter.ofPattern("d MMMM yyyy · HH:mm",MainActivity.RU));}
    public static List<UserRecord> parse(JSONObject response)throws Exception{
        if(!response.optBoolean("success"))throw new java.io.IOException("YCLIENTS не подтвердил загрузку записей");
        JSONArray all=response.getJSONArray("data");Map<Long,UserRecord> records=new LinkedHashMap<>();
        for(int i=0;i<all.length();i++){
            JSONObject o=all.getJSONObject(i);JSONObject company=o.optJSONObject("company");
            if(company!=null&&company.optLong("id")==1538880L){UserRecord r=new UserRecord(o);records.put(r.id,r);}
        }
        return new ArrayList<>(records.values());
    }
}
