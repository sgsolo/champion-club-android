package club.champion.tennis;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.security.NetworkSecurityPolicy;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Actual HTTP to the computer via adb reverse. No fake Java API implementations. */
public class LocalServerTest extends InstrumentationTestCase {
    void requireLocal(){assertTrue("Run against the sandbox build only",AppEnvironment.LOCAL);assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".sandbox"));}
    public void testLocalEnvironmentHasNoProductionFallback()throws Exception{
        requireLocal();assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"));
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("api.yclients.ru"));
        for(String url:Arrays.asList("https://api.yclients.ru/api/v1/user/auth","https://n1724922.yclients.com/","http://127.0.0.1:80/")){
            try{AppEnvironment.open(url);fail("Sandbox must refuse "+url);}catch(IOException expected){}
        }
        assertTrue(control("GET","/health",null).getBoolean("local_only"));
        assertEquals(AppEnvironment.LOCAL_PARTNER,new ProfileStore(getInstrumentation().getTargetContext()).token());
    }
    public void testHomeBannerGuestRecordOfferAndFailure()throws Exception{
        requireLocal();control("POST","/__sandbox/reset",new JSONObject());Context context=getInstrumentation().getTargetContext();ProfileStore store=new ProfileStore(context);store.logout();
        MainActivity home=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));RecordActivity detail=null;NativeBookingActivity form=null;AccountActivity auth=null;
        try{
            waitMain(home,false);assertEquals("guest",home.bannerState);assertEquals(LocalDate.now(YclientsApi.CLUB_ZONE),home.selected);assertNull(home.nextRecord);assertNull(home.suggestedTraining);screenshot(home,"27-home-guest.png");
            Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(AccountActivity.class.getName(),null,false);getInstrumentation().runOnMainSync(()->home.homeBannerAction.performClick());auth=(AccountActivity)getInstrumentation().waitForMonitorWithTimeout(monitor,5000);getInstrumentation().removeMonitor(monitor);assertNotNull(auth);final AccountActivity login=auth;getInstrumentation().runOnMainSync(login::finish);
            UserAccountApi api=new UserAccountApi();api.sendCode("70000000000",store.token());store.session(api.login("70000000000","1234",store.token()));getInstrumentation().runOnMainSync(home::render);waitBanner(home);waitMain(home,false);
            assertEquals("record",home.bannerState);List<UserRecord> history=api.records(store.token(),store.session());UserRecord expected=history.stream().filter(r->!r.deleted&&r.date.toInstant().isAfter(Instant.now())).min(Comparator.comparing(r->r.date.toInstant())).orElseThrow();assertEquals(expected.id,home.nextRecord.id);
            assertTrue("Compact banner should leave room for the schedule",home.homeBanner.getHeight()<home.dp(185));screenshot(home,"28-home-next-record.png");
            // Changing the calendar must not change the nearest record banner.
            getInstrumentation().runOnMainSync(()->home.select(LocalDate.now(YclientsApi.CLUB_ZONE).plusDays(10)));waitMain(home,false);assertEquals(expected.id,home.nextRecord.id);
            monitor=getInstrumentation().addMonitor(RecordActivity.class.getName(),null,false);getInstrumentation().runOnMainSync(()->home.homeBannerAction.performClick());detail=(RecordActivity)getInstrumentation().waitForMonitorWithTimeout(monitor,5000);getInstrumentation().removeMonitor(monitor);assertNotNull(detail);waitRecord(detail);assertEquals(expected.id,detail.record.id);final RecordActivity page=detail;getInstrumentation().runOnMainSync(page::finish);waitBanner(home);
            for(UserRecord record:history)if(record.cancellable(Instant.now()))api.cancel(record.id,"",store.token(),store.session());
            getInstrumentation().runOnMainSync(home::loadHomeBanner);waitBanner(home);assertEquals("offer",home.bannerState);assertNull(home.nextRecord);assertNotNull(home.suggestedTraining);assertTrue(home.suggestedTraining.bookable());assertTrue(home.suggestedTraining.date.toLocalDate().isBefore(home.selected));screenshot(home,"29-home-available-class.png");
            long trainingId=home.suggestedTraining.id;monitor=getInstrumentation().addMonitor(NativeBookingActivity.class.getName(),null,false);getInstrumentation().runOnMainSync(()->home.homeBannerAction.performClick());form=(NativeBookingActivity)getInstrumentation().waitForMonitorWithTimeout(monitor,5000);getInstrumentation().removeMonitor(monitor);assertNotNull(form);assertEquals(trainingId,form.training.id);final NativeBookingActivity booking=form;getInstrumentation().runOnMainSync(booking::finish);waitBanner(home);
            control("POST","/__sandbox/mode",new JSONObject().put("mode","full"));getInstrumentation().runOnMainSync(home::loadHomeBanner);waitBanner(home);assertEquals("empty",home.bannerState);assertNull(home.nextRecord);assertNull(home.suggestedTraining);
            control("POST","/__sandbox/mode",new JSONObject().put("mode","auth_expired"));getInstrumentation().runOnMainSync(home::loadHomeBanner);waitBanner(home);assertEquals("error",home.bannerState);assertNull(home.nextRecord);assertNull(home.suggestedTraining);screenshot(home,"30-home-session-error.png");
            store.logout();getInstrumentation().runOnMainSync(home::render);assertEquals("guest",home.bannerState);assertFalse(home.bannerLoading);assertNull(home.nextRecord);assertNull(home.suggestedTraining);
        }finally{final Activity a=auth,b=detail,c=form;getInstrumentation().runOnMainSync(()->{if(a!=null)a.finish();if(b!=null)b.finish();if(c!=null)c.finish();home.finish();});store.logout();control("POST","/__sandbox/reset",new JSONObject());}
    }
    public void testNativeFlowThroughComputerHttpServer()throws Exception{
        requireLocal();control("POST","/__sandbox/reset",new JSONObject());
        JSONObject previousTotals=control("GET","/__sandbox/state",null).getJSONObject("totals");
        Context context=getInstrumentation().getTargetContext();ProfileStore store=new ProfileStore(context);store.logout();store.clear();store.smsAfter("70000000000",0);
        Training target=new YclientsApi().schedule(LocalDate.now(YclientsApi.CLUB_ZONE).plusDays(1)).stream().filter(t->t.time().equals("17:00")).findFirst().orElseThrow();
        store.attempt(target.id,null);AccountActivity auth=null;MainActivity main=null;NativeBookingActivity booking=null;RecordActivity detail=null;
        try{
            auth=(AccountActivity)getInstrumentation().startActivitySync(new Intent(context,AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final AccountActivity login=auth;
            assertEquals("+7 (000) 000-00-00",login.phone.getText().toString());assertNull(login.code);assertNull(login.confirm);screenshot(login,"22-login-phone.png");
            getInstrumentation().runOnMainSync(login::sendCode);waitAuth(login);waitCodeFocus(login);
            assertNull(login.phone);assertTrue(login.codeStep);assertTrue(login.code.hasFocus());assertFalse(login.send.isEnabled());screenshot(login,"23-login-sms.png");
            getInstrumentation().runOnMainSync(login::returnToPhone);assertFalse(login.codeStep);assertNull(login.code);assertEquals("+7 (000) 000-00-00",login.phone.getText().toString());assertEquals("Продолжить",login.send.getText().toString());
            getInstrumentation().runOnMainSync(login::continueOrSend);waitCodeFocus(login);assertNull(login.phone);assertTrue(login.code.hasFocus());
            getInstrumentation().runOnMainSync(login::sendCode);assertFalse(login.busy);
            JSONObject authTotals=control("GET","/__sandbox/state",null).getJSONObject("totals");String smsRoute="POST /api/v1/book_code/1538880";
            assertEquals(previousTotals.optInt(smsRoute)+1,authTotals.optInt(smsRoute));
            getInstrumentation().runOnMainSync(()->{login.code.setText("9999");login.signIn();});waitAuth(login);assertNull(store.session());
            waitCodeFocus(login);assertTrue(login.code.hasFocus());assertTrue(login.codeStep);assertNull(login.phone);
            getInstrumentation().runOnMainSync(()->{login.code.setText("1234");login.signIn();});
            long until=SystemClock.elapsedRealtime()+10000;while(store.session()==null&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);assertNotNull(store.session());
            main=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity home=main;waitMain(home,false);
            assertEquals(7,home.trainings.size());screenshot(home,"12-local-schedule.png");
            getInstrumentation().runOnMainSync(()->{home.page=1;home.render();});waitMain(home,true);assertTrue(home.records.size()>150);int baseline=home.records.size();int upcoming=(int)home.records.stream().filter(r->r.upcoming(Instant.now())).count();
            getInstrumentation().runOnMainSync(()->home.selectRecords(true));assertTrue(home.recordList.getChildCount()>100);screenshot(home,"13-local-past.png");
            booking=(NativeBookingActivity)getInstrumentation().startActivitySync(new Intent(context,NativeBookingActivity.class).putExtra("training",target.source.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final NativeBookingActivity form=booking;
            until=SystemClock.elapsedRealtime()+10000;while(!form.loaded&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);assertTrue(form.loaded);
            assertEquals("+7 (000) 000-00-00",form.phone.getText().toString());assertFalse(form.phone.isEnabled());
            Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(RecordActivity.class.getName(),null,false);
            getInstrumentation().runOnMainSync(()->{form.consent.setChecked(true);form.submit();form.submit();});
            until=SystemClock.elapsedRealtime()+10000;while(form.busy&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);getInstrumentation().waitForIdleSync();
            assertFalse(form.busy);assertEquals("created",store.attempts().getJSONObject(Long.toString(target.id)).getString("state"));
            detail=(RecordActivity)getInstrumentation().waitForMonitorWithTimeout(monitor,10000);getInstrumentation().removeMonitor(monitor);assertNotNull("Booking must open record details",detail);waitRecord(detail);assertTrue(detail.created);assertEquals(store.attempts().getJSONObject(Long.toString(target.id)).getJSONObject("receipt").getLong("id"),detail.record.id);assertNotNull(detail.cancelButton);screenshot(detail,"24-record-created.png");
            List<UserRecord> records=new UserAccountApi().records(store.token(),store.session());assertEquals(baseline+1,records.size());
            assertEquals(target.remaining-1,new YclientsApi().detail(target.id).remaining);
            final RecordActivity confirmation=detail;getInstrumentation().runOnMainSync(()->{confirmation.finish();form.finish();home.pastRecords=false;home.render();});waitMain(home,true);assertEquals(upcoming+1,home.recordList.getChildCount());screenshot(home,"15-local-upcoming.png");
            JSONObject totals=control("GET","/__sandbox/state",null).getJSONObject("totals");String route="POST /api/v1/activity/1538880/"+target.id+"/book";
            assertEquals(previousTotals.optInt(route)+1,totals.optInt(route));
            getInstrumentation().runOnMainSync(()->{try{store.logout();}catch(Exception e){throw new RuntimeException(e);}home.render();});assertEquals(0,home.records.size());assertNull(home.account);
        }finally{
            final Activity a=auth,b=main,c=booking,d=detail;getInstrumentation().runOnMainSync(()->{if(a!=null)a.finish();if(b!=null)b.finish();if(c!=null)c.finish();if(d!=null)d.finish();});store.logout();store.clear();store.attempt(target.id,null);store.smsAfter("70000000000",0);control("POST","/__sandbox/reset",new JSONObject());
        }
    }
    public void testUpcomingRecordDetailsAndCancellation()throws Exception{
        requireLocal();control("POST","/__sandbox/reset",new JSONObject());Context context=getInstrumentation().getTargetContext();ProfileStore store=new ProfileStore(context);store.logout();
        UserAccountApi api=new UserAccountApi();api.sendCode("70000000000",store.token());store.session(api.login("70000000000","1234",store.token()));
        MainActivity home=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));RecordActivity detail=null;
        try{
            getInstrumentation().runOnMainSync(()->{home.page=1;home.render();});waitMain(home,true);int count=home.recordList.getChildCount();
            Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor(RecordActivity.class.getName(),null,false);
            getInstrumentation().runOnMainSync(()->home.recordList.getChildAt(0).performClick());detail=(RecordActivity)getInstrumentation().waitForMonitorWithTimeout(monitor,10000);getInstrumentation().removeMonitor(monitor);assertNotNull(detail);waitRecord(detail);final RecordActivity page=detail;
            assertNotNull(page.record);assertFalse(page.created);assertNotNull(page.cancelButton);long id=page.record.id;long activity=page.record.source.getLong("activity_id");int remaining=new YclientsApi().detail(activity).remaining;screenshot(page,"25-record-upcoming.png");
            JSONObject before=control("GET","/__sandbox/state",null).getJSONObject("totals");String route="DELETE /api/v1/user/records/"+id+"/";
            getInstrumentation().runOnMainSync(page::confirmCancellation);getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(()->page.cancellationDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());getInstrumentation().waitForIdleSync();
            assertEquals(before.optInt(route),control("GET","/__sandbox/state",null).getJSONObject("totals").optInt(route));
            control("POST","/__sandbox/mode",new JSONObject().put("mode","cancel_rejected"));getInstrumentation().runOnMainSync(page::cancel);waitRecord(page);assertFalse(page.record.deleted);assertNotNull(page.cancelButton);
            control("POST","/__sandbox/mode",new JSONObject().put("mode","cancel_lost_response"));getInstrumentation().runOnMainSync(page::confirmCancellation);getInstrumentation().waitForIdleSync();getInstrumentation().runOnMainSync(()->{page.cancellationDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();page.cancel();});waitRecord(page);assertTrue(page.record.deleted);assertNull(page.cancelButton);screenshot(page,"26-record-cancelled.png");
            assertEquals(remaining+1,new YclientsApi().detail(activity).remaining);assertTrue(api.record(id,"",store.token(),store.session()).deleted);
            assertEquals(before.optInt(route)+2,control("GET","/__sandbox/state",null).getJSONObject("totals").optInt(route));
            getInstrumentation().runOnMainSync(page::finish);getInstrumentation().waitForIdleSync();waitMain(home,true);assertEquals(count-1,home.recordList.getChildCount());
        }finally{final RecordActivity page=detail;getInstrumentation().runOnMainSync(()->{if(page!=null)page.finish();home.finish();});store.logout();control("POST","/__sandbox/reset",new JSONObject());}
    }
    public void testStatisticsTabWithLargeHistoryAndAccountExit()throws Exception{
        requireLocal();control("POST","/__sandbox/reset",new JSONObject());Context context=getInstrumentation().getTargetContext();ProfileStore store=new ProfileStore(context);store.logout();
        UserAccountApi api=new UserAccountApi();api.sendCode("70000000000",store.token());store.session(api.login("70000000000","1234",store.token()));
        MainActivity home=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            getInstrumentation().runOnMainSync(()->{home.page=2;home.render();});waitMain(home,true);
            assertTrue(home.recordsLoaded);assertNotNull(home.attendanceStats);assertTrue(home.attendanceStats.total>100);assertTrue(home.attendanceStats.lastYearTotal>20);assertEquals(YearMonth.now(YclientsApi.CLUB_ZONE),home.attendanceStats.monthDates[11]);
            assertEquals(home.attendanceStats.lastYearTotal,Arrays.stream(home.attendanceStats.months).sum());assertEquals(home.attendanceStats.recentTotal,Arrays.stream(home.attendanceStats.recentVisits).sum());
            screenshot(home,"20-statistics-no-selector.png");
            getInstrumentation().runOnMainSync(()->((android.widget.ScrollView)home.content.getParent()).fullScroll(android.view.View.FOCUS_DOWN));
            screenshot(home,"21-last-twelve-months.png");
            control("POST","/__sandbox/mode",new JSONObject().put("mode","auth_expired"));getInstrumentation().runOnMainSync(home::loadRecords);waitMain(home,true);assertFalse(home.recordsLoaded);assertNull(home.attendanceStats);
            getInstrumentation().runOnMainSync(()->{try{store.logout();}catch(Exception e){throw new RuntimeException(e);}home.render();});assertNull(home.recordList);assertNull(home.attendanceStats);assertNull(home.account);
        }finally{getInstrumentation().runOnMainSync(home::finish);store.logout();control("POST","/__sandbox/reset",new JSONObject());}
    }
    void waitAuth(AccountActivity a){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){boolean[] busy={true};getInstrumentation().runOnMainSync(()->busy[0]=a.busy);if(!busy[0])return;SystemClock.sleep(50);}fail("Local authentication timed out");}
    void waitBanner(MainActivity a){long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){boolean[] busy={true};getInstrumentation().runOnMainSync(()->busy[0]=a.bannerLoading);if(!busy[0]){getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(50);}fail("Home banner request timed out");}
    void waitRecord(RecordActivity a){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){boolean[] busy={true};getInstrumentation().runOnMainSync(()->busy[0]=a.busy);if(!busy[0]){getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(50);}fail("Record request timed out");}
    void waitCodeFocus(AccountActivity a){long end=SystemClock.elapsedRealtime()+5000;while(SystemClock.elapsedRealtime()<end){boolean[] ready={false};getInstrumentation().runOnMainSync(()->{android.view.WindowInsets insets=a.getWindow().getDecorView().getRootWindowInsets();ready[0]=a.code!=null&&a.code.hasFocus()&&insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime());});if(ready[0])return;SystemClock.sleep(50);}fail("SMS field must receive focus and open the keyboard");}
    void waitMain(MainActivity a,boolean records){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){boolean[] busy={true};getInstrumentation().runOnMainSync(()->busy[0]=records?a.recordsLoading:a.loading);if(!busy[0]){getInstrumentation().waitForIdleSync();return;}SystemClock.sleep(50);}fail("Local GET timed out");}
    JSONObject control(String method,String path,JSONObject payload)throws Exception{
        HttpURLConnection c=AppEnvironment.open(AppEnvironment.LOCAL_BASE+path);
        try{c.setRequestMethod(method);c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestProperty("Content-Type","application/json");
            if(payload!=null){c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}}
            assertEquals(200,c.getResponseCode());try(InputStream in=c.getInputStream()){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        }finally{c.disconnect();}
    }
    void screenshot(Activity a,String name)throws Exception{
        getInstrumentation().waitForIdleSync();SystemClock.sleep(200);Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();File dir=new File(a.getExternalFilesDir(null),"screenshots");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
}
