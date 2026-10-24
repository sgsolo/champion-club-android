package club.champion.tennis;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Read-only adapter to the same public widget used by the club's website. */
public final class YclientsApi {
    public static final String BASE = "https://n1724922.yclients.com";
    public static final ZoneId CLUB_ZONE = ZoneId.of("Europe/Samara");
    private volatile String widgetToken;

    public List<Training> schedule(LocalDate date) throws Exception {
        List<Training> all = new ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            JSONObject response = get("activity/1538880/search?count=100&from=" + date + "&till=" + date + "&page=" + page);
            JSONArray data = response.getJSONArray("data");
            all.addAll(Training.parse(data));
            JSONObject meta = response.optJSONObject("meta");
            if (data.length() == 0 || (meta != null && all.size() >= meta.optInt("count", all.size())) || data.length() < 100) break;
            if (page == 100) throw new IOException("Расписание слишком большое. Откройте сайт клуба.");
        }
        all.sort(Comparator.comparing(t -> t.date));
        return all;
    }
    public Training detail(long id) throws Exception { return new Training(get("activity/1538880/" + id).getJSONObject("data")); }
    private JSONObject get(String path) throws Exception {
        if(AppEnvironment.LOCAL) {
            JSONObject result=new JSONObject(download(AppEnvironment.apiBase()+"/"+path,"Bearer "+AppEnvironment.LOCAL_PARTNER));
            if(!result.optBoolean("success"))throw new IOException("Локальный сервер отклонил запрос расписания");
            return result;
        }
        String currentToken = publicToken();
        String body;
        try { body = download(BASE + "/api/v1/" + path, currentToken); }
        catch (Unauthorized e) {
            widgetToken = discoverPublicWidgetToken();
            body = download(BASE + "/api/v1/" + path, widgetToken);
        }
        JSONObject json = new JSONObject(body);
        if (!json.optBoolean("success", false)) throw new IOException("Сервис расписания временно недоступен");
        return json;
    }
    private synchronized String publicToken() throws IOException {
        if (widgetToken == null) widgetToken = discoverPublicWidgetToken();
        return widgetToken;
    }
    /* This is the public browser-widget token, never a salon/user API credential.
       Resolve it from the public assets instead of embedding any account secret. */
    private String discoverPublicWidgetToken() throws IOException {
        String html = download(BASE + "/", null);
        Matcher main = Pattern.compile("src=\"(main-[A-Za-z0-9]+\\.js)\"").matcher(html);
        if (!main.find()) throw new IOException("Форма записи обновилась. Откройте сайт клуба.");
        String script = download(BASE + "/" + main.group(1), null);
        String token = extractToken(script);
        if (token != null) return token;
        Matcher chunks = Pattern.compile("(?:from\\s*|import\\s*)[\"']\\./(chunk-[A-Za-z0-9]+\\.js)[\"']").matcher(script);
        Set<String> seen = new LinkedHashSet<>();
        while (chunks.find()) seen.add(chunks.group(1));
        List<String> candidates = new ArrayList<>(seen);
        Collections.reverse(candidates);
        for (String chunk : candidates) {
            token = extractToken(download(BASE + "/" + chunk, null));
            if (token != null) return token;
        }
        throw new IOException("Форма записи обновилась. Откройте сайт клуба.");
    }
    private String extractToken(String script) {
        Matcher m = Pattern.compile("apiToken:\"(Bearer [^\"]+)\",apiUrl:\"/api/v1\"").matcher(script);
        return m.find() ? m.group(1) : null;
    }
    private String download(String url, String token) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        HttpsURLConnectionCompat guard = new HttpsURLConnectionCompat(url);
        HttpURLConnection conn = guard.connection;
        try {
            conn.setConnectTimeout(12000); conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", token == null ? "*/*" : "application/vnd.yclients.v2+json");
            conn.setRequestProperty("User-Agent", "ChampionTennis/1.0 Android");
            if (token != null) conn.setRequestProperty("Authorization", token);
            int status = conn.getResponseCode();
            if (status == 401 || status == 403) throw new Unauthorized();
            if (status < 200 || status >= 300) throw new IOException("Сервис ответил ошибкой " + status);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    if (out.size() + n > 12 * 1024 * 1024) throw new IOException("Слишком большой ответ сервиса");
                    out.write(buffer, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { conn.disconnect(); }
    }
    private static class Unauthorized extends IOException {}
    private static class HttpsURLConnectionCompat {
        final HttpURLConnection connection;
        HttpsURLConnectionCompat(String url) throws IOException {
            URL parsed = new URL(url);
            if (!AppEnvironment.LOCAL && (!"https".equals(parsed.getProtocol()) || !"n1724922.yclients.com".equals(parsed.getHost()))) throw new IOException("Недопустимый адрес");
            connection = AppEnvironment.open(url);
            connection.setInstanceFollowRedirects(false);
        }
    }
}
