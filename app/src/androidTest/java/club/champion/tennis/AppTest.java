package club.champion.tennis;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Live GET integration and native form tests. Booking POSTs use a fake transport in the emulator only. */
public class AppTest extends InstrumentationTestCase {
    public void testModelAndAvailability() throws Exception {
        try (InputStream in = getInstrumentation().getContext().getAssets().open("schedule.json")) {
            JSONObject fixture = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            List<Training> items = Training.parse(fixture.getJSONArray("data"));
            assertEquals(7, items.size());
            Training full = items.get(2), selected = items.get(3);
            assertEquals("16:00", full.time()); assertEquals(0, full.remaining); assertFalse(full.bookable());
            assertEquals(53536293L, selected.id); assertEquals("17:00", selected.time());
            assertEquals("PRE-SATELLITE", selected.title); assertEquals(3, selected.remaining);
            assertEquals("900 ₽", selected.price()); assertTrue(selected.bookingUrl().contains("aid53536293c1"));
            JSONObject future = new JSONObject(selected.source.toString());
            future.put("date", LocalDate.now(YclientsApi.CLUB_ZONE).plusDays(1) + " 17:00:00");
            future.put("records_count", 1); assertTrue(new Training(future).bookable());
            future.getJSONObject("service").put("is_online_booking_available", false); assertFalse(new Training(future).bookable());
        }
    }
    public void testLiveScheduleAndNativeFlow() throws Exception {
        MainActivity activity = (MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            getInstrumentation().runOnMainSync(() -> activity.select(LocalDate.of(2026,10,24)));
            waitLoaded(activity);
            assertTrue("Live schedule must be loaded, not an offline placeholder", activity.trainings.size() > 0);
            Training target = activity.trainings.stream().filter(t -> t.id == 53536293L).findFirst().orElseThrow();
            assertEquals("17:00", target.time());
            screenshot(activity, "01-schedule.png");
            getInstrumentation().runOnMainSync(() -> {
                activity.level = "PRE-SATELLITE"; activity.levelButton.setText("PRE-SATELLITE  ⌄"); activity.showList();
                ScrollView scroll = (ScrollView)activity.content.getParent(); scroll.scrollTo(0, activity.dp(360));
            });
            getInstrumentation().waitForIdleSync();
            assertEquals((int)activity.trainings.stream().filter(t -> t.title.equals("PRE-SATELLITE")).count(), activity.list.getChildCount());
            screenshot(activity, "02-filtered.png");
            getInstrumentation().runOnMainSync(() -> { activity.page = 1; activity.render(); });
            assertTrue(findText(activity.content,"Войти по телефону →")); screenshot(activity,"03-records-guest.png");
            getInstrumentation().runOnMainSync(() -> activity.detail(target));
            SystemClock.sleep(2000); screenshot(activity,"04-detail.png");
        } finally { getInstrumentation().runOnMainSync(activity::finish); }
    }
    public void testNativePayloadAndServerReceipt() throws Exception {
        Training t = nativeFixture();
        ClientProfile p = new ClientProfile("Тест", "Проверка", "8 (000) 000-00-00", "test@example.invalid");
        JSONObject body = NativeBookingApi.payload(t,p,"Тест",true);
        assertEquals("70000000000",body.getString("phone"));
        assertEquals(53536293L,body.getJSONArray("appointments").getJSONObject(0).getLong("activityId"));
        assertEquals("2026-10-24T17:00:00",body.getJSONArray("appointments").getJSONObject(0).getString("datetime"));
        assertFalse(body.getBoolean("is_newsletter_allowed"));
        try { NativeBookingApi.payload(t,p,"",false);fail("No consent must prevent submission"); } catch(IllegalArgumentException expected) {}
        assertEquals(123L,NativeBookingApi.receipt(new JSONObject("{\"success\":true,\"data\":{\"id\":123,\"hash\":\"test\"}}")).getLong("id"));
        try { NativeBookingApi.receipt(new JSONObject("{\"success\":true,\"data\":{}}"));fail("Missing server ID must not be success"); } catch(IOException expected) {}
    }
    public void testNativeProfileSurvivesReopenEncrypted() throws Exception {
        NativeBookingActivity a = startNative("profile",null);
        try {
            getInstrumentation().runOnMainSync(()->{a.name.setText("Тестовый пользователь");a.surname.setText("Проверка");a.phone.setText("70000000000");a.email.setText("test@example.invalid");assertTrue(a.saveProfile(false));});
            assertEquals("Тестовый пользователь",new ProfileStore(a).load().name);
            String raw = a.getSharedPreferences("private_contact_profile",0).getString("contact","");
            assertFalse(raw.contains("Тестовый"));assertFalse(raw.contains("70000000000"));assertFalse(raw.contains("test@example.invalid"));
        } finally {getInstrumentation().runOnMainSync(a::finish);}
        NativeBookingActivity reopened = startNative("profile",null);
        try {
            assertEquals("Тестовый пользователь",reopened.name.getText().toString());
            assertEquals("+7 (000) 000-00-00",reopened.phone.getText().toString());
            screenshot(reopened,"06-native-profile.png");
        }finally{getInstrumentation().runOnMainSync(()->{reopened.name.setText("");reopened.surname.setText("");reopened.phone.setText("");reopened.email.setText("");reopened.saveProfile(false);reopened.finish();});}
    }
    public void testNativeBookingSingleSubmissionAndConfirmation() throws Exception {
        Training t=nativeFixture();NativeBookingActivity a=startNative(null,t);
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        try {
            waitNative(a);
            getInstrumentation().runOnMainSync(()->{
                try {a.store.attempt(t.id,null);a.store=new ProfileStore(a,"test_token_never_sent_to_network");}catch(Exception e){throw new RuntimeException(e);}
                a.name.setText("Тестовый пользователь");a.phone.setText("70000000000");a.email.setText("test@example.invalid");
                a.bookingApi=new NativeBookingApi(){@Override public JSONObject book(long id,JSONObject payload,String token,String userToken)throws Exception{calls.incrementAndGet();assertEquals("70000000000",payload.getString("phone"));return new JSONObject().put("id",123456L).put("hash","test_response_only");}};
                a.submit();assertEquals(0,calls.get());assertTrue(a.status.getText().toString().contains("согласие"));
                a.consent.setChecked(true);
            });
            screenshot(a,"07-native-booking.png");
            getInstrumentation().runOnMainSync(()->{a.submit();a.submit();});waitIdleBooking(a);
            assertEquals(1,calls.get());assertEquals("created",a.store.attempts().getJSONObject(Long.toString(t.id)).getString("state"));
            assertEquals("Вы записаны",a.primary.getText().toString());
        }finally{getInstrumentation().runOnMainSync(()->{try{a.store.attempt(t.id,null);a.name.setText("");a.phone.setText("");a.email.setText("");a.saveProfile(false);}catch(Exception e){throw new RuntimeException(e);}a.finish();});}
    }
    public void testUncertainResponsePreventsDuplicate() throws Exception {
        Training t=nativeFixture();NativeBookingActivity a=startNative(null,t);
        try{
            waitNative(a);getInstrumentation().runOnMainSync(()->{
                try{a.store.attempt(t.id,null);a.store=new ProfileStore(a,"test_token_never_sent_to_network");}catch(Exception e){throw new RuntimeException(e);}
                a.name.setText("Тест");a.phone.setText("70000000000");a.consent.setChecked(true);
                a.bookingApi=new NativeBookingApi(){@Override public JSONObject book(long id,JSONObject payload,String token,String userToken)throws Exception{throw new java.net.SocketTimeoutException();}};a.submit();
            });waitIdleBooking(a);
            assertFalse(a.primary.isEnabled());assertEquals("unknown",a.store.attempts().getJSONObject(Long.toString(t.id)).getString("state"));
            assertTrue(a.status.getText().toString().contains("могла быть создана"));
        }finally{getInstrumentation().runOnMainSync(()->{try{a.store.attempt(t.id,null);a.name.setText("");a.phone.setText("");a.saveProfile(false);}catch(Exception e){throw new RuntimeException(e);}a.finish();});}
    }
    public void testSignedInBookingUsesAccountIdentity() throws Exception {
        ProfileStore store=new ProfileStore(getInstrumentation().getTargetContext());store.session(sampleUser());
        store.save(new ClientProfile("Другой игрок","Чужая фамилия","71111111111","other@example.invalid"),true);
        NativeBookingActivity a=startNative(null,nativeFixture());
        try {
            assertEquals("Тестовый игрок",a.name.getText().toString());assertEquals("+7 (000) 000-00-00",a.phone.getText().toString());
            assertEquals("test@example.invalid",a.email.getText().toString());assertEquals("",a.surname.getText().toString());assertFalse(a.phone.isEnabled());
            assertEquals("session_test_token",a.bookingAccount.token);
        } finally {getInstrumentation().runOnMainSync(a::finish);store.logout();store.clear();}
    }
    public void testPhoneSignInSessionAndLogout() throws Exception {
        Context c=getInstrumentation().getTargetContext();ProfileStore store=new ProfileStore(c);store.logout();
        AccountActivity a=(AccountActivity)getInstrumentation().startActivitySync(new Intent(c,AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.util.concurrent.atomic.AtomicInteger sms=new java.util.concurrent.atomic.AtomicInteger(),logins=new java.util.concurrent.atomic.AtomicInteger();
        try {
            store.smsAfter("70000000000",0);
            getInstrumentation().runOnMainSync(()->{
                a.store=new ProfileStore(a,"test_partner_never_sent_to_network");
                a.accountApi=new UserAccountApi(){
                    @Override public void sendCode(String phone,String partner){assertEquals("70000000000",phone);sms.incrementAndGet();}
                    @Override public UserSession login(String phone,String code,String partner)throws Exception{assertEquals("1234",code);logins.incrementAndGet();return sampleUser();}
                };
                a.phone.setText("8 (000) 000-00-00");a.sendCode();a.sendCode();
            });waitAccount(a);assertEquals(1,sms.get());assertFalse(a.send.isEnabled());
            screenshot(a,"08-account-code.png");
            getInstrumentation().runOnMainSync(()->{a.code.setText("1234");a.signIn();a.signIn();});
            long end=SystemClock.elapsedRealtime()+10000;while(store.session()==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
            assertNotNull(store.session());assertEquals(1,logins.get());
            String encrypted=c.getSharedPreferences("private_contact_profile",0).getString("account_session","");
            assertFalse(encrypted.contains("session_test_token"));assertFalse(encrypted.contains("70000000000"));
            assertEquals("Тестовый игрок",new ProfileStore(c).session().name);
            assertEquals("Bearer partner, User user",UserAccountApi.authorization("partner","user"));
        } finally {getInstrumentation().runOnMainSync(a::finish);}
        AccountActivity reopened=(AccountActivity)getInstrumentation().startActivitySync(new Intent(c,AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {assertNotNull(reopened.session);screenshot(reopened,"09-account.png");getInstrumentation().runOnMainSync(reopened::logout);assertNull(store.session());}
        finally {getInstrumentation().runOnMainSync(reopened::finish);store.smsAfter("70000000000",0);store.logout();}
    }
    public void testServerRecordsPastFutureErrorsAndAccountIsolation() throws Exception {
        Context c=getInstrumentation().getTargetContext();ProfileStore store=new ProfileStore(c);store.logout();
        MainActivity a=(MainActivity)getInstrumentation().startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            store.session(sampleUser());a.store=new ProfileStore(a,"test_partner_never_sent_to_network");
            List<UserRecord> parsed=UserRecord.parse(sampleRecords());assertEquals(3,parsed.size());
            assertEquals(17,parsed.get(0).date.getHour());assertEquals(ZoneId.of("Europe/Samara"),parsed.get(0).date.getZone());
            getInstrumentation().runOnMainSync(()->{
                a.store=new ProfileStore(a,"test_partner_never_sent_to_network");
                a.accountApi=new UserAccountApi(){@Override public List<UserRecord> records(String partner,UserSession user)throws Exception{assertEquals(12L,user.id);return UserRecord.parse(sampleRecords());}};
                a.page=1;a.render();
            });waitRecords(a);assertEquals(1,a.recordList.getChildCount());
            screenshot(a,"10-upcoming-records.png");
            getInstrumentation().runOnMainSync(()->a.selectRecords(true));assertEquals(2,a.recordList.getChildCount());assertTrue(findText(a.recordList,"Посещено · №2"));
            screenshot(a,"11-past-records.png");
            getInstrumentation().runOnMainSync(()->{a.accountApi=new UserAccountApi(){@Override public List<UserRecord> records(String p,UserSession u)throws Exception{throw new ApiError(401,"Сеанс истёк",0);}};a.loadRecords();});waitRecords(a);
            assertTrue(findText(a.recordList,NetworkMessage.ERROR));getInstrumentation().runOnMainSync(()->a.selectRecords(false));assertTrue(findText(a.recordList,NetworkMessage.ERROR));
            store.logout();getInstrumentation().runOnMainSync(a::render);assertNull(a.account);assertEquals(0,a.records.size());assertTrue(findText(a.content,"Войти по телефону →"));
        } finally {getInstrumentation().runOnMainSync(a::finish);store.logout();}
    }
    public void testConsumerApiPathsAndInvalidSession() throws Exception {
        List<String> calls=new ArrayList<>();
        UserAccountApi api=new UserAccountApi(){@Override JSONObject request(String method,String path,JSONObject payload,String partner,String user)throws Exception{
            calls.add(method+" "+path);assertEquals("partner",partner);
            if(path.equals("/book_code/1538880")){assertEquals("70000000000",payload.getString("phone"));assertNull(user);return new JSONObject().put("success",true);}
            if(path.equals("/user/auth")){assertEquals("1234",payload.getString("code"));assertEquals(1538880,payload.getInt("company_id"));return sampleUser().toJson();}
            assertEquals("session_test_token",user);return sampleRecords();
        }};
        api.sendCode("70000000000","partner");UserSession session=api.login("70000000000","1234","partner");assertEquals(3,api.records("partner",session).size());
        assertEquals(Arrays.asList("POST /book_code/1538880","POST /user/auth","GET /user/records"),calls);
        try{new UserSession(new JSONObject().put("id",12).put("phone","70000000000"));fail("Missing token must not authenticate");}catch(JSONException expected){}
        try{UserRecord.parse(new JSONObject().put("success",false).put("data",new JSONArray()));fail("Error must not become an empty history");}catch(IOException expected){}
        try{UserAccountApi.authorization("","session");fail("Missing partner key");}catch(UserAccountApi.ApiError expected){}
    }
    UserSession sampleUser() throws Exception {return new UserSession(new JSONObject().put("id",12).put("user_token","session_test_token").put("phone","70000000000").put("name","Тестовый игрок").put("email","test@example.invalid"));}
    JSONObject sampleRecords() throws Exception {
        JSONArray records=new JSONArray();
        for(int n=1;n<=4;n++){
            LocalDate day=LocalDate.now(YclientsApi.CLUB_ZONE).plusDays(n==2?-2:2);
            records.put(new JSONObject().put("id",n).put("company",new JSONObject().put("id",n==4?999:1538880).put("address","Тестовый адрес"))
                .put("services",new JSONArray().put(new JSONObject().put("title","PRE-SATELLITE"))).put("staff",new JSONObject().put("name","Корт · Хард"))
                .put("date",day+" 17:00:00").put("length",3600).put("deleted",n==3).put("attendance",n==2?1:0));
        }
        return new JSONObject().put("success",true).put("data",records);
    }
    void waitAccount(AccountActivity a){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){boolean[] busy={true};getInstrumentation().runOnMainSync(()->busy[0]=a.busy);if(!busy[0])return;SystemClock.sleep(50);}fail("Auth request did not finish");}
    void waitRecords(MainActivity a){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){boolean[] busy={true};getInstrumentation().runOnMainSync(()->busy[0]=a.recordsLoading);if(!busy[0]){getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(50);}fail("Records request did not finish");}
    boolean findText(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(findText(group.getChildAt(i),text))return true;}return false;}
    Training nativeFixture() throws Exception {
        try(InputStream in=getInstrumentation().getContext().getAssets().open("schedule.json")){
            Training t=Training.parse(new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("data")).get(3);
            t.source.put("staff_id",4479474L).put("service_id",32014362L);return t;
        }
    }
    NativeBookingActivity startNative(String mode,Training t){
        Intent i=new Intent(getInstrumentation().getTargetContext(),NativeBookingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if(mode!=null)i.putExtra("mode",mode);if(t!=null)i.putExtra("training",t.source.toString());
        return (NativeBookingActivity)getInstrumentation().startActivitySync(i);
    }
    void waitNative(NativeBookingActivity a){
        long end=SystemClock.elapsedRealtime()+120000;while(SystemClock.elapsedRealtime()<end){boolean[] ready={false};getInstrumentation().runOnMainSync(()->ready[0]=a.loaded);if(ready[0])return;SystemClock.sleep(150);}fail("Native booking did not load");
    }
    void waitIdleBooking(NativeBookingActivity a){
        long end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){boolean[] busy={true};getInstrumentation().runOnMainSync(()->busy[0]=a.busy);if(!busy[0])return;SystemClock.sleep(100);}fail("Native booking did not finish");
    }
    void waitLoaded(MainActivity activity) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 120000;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] busy = {true}; getInstrumentation().runOnMainSync(() -> busy[0] = activity.loading);
            if (!busy[0]) { getInstrumentation().waitForIdleSync(); return; }
            SystemClock.sleep(200);
        }
        fail("Schedule loading timed out");
    }
    void screenshot(Activity activity, String name) throws Exception {
        getInstrumentation().waitForIdleSync(); SystemClock.sleep(350);
        Bitmap image = getInstrumentation().getUiAutomation().takeScreenshot();
        File dir = new File(activity.getExternalFilesDir(null), "screenshots"); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir,name))) { image.compress(Bitmap.CompressFormat.PNG,100,out); }
        image.recycle();
    }
}
