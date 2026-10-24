package club.champion.tennis;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int INK = Color.rgb(22, 47, 34), GREEN = Color.rgb(29, 66, 46),
        LIME = Color.rgb(224, 249, 121), BG = Color.rgb(247, 248, 243),
        MUTED = Color.rgb(111, 122, 111), LINE = Color.rgb(225, 230, 218), WHITE = Color.WHITE;
    static final Locale RU = Locale.forLanguageTag("ru-RU");
    final YclientsApi api = new YclientsApi();
    final ExecutorService network = Executors.newFixedThreadPool(2);
    Future<?> scheduleTask;
    LocalDate selected = LocalDate.now(YclientsApi.CLUB_ZONE);
    List<Training> trainings = new ArrayList<>();
    String level = "Все уровни", court = "Любой корт";
    boolean availableOnly, loading;
    int generation, page;
    LinearLayout root, content, list, dates;
    TextView levelButton, courtButton, summary, dateTitle;
    HorizontalScrollView dateScroll;
    ProfileStore store;
    UserAccountApi accountApi = new UserAccountApi();
    UserSession account;
    String accountIdentity = "";
    List<UserRecord> records = new ArrayList<>();
    LinearLayout recordList;
    TextView recordSummary, upcomingButton, pastButton;
    boolean recordsLoading, recordsLoaded, pastRecords;
    Future<?> recordsTask;
    AttendanceStats attendanceStats;
    LinearLayout homeBanner;
    TextView homeBannerAction;
    UserRecord nextRecord;
    Training suggestedTraining;
    Future<?> bannerTask;
    boolean bannerLoading;
    int bannerGeneration;
    String bannerState="";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new ProfileStore(this);
        if (state != null) {
            selected = LocalDate.parse(state.getString("date", selected.toString()));
            level = state.getString("level", level); court = state.getString("court", court);
            availableOnly = state.getBoolean("available", false);
            page = state.getInt("page", 0); pastRecords = state.getBoolean("pastRecords",false);
        }
        render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("date", selected.toString()); state.putString("level", level);
        state.putString("court", court); state.putBoolean("available", availableOnly); state.putInt("page", page); state.putBoolean("pastRecords",pastRecords);
    }
    @Override protected void onResume() {
        super.onResume();
        UserSession current = readAccount();
        if (!identity(current).equals(accountIdentity)) { render(); return; }
        if (list != null && page == 0 && !loading) load();
        if(page==0&&!bannerLoading)loadHomeBanner();
        if ((page == 1 || page == 2) && !recordsLoading && account != null) loadRecords();
    }
    @Override public void onDestroy() { generation++; bannerGeneration++; network.shutdownNow(); super.onDestroy(); }
    int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }
    LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    void rounded(View v, int color, int radius) { v.setBackground(shape(color, radius)); }
    void gap(LinearLayout p, int h) { p.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h))); }
    void add(LinearLayout p, View v) { p.addView(v, new LinearLayout.LayoutParams(-1, -2)); }
    void weight(LinearLayout p, View v) { p.addView(v, new LinearLayout.LayoutParams(0, -2, 1)); }
    void margins(View v, int bottom) { LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)v.getLayoutParams(); lp.bottomMargin = dp(bottom); v.setLayoutParams(lp); }
    TextView button(String label, int bg, int fg, Runnable action) {
        TextView b = text(label, 15, fg, true); b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(15), dp(16), dp(15)); b.setMinHeight(dp(50));
        rounded(b, bg, 16); b.setOnClickListener(v -> action.run());
        b.setFocusable(true); b.setContentDescription(label);
        b.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View v, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(v, info); info.setClassName("android.widget.Button");
            }
        });
        return b;
    }
    void render() {
        generation++; loading = false; recordsLoading = false;
        if (scheduleTask != null) scheduleTask.cancel(true);
        if (recordsTask != null) recordsTask.cancel(true);
        bannerGeneration++;if(bannerTask!=null)bannerTask.cancel(true);bannerLoading=false;homeBanner=null;homeBannerAction=null;nextRecord=null;suggestedTraining=null;bannerState="";
        account = readAccount(); accountIdentity = identity(account);
        records = new ArrayList<>(); recordList = null; attendanceStats = null; recordsLoaded = false;
        root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        root.setClipToPadding(true);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(true);
        content = column(); content.setPadding(dp(22), dp(16), dp(22), dp(24));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        header();
        if(AppEnvironment.LOCAL){TextView banner=text("ТЕСТ · сервер на компьютере · записи вымышленные",12,GREEN,true);banner.setPadding(dp(12),dp(12),dp(12),dp(12));rounded(banner,LIME,12);add(content,banner);gap(content,18);}
        if (page == 0) schedule(); else if (page == 1) myRecords(); else if(page == 2) statistics(); else club();
        LinearLayout nav = row(); nav.setPadding(dp(12), dp(8), dp(12), dp(8)); nav.setBackgroundColor(WHITE);
        String[] labels = {"Расписание", "Мои записи", "Статистика", "Клуб"};
        String[] symbols = {"▦", "☷", "▥", "⌂"};
        for (int i = 0; i < labels.length; i++) {
            final int target = i;
            LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setPadding(dp(2), dp(8), dp(2), dp(8));
            TextView icon = text(symbols[i], 23, page == i ? GREEN : MUTED, true); icon.setGravity(Gravity.CENTER); item.addView(icon);
            gap(item, 5); TextView label = text(labels[i], 11, page == i ? GREEN : MUTED, page == i); label.setGravity(Gravity.CENTER); item.addView(label);
            if (page == i) rounded(item, BG, 16);
            item.setContentDescription(labels[i]); item.setFocusable(true);
            item.setOnClickListener(v -> { page = target; render(); }); weight(nav, item);
        }
        add(root, nav);
    }
    void header() {
        LinearLayout bar = row();
        BallView logo = new BallView(this); bar.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout names = column(); names.setPadding(dp(10), 0, 0, 0);
        names.addView(text("CHAMPION", 17, INK, true)); gap(names, 4); names.addView(text("TENNIS CLUB · ИЖЕВСК", 9, MUTED, true));
        weight(bar, names);
        String accountLabel = account == null ? "Войти" : account.name.trim().isEmpty() ? "Аккаунт" : account.name.trim();
        TextView login = button(accountLabel, WHITE, GREEN, this::openAccount);
        login.setSingleLine(true); login.setMaxWidth(dp(140)); login.setEllipsize(android.text.TextUtils.TruncateAt.END);
        login.setTextSize(13); login.setPadding(dp(12),dp(12),dp(12),dp(12)); login.setContentDescription(account == null ? "Войти в аккаунт" : "Мой аккаунт: " + accountLabel);
        bar.addView(login); add(content, bar); gap(content, 22);
    }
    void schedule() {
        homeBanner=column();homeBanner.setPadding(dp(16),dp(14),dp(16),dp(14));rounded(homeBanner,GREEN,22);add(content,homeBanner);gap(content,20);loadHomeBanner();
        LinearLayout heading = row(); weight(heading, text("Расписание", 25, INK, true));
        heading.addView(button("Обновить", BG, GREEN, ()->{load();loadHomeBanner();})); add(content, heading); gap(content, 6);
        TextView tz = text("Время клуба · Ижевск (UTC+4)", 12, MUTED, false); add(content, tz); gap(content, 16);
        LinearLayout month = row();
        dateTitle = text(cap(selected.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU))), 16, INK, true); weight(month, dateTitle);
        month.addView(button("Календарь ↗", BG, GREEN, this::pickDate)); add(content, month); gap(content, 8);
        dateScroll = new HorizontalScrollView(this); dateScroll.setHorizontalScrollBarEnabled(false);
        dates = row(); dateScroll.addView(dates); add(content, dateScroll); drawDates(); gap(content, 16);
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this); chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        levelButton = button(level + "  ⌄", WHITE, INK, this::pickLevel); chips.addView(levelButton);
        View spacer = new View(this); chips.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
        courtButton = button(court + "  ⌄", WHITE, INK, this::pickCourt); chips.addView(courtButton);
        chipsScroll.addView(chips); add(content, chipsScroll); gap(content, 10);
        LinearLayout availability = row(); weight(availability, text("Только со свободными местами", 13, MUTED, false));
        Switch sw = new Switch(this); sw.setContentDescription("Только со свободными местами"); sw.setChecked(availableOnly);
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));
        sw.setOnCheckedChangeListener((v, checked) -> { availableOnly = checked; showList(); }); availability.addView(sw); add(content, availability); gap(content, 16);
        summary = text("Загружаем расписание…", 12, MUTED, false); add(content, summary); gap(content, 12);
        list = column(); add(content, list); load();
    }
    void loadHomeBanner(){
        if(page!=0||homeBanner==null||network.isShutdown())return;
        if(bannerTask!=null)bannerTask.cancel(true);final int request=++bannerGeneration;
        bannerLoading=false;nextRecord=null;suggestedTraining=null;final UserSession user=account;
        if(user==null){bannerState="guest";drawHomeBanner("ТВОЙ CHAMPION","Твои занятия под рукой","Войди, чтобы видеть ближайшую запись.","","Войти →",this::openAccount);return;}
        bannerState="loading";bannerLoading=true;drawHomeBanner("БЛИЖАЙШЕЕ ЗАНЯТИЕ","Проверяем твои записи…","", "",null,null);
        bannerTask=network.submit(()->{try{
            List<UserRecord> all=accountApi.records(store.token(),user);Instant now=Instant.now();
            UserRecord next=all.stream().filter(r->!r.deleted&&r.attendance!=1&&r.attendance!=-1&&r.date.plusMinutes(r.minutes).toInstant().isAfter(now)).min(Comparator.comparing(r->r.date.toInstant())).orElse(null);
            Training offer=null;
            if(next==null){LocalDate today=LocalDate.now(YclientsApi.CLUB_ZONE);
                for(int offset=0;offset<14&&offer==null;offset++){
                    if(Thread.currentThread().isInterrupted())return;
                    for(Training candidate:api.schedule(today.plusDays(offset)))if(candidate.bookable()){offer=candidate;break;}
                }
            }
            final Training available=offer;
            runOnUiThread(()->{if(!activeBanner(request,user))return;bannerLoading=false;nextRecord=next;suggestedTraining=available;showHomeBanner();});
        }catch(Exception e){runOnUiThread(()->{if(!activeBanner(request,user))return;bannerLoading=false;bannerState="error";nextRecord=null;suggestedTraining=null;
            drawHomeBanner("БЛИЖАЙШЕЕ ЗАНЯТИЕ","Не удалось загрузить запись",NetworkMessage.ERROR,"","Обновить →",this::loadHomeBanner);
        });}});
    }
    boolean activeBanner(int request,UserSession user){return !isFinishing()&&!isDestroyed()&&page==0&&homeBanner!=null&&request==bannerGeneration&&identity(readAccount()).equals(user.identity());}
    void showHomeBanner(){
        if(nextRecord!=null){final UserRecord next=nextRecord;bannerState="record";
            String title=next.date.toInstant().isBefore(Instant.now())?"Сейчас · "+timeRange(next.date.toLocalDateTime(),next.minutes):bannerDate(next.date.toLocalDateTime(),next.minutes);
            drawHomeBanner("ТВОЁ БЛИЖАЙШЕЕ ЗАНЯТИЕ",title,next.title,next.staff,"Подробнее →",()->{if(!identity(readAccount()).equals(accountIdentity)){render();return;}startActivity(new Intent(this,RecordActivity.class).putExtra("record_id",next.id).putExtra("account",accountIdentity));});
        }else if(suggestedTraining!=null){final Training offer=suggestedTraining;bannerState="offer";
            drawHomeBanner("ЕСТЬ МЕСТО НА КОРТЕ",bannerDate(offer.date,offer.duration),offer.title+" · "+offer.court,offer.price()+" · мест: "+offer.remaining,"Записаться →",()->{if(!identity(readAccount()).equals(accountIdentity)){render();return;}if(offer.bookable())openNativeBooking(offer);else loadHomeBanner();});
        }else{bannerState="empty";drawHomeBanner("ТВОЙ CHAMPION","Пока нет свободных занятий","На ближайшие 14 дней. Проверь расписание позже.","","Обновить →",this::loadHomeBanner);}
    }
    String bannerDate(LocalDateTime date,int duration){LocalDate today=LocalDate.now(YclientsApi.CLUB_ZONE);String day=date.toLocalDate().equals(today)?"Сегодня":date.toLocalDate().equals(today.plusDays(1))?"Завтра":date.format(DateTimeFormatter.ofPattern("d MMM",RU));return day+" · "+timeRange(date,duration);}
    String timeRange(LocalDateTime date,int minutes){DateTimeFormatter time=DateTimeFormatter.ofPattern("HH:mm",RU);return date.format(time)+"–"+date.plusMinutes(minutes).format(time);}
    void drawHomeBanner(String eyebrow,String title,String detail,String extra,String action,Runnable click){
        if(homeBanner==null)return;homeBanner.removeAllViews();homeBannerAction=null;
        add(homeBanner,text(eyebrow,9,LIME,true));gap(homeBanner,7);add(homeBanner,text(title,20,WHITE,true));
        if(!detail.isEmpty()){gap(homeBanner,5);TextView description=text(detail,13,Color.rgb(213,226,210),false);description.setMaxLines(2);description.setEllipsize(android.text.TextUtils.TruncateAt.END);add(homeBanner,description);}
        if(action!=null){gap(homeBanner,7);LinearLayout bottom=row();TextView caption=text(extra,12,Color.rgb(213,226,210),false);caption.setMaxLines(2);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);caption.setPadding(0,0,dp(8),0);weight(bottom,caption);
            homeBannerAction=button(action,LIME,GREEN,click);homeBannerAction.setTextSize(12);homeBannerAction.setMinHeight(dp(40));homeBannerAction.setPadding(dp(12),dp(8),dp(12),dp(8));bottom.addView(homeBannerAction);add(homeBanner,bottom);
        }
    }
    void drawDates() {
        dates.removeAllViews(); LocalDate today = LocalDate.now(YclientsApi.CLUB_ZONE);
        LocalDate start = selected.isBefore(today.plusDays(14)) ? today : selected.minusDays(3);
        for (int i = 0; i < 14; i++) {
            LocalDate day = start.plusDays(i); boolean active = day.equals(selected);
            LinearLayout box = column(); box.setGravity(Gravity.CENTER); box.setPadding(0, dp(12), 0, dp(12));
            TextView dayName = text(day.format(DateTimeFormatter.ofPattern("EE", RU)).toUpperCase(RU), 10, active ? LIME : MUTED, true); dayName.setGravity(Gravity.CENTER); box.addView(dayName); gap(box, 10);
            TextView dayNumber = text(String.valueOf(day.getDayOfMonth()), 21, active ? WHITE : INK, true); dayNumber.setGravity(Gravity.CENTER); box.addView(dayNumber);
            rounded(box, active ? GREEN : WHITE, 18);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(80)); lp.rightMargin = dp(8); dates.addView(box, lp);
            box.setContentDescription(day.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", RU))); box.setSelected(active); box.setFocusable(true);
            box.setOnClickListener(v -> select(day));
            if (active) { final int x = i * dp(60); dateScroll.post(() -> dateScroll.smoothScrollTo(Math.max(0, x - dp(100)), 0)); }
        }
    }
    void select(LocalDate date) { selected = date; dateTitle.setText(cap(date.format(DateTimeFormatter.ofPattern("LLLL yyyy", RU)))); drawDates(); load(); }
    void pickDate() {
        DatePickerDialog dialog = new DatePickerDialog(this, (v,y,m,d) -> select(LocalDate.of(y,m+1,d)), selected.getYear(), selected.getMonthValue()-1, selected.getDayOfMonth());
        dialog.getDatePicker().setMinDate(LocalDate.now(YclientsApi.CLUB_ZONE).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()); dialog.show();
    }
    void pickLevel() {
        LinkedHashSet<String> levels = new LinkedHashSet<>(); levels.add("Все уровни"); for (Training t : trainings) levels.add(t.title);
        if (!levels.contains(level)) levels.add(level);
        String[] options = levels.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Уровень группы").setSingleChoiceItems(options, Arrays.asList(options).indexOf(level), (d,which) -> {
            level = options[which]; levelButton.setText(level + "  ⌄"); d.dismiss(); showList();
        }).setNegativeButton("Закрыть", null).show();
    }
    void pickCourt() {
        String[] options = {"Любой корт", "Трава", "Хард"};
        new AlertDialog.Builder(this).setTitle("Покрытие корта").setSingleChoiceItems(options, Arrays.asList(options).indexOf(court), (d,which) -> {
            court = options[which]; courtButton.setText(court + "  ⌄"); d.dismiss(); showList();
        }).setNegativeButton("Закрыть", null).show();
    }
    void load() {
        if (page != 0 || list == null || network.isShutdown()) return;
        if (scheduleTask != null) scheduleTask.cancel(true);
        final int request = ++generation; final LocalDate date = selected; loading = true;
        list.removeAllViews(); summary.setText("Загружаем расписание…");
        ProgressBar progress = new ProgressBar(this); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36)); lp.gravity = Gravity.CENTER; list.addView(progress, lp);
        scheduleTask = network.submit(() -> {
            try {
                List<Training> result = api.schedule(date);
                runOnUiThread(() -> { if (isFinishing() || request != generation) return; loading = false; trainings = result; showList(); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (isFinishing() || request != generation) return; loading = false; trainings = new ArrayList<>(); list.removeAllViews(); summary.setText("Не удалось обновить расписание");
                    add(list, text("Проверьте подключение к интернету", 19, INK, true)); gap(list, 10);
                    add(list, text(AppEnvironment.LOCAL?"Запустите сервер на компьютере и проверьте USB-подключение.":"Повторите попытку или откройте запись на сайте клуба.", 14, MUTED, false)); gap(list, 16);
                    add(list, button("Повторить", GREEN, WHITE, this::load)); gap(list, 8);
                    if(!AppEnvironment.LOCAL)add(list, button("Открыть сайт клуба", WHITE, GREEN, () -> openWeb(YclientsApi.BASE + "/company/1538880/activity/select?o=act" + selected)));
                });
            }
        });
    }
    void showList() {
        if (loading || page != 0 || list == null) return;
        list.removeAllViews(); int count = 0;
        for (Training t : trainings) {
            if (!level.equals("Все уровни") && !level.equals(t.title)) continue;
            if (!court.equals("Любой корт") && !t.court.toLowerCase(RU).contains(court.toLowerCase(RU))) continue;
            if (availableOnly && !t.bookable()) continue;
            card(list, t); count++;
        }
        summary.setText(selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)) + " · занятий: " + count);
        if (count == 0) {
            LinearLayout empty = column(); empty.setPadding(dp(22), dp(26), dp(22), dp(26)); rounded(empty, WHITE, 22);
            add(empty, text(trainings.isEmpty() ? "В этот день занятий нет" : "Ничего не нашлось", 21, INK, true)); gap(empty, 10);
            add(empty, text(trainings.isEmpty() ? "Выберите другой день — увидимся на корте." : "Попробуйте другой уровень, корт или день.", 14, MUTED, false)); gap(empty, 18);
            add(empty, button(trainings.isEmpty() ? "Следующий день →" : "Сбросить фильтры", GREEN, WHITE, () -> {
                if (trainings.isEmpty()) select(selected.plusDays(1));
                else { level = "Все уровни"; court = "Любой корт"; availableOnly = false; render(); }
            })); add(list, empty);
        }
    }
    void card(LinearLayout target, Training t) {
        LinearLayout c = column(); c.setPadding(dp(18), dp(18), dp(18), dp(18)); rounded(c, WHITE, 22);
        LinearLayout top = row(); weight(top, text(t.time() + "  —  " + t.date.plusMinutes(t.duration).toLocalTime(), 19, INK, true));
        String places = t.remaining == 0 ? "Нет мест" : t.remaining + " " + plural(t.remaining, "место", "места", "мест");
        TextView badge = text(places, 11, t.remaining == 0 ? MUTED : GREEN, true); badge.setPadding(dp(9), dp(6), dp(9), dp(6)); rounded(badge, t.remaining == 0 ? BG : Color.rgb(236,243,221), 10); top.addView(badge); add(c, top); gap(c, 15);
        add(c, text(t.title, 17, INK, true)); gap(c, 8);
        add(c, text(t.court + "  ·  " + t.duration + " мин", 12, MUTED, false)); gap(c, 18);
        LinearLayout bottom = row();
        LinearLayout price = column(); price.addView(text(t.price(), 20, INK, true)); gap(price, 3); price.addView(text("за занятие", 10, MUTED, false)); weight(bottom, price);
        TextView book = button(t.bookable() ? "Выбрать →" : "Подробнее", t.bookable() ? GREEN : BG, t.bookable() ? WHITE : MUTED, () -> detail(t));
        book.setContentDescription("Занятие " + t.title + " в " + t.time()); bottom.addView(book); add(c, bottom);
        add(target, c); margins(c, 12);
    }
    void detail(Training initial) {
        Dialog dialog = new Dialog(this); LinearLayout box = column(); box.setPadding(dp(24), dp(24), dp(24), dp(26)); rounded(box, BG, 28);
        ScrollView scroll = new ScrollView(this); scroll.addView(box); dialog.setContentView(scroll);
        Window window = dialog.getWindow(); if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.setLayout(-1,-2); window.setGravity(Gravity.BOTTOM); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); }
        TextView checking = text("Проверяем свободные места…", 13, MUTED, false); add(box, checking); gap(box, 16);
        add(box, text(initial.title, 26, INK, true)); gap(box, 20);
        add(box, text(initial.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)), 18, INK, true)); gap(box, 8);
        add(box, text(initial.time() + " · " + initial.duration + " мин · Ижевск (UTC+4)", 14, MUTED, false)); gap(box, 8);
        add(box, text(initial.court, 14, MUTED, false)); gap(box, 22);
        TextView pricing = text(initial.price(), 27, INK, true); add(box, pricing); gap(box, 12);
        add(box, text("На следующем экране проверьте данные и подтвердите запись на занятие.", 13, MUTED, false)); gap(box, 20);
        TextView book = button("Проверяем…", GREEN, WHITE, () -> {}); book.setEnabled(false); book.setAlpha(.45f); add(box, book); gap(box, 8);
        add(box, button("Закрыть", BG, GREEN, dialog::dismiss));
        dialog.show(); if (window != null) window.setLayout(-1, -2);
        network.execute(() -> {
            try {
                Training current = api.detail(initial.id);
                runOnUiThread(() -> { if (!dialog.isShowing() || isFinishing()) return;
                    checking.setText(current.remaining + " из " + current.capacity + " мест свободно · обновлено сейчас"); pricing.setText(current.price());
                    book.setText(current.bookable() ? "Перейти к записи →" : "Запись недоступна");
                    book.setEnabled(current.bookable()); book.setAlpha(current.bookable() ? 1 : .45f);
                    book.setOnClickListener(v -> { dialog.dismiss(); openNativeBooking(current); });
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!dialog.isShowing()) return;
                    checking.setText("Не удалось проверить места. Повторите проверку."); book.setText("Повторить →"); book.setEnabled(true); book.setAlpha(1);
                    book.setOnClickListener(v -> { dialog.dismiss(); detail(initial); });
                });
            }
        });
    }
    UserSession readAccount() {
        try { return store.session(); }
        catch (Exception e) { return null; }
    }
    static String identity(UserSession session) { return session == null ? "" : session.identity(); }
    void openAccount() { startActivityForResult(new Intent(this,AccountActivity.class),20); }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==20 && result==RESULT_OK) {
            if(data!=null && data.getBooleanExtra("records",false))page=1;
            render();
        }
    }
    void myRecords() {
        list=null;
        LinearLayout heading=row();weight(heading,text("Мои записи",28,INK,true));
        if(account!=null)heading.addView(button("Обновить",BG,GREEN,this::loadRecords));add(content,heading);gap(content,12);
        add(content,text("Занятия в Champion Club · время Ижевска (UTC+4)",13,MUTED,false));gap(content,20);
        if(account==null){
            LinearLayout panel=column();panel.setPadding(dp(22),dp(26),dp(22),dp(26));rounded(panel,WHITE,22);
            add(panel,text("Твои выходы на корт",23,INK,true));gap(panel,12);
            add(panel,text("Войди в аккаунт YCLIENTS, чтобы увидеть предстоящие и прошедшие занятия, в том числе записи с сайта и через администратора.",15,MUTED,false));gap(panel,22);
            add(panel,button("Войти по телефону →",GREEN,WHITE,this::openAccount));add(content,panel);return;
        }
        add(content,text(account.name.isEmpty()?PhoneMask.format(account.phone):account.name,16,INK,true));gap(content,16);
        LinearLayout tabs=row();
        upcomingButton=button("Предстоящие",pastRecords?WHITE:GREEN,pastRecords?GREEN:WHITE,()->selectRecords(false));
        pastButton=button("Прошедшие",pastRecords?GREEN:WHITE,pastRecords?WHITE:GREEN,()->selectRecords(true));
        weight(tabs,upcomingButton);View spacer=new View(this);tabs.addView(spacer,new LinearLayout.LayoutParams(dp(8),1));weight(tabs,pastButton);add(content,tabs);gap(content,18);
        recordSummary=text("Загружаем записи из YCLIENTS…",12,MUTED,false);add(content,recordSummary);gap(content,12);
        recordList=column();add(content,recordList);loadRecords();
    }
    void selectRecords(boolean past) {
        pastRecords=past;
        rounded(upcomingButton,past?WHITE:GREEN,16);upcomingButton.setTextColor(past?GREEN:WHITE);
        rounded(pastButton,past?GREEN:WHITE,16);pastButton.setTextColor(past?WHITE:GREEN);
        if(!recordsLoading && recordsLoaded)showRecords();
    }
    void loadRecords() {
        if((page!=1 && page!=2)||account==null||recordList==null||network.isShutdown())return;
        if(recordsTask!=null)recordsTask.cancel(true);
        final int request=++generation;final UserSession user=account;
        recordsLoading=true;recordsLoaded=false;records=new ArrayList<>();attendanceStats=null;recordList.removeAllViews();recordSummary.setText("Загружаем записи из YCLIENTS…");
        ProgressBar progress=new ProgressBar(this);recordList.addView(progress,new LinearLayout.LayoutParams(dp(36),dp(36)));
        recordsTask=network.submit(()->{try{
            String partner=store.token();List<UserRecord> result=accountApi.records(partner,user);
            runOnUiThread(()->{if(isFinishing()||request!=generation||!identity(readAccount()).equals(user.identity()))return;
                recordsLoading=false;recordsLoaded=true;records=result;if(page==2)showStatistics();else showRecords();
            });
        }catch(Exception e){runOnUiThread(()->{
            if(isFinishing()||request!=generation)return;recordsLoading=false;records=new ArrayList<>();recordList.removeAllViews();recordSummary.setText("Записи не загружены");
            add(recordList,text(e instanceof UserAccountApi.ApiError?e.getMessage():"Не удалось загрузить записи. Проверьте интернет и повторите попытку.",15,MUTED,false));gap(recordList,16);
            add(recordList,button("Повторить",GREEN,WHITE,this::loadRecords));gap(recordList,8);
        });}});
    }
    void showRecords() {
        if(page!=1||recordList==null||recordsLoading)return;
        recordList.removeAllViews();Instant now=Instant.now();List<UserRecord> filtered=new ArrayList<>();
        for(UserRecord record:records)if((!record.deleted&&record.upcoming(now))!=pastRecords)filtered.add(record);
        filtered.sort(pastRecords?Comparator.comparing((UserRecord r)->r.date.toInstant()).reversed():Comparator.comparing(r->r.date.toInstant()));
        recordSummary.setText((pastRecords?"Прошедших":"Предстоящих")+" записей: "+filtered.size()+(AppEnvironment.LOCAL?" · локальный сервер":" · данные YCLIENTS"));
        if(filtered.isEmpty()){
            gap(recordList,16);add(recordList,text(pastRecords?"Прошедших занятий пока нет":"Новых встреч на корте пока нет",22,INK,true));gap(recordList,12);
            add(recordList,text(pastRecords?"Здесь появятся занятия из истории аккаунта в этом клубе.":"Выберите подходящую группу в расписании.",14,MUTED,false));gap(recordList,20);
            if(!pastRecords)add(recordList,button("Выбрать занятие →",GREEN,WHITE,()->{page=0;render();}));return;
        }
        for(UserRecord record:filtered){
            LinearLayout card=column();card.setPadding(dp(18),dp(18),dp(18),dp(18));rounded(card,WHITE,22);
            add(card,text(record.dateLabel(),18,INK,true));gap(card,12);
            add(card,text(record.title,17,INK,true));gap(card,8);
            String detail=record.staff+(record.minutes>0?(record.staff.isEmpty()?"":" · ")+record.minutes+" мин":"");
            if(!detail.isEmpty()){add(card,text(detail,13,MUTED,false));gap(card,10);}
            add(card,text(record.status()+" · №"+record.id,12,record.deleted?MUTED:GREEN,true));
            if(!pastRecords){gap(card,14);add(card,text("Подробнее о записи →",13,GREEN,true));card.setFocusable(true);card.setContentDescription(record.title+", "+record.dateLabel()+", подробнее о записи");card.setOnClickListener(v->startActivity(new Intent(this,RecordActivity.class).putExtra("record_id",record.id).putExtra("account",accountIdentity)));}
            add(recordList,card);margins(card,12);
        }
    }
    void statistics() {
        list=null;
        LinearLayout heading=row();weight(heading,text("Статистика",28,INK,true));
        if(account!=null)heading.addView(button("Обновить",BG,GREEN,this::loadRecords));add(content,heading);gap(content,12);
        add(content,text("Твоя история игры в Champion Club",14,MUTED,false));gap(content,20);
        if(account==null){
            LinearLayout panel=column();panel.setPadding(dp(22),dp(24),dp(22),dp(24));rounded(panel,WHITE,22);
            add(panel,text("Каждый выход на корт считается",23,INK,true));gap(panel,12);
            add(panel,text("Войди в аккаунт, чтобы увидеть количество посещённых занятий и свою активность за последние семь дней и по месяцам.",15,MUTED,false));gap(panel,20);
            add(panel,button("Войти по телефону →",GREEN,WHITE,this::openAccount));add(content,panel);return;
        }
        recordSummary=text("Загружаем историю посещений…",12,MUTED,false);add(content,recordSummary);gap(content,12);
        recordList=column();add(content,recordList);loadRecords();
    }
    void showStatistics(){
        if(page!=2||recordList==null||recordsLoading||!recordsLoaded)return;
        attendanceStats=AttendanceStats.calculate(records,Instant.now());recordList.removeAllViews();
        recordSummary.setText(AppEnvironment.LOCAL?"Тестовая история · сервер на компьютере":"По загруженной истории аккаунта · время Ижевска");
        LinearLayout total=column();total.setPadding(dp(22),dp(22),dp(22),dp(22));rounded(total,GREEN,24);
        add(total,text("ВСЕГО ПОСЕЩЕНО",11,LIME,true));gap(total,10);
        add(total,text(String.valueOf(attendanceStats.total),52,WHITE,true));gap(total,6);
        add(total,text(plural(attendanceStats.total,"занятие","занятия","занятий")+" за всю историю с клубом",13,Color.rgb(213,226,210),false));gap(total,18);
        TextView annual=text(attendanceStats.lastYearTotal+" "+plural(attendanceStats.lastYearTotal,"занятие","занятия","занятий")+" за последние 12 месяцев",14,GREEN,true);annual.setPadding(dp(12),dp(10),dp(12),dp(10));rounded(annual,LIME,12);add(total,annual);add(recordList,total);gap(recordList,14);
        add(recordList,text("Учитываем прошедшие занятия с отметкой «Пришёл». Отмены, неявки и записи без этой отметки исключены.",12,MUTED,false));gap(recordList,22);
        if(attendanceStats.total==0){add(recordList,text("Посещений с отметкой «Пришёл» пока нет",19,INK,true));gap(recordList,12);}
        String[] days=new String[7],dates=new String[7],accessibleDays=new String[7];
        for(int i=0;i<7;i++){
            LocalDate day=attendanceStats.recentDays[i];
            days[i]=cap(day.format(DateTimeFormatter.ofPattern("EE",RU)));
            dates[i]=day.format(DateTimeFormatter.ofPattern("dd.MM",RU));
            accessibleDays[i]=day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM",RU));
        }
        String range=attendanceStats.recentDays[0].format(DateTimeFormatter.ofPattern("d MMM",RU))+" – "+attendanceStats.recentDays[6].format(DateTimeFormatter.ofPattern("d MMM",RU));
        statisticsChart("За последние 7 дней",range+" · "+attendanceStats.recentTotal+" "+plural(attendanceStats.recentTotal,"занятие","занятия","занятий"),days,dates,accessibleDays,attendanceStats.recentVisits);gap(recordList,18);
        String[] shortMonths={"янв","фев","мар","апр","май","июн","июл","авг","сен","окт","ноя","дек"};
        String[] months=new String[12],monthYears=new String[12],fullMonths=new String[12];
        for(int i=0;i<12;i++){
            YearMonth month=attendanceStats.monthDates[i];months[i]=shortMonths[month.getMonthValue()-1];monthYears[i]=month.format(DateTimeFormatter.ofPattern("yy",RU));
            fullMonths[i]=month.format(DateTimeFormatter.ofPattern("LLLL yyyy",RU));
        }
        String yearRange=attendanceStats.monthDates[0].format(DateTimeFormatter.ofPattern("LLL yyyy",RU))+" – "+attendanceStats.monthDates[11].format(DateTimeFormatter.ofPattern("LLL yyyy",RU));
        statisticsChart("Занятия за год",yearRange+" · "+attendanceStats.lastYearTotal+" "+plural(attendanceStats.lastYearTotal,"занятие","занятия","занятий"),months,monthYears,fullMonths,attendanceStats.months);
    }
    void statisticsChart(String title,String subtitle,String[] labels,String[] secondary,String[] accessibleLabels,int[] values){
        LinearLayout card=column();card.setPadding(dp(16),dp(20),dp(16),dp(20));rounded(card,WHITE,22);
        add(card,text(title,21,INK,true));gap(card,7);add(card,text(subtitle,12,MUTED,false));gap(card,14);
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setFillViewport(true);scroll.setHorizontalScrollBarEnabled(false);
        ColumnChart chart=new ColumnChart(this,labels,secondary,accessibleLabels,values);
        scroll.addView(chart,new android.widget.FrameLayout.LayoutParams(-2,dp(220*Math.max(1,getResources().getConfiguration().fontScale))));add(card,scroll);add(recordList,card);
    }
    static class ColumnChart extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),type=new Paint(Paint.ANTI_ALIAS_FLAG);
        final String[] labels,secondary;final int[] values;final float density,fontScale;
        ColumnChart(Context context,String[] labels,String[] secondary,String[] accessibleLabels,int[] values){
            super(context);this.labels=labels;this.secondary=secondary;this.values=values.clone();density=getResources().getDisplayMetrics().density;fontScale=getResources().getConfiguration().fontScale;
            setMinimumWidth((int)((32+labels.length*(secondary!=null&&secondary[0].length()>2?34:22))*fontScale*density));
            StringBuilder description=new StringBuilder();for(int i=0;i<values.length;i++){if(i>0)description.append("; ");description.append(accessibleLabels[i]).append(": ").append(values[i]).append(" ").append(plural(values[i],"занятие","занятия","занятий"));}
            setContentDescription(description.toString());setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);setFocusable(true);
        }
        @Override protected void onDraw(Canvas canvas){
            float left=24*density*fontScale,right=getWidth()-4*density,top=24*density*fontScale;
            float baseline=getHeight()-(secondary==null?26:43)*density*fontScale;
            float plotHeight=Math.max(density,baseline-top),cell=(right-left)/values.length;
            int max=1;for(int value:values)max=Math.max(max,value);
            int step=Math.max(1,(int)Math.ceil(max/4f)),ticks=(int)Math.ceil(max/(float)step),axisMax=ticks*step;
            type.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));type.setTextSize(10*density*fontScale);
            for(int tick=0;tick<=ticks;tick++){
                float y=baseline-plotHeight*tick/ticks;paint.setColor(LINE);paint.setStrokeWidth(density);canvas.drawLine(left,y,right,y,paint);
                type.setColor(MUTED);type.setTextAlign(Paint.Align.RIGHT);canvas.drawText(String.valueOf(tick*step),left-6*density,y+3*density,type);
            }
            float width=Math.min(28*density,cell*.62f);
            for(int i=0;i<values.length;i++){
                float x=left+cell*(i+.5f),height=plotHeight*values[i]/axisMax;
                if(values[i]>0){paint.setColor(GREEN);float radius=Math.min(4*density,height/2);canvas.drawRoundRect(x-width/2,baseline-height,x+width/2,baseline,radius,radius,paint);}
                type.setTextAlign(Paint.Align.CENTER);type.setColor(INK);type.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
                canvas.drawText(String.valueOf(values[i]),x,baseline-height-6*density,type);
                type.setColor(MUTED);type.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));canvas.drawText(labels[i],x,baseline+17*density*fontScale,type);
                if(secondary!=null)canvas.drawText(secondary[i],x,baseline+32*density*fontScale,type);
            }
        }
    }
    void club() {
        list = null; add(content, text("Место, где\nначинается теннис.", 31, INK, true)); gap(content, 24);
        CourtView court = new CourtView(this); rounded(court, GREEN, 26); court.setClipToOutline(true); content.addView(court, new LinearLayout.LayoutParams(-1, dp(190))); gap(content, 24);
        add(content, text("Champion Club", 24, INK, true)); gap(content, 10);
        add(content, text("Ижевск, улица Орджоникидзе, 23Б", 16, MUTED, false)); gap(content, 8);
        add(content, text("Часовой пояс клуба: UTC+4", 13, MUTED, false)); gap(content, 22);
        add(content, button("Построить маршрут ↗", GREEN, WHITE, this::map)); gap(content, 10);
        add(content, button("Мои данные →", WHITE, GREEN, () -> openNativeMode("profile"))); gap(content, 10);
        add(content, button("История отправок →", WHITE, GREEN, () -> openNativeMode("history"))); gap(content, 24);
        add(content, text("О приложении", 19, INK, true)); gap(content, 10);
        add(content, text(AppEnvironment.LOCAL?"Champion Тест · локальный контур\nРасписание, вход и записи обслуживает сервер на компьютере через USB. Данные вымышленные. Код входа: 1234.":"Champion Tennis · версия "+BuildConfig.VERSION_NAME+"\nРасписание загружается с сайта клуба. Здесь можно записаться на групповые занятия клуба.\n\nВход по телефону и SMS, предстоящие и прошедшие занятия — во вкладке «Мои записи».\n\nКонтакты и сеанс входа хранятся зашифрованными на устройстве. Приложение не запрашивает доступ к контактам или геолокации.", 13, MUTED, false));
    }
    void map() { external(android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode("Ижевск, улица Орджоникидзе, 23Б Champion Club"))); }
    void external(android.net.Uri uri) {
        if(AppEnvironment.LOCAL){Toast.makeText(this,"Тестовая версия работает только с локальным сервером",Toast.LENGTH_LONG).show();return;}
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Нет приложения для открытия ссылки", Toast.LENGTH_LONG).show(); }
    }
    void openWeb(String url) { external(android.net.Uri.parse(url)); }
    void openNativeBooking(Training t) { startActivity(new Intent(this, NativeBookingActivity.class).putExtra("training",t.source.toString())); }
    void openNativeMode(String mode) { startActivity(new Intent(this, NativeBookingActivity.class).putExtra("mode",mode)); }
    static String cap(String s) { return s.isEmpty() ? s : s.substring(0,1).toUpperCase(RU) + s.substring(1); }
    static String plural(int n, String one, String few, String many) { int m = n % 100; return m >= 11 && m <= 14 ? many : n % 10 == 1 ? one : n % 10 >= 2 && n % 10 <= 4 ? few : many; }

    static class BallView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BallView(Context c) { super(c); setContentDescription("Champion Tennis"); }
        @Override public void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(); p.setColor(GREEN); p.setStyle(Paint.Style.FILL); c.drawCircle(w/2,h/2,w/2,p);
            c.save(); c.translate(w*.5f,h*.5f); c.rotate(-27); float r=w*.28f; p.setColor(LIME); c.drawCircle(0,0,r,p);
            p.setColor(GREEN); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w*.035f);
            c.drawArc(-r*1.7f,-r,r*.15f,r,-90,180,false,p); c.drawArc(-r*.15f,-r,r*1.7f,r,90,180,false,p); c.restore(); p.setStyle(Paint.Style.FILL);
        }
    }
    static class CourtView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        CourtView(Context c) { super(c); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override public void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(); c.save(); c.rotate(-24,w*.85f,h*.5f);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f*getResources().getDisplayMetrics().density); p.setColor(Color.argb(80,218,244,170));
            float l=w*.62f, r=w*1.17f, t=-h*.2f, b=h*1.3f;
            c.drawRect(l,t,r,b,p); c.drawLine(l+w*.055f,t,l+w*.055f,b,p); c.drawLine(r-w*.055f,t,r-w*.055f,b,p);
            c.drawLine(l,h*.55f,r,h*.55f,p); c.drawLine(l+w*.055f,h*.12f,r-w*.055f,h*.12f,p); c.drawLine(l+w*.055f,h*.97f,r-w*.055f,h*.97f,p);
            c.drawLine((l+r)/2,h*.12f,(l+r)/2,h*.97f,p); c.restore();
            p.setStyle(Paint.Style.FILL); p.setColor(LIME); c.drawCircle(w*.85f,h*.76f,w*.055f,p);
            p.setStyle(Paint.Style.STROKE); p.setColor(GREEN); p.setStrokeWidth(2); float x=w*.85f,y=h*.76f,radius=w*.055f;
            c.drawArc(x-radius*1.5f,y-radius,x+radius*.1f,y+radius,-80,150,false,p); p.setStyle(Paint.Style.FILL);
        }
    }
}
