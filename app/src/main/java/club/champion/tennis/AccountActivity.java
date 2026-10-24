package club.champion.tennis;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.concurrent.*;

/** Two native sign-in screens. Only the resulting session is persisted, never the SMS code. */
public class AccountActivity extends Activity {
    ProfileStore store;
    UserAccountApi accountApi=new UserAccountApi();
    final ExecutorService executor=Executors.newSingleThreadExecutor();
    final Handler timer=new Handler(Looper.getMainLooper());
    LinearLayout content;
    EditText phone,code;
    TextView send,confirm,status;
    UserSession session;
    String requestedPhone="",phoneDraft="",screenMessage="";
    boolean busy,codeStep,codeRequested,focusPending;
    final Runnable tick=new Runnable(){public void run(){updateSend();timer.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new ProfileStore(this);
        try{session=store.session();}catch(Exception e){Toast.makeText(this,"Не удалось прочитать сеанс. Войдите заново.",Toast.LENGTH_LONG).show();}
        if(state!=null && session==null){
            phoneDraft=state.getString("phone","");requestedPhone=state.getString("requestedPhone","");
            codeRequested=state.getBoolean("codeRequested");codeStep=state.getBoolean("codeStep")&&codeRequested&&!requestedPhone.isEmpty();
        }
        render();
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::back);
    }
    void render(){
        phone=null;code=null;send=null;confirm=null;
        LinearLayout root=column();root.setBackgroundColor(MainActivity.BG);
        root.setOnApplyWindowInsetsListener((v,i)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);}
            else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;
        });
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=column();content.setPadding(dp(22),dp(12),dp(22),dp(28));scroll.addView(content);root.addView(scroll);setContentView(root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|(codeStep?WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE:WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(button("‹ Назад",false,this::back));TextView brand=text("CHAMPION",12,MainActivity.MUTED,true);brand.setGravity(Gravity.END);bar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));add(bar);gap(24);
        if(AppEnvironment.LOCAL){add(text("ТЕСТ · сервер на компьютере",12,MainActivity.GREEN,true));gap(12);}
        if(session!=null)accountScreen();else if(codeStep)codeScreen();else phoneScreen();
        status=text(screenMessage,14,MainActivity.GREEN,false);add(status);gap(16);
        setBusy(busy);
        if(codeStep){focusPending=true;focusCode();}
    }
    void accountScreen(){
        add(text("Мой аккаунт",30,MainActivity.INK,true));gap(20);
        add(text(session.name.isEmpty()?"Игрок Champion":session.name,22,MainActivity.INK,true));gap(8);
        add(text(PhoneMask.format(session.phone),17,MainActivity.MUTED,false));gap(8);
        if(!session.email.isEmpty())add(text(session.email,15,MainActivity.MUTED,false));gap(22);
        add(text(AppEnvironment.LOCAL?"Вы вошли в тестовый аккаунт локального сервера. В «Мои записи» — вымышленные занятия.":"Вы вошли в аккаунт YCLIENTS. Предстоящие и прошедшие занятия клуба доступны во вкладке «Мои записи».",15,MainActivity.MUTED,false));gap(20);
        add(button("Мои записи →",true,()->{setResult(RESULT_OK,new Intent().putExtra("records",true));finish();}));gap(12);
        add(button("Выйти из аккаунта",false,this::logout));
    }
    void phoneScreen(){
        add(text("Вход в аккаунт",30,MainActivity.INK,true));gap(12);
        add(text("Введите номер телефона, с которым записываетесь в клуб. Мы отправим код подтверждения.",15,MainActivity.MUTED,false));gap(26);
        phone=field("Номер телефона",InputType.TYPE_CLASS_PHONE);PhoneMask.attach(phone);phone.setAutofillHints("phone");phone.setImeOptions(EditorInfo.IME_ACTION_GO);
        if(phoneDraft.isEmpty())try{phoneDraft=store.load().phone;}catch(Exception ignored){}
        if(AppEnvironment.LOCAL&&phoneDraft.isEmpty())phoneDraft="70000000000";
        phone.setText(phoneDraft);phone.setSelection(phone.length());
        phone.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_GO){continueOrSend();return true;}return false;});
        phone.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){updateSend();}public void afterTextChanged(android.text.Editable value){}});
        if(AppEnvironment.LOCAL){add(text("Тестовый номер: 70000000000. Настоящая SMS не отправляется.",13,MainActivity.GREEN,false));gap(14);}
        send=button("Получить код",true,this::continueOrSend);add(send);gap(18);
        add(text("После входа сеанс сохранится на этом устройстве.",12,MainActivity.MUTED,false));gap(14);
    }
    void codeScreen(){
        add(text("Введите код",30,MainActivity.INK,true));gap(12);
        add(text(AppEnvironment.LOCAL?"Код для тестового номера "+PhoneMask.format(requestedPhone):"Отправили SMS на "+PhoneMask.format(requestedPhone),16,MainActivity.MUTED,false));gap(8);
        add(button("Изменить номер",false,this::returnToPhone));gap(24);
        code=field("Код из SMS",InputType.TYPE_CLASS_NUMBER);code.setSingleLine(true);code.setSaveEnabled(false);code.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});code.setTextSize(30);code.setLetterSpacing(.2f);code.setGravity(Gravity.CENTER);code.setMinHeight(dp(64));
        code.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        code.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){signIn();return true;}return false;});
        confirm=button("Войти",true,this::signIn);add(confirm);gap(12);
        send=button("Получить код ещё раз",false,this::sendCode);add(send);gap(18);
        add(text(AppEnvironment.LOCAL?"Тестовый код — 1234. SMS не отправляется.":"Код не сохраняется на устройстве.",12,MainActivity.MUTED,false));gap(12);
    }
    String partner(){
        try{String key=store.token();if(key!=null&&!key.isEmpty())return key;}catch(Exception ignored){}
        status.setText(NetworkMessage.ERROR);return null;
    }
    String normalizedPhone(){return new ClientProfile("","",phone==null?phoneDraft:phone.getText().toString(),"").normalizedPhone();}
    boolean canContinue(){return codeRequested&&!requestedPhone.isEmpty()&&requestedPhone.equals(normalizedPhone());}
    void continueOrSend(){if(busy)return;if(canContinue())showCodeScreen("");else sendCode();}
    void showCodeScreen(String message){
        if(phone!=null)phoneDraft=phone.getText().toString();
        codeRequested=true;codeStep=true;screenMessage=message;render();
    }
    void sendCode(){
        if(busy||session!=null)return;
        String number=codeStep?requestedPhone:normalizedPhone();ClientProfile p=new ClientProfile("","",number,"");
        if(!p.validPhone()){if(phone!=null){phone.setError("Проверьте номер телефона");phone.requestFocus();}return;}
        String key=partner();if(key==null)return;
        try{long after=store.smsAfter(number);if(after>System.currentTimeMillis()){status.setText("Подождите перед повторной отправкой кода.");return;}
            store.smsAfter(number,System.currentTimeMillis()+60000);
        }catch(Exception e){status.setText("Не удалось сохранить время отправки. Код не запрошен.");return;}
        if(phone!=null)phoneDraft=phone.getText().toString();if(!number.equals(requestedPhone))codeRequested=false;requestedPhone=number;setBusy(true);status.setText("Запрашиваем код…");
        executor.execute(()->{try{
            accountApi.sendCode(number,key);
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;showCodeScreen(AppEnvironment.LOCAL?"Тестовый код — 1234. SMS не отправлялась.":"");});
        }catch(Exception e){
            if(e instanceof UserAccountApi.ApiError&&((UserAccountApi.ApiError)e).status==429){try{store.smsAfter(number,System.currentTimeMillis()+((UserAccountApi.ApiError)e).retrySeconds*1000);}catch(Exception ignored){}}
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;setBusy(false);
                // A lost response may still mean the SMS was sent: allow entering it without an automatic retry.
                if(!(e instanceof UserAccountApi.ApiError))showCodeScreen("Не удалось подтвердить отправку. Если код придёт, введите его. Повторный запрос доступен после паузы.");
                else{status.setText(e.getMessage());if(codeStep){focusPending=true;focusCode();}}
            });
        }});
    }
    void signIn(){
        if(busy||!codeStep||code==null||requestedPhone.isEmpty()||session!=null)return;
        String pin=code.getText().toString().trim();if(!pin.matches("\\d{4,8}")){code.setError("Введите код из SMS");focusPending=true;focusCode();return;}
        String key=partner();if(key==null)return;String number=requestedPhone;setBusy(true);status.setText("Входим в аккаунт…");
        executor.execute(()->{try{
            UserSession result=accountApi.login(number,pin,key);
            if(!new ClientProfile("","",result.phone,"").normalizedPhone().equals(number))throw new java.io.IOException("YCLIENTS вернул другой номер. Вход не сохранён.");
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;try{
                store.session(result);code.setText("");hideKeyboard();busy=false;setResult(RESULT_OK);finish();
            }catch(Exception e){setBusy(false);status.setText("Не удалось сохранить сеанс. Повторите вход.");focusPending=true;focusCode();}});
        }catch(Exception e){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;setBusy(false);status.setText(e instanceof UserAccountApi.ApiError?e.getMessage():"Не удалось войти. Проверьте интернет и повторите ввод кода.");if(e instanceof UserAccountApi.ApiError&&((UserAccountApi.ApiError)e).status==422){code.setError("Проверьте код");code.selectAll();}focusPending=true;focusCode();});}});
    }
    void setBusy(boolean value){busy=value;if(phone!=null)phone.setEnabled(!value);if(code!=null)code.setEnabled(!value);if(confirm!=null){confirm.setEnabled(!value);confirm.setAlpha(value?.45f:1);}updateSend();}
    void updateSend(){
        if(send==null||isFinishing())return;
        if(!codeStep&&canContinue()){send.setText("Продолжить");send.setEnabled(!busy);send.setAlpha(busy?.45f:1);return;}
        String number=codeStep?requestedPhone:normalizedPhone();long seconds=0;try{seconds=Math.max(0,(store.smsAfter(number)-System.currentTimeMillis()+999)/1000);}catch(Exception ignored){}
        send.setText(seconds>0?"Получить код через "+seconds+" с":codeStep?"Получить код ещё раз":"Получить код");send.setEnabled(!busy&&seconds==0);send.setAlpha(send.isEnabled()?1:.45f);
    }
    void focusCode(){
        if(!codeStep||code==null||busy||!focusPending||isFinishing())return;
        final EditText target=code;target.requestFocus();
        target.post(()->{if(!codeStep||code!=target||busy||isFinishing()||isDestroyed()||!target.hasWindowFocus())return;
            InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(target,InputMethodManager.SHOW_IMPLICIT);
            if(Build.VERSION.SDK_INT>=30&&getWindow().getInsetsController()!=null)getWindow().getInsetsController().show(WindowInsets.Type.ime());focusPending=false;
        });
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)focusCode();}
    void hideKeyboard(){View focused=getCurrentFocus();if(focused!=null){InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(focused.getWindowToken(),0);}if(Build.VERSION.SDK_INT>=30&&getWindow().getInsetsController()!=null)getWindow().getInsetsController().hide(WindowInsets.Type.ime());}
    void returnToPhone(){if(busy)return;hideKeyboard();codeStep=false;focusPending=false;screenMessage="";render();}
    void logout(){try{store.logout();session=null;setResult(RESULT_OK);finish();}catch(Exception e){status.setText("Не удалось выйти из аккаунта. Повторите попытку.");}}
    void back(){if(busy){Toast.makeText(this,"Дождитесь ответа YCLIENTS",Toast.LENGTH_SHORT).show();return;}if(codeStep){returnToPhone();return;}finish();}
    @android.annotation.SuppressLint("GestureBackNavigation") @Override public void onBackPressed(){back();}
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("phone",phone==null?phoneDraft:phone.getText().toString());state.putString("requestedPhone",requestedPhone);state.putBoolean("codeRequested",codeRequested);state.putBoolean("codeStep",codeStep);}
    @Override public void onResume(){super.onResume();timer.post(tick);}
    @Override public void onPause(){timer.removeCallbacks(tick);super.onPause();}
    @Override public void onDestroy(){timer.removeCallbacks(tick);executor.shutdown();super.onDestroy();}
    int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    void add(View v){content.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    void gap(int n){content.addView(new View(this),new LinearLayout.LayoutParams(1,dp(n)));}
    void background(View v,int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(16));v.setBackground(d);}
    TextView button(String label,boolean solid,Runnable action){TextView v=text(label,15,solid?MainActivity.WHITE:MainActivity.GREEN,true);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(50));v.setPadding(dp(14),dp(12),dp(14),dp(12));background(v,solid?MainActivity.GREEN:MainActivity.WHITE);v.setFocusable(true);v.setContentDescription(label);v.setOnClickListener(w->action.run());return v;}
    EditText field(String label,int type){add(text(label,13,MainActivity.INK,true));gap(6);EditText v=new EditText(this);v.setInputType(type);v.setTextSize(18);v.setTextColor(MainActivity.INK);v.setPadding(dp(14),dp(12),dp(14),dp(12));v.setMinHeight(dp(52));v.setContentDescription(label);background(v,MainActivity.WHITE);add(v);gap(16);return v;}
}
