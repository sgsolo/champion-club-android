package club.champion.tennis;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Official consumer online-booking endpoints. No retries of SMS or login POSTs. */
public class UserAccountApi {
    public void sendCode(String phone,String partner)throws Exception{
        request("POST","/book_code/1538880",new JSONObject().put("phone",phone),partner,null);
    }
    public UserSession login(String phone,String code,String partner)throws Exception{
        return new UserSession(request("POST","/user/auth",new JSONObject().put("phone",phone).put("code",code).put("company_id",1538880),partner,null));
    }
    public List<UserRecord> records(String partner,UserSession user)throws Exception{
        return UserRecord.parse(request("GET","/user/records",null,partner,user.token));
    }
    public UserRecord record(long id,String hash,String partner,UserSession user)throws Exception{
        if(user!=null){for(UserRecord record:records(partner,user))if(record.id==id)return record;throw new ApiError(404,"Запись не найдена в вашем аккаунте.",0);}
        List<UserRecord> records=UserRecord.parse(request("GET",recordPath(id,hash),null,partner,null));
        for(UserRecord record:records)if(record.id==id)return record;
        throw new ApiError(404,"Запись не найдена.",0);
    }
    public void cancel(long id,String hash,String partner,UserSession user)throws Exception{
        request("DELETE",recordPath(id,user==null?hash:""),null,partner,user==null?null:user.token);
    }
    static String recordPath(long id,String hash)throws IOException{
        if(id<=0)throw new IOException("Нет номера записи");
        if(hash==null)hash="";
        if(!hash.matches("[A-Za-z0-9_-]*"))throw new IOException("Некорректный ключ записи");
        return "/user/records/"+id+"/"+hash;
    }
    static String authorization(String partner,String user)throws IOException{
        if(partner==null||partner.trim().isEmpty())throw new ApiError(0,NetworkMessage.ERROR,0);
        if(partner.matches("(?s).*[\\s,].*")||(user!=null&&user.matches("(?s).*[\\s,].*")))throw new ApiError(0,NetworkMessage.ERROR,0);
        return "Bearer "+partner+(user==null?"":", User "+user);
    }
    JSONObject request(String method,String path,JSONObject payload,String partner,String user)throws Exception{
        String auth=authorization(partner,user);
        HttpURLConnection c=AppEnvironment.open(NativeBookingApi.API+path);
        try{
            c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);
            c.setRequestProperty("Authorization",auth);c.setRequestProperty("Accept","application/vnd.yclients.v2+json");
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("User-Agent","ChampionTennis/1.2 Android");
            if(payload!=null){c.setDoOutput(true);byte[] b=payload.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);try(OutputStream out=c.getOutputStream()){out.write(b);}}
            int status=c.getResponseCode();String body;
            try(InputStream in=status>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                if(in!=null){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>8*1024*1024)throw new IOException("Ответ YCLIENTS слишком большой");out.write(b,0,n);}}
                body=out.toString(StandardCharsets.UTF_8.name());
            }
            JSONObject json=null;try{json=new JSONObject(body);}catch(JSONException ignored){}
            if(status<200||status>=300){
                String message=NetworkMessage.ERROR;
                if(status==401||status==403||status>=500)message=NetworkMessage.ERROR;
                else if(status==429)message="Слишком много запросов. Дождитесь окончания ограничения YCLIENTS.";
                else if(status>=400&&status<500&&json!=null){JSONObject meta=json.optJSONObject("meta");if(meta!=null&&!meta.optString("message").isEmpty())message=meta.optString("message");}
                long retry=60;try{retry=Math.max(60,Long.parseLong(c.getHeaderField("Retry-After")));}catch(Exception ignored){}
                throw new ApiError(status,message,retry);
            }
            if(json==null&&method.equals("DELETE")&&body.trim().isEmpty())return new JSONObject().put("success",true);
            if(json==null)throw new IOException("YCLIENTS прислал непонятный ответ. Проверьте результат перед повторным запросом.");
            if(json.has("success")&&!json.optBoolean("success"))throw new ApiError(422,"YCLIENTS отклонил запрос. Проверьте данные и условия записи.",60);
            return json;
        }finally{c.disconnect();}
    }
    public static class ApiError extends IOException{
        public final int status;public final long retrySeconds;
        public ApiError(int status,String message,long retry){super(status==0||status==401||status==403||status>=500?NetworkMessage.ERROR:NetworkMessage.safe(message));this.status=status;retrySeconds=retry;}
    }
}
