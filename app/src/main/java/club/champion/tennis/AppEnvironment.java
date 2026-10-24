package club.champion.tennis;

import java.io.IOException;
import java.net.*;

/** The sandbox APK is a separate app and can only connect to the USB loopback server. */
public final class AppEnvironment {
    public static final boolean LOCAL = BuildConfig.LOCAL_SERVER;
    public static final String LOCAL_BASE = "http://127.0.0.1:18765";
    public static final String LOCAL_PARTNER = "champion-local-partner";
    public static String partnerToken(){return LOCAL?LOCAL_PARTNER:BuildConfig.PARTNER_TOKEN;}
    public static String apiBase(){return LOCAL?LOCAL_BASE+"/api/v1":"https://api.yclients.ru/api/v1";}
    public static HttpURLConnection open(String address)throws IOException{
        URL url=new URL(address);
        if(LOCAL && (!url.getProtocol().equals("http")||!url.getHost().equals("127.0.0.1")||url.getPort()!=18765))
            throw new IOException("Тестовая сборка может обращаться только к локальному серверу");
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);return connection;
    }
    private AppEnvironment(){}
}
