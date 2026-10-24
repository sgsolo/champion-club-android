package club.champion.tennis;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Locale;

/** Consumer UI never asks the player to configure integration credentials. */
public class CustomerFlowTest extends InstrumentationTestCase {
    void requireLocal(){assertTrue("Use the isolated sandbox server",AppEnvironment.LOCAL);}
    public void testCredentialSourceAndPublicErrors()throws Exception{
        requireLocal();Context context=getInstrumentation().getTargetContext();SharedPreferences prefs=context.getSharedPreferences("private_contact_profile",0);String old=prefs.getString("integration",null);
        try{
            prefs.edit().putString("integration","invalid legacy preference that must never be read").commit();
            assertEquals(AppEnvironment.partnerToken(),new ProfileStore(context).token());
            for(String key:new String[]{"","bad key"})try{UserAccountApi.authorization(key,null);fail("Missing/malformed credentials must fail");}catch(UserAccountApi.ApiError e){assertEquals(NetworkMessage.ERROR,e.getMessage());}
            for(int status:new int[]{0,401,403,500})assertEquals(NetworkMessage.ERROR,new UserAccountApi.ApiError(status,"Partner Token rejected",0).getMessage());
            assertEquals(NetworkMessage.ERROR,new UserAccountApi.ApiError(422,"Invalid api-ключ",0).getMessage());
            assertEquals("Неверный код",new UserAccountApi.ApiError(422,"Неверный код",0).getMessage());
            assertEquals(NetworkMessage.ERROR,new NativeBookingApi.Rejected("Partner Token missing").getMessage());
        }finally{SharedPreferences.Editor editor=prefs.edit();if(old==null)editor.remove("integration");else editor.putString("integration",old);editor.commit();}
    }
    public void testScreensAndMissingCredentialDoNotRequestSms()throws Exception{
        requireLocal();Context context=getInstrumentation().getTargetContext();ProfileStore store=new ProfileStore(context);store.logout();store.smsAfter("70000000000",0);
        AccountActivity auth=(AccountActivity)getInstrumentation().startActivitySync(new Intent(context,AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MainActivity home=null;NativeBookingActivity profile=null;
        try{
            assertConsumerUi(auth.getWindow().getDecorView());int before=totals().optInt("POST /api/v1/book_code/1538880");
            getInstrumentation().runOnMainSync(()->{auth.store=new ProfileStore(auth,"");auth.sendCode();});
            assertEquals(NetworkMessage.ERROR,auth.status.getText().toString());assertFalse(auth.codeStep);assertNull(auth.code);assertEquals(before,totals().optInt("POST /api/v1/book_code/1538880"));assertEquals(0,store.smsAfter("70000000000"));
            home=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity main=home;getInstrumentation().runOnMainSync(()->{main.page=3;main.render();});assertConsumerUi(main.content);
            profile=(NativeBookingActivity)getInstrumentation().startActivitySync(new Intent(context,NativeBookingActivity.class).putExtra("mode","profile").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertConsumerUi(profile.getWindow().getDecorView());
        }finally{final Activity a=home,b=profile;getInstrumentation().runOnMainSync(()->{auth.finish();if(a!=null)a.finish();if(b!=null)b.finish();});}
    }
    public void testRejectedCredentialUsesNetworkErrorForLoginAndBooking()throws Exception{
        requireLocal();Context context=getInstrumentation().getTargetContext();ProfileStore store=new ProfileStore(context);store.logout();store.smsAfter("70000000000",0);
        AccountActivity auth=(AccountActivity)getInstrumentation().startActivitySync(new Intent(context,AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));NativeBookingActivity booking=null;
        Training target=new YclientsApi().schedule(LocalDate.now(YclientsApi.CLUB_ZONE).plusDays(4)).stream().filter(t->t.time().equals("17:00")).findFirst().orElseThrow();store.attempt(target.id,null);
        try{
            getInstrumentation().runOnMainSync(()->{auth.store=new ProfileStore(auth,"invalid_integration");auth.sendCode();});waitBusy(auth);assertEquals(NetworkMessage.ERROR,auth.status.getText().toString());assertFalse(auth.codeStep);assertConsumerUi(auth.getWindow().getDecorView());
            getInstrumentation().runOnMainSync(()->{auth.requestedPhone="70000000000";auth.showCodeScreen("");auth.code.setText("1234");auth.signIn();});waitBusy(auth);assertEquals(NetworkMessage.ERROR,auth.status.getText().toString());assertNull(store.session());
            booking=(NativeBookingActivity)getInstrumentation().startActivitySync(new Intent(context,NativeBookingActivity.class).putExtra("training",target.source.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final NativeBookingActivity form=booking;
            long end=SystemClock.elapsedRealtime()+10000;while(!form.loaded&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);assertTrue(form.loaded);
            String route="POST /api/v1/activity/1538880/"+target.id+"/book";int before=totals().optInt(route);
            getInstrumentation().runOnMainSync(()->{form.store=new ProfileStore(form,"");form.name.setText("Тестовый игрок");form.phone.setText("70000000000");form.email.setText("");form.consent.setChecked(true);form.submit();});
            assertEquals(NetworkMessage.ERROR,form.status.getText().toString());assertEquals(before,totals().optInt(route));assertFalse(store.attempts().has(Long.toString(target.id)));
            getInstrumentation().runOnMainSync(()->{form.store=new ProfileStore(form,"invalid_integration");form.submit();});end=SystemClock.elapsedRealtime()+10000;while(form.busy&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);getInstrumentation().waitForIdleSync();
            assertFalse(form.busy);assertEquals(NetworkMessage.ERROR,form.status.getText().toString());assertEquals(before+1,totals().optInt(route));assertFalse(store.attempts().has(Long.toString(target.id)));assertConsumerUi(form.getWindow().getDecorView());
        }finally{final Activity form=booking;getInstrumentation().runOnMainSync(()->{auth.finish();if(form!=null)form.finish();});store.attempt(target.id,null);store.smsAfter("70000000000",0);}
    }
    void waitBusy(AccountActivity a){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){boolean[] busy={true};getInstrumentation().runOnMainSync(()->busy[0]=a.busy);if(!busy[0])return;SystemClock.sleep(50);}fail("Authentication timed out");}
    void assertConsumerUi(View view){
        if(view instanceof TextView){String text=((TextView)view).getText().toString().toLowerCase(Locale.ROOT);for(String term:new String[]{"partner","token","токен","api-подключ","подключение yclients","api-ключ"})assertFalse("Technical setup leaked into UI",text.contains(term));}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)assertConsumerUi(group.getChildAt(i));}
    }
    JSONObject totals()throws Exception{HttpURLConnection c=AppEnvironment.open(AppEnvironment.LOCAL_BASE+"/__sandbox/state");try{c.setConnectTimeout(3000);c.setReadTimeout(5000);try(InputStream in=c.getInputStream()){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getJSONObject("totals");}}finally{c.disconnect();}}
}
