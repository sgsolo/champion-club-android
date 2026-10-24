package club.champion.tennis;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Native contact form and booking. Does not contain or instantiate a WebView. */
public class NativeBookingActivity extends Activity {
    ProfileStore store;
    NativeBookingApi bookingApi = new NativeBookingApi();
    final YclientsApi scheduleApi = new YclientsApi();
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    LinearLayout root, content;
    EditText name, surname, phone, email, comment;
    CheckBox remember, consent;
    TextView primary, status, trainingSummary, apiStatus;
    Training training;
    UserSession bookingAccount;
    JSONObject settings;
    boolean profileOnly, busy, loaded;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store = new ProfileStore(this);
        String mode = getIntent().getStringExtra("mode");
        screen();
        if ("history".equals(mode)) { history(); return; }
        profileOnly = "profile".equals(mode);
        if (!profileOnly) {
            try { training = new Training(new JSONObject(getIntent().getStringExtra("training"))); }
            catch (Exception e) { finish(); return; }
        }
        try { bookingAccount=store.session(); } catch(Exception e) { Toast.makeText(this,"Не удалось прочитать сеанс входа",Toast.LENGTH_LONG).show(); }
        form();
        if (state != null) {
            name.setText(state.getString("name", "")); surname.setText(state.getString("surname", ""));
            phone.setText(state.getString("phone", "")); email.setText(state.getString("email", ""));
            remember.setChecked(state.getBoolean("remember",true));
            if (comment != null) comment.setText(state.getString("comment", ""));
        }
        if (!profileOnly && bookingAccount != null) fillAccount();
        if (!profileOnly) refresh();
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
    }
    void screen() {
        root = column(); root.setBackgroundColor(MainActivity.BG);
        root.setOnApplyWindowInsetsListener((v,i) -> {
            if (Build.VERSION.SDK_INT >= 30) { android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom); }
            else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());
            return i;
        });
        LinearLayout bar = row(); bar.setPadding(dp(10),dp(6),dp(10),dp(6));
        bar.addView(button("‹ Назад",false,this::back)); TextView brand=text("CHAMPION TENNIS",12,MainActivity.MUTED,true); brand.setGravity(Gravity.END);
        bar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content=column();content.setPadding(dp(22),dp(16),dp(22),dp(24)); scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    void form() {
        if(AppEnvironment.LOCAL){add(text("ТЕСТ · запись сохранится только на компьютере",13,MainActivity.GREEN,true));gap(14);}
        add(text(profileOnly?"Мои данные":"Запись на занятие",28,MainActivity.INK,true));gap(12);
        if (training != null) {
            trainingSummary=text(summary(training),16,MainActivity.INK,true); trainingSummary.setPadding(dp(18),dp(18),dp(18),dp(18));background(trainingSummary,MainActivity.WHITE,20);add(trainingSummary);gap(20);
        }
        add(text("Введите контакты один раз — в следующих записях они заполнятся автоматически.",14,MainActivity.MUTED,false));gap(20);
        name=field("Имя *",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS,"givenName");
        surname=field("Фамилия",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS,"familyName");
        phone=field("Телефон *",InputType.TYPE_CLASS_PHONE,"phone");PhoneMask.attach(phone);
        email=field("E-mail",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,"emailAddress");
        remember=new CheckBox(this); remember.setText("Сохранять контакты на этом устройстве"); remember.setTextSize(13);remember.setTextColor(MainActivity.INK);remember.setChecked(store.enabled());add(remember);gap(8);
        add(text(AppEnvironment.LOCAL?"Это тестовые контакты. При записи данные передаются только серверу на компьютере.":"Контакты хранятся в зашифрованном виде. При записи они передаются в YCLIENTS и клуб.",12,MainActivity.MUTED,false));gap(18);
        try { ClientProfile p=store.load(); name.setText(p.name);surname.setText(p.surname);phone.setText(p.phone);email.setText(p.email); }
        catch(Exception e) { Toast.makeText(this,"Не удалось прочитать сохранённые контакты. Введите их заново.",Toast.LENGTH_LONG).show(); }
        if (!profileOnly && bookingAccount != null) { add(text("Телефон из аккаунта YCLIENTS. Сменить аккаунт можно на главном экране.",12,MainActivity.GREEN,false));gap(12); }
        if (!profileOnly) {
            comment=field("Комментарий к этой записи",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE,null);comment.setMinLines(2);
            consent=new CheckBox(this);consent.setText(AppEnvironment.LOCAL?"Подтверждаю создание тестовой записи на локальном сервере":"Согласен с условиями YCLIENTS и обработкой персональных данных для записи");consent.setTextSize(13);consent.setTextColor(MainActivity.INK);add(consent);
            if(!AppEnvironment.LOCAL)add(button("Условия и обработка данных ↗",false,()->links()));gap(10);
        }
        apiStatus=text("",13,MainActivity.MUTED,false);add(apiStatus);gap(8);
        status=text(profileOnly?"Контакты сохраняются при выходе.":"Проверяем занятие и настройки клуба…",13,MainActivity.MUTED,false);add(status);gap(16);
        primary=button(profileOnly?"Сохранить данные":"Проверяем…",true,()->{if(profileOnly){saveProfile(true);}else submit();});add(primary);gap(10);
        add(button("Очистить сохранённые контакты",false,()->{name.setText("");surname.setText("");phone.setText("");email.setText("");if(!profileOnly&&bookingAccount!=null)fillAccount();store.clear();Toast.makeText(this,"Контакты удалены с устройства",Toast.LENGTH_SHORT).show();}));
        updateApiStatus(); if(!profileOnly)primary.setEnabled(false);
    }
    void fillAccount() {
        ClientProfile saved=profile();boolean same=saved.normalizedPhone().equals(new ClientProfile("","",bookingAccount.phone,"").normalizedPhone());
        if(!bookingAccount.name.isEmpty())name.setText(bookingAccount.name);else if(!same)name.setText("");
        if(!same)surname.setText("");
        email.setText(!bookingAccount.email.isEmpty()?bookingAccount.email:same?saved.email:"");
        phone.setText(bookingAccount.phone);phone.setEnabled(false);
    }
    ClientProfile profile() { return new ClientProfile(value(name),value(surname),value(phone),value(email)); }
    boolean saveProfile(boolean showMessage) {
        if(name==null)return true;
        try { store.save(profile(),remember.isChecked());if(showMessage)Toast.makeText(this,remember.isChecked()?"Данные сохранены":"Сохранение контактов отключено",Toast.LENGTH_SHORT).show();return true; }
        catch(Exception e){Toast.makeText(this,"Не удалось сохранить данные на устройстве",Toast.LENGTH_LONG).show();return false;}
    }
    void updateApiStatus() {
        if(AppEnvironment.LOCAL){apiStatus.setText("Сервер на компьютере · реальные записи не создаются.");if(primary!=null&&!profileOnly&&loaded&&!busy){primary.setEnabled(true);primary.setText("Записаться (тест)");}return;}
        apiStatus.setText("");
        if(primary!=null&&!profileOnly&&loaded&&!busy) { primary.setEnabled(true);primary.setText("Записаться"); }
    }
    void refresh() {
        loaded=false;primary.setEnabled(false);
        executor.execute(()->{try{
            Training fresh=scheduleApi.detail(training.id);JSONObject options=bookingApi.settings();
            runOnUiThread(()->{if(isFinishing())return;training=fresh;settings=options;loaded=true;trainingSummary.setText(summary(fresh));
                if(options.optBoolean("phone_confirmation",false)){status.setText("Клуб требует отдельного SMS-подтверждения записи. Этот режим пока не поддерживается.");primary.setText("Нужна поддержка SMS");return;}
                status.setText(fresh.bookable()?"Места и цена обновлены. Рекламные рассылки не включены.":"Запись на это занятие сейчас недоступна.");updateApiStatus();primary.setEnabled(fresh.bookable());
                showStoredAttempt();
            });
        }catch(Exception e){runOnUiThread(()->{if(isFinishing())return;status.setText("Не удалось проверить занятие. Проверьте интернет.");primary.setText("Повторить проверку");primary.setEnabled(true);primary.setOnClickListener(v->{primary.setOnClickListener(w->submit());refresh();});});}});
    }
    void showStoredAttempt() {
        try { JSONObject attempt=store.attempts().optJSONObject(Long.toString(training.id));if(attempt!=null){primary.setEnabled(false);receiptView(attempt);} }
        catch(Exception e){primary.setEnabled(false);status.setText("Не удалось проверить историю отправок. Перезапустите приложение.");}
    }
    void submit() {
        if(busy||!loaded)return;
        ClientProfile p=profile();
        if(p.name.isEmpty()){name.setError("Введите имя");name.requestFocus();return;}
        if(!p.validPhone()){phone.setError("Проверьте номер телефона");phone.requestFocus();return;}
        if(settings.optBoolean("booking_surname_required")&&p.surname.isEmpty()){surname.setError("Фамилия обязательна для клуба");surname.requestFocus();return;}
        if((settings.optBoolean("booking_email_required")||!p.email.isEmpty())&&!android.util.Patterns.EMAIL_ADDRESS.matcher(p.email).matches()){email.setError("Проверьте e-mail");email.requestFocus();return;}
        if(settings.optBoolean("booking_comment_required")&&value(comment).isEmpty()){comment.setError("Клуб просит заполнить комментарий");return;}
        if(settings.optBoolean("booking_patronymic_required")||settings.optBoolean("phone_confirmation")){status.setText("Настройки клуба требуют дополнительных полей, которые пока не поддерживаются. Запрос не отправлен.");return;}
        if(!consent.isChecked()){status.setText("Для передачи данных нужно ваше согласие с условиями записи.");consent.requestFocus();return;}
        final String token;
        try {token=store.token();if(token==null||token.isEmpty()){status.setText(NetworkMessage.ERROR);return;}if(store.attempts().has(Long.toString(training.id))){showStoredAttempt();return;}}
        catch(Exception e){status.setText(NetworkMessage.ERROR);return;}
        if(!saveProfile(false))return;
        final Training reviewed=training;final String note=value(comment);busy=true;primary.setEnabled(false);status.setText("Проверяем места и отправляем запись…");
        executor.execute(()->{
            boolean sent=false;
            try {
                Training fresh=scheduleApi.detail(reviewed.id);
                if(!fresh.bookable())throw new NativeBookingApi.Rejected("Свободных мест больше нет или запись закрыта.");
                if(fresh.priceMin!=reviewed.priceMin||fresh.priceMax!=reviewed.priceMax||!fresh.date.equals(reviewed.date)||!fresh.title.equals(reviewed.title)||!fresh.court.equals(reviewed.court)){
                    runOnUiThread(()->{busy=false;training=fresh;trainingSummary.setText(summary(fresh));status.setText("Условия занятия изменились. Проверьте их и нажмите «Записаться» ещё раз.");primary.setEnabled(true);});return;
                }
                JSONObject body=NativeBookingApi.payload(fresh,p,note,true);
                JSONObject pending=new JSONObject().put("state","unknown").put("training",fresh.source).put("created_at",Instant.now().toString());
                store.attempt(fresh.id,pending);sent=true;
                JSONObject receipt=bookingApi.book(fresh.id,body,token,bookingAccount==null?null:bookingAccount.token);
                JSONObject confirmed=new JSONObject().put("state","created").put("training",fresh.source).put("receipt",receipt).put("created_at",Instant.now().toString());
                boolean persisted=true;try{store.attempt(fresh.id,confirmed);}catch(Exception e){persisted=false;}
                final boolean saved=persisted;
                runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;receiptView(confirmed);
                    startActivity(new Intent(this,RecordActivity.class).putExtra("record_id",receipt.optLong("id")).putExtra("hash",receipt.optString("hash")).putExtra("account",MainActivity.identity(bookingAccount)).putExtra("created",true).putExtra("receipt_saved",saved));finish();});
            } catch(NativeBookingApi.Rejected e) {
                boolean cleared=true;try{if(sent)store.attempt(reviewed.id,null);}catch(Exception ignored){cleared=false;}
                final boolean canRetry=cleared;
                runOnUiThread(()->{busy=false;if(isFinishing())return;status.setText(e.getMessage());primary.setEnabled(canRetry);});
            } catch(Exception e) {
                final boolean unknown=sent;
                runOnUiThread(()->{busy=false;if(isFinishing())return;primary.setEnabled(!unknown);status.setText(unknown?"Ответ сервера не получен. Запись могла быть создана. Чтобы не получить дубль, повторная отправка заблокирована — уточните результат у клуба.":"Не удалось проверить занятие или сохранить данные. Запрос записи не отправлен.");});
            }
        });
    }
    void receiptView(JSONObject attempt) {
        boolean created="created".equals(attempt.optString("state"));
        if(!created){status.setText("Предыдущая отправка не получила подтверждения. Запись могла быть создана. Уточните результат у клуба перед новой попыткой.");primary.setText("Нужна проверка у клуба");primary.setEnabled(false);return;}
        JSONObject receipt=attempt.optJSONObject("receipt");
        status.setText((AppEnvironment.LOCAL?"Тестовая запись создана на компьютере. Номер: ":"Запись создана в YCLIENTS. Номер: ")+(receipt==null?"—":receipt.optLong("id"))+". Подтверждение сохранено на устройстве.");
        primary.setText("Вы записаны");primary.setEnabled(false);
        if(receipt!=null&&receipt.optJSONObject("charge")!=null){status.append(" Если клуб требует предоплату, статус нужно уточнить у клуба. Приложение не подтверждает оплату.");}
    }
    void history() {
        add(text("История отправок",28,MainActivity.INK,true));gap(12);
        add(text("Здесь хранятся ответы на записи, отправленные из этого приложения. Записи с сайта и изменения администратора автоматически не синхронизируются.",14,MainActivity.MUTED,false));gap(20);
        try {
            JSONObject all=store.attempts();List<JSONObject> entries=new ArrayList<>();Iterator<String> keys=all.keys();while(keys.hasNext())entries.add(all.getJSONObject(keys.next()));
            entries.sort((a,b)->b.optString("created_at").compareTo(a.optString("created_at")));
            if(entries.isEmpty())add(text("Записей из приложения пока нет.",18,MainActivity.INK,true));
            for(JSONObject entry:entries){Training t=new Training(entry.getJSONObject("training"));LinearLayout card=column();card.setPadding(dp(18),dp(18),dp(18),dp(18));background(card,MainActivity.WHITE,20);
                card.addView(text(summary(t),16,MainActivity.INK,true));TextView state=text("created".equals(entry.optString("state"))?"Создана · №"+entry.getJSONObject("receipt").optLong("id"):"Результат неизвестен · уточните у клуба",13,MainActivity.MUTED,false);state.setPadding(0,dp(12),0,0);card.addView(state);
                if(!"created".equals(entry.optString("state")))card.addView(button("Разрешить повтор после проверки",false,()->new AlertDialog.Builder(this).setTitle("Клуб подтвердил отсутствие записи?").setMessage("Повторная отправка может создать дубль. Разрешайте её только после проверки результата у клуба.").setPositiveButton("Да, записи нет",(d,w)->{try{store.attempt(t.id,null);content.removeAllViews();history();}catch(Exception e){Toast.makeText(this,"Не удалось сохранить изменение",Toast.LENGTH_LONG).show();}}).setNegativeButton("Отмена",null).show()));
                add(card);gap(12);
            }
        }catch(Exception e){add(text("Не удалось прочитать локальную историю.",16,MainActivity.MUTED,false));}
    }
    String summary(Training t){return t.title+"\n"+t.date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE · HH:mm",MainActivity.RU))+"\n"+t.court+" · "+t.duration+" мин\n"+t.price()+" · Ижевск (UTC+4)";}
    void links(){new AlertDialog.Builder(this).setTitle("Условия записи").setItems(new String[]{"Пользовательское соглашение YCLIENTS","Политика конфиденциальности","Обработка персональных данных"},(d,i)->external(new String[]{"https://www.yclients.com/info/user-agreement","https://www.yclients.com/info/confidential","https://www.yclients.com/info/data-processing-agreement"}[i])).show();}
    void external(String url){if(AppEnvironment.LOCAL){Toast.makeText(this,"Тестовый контур работает локально",Toast.LENGTH_LONG).show();return;}try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){Toast.makeText(this,"Нет приложения для открытия ссылки",Toast.LENGTH_SHORT).show();}}
    void back(){if(busy){Toast.makeText(this,"Дождитесь ответа сервера. Запрос нельзя отправлять повторно.",Toast.LENGTH_LONG).show();return;}finish();}
    @android.annotation.SuppressLint("GestureBackNavigation") @Override public void onBackPressed(){back();}
    @Override public void onPause(){if(!busy)saveProfile(false);super.onPause();}
    @Override public void onDestroy(){executor.shutdown();super.onDestroy();}
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);if(name!=null){b.putString("name",value(name));b.putString("surname",value(surname));b.putString("phone",value(phone));b.putString("email",value(email));b.putBoolean("remember",remember.isChecked());if(comment!=null)b.putString("comment",value(comment));}}
    int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
    String value(EditText e){return e==null?"":e.getText().toString().trim();}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    void background(View v,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));v.setBackground(d);}
    void add(View v){content.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    void gap(int n){content.addView(new View(this),new LinearLayout.LayoutParams(1,dp(n)));}
    TextView button(String label,boolean solid,Runnable callback){TextView t=text(label,15,solid?MainActivity.WHITE:MainActivity.GREEN,true);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(48));t.setPadding(dp(14),dp(12),dp(14),dp(12));background(t,solid?MainActivity.GREEN:MainActivity.BG,16);t.setOnClickListener(v->callback.run());t.setFocusable(true);return t;}
    EditText field(String label,int type,String hint){add(text(label,13,MainActivity.INK,true));gap(6);EditText e=new EditText(this);e.setInputType(type);e.setTextSize(16);e.setTextColor(MainActivity.INK);e.setPadding(dp(14),dp(12),dp(14),dp(12));background(e,MainActivity.WHITE,14);e.setMinHeight(dp(50));e.setContentDescription(label);if(hint!=null)e.setAutofillHints(hint);add(e);gap(16);return e;}
}
