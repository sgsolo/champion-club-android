package club.champion.tennis;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.Instant;
import java.util.concurrent.*;

/** The same native record page is used after booking and from the upcoming list. */
public class RecordActivity extends Activity {
    ProfileStore store;
    UserAccountApi api=new UserAccountApi();
    final ExecutorService executor=Executors.newSingleThreadExecutor();
    UserSession user;
    UserRecord record;
    LinearLayout content;
    TextView status,cancelButton;
    AlertDialog cancellationDialog;
    long recordId;
    String hash,expectedIdentity,message="";
    boolean busy,created;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new ProfileStore(this);recordId=getIntent().getLongExtra("record_id",0);
        hash=getIntent().getStringExtra("hash");expectedIdentity=getIntent().getStringExtra("account");
        if(expectedIdentity==null)expectedIdentity="";created=getIntent().getBooleanExtra("created",false);
        try{user=store.session();}catch(Exception ignored){}
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::back);
        load();
    }
    boolean sameAccount(){try{return expectedIdentity.equals(MainActivity.identity(store.session()));}catch(Exception e){return false;}}
    void load(){
        if(busy)return;
        if(!sameAccount()){record=null;message="Аккаунт изменился. Откройте запись заново из «Мои записи».";render();return;}
        busy=true;message="Загружаем информацию о записи…";render();
        executor.execute(()->{try{
            UserRecord fresh=api.record(recordId,hash,store.token(),user);
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;if(!sameAccount()){record=null;message="Аккаунт изменился. Откройте запись заново.";}else{record=fresh;message="";}render();});
        }catch(Exception e){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;record=null;message=error(e,"Не удалось загрузить запись. Проверьте соединение и повторите попытку.");render();});}});
    }
    void render(){
        cancelButton=null;
        LinearLayout root=column();root.setBackgroundColor(MainActivity.BG);
        root.setOnApplyWindowInsetsListener((v,i)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);}else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=column();content.setPadding(dp(22),dp(12),dp(22),dp(28));scroll.addView(content);root.addView(scroll);setContentView(root);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.addView(button("‹ Назад",false,this::back));TextView brand=text("CHAMPION",12,MainActivity.MUTED,true);brand.setGravity(Gravity.END);bar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));add(bar);gap(24);
        if(AppEnvironment.LOCAL){add(text("ТЕСТ · запись на локальном сервере",12,MainActivity.GREEN,true));gap(18);}
        add(text(record!=null&&record.deleted?"Запись отменена":created?"Вы записаны!":"Ваша запись",30,MainActivity.INK,true));gap(10);
        add(text("№ "+recordId+" · Champion Club",13,MainActivity.MUTED,false));gap(24);
        if(record!=null){
            LinearLayout card=column();card.setPadding(dp(20),dp(22),dp(20),dp(22));background(card,MainActivity.WHITE);add(card);
            card.addView(text(record.title,23,MainActivity.INK,true));space(card,18);
            card.addView(text(record.dateLabel(),19,MainActivity.INK,true));space(card,8);
            card.addView(text("Время клуба · Ижевск (UTC+4)",12,MainActivity.MUTED,false));space(card,20);
            if(!record.staff.isEmpty()){card.addView(text(record.staff,16,MainActivity.INK,false));space(card,10);}
            if(record.minutes>0){card.addView(text(record.minutes+" минут",15,MainActivity.MUTED,false));space(card,10);}
            if(!record.address.isEmpty()){card.addView(text(record.address,15,MainActivity.MUTED,false));space(card,14);}
            double cost=0;boolean hasCost=false;JSONArray services=record.source.optJSONArray("services");
            if(services!=null)for(int i=0;i<services.length();i++){JSONObject service=services.optJSONObject(i);if(service!=null&&service.has("cost")){cost+=service.optDouble("cost");hasCost=true;}}
            if(hasCost){card.addView(text(String.format(MainActivity.RU,"Стоимость: %.0f ₽",cost),16,MainActivity.INK,true));space(card,14);}
            card.addView(text(record.status(),14,record.deleted?MainActivity.MUTED:MainActivity.GREEN,true));gap(22);
            if(record.cancellable(Instant.now())){cancelButton=button("Отменить запись",false,this::confirmCancellation);cancelButton.setTextColor(0xffa33131);cancelButton.setEnabled(!busy);cancelButton.setAlpha(busy?.45f:1);add(cancelButton);gap(14);}
            else if(!record.deleted){add(text("Отмена в приложении доступна до начала занятия. По вопросам записи обратитесь в клуб.",13,MainActivity.MUTED,false));gap(14);}
        }
        status=text(message,14,MainActivity.MUTED,false);add(status);gap(16);
        if(busy){ProgressBar progress=new ProgressBar(this);content.addView(progress,new LinearLayout.LayoutParams(dp(32),dp(32)));}
        else if(sameAccount()){add(button("Обновить информацию",false,this::load));}
        if(created&&!getIntent().getBooleanExtra("receipt_saved",true)){gap(14);add(text("Подтверждение не удалось сохранить на устройстве. Запись создана: не отправляйте её повторно.",13,MainActivity.MUTED,false));}
    }
    void confirmCancellation(){
        if(busy||record==null||!record.cancellable(Instant.now()))return;
        if(cancellationDialog!=null&&cancellationDialog.isShowing())return;
        cancellationDialog=new AlertDialog.Builder(this).setTitle("Отменить запись?").setMessage(record.title+"\n"+record.dateLabel()+"\n\nВаше место освободится для другого игрока.")
            .setNegativeButton("Оставить запись",null).setPositiveButton("Отменить запись",(dialog,which)->cancel()).create();cancellationDialog.show();
    }
    void cancel(){
        if(busy||record==null||!record.cancellable(Instant.now()))return;
        if(!sameAccount()){record=null;message="Аккаунт изменился. Откройте запись заново.";render();return;}
        busy=true;message="Отменяем запись…";render();
        executor.execute(()->{boolean sent=false;try{
            String partner=store.token();UserRecord fresh=api.record(recordId,hash,partner,user);
            if(fresh.deleted){finishCancellation(fresh);return;}
            if(!fresh.cancellable(Instant.now()))throw new UserAccountApi.ApiError(422,"Занятие уже началось или статус изменился. Обратитесь в клуб.",0);
            if(!sameAccount())throw new UserAccountApi.ApiError(401,"Аккаунт изменился. Откройте запись заново.",0);
            sent=true;api.cancel(recordId,hash,partner,user);
            finishCancellation(new UserRecord(new JSONObject(fresh.source.toString()).put("deleted",true)));
        }catch(Exception e){
            // A disconnected response may still mean cancellation succeeded. Read once; never repeat DELETE automatically.
            if(sent&&!(e instanceof UserAccountApi.ApiError))try{UserRecord fresh=api.record(recordId,hash,store.token(),user);if(fresh.deleted){finishCancellation(fresh);return;}}catch(Exception ignored){}
            final boolean unknown=sent&&!(e instanceof UserAccountApi.ApiError);
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;
                if(!sameAccount())record=null;
                else if(unknown)record=null;
                message=error(e,unknown?"Ответ на отмену не получен. Обновите информацию, чтобы проверить результат. Повторная отмена автоматически не отправляется.":"Не удалось проверить запись. Проверьте соединение и повторите попытку.");render();});
        }});
    }
    void finishCancellation(UserRecord fresh){
        if(sameAccount())try{JSONObject attempts=store.attempts();java.util.Iterator<String> keys=attempts.keys();while(keys.hasNext()){String key=keys.next();JSONObject attempt=attempts.optJSONObject(key);JSONObject receipt=attempt==null?null:attempt.optJSONObject("receipt");if(receipt!=null&&receipt.optLong("id")==recordId)store.attempt(Long.parseLong(key),null);}}catch(Exception ignored){}
        runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;if(!sameAccount()){record=null;message="Аккаунт изменился. Откройте запись заново.";}else{record=fresh;message="Ваше место освобождено. Запись отменена.";setResult(RESULT_OK);}render();});
    }
    String error(Exception e,String fallback){return e instanceof UserAccountApi.ApiError?e.getMessage():fallback;}
    void back(){if(busy){Toast.makeText(this,"Дождитесь ответа сервера",Toast.LENGTH_SHORT).show();return;}finish();}
    @android.annotation.SuppressLint("GestureBackNavigation") @Override public void onBackPressed(){back();}
    @Override public void onDestroy(){if(cancellationDialog!=null)cancellationDialog.dismiss();executor.shutdown();super.onDestroy();}
    int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    void add(View v){content.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    void space(LinearLayout parent,int n){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(n)));}
    void gap(int n){space(content,n);}
    void background(View v,int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(20));v.setBackground(d);}
    TextView button(String label,boolean solid,Runnable action){TextView v=text(label,15,solid?MainActivity.WHITE:MainActivity.GREEN,true);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(50));v.setPadding(dp(14),dp(12),dp(14),dp(12));background(v,solid?MainActivity.GREEN:MainActivity.WHITE);v.setFocusable(true);v.setContentDescription(label);v.setOnClickListener(w->action.run());return v;}
}
