package com.darsbridge.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.ServiceWorkerControllerCompat;
import androidx.webkit.ServiceWorkerClientCompat;
import org.json.*;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Personal educational browser, not a hardened anonymity browser or a VPN. */
public class MainActivity extends Activity {
    // System-following UI palette: the browser never stores its own night-mode setting.
    private int BG, INK, TEAL, MUTED, SURFACE, ACTION, ACTION_TEXT;
    private boolean night;
    private final Map<TextView,Integer> uiTextRoles = new WeakHashMap<>();
    private final Set<Button> uiButtons = Collections.newSetFromMap(new WeakHashMap<Button,Boolean>());
    private static final int EXPORT_TEXT=80, EXPORT_BACKUP=81, IMPORT_BACKUP=82;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private FrameLayout root, pageArea, fullscreen;
    private LinearLayout shell, home;
    private WebView web, probe;
    private EditText address;
    private TextView routeText, detailText;
    private Button modeButton, connectionButton, tabsButton, continueTabButton, menuButton, connectionInfoButton;
    private LinearLayout connectionPanel;
    private TextView connectionSummary;
    private AlertDialog linkDialog;
    private int linkMenuEpoch=0;
    private TextView continueTabText;
    private BrowserStore browser;
    private static final class SessionTab {
        final BrowserStore.Tab data;
        WebView view;
        boolean failed=false,finished=false,needsLoad;
        long documentGeneration=0;
        volatile boolean allowed=false;
        SessionTab(BrowserStore.Tab data){this.data=data;needsLoad=!data.url.isEmpty();}
    }
    private final ArrayList<SessionTab> tabs=new ArrayList<>();
    private volatile SessionTab currentTab;
    private String pendingTabId="";
    private boolean sessionSaveWarning=false;
    private ProgressBar progress;
    static final String PREFS_NAME="darsbridge";
    private PageStore store;
    private RestoreTransaction restore;
    private boolean restoreBlocked=false,restoreBusy=false,restoreRecovered=false;
    private EmbeddedTor embedded;
    private IpScanDialog ipScanDialog;
    private Runnable waitingForEmbedded;
    private String embeddedMessage="Tor داخلی هنوز راه‌اندازی نشده";
    private String lastDiagnostic="";
    private ConnectionReport.TorCheck lastCheck=ConnectionReport.TorCheck.NOT_RUN;
    private final RecoveryPolicy recovery=new RecoveryPolicy();
    private volatile boolean visible=false;
    private boolean connectionWanted=false, recovering=false, autoReconnect=true;
    private boolean routeApplied=false,contentGuardReady=false,recoveryScheduled=false;
    private final RouteSerialiser proxyRoutes=new RouteSerialiser((port,complete)->{
        if(port>0){
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))throw new IllegalStateException("Proxy unavailable");
            ProxyConfig config=new ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:"+port).removeImplicitRules().build();
            ProxyController.getInstance().setProxyOverride(config,ui::post,complete);
        }else if(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){
            ProxyController.getInstance().clearProxyOverride(ui::post,complete);
        }else complete.run();
    });
    private int appliedPort=-1;
    private ConnectionPolicy.Mode appliedMode=null;
    private String appliedNetwork="";
    private final Runnable recoveryTask=()->{recoveryScheduled=false;attemptRecovery();};
    private ConnectionPolicy.Mode mode;
    private ConnectionPolicy.Route route=ConnectionPolicy.Route.BLOCKED;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean alive=true;
    private boolean changing=false, pageFailed=false, returnFromOrbot=false, probeActive=false;
    private volatile boolean networkAllowed=false;
    private int routeEpoch=0, probeEpoch=0, socksPort=9050;
    private String pendingUrl=null, networkKey="", exportText="", verification="";
    private long verifiedAt=0;
    private WebChromeClient.CustomViewCallback fullCallback;
    private View customView;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        applyColorValues((getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        store=new PageStore(this);
        restore=new RestoreTransaction(this,store,prefs());
        try{restoreRecovered=restore.recover();}catch(Exception e){restoreBlocked=true;}
        if(!prefs().contains("dataSchema"))prefs().edit().putInt("dataSchema",1).apply();
        embedded=new EmbeddedTor(this,(ready,message)->onEmbeddedEvent(ready,message));
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        mode=ConnectionPolicy.parse(prefs().getString("mode","AUTO"));
        autoReconnect=prefs().getBoolean("autoReconnect",true);
        socksPort=prefs().getInt("port",9050);
        if(socksPort<1 || socksPort>65535) socksPort=9050;
        connectionWanted=ResumePolicy.reconnect(autoReconnect,prefs().getBoolean("connectionWanted",false));
        browser=new BrowserStore(this);
        for(BrowserStore.Tab data:browser.tabs)tabs.add(new SessionTab(data));
        for(SessionTab tab:tabs)if(tab.data.id.equals(browser.selected))currentTab=tab;
        buildUi();
        configureWebView();
        networkKey=networkIdentity();
        watchNetwork();
        displayCurrentTab();
        if(browser.readFailed)detail("خواندن تب‌ها و تاریخچه ممکن نشد؛ نشانک‌ها و کتابخانه محفوظ‌اند.");
        if(restoreBlocked)detail("بازیابی قبلی کامل نشده؛ از صفحهٔ خانه آن را بررسی کنید.");
        else if(restoreRecovered)detail("بازیابی ناتمام قبلی با موفقیت تکمیل شد.");
        refreshRouteText();
        if(state!=null){String typed=state.getString("typedAddress","");address.setText(typed);}
        if(Intent.ACTION_SEND.equals(getIntent().getAction())) {
            String shared=getIntent().getStringExtra(Intent.EXTRA_TEXT);
            if(shared!=null) address.setText(shared.trim());
        }
    }
    private void applyColorValues(boolean newNight){
        night=newNight;
        BG=night?0xff10191f:0xfff3f6f5;
        INK=night?0xffe7f0f0:0xff192e36;
        TEAL=night?0xff68dcc0:0xff11685d;
        MUTED=night?0xffa3b8c1:0xff53636a;
        SURFACE=night?0xff19272d:Color.WHITE;
        ACTION=night?0xff283d44:0xffe3eeea;
        ACTION_TEXT=night?0xff102424:Color.WHITE;
    }
    private void applySystemAppearance(int mode){
        boolean newNight=(mode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        if(night==newNight){styleSystemBars();return;}
        applyColorValues(newNight);
        // Keep the existing WebView and Tor circuit alive while Android changes appearance.
        setTheme(R.style.AppTheme);
        if(root!=null)root.setBackgroundColor(BG);
        if(pageArea!=null)pageArea.setBackground(background(SURFACE));
        if(home!=null && home.getParent()!=null)home.getParent().requestLayout();
        View homeScroll=pageArea==null?null:pageArea.findViewWithTag("home");
        if(homeScroll!=null)homeScroll.setBackgroundColor(SURFACE);
        for(Map.Entry<TextView,Integer> e:new ArrayList<>(uiTextRoles.entrySet())){
            TextView t=e.getKey();if(t!=null)t.setTextColor(e.getValue()==1?TEAL:e.getValue()==2?MUTED:INK);
        }
        for(Button b:new ArrayList<>(uiButtons)){
            if(b!=null){b.setTextColor(INK);b.setBackground(background(ACTION));b.setBackgroundTintList(ColorStateList.valueOf(ACTION));b.setElevation(0);}
        }
        if(address!=null){
            address.setTextColor(INK);address.setHintTextColor(MUTED);
            address.setBackground(background(SURFACE));
        }
        for(SessionTab tab:tabs)if(tab.view!=null){tab.view.setBackgroundColor(SURFACE);styleWebContent(tab.view);tab.view.invalidate();}
        if(probe!=null)styleWebContent(probe);
        if(progress!=null){
            progress.setProgressTintList(ColorStateList.valueOf(TEAL));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(ACTION));
        }
        refreshRouteText();styleSystemBars();
    }
    private void styleWebContent(WebView view){
        if(WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.getSettings(),night);
    }
    private void styleSystemBars(){
        if(getWindow()==null || customView!=null)return;
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=30 && getWindow().getInsetsController()!=null){
            android.view.WindowInsetsController bars=getWindow().getInsetsController();
            bars.setSystemBarsAppearance(night?0:android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                 android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            bars.setSystemBarsAppearance(night?0:android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                 android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }else{
            int flags=getWindow().getDecorView().getSystemUiVisibility();
            flags&=~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            if(!night)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }
    @Override public void onConfigurationChanged(Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        applySystemAppearance(newConfig.uiMode);
    }
    private SharedPreferences prefs(){return getSharedPreferences(PREFS_NAME,MODE_PRIVATE);}
    private String displayVersion(){
        String out=BuildConfig.VERSION_NAME;
        for(int i=0;i<10;i++)out=out.replace((char)('0'+i),(char)('۰'+i));
        return out;
    }
    private boolean canChangeLibrary(){
        if(restoreBusy){toast("انتقال اطلاعات در حال انجام است؛ کمی صبر کنید.");return false;}
        if(restoreBlocked){toast("ابتدا بازیابی ناتمام را از صفحهٔ خانه بررسی کنید.");return false;}
        return true;
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        t.setGravity(Gravity.START);t.setPadding(dp(4),dp(4),dp(4),dp(4));
        uiTextRoles.put(t,color==TEAL?1:color==MUTED?2:0);
        return t;
    }
    private GradientDrawable background(int color){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;
    }
    private Button button(String label,View.OnClickListener listener){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(INK);
        b.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setPadding(dp(10),dp(2),dp(10),dp(2));
        b.setBackgroundTintList(ColorStateList.valueOf(ACTION));
        uiButtons.add(b);
        b.setOnClickListener(listener);return b;
    }
    private void buildUi(){
        root=new FrameLayout(this);root.setBackgroundColor(BG);setContentView(root);
        shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);shell.setPadding(dp(12),0,dp(12),0);
        root.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        shell.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
                shell.setPadding(dp(12)+bars.left,bars.top,dp(12)+bars.right,bars.bottom);
            }else shell.setPadding(dp(12),insets.getSystemWindowInsetTop(),dp(12),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);shell.addView(titleRow);
        TextView title=text("درس‌گذر "+displayVersion(),16,INK);title.setTypeface(null,Typeface.BOLD);
        titleRow.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        Button newTab=button("+",v->newTab());newTab.setTextSize(24);newTab.setTag("new-tab");newTab.setContentDescription("تب تازه");
        titleRow.addView(newTab,new LinearLayout.LayoutParams(dp(44),dp(48)));
        tabsButton=button("",v->showTabs());tabsButton.setTag("tabs");
        titleRow.addView(tabsButton,new LinearLayout.LayoutParams(dp(76),dp(48)));
        menuButton=button("⋮",v->showMenu());menuButton.setTag("menu");menuButton.setTextSize(24);menuButton.setContentDescription("منو");
        titleRow.addView(menuButton,new LinearLayout.LayoutParams(dp(44),dp(48)));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);shell.addView(bar);
        address=new EditText(this);address.setTag("address");address.setHint("جست‌وجوی گوگل یا آدرس سایت");
        address.setTextSize(14);address.setSingleLine(true);address.setSelectAllOnFocus(true);
        address.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);address.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        address.setTextColor(INK);address.setHintTextColor(MUTED);address.setBackground(background(SURFACE));address.setPadding(dp(12),0,dp(12),0);
        bar.addView(address,new LinearLayout.LayoutParams(0,dp(48),1));
        bar.addView(button("برو",v->navigate(address.getText().toString())),new LinearLayout.LayoutParams(dp(52),dp(48)));
        address.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_SEARCH || id==EditorInfo.IME_ACTION_GO){navigate(address.getText().toString());return true;}return false;});
        LinearLayout connectionStrip=new LinearLayout(this);connectionStrip.setGravity(Gravity.CENTER_VERTICAL);shell.addView(connectionStrip);
        connectionSummary=text("",12,TEAL);connectionSummary.setTag("connection-summary");connectionSummary.setMaxLines(1);connectionSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        connectionSummary.setOnClickListener(v->toggleConnectionPanel());connectionSummary.setContentDescription("جزئیات اتصال");
        connectionStrip.addView(connectionSummary,new LinearLayout.LayoutParams(0,dp(44),1));
        connectionButton=button("اتصال",v->{
            if(connectionWanted)stopConnection();else{userConnectionRequest();queueRestoredPage();prepareRoute(()->{loadPendingUrl();detail("مسیر آماده است؛ می‌توانید سایت را باز کنید.");});}
        });connectionButton.setTag("connect");
        connectionStrip.addView(connectionButton,new LinearLayout.LayoutParams(dp(108),dp(44)));
        connectionInfoButton=button("⌄",v->toggleConnectionPanel());connectionInfoButton.setTag("connection-details");connectionInfoButton.setContentDescription("نمایش جزئیات اتصال");
        connectionStrip.addView(connectionInfoButton,new LinearLayout.LayoutParams(dp(44),dp(44)));
        connectionPanel=new LinearLayout(this);connectionPanel.setOrientation(LinearLayout.VERTICAL);connectionPanel.setTag("connection-panel");connectionPanel.setVisibility(View.GONE);shell.addView(connectionPanel);
        modeButton=button("",v->chooseMode());modeButton.setTag("mode");connectionPanel.addView(modeButton,new LinearLayout.LayoutParams(-1,dp(44)));
        routeText=text("",12,TEAL);routeText.setTag("route");routeText.setMaxLines(2);connectionPanel.addView(routeText);
        detailText=text("",12,MUTED);detailText.setTag("detail");detailText.setMaxLines(4);connectionPanel.addView(detailText);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setVisibility(View.GONE);
        shell.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        pageArea=new FrameLayout(this);pageArea.setBackground(background(SURFACE));
        shell.addView(pageArea,new LinearLayout.LayoutParams(-1,0,1));
        web=createTabWeb(currentTab);web.setTag("web");
        ScrollView scroll=new ScrollView(this);
        home=new LinearLayout(this);home.setOrientation(LinearLayout.VERTICAL);home.setPadding(dp(16),dp(18),dp(16),dp(18));
        scroll.addView(home);scroll.setTag("home");scroll.setBackgroundColor(SURFACE);pageArea.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        TextView hello=text("آمادهٔ مطالعه",24,INK);hello.setTypeface(null,Typeface.BOLD);home.addView(hello);
        home.addView(text("جست‌وجو یا آدرس سایت را بالا بنویس. روی لینک‌ها انگشتت را نگه دار تا آن‌ها را در تب دیگر باز کنی یا کپی و اشتراک‌گذاری کنی.",15,MUTED));
        continueTabText=text("",14,MUTED);continueTabText.setTag("continue-tab-title");home.addView(continueTabText);
        continueTabButton=button("ادامهٔ این تب",v->{if(!currentTab.data.url.isEmpty())navigate(currentTab.data.url);});
        continueTabButton.setTag("continue-tab");home.addView(continueTabButton);
        home.addView(button("تاریخچهٔ مرور",v->showBrowsingHistory()));
        home.addView(button("باز کردن یوتیوب",v->navigate("https://m.youtube.com/")));
        home.addView(button("اتصال داخلی بدون برنامهٔ دیگر",v->useSnowflake()));
        home.addView(button("جست‌وجوی IP مستقیم",v->showIpScanner()));
        home.addView(button("راهنما و بررسی برنامه",v->guide()));
        home.addView(button("نشانک‌ها",v->showBookmarks()));
        home.addView(button("کتابخانهٔ آفلاین",v->showLibrary()));
        home.addView(button("پشتیبان‌گیری از نشانک‌ها و متن‌ها",v->backupMenu()));
        home.addView(button("انتقال اطلاعات نسخهٔ ۰.۹",v->migrationHelp()));
        if(restoreBlocked)home.addView(button("بررسی بازیابی ناتمام",v->resolvePendingRestore()));
        home.addView(text("برای استفادهٔ معمول، یک سایت یا ویدئو باز کن. اگر مشکلی دیدی، از «راهنما و بررسی برنامه» وضعیت را کپی کن. برای حساب‌های حساس از Tor Browser رسمی استفاده کن.",13,MUTED));
        LinearLayout nav=new LinearLayout(this);shell.addView(nav);
        nav.addView(button("برگشت",v->{if(web.canGoBack())history(-1);else showHome();}),new LinearLayout.LayoutParams(0,dp(48),1));
        nav.addView(button("جلو",v->history(1)),new LinearLayout.LayoutParams(0,dp(48),1));
        nav.addView(button("تازه‌سازی",v->refreshPage()),new LinearLayout.LayoutParams(0,dp(48),1));
        nav.addView(button("خانه",v->showHome()),new LinearLayout.LayoutParams(0,dp(48),1));
        fullscreen=new FrameLayout(this);fullscreen.setBackgroundColor(Color.BLACK);fullscreen.setVisibility(View.GONE);
        root.addView(fullscreen,new FrameLayout.LayoutParams(-1,-1));
        refreshMode();
        styleSystemBars();
    }
    @SuppressLint("SetJavaScriptEnabled")
    private boolean secureSettings(WebView w){
        WebSettings s=w.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSafeBrowsingEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);s.setBlockNetworkLoads(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w,false);
        // Respect web pages' own color-scheme; darken pages that have none if WebView supports it.
        styleWebContent(w);
        return ContentGuard.install(w);
    }
    private WebView createTabWeb(SessionTab tab){
        WebView view=new WebView(this);view.setBackgroundColor(SURFACE);view.setVisibility(View.GONE);
        tab.view=view;pageArea.addView(view,0,new FrameLayout.LayoutParams(-1,-1));
        configureTab(tab);return view;
    }
    private void configureWebView(){
        if(WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(new ServiceWorkerClientCompat(){
                @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request){
                    return networkAllowed?null:pausedResponse();
                }
            });
        }
        blockWorkers(true);
    }
    private static WebResourceResponse pausedResponse(){
        return new WebResourceResponse("text/plain","utf-8",503,"Connection paused",Collections.emptyMap(),new java.io.ByteArrayInputStream(new byte[0]));
    }
    private boolean active(SessionTab tab){return alive && currentTab==tab;}
    private void configureTab(SessionTab tab){
        WebView view=tab.view;
        view.setOnLongClickListener(v->longPressContent(tab));
        boolean guarded=secureSettings(view);
        contentGuardReady=tabs.stream().noneMatch(t->t.view!=null && t!=tab) ? guarded : contentGuardReady && guarded;
        view.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView v,int p){if(!active(tab))return;progress.setProgress(p);progress.setVisibility(p>=100 || tab.data.home?View.GONE:View.VISIBLE);}
            @Override public void onReceivedTitle(WebView v,String title){
                if(!alive || !tabs.contains(tab) || !UrlTools.isHttps(v.getUrl()))return;
                tab.data.title=BrowserStore.title(title);
                if(tab.finished && !tab.failed){browser.updateTitle(tab.data.url,tab.data.title);saveSession();}
            }
            @Override public void onPermissionRequest(PermissionRequest r){r.deny();}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
            @Override public void onShowCustomView(View v,CustomViewCallback cb){if(active(tab))enterFull(v,cb);else cb.onCustomViewHidden();}
            @Override public void onHideCustomView(){if(active(tab))exitFull();}
        });
        view.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){
                if(!UrlTools.isHttps(req.getUrl().toString())){if(active(tab))detail("فقط صفحات HTTPS در این نسخه باز می‌شوند.");return true;}
                if(!networkAllowed || changing || !tab.allowed){if(active(tab))detail("ابتدا اتصال را برقرار کنید.");return true;}
                return false;
            }
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){
                if(!alive || !tabs.contains(tab) || !UrlTools.isHttps(url))return;
                if(active(tab))dismissLinkMenu();tab.documentGeneration++;
                tab.failed=false;tab.finished=false;
                if(active(tab)){pageFailed=false;address.setText(url);detail("در حال بارگذاری «"+UrlTools.host(url)+"»…");}
            }
            @Override public void onPageFinished(WebView v,String url){
                if(!alive || !tabs.contains(tab))return;
                tab.finished=true;
                if(!tab.failed && networkAllowed && tab.allowed && UrlTools.isHttps(url)){
                    recordVisit(tab,url);
                    if(active(tab)){recovery.reset();address.setText(url);detail("بارگذاری صفحه پایان یافت • "+UrlTools.host(url));}
                }
            }
            @Override public void doUpdateVisitedHistory(WebView v,String url,boolean reload){
                // Also retain single-page-app routes such as /chat/id after pushState.
                if(tab.finished && !tab.failed && !reload && networkAllowed && tab.allowed){if(active(tab) && !url.equals(tab.data.url))dismissLinkMenu();recordVisit(tab,url);}
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){
                if(!r.isForMainFrame() || !alive || !tabs.contains(tab))return;
                tab.failed=true;if(!active(tab))return;
                pageFailed=true;detail("صفحه باز نشد. اتصال را بررسی کنید؛ خطا به‌تنهایی علت فیلترینگ را مشخص نمی‌کند.");
                int code=e.getErrorCode();
                if(connectionWanted && !recovering && (code==ERROR_CONNECT || code==ERROR_HOST_LOOKUP || code==ERROR_TIMEOUT || code==ERROR_IO)){
                    recovering=true;routeApplied=false;verification="";verifiedAt=0;
                    pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();scheduleRecovery();
                }
            }
            @Override public void onFormResubmission(WebView v,android.os.Message dontResend,android.os.Message resend){
                dontResend.sendToTarget();if(active(tab))detail("فرم خودکار دوباره ارسال نشد؛ برای ارسال تازه از خود صفحه اقدام کنید.");
            }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){
                if(r.isForMainFrame()){tab.failed=true;if(active(tab)){pageFailed=true;detail("پاسخ سایت: "+e.getStatusCode()+"؛ مشکل ممکن است از سایت یا مسیر اتصال باشد.");}}
            }
            @Override public void onReceivedSslError(WebView v,SslErrorHandler handler,SslError error){
                handler.cancel();tab.failed=true;if(active(tab)){pageFailed=true;detail("گواهی HTTPS معتبر نیست؛ برای حفظ امنیت اتصال لغو شد.");}
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if(!networkAllowed || !tab.allowed)return pausedResponse();
                if(r.isForMainFrame())tab.data.safeResume="GET".equals(r.getMethod());
                return null;
            }
        });
        view.setDownloadListener((url,ua,disposition,mime,size)->{
            if(active(tab))new AlertDialog.Builder(this).setTitle("دریافت فایل")
                .setMessage("دانلود مستقیم فایل در این نسخه پیاده‌سازی نشده است. برای متن صفحه از «ذخیره متن آفلاین» استفاده کنید؛ آدرس فایل به برنامه دیگری فرستاده نشد.")
                .setPositiveButton("باشه",null).show();
        });
    }
    private void dismissLinkMenu(){
        linkMenuEpoch++;
        if(linkDialog!=null){AlertDialog previous=linkDialog;linkDialog=null;previous.dismiss();}
    }
    private boolean validLinkSource(SessionTab tab,long generation,String document,int ticket){
        return active(tab) && visible && !tab.data.home && tab.view==web && web.getVisibility()==View.VISIBLE
            && generation==tab.documentGeneration && document!=null && document.equals(web.getUrl()) && ticket==linkMenuEpoch;
    }
    private boolean longPressContent(SessionTab tab){
        if(!active(tab) || !visible || tab.data.home || tab.view.getVisibility()!=View.VISIBLE)return false;
        WebView.HitTestResult hit=tab.view.getHitTestResult();if(hit==null)return false;
        int type=hit.getType();
        if(type!=WebView.HitTestResult.SRC_ANCHOR_TYPE && type!=WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE && type!=WebView.HitTestResult.IMAGE_TYPE)return false;
        dismissLinkMenu();final int ticket=linkMenuEpoch;final long generation=tab.documentGeneration;final String document=tab.view.getUrl();
        final String extra=hit.getExtra();
        if(type==WebView.HitTestResult.IMAGE_TYPE){showLinkActions(tab,generation,document,ticket,extra,"",true);return true;}
        // For an image inside an anchor, extra can be the image src; resolve the actual href.
        Handler href=new Handler(Looper.getMainLooper(),message->{
            if(!validLinkSource(tab,generation,document,ticket) || linkDialog!=null)return true;
            Bundle data=message.getData();String url=data.getString("url","");
            if(url.isEmpty() && type==WebView.HitTestResult.SRC_ANCHOR_TYPE)url=extra;
            showLinkActions(tab,generation,document,ticket,url,data.getString("title",""),false);return true;
        });
        tab.view.requestFocusNodeHref(href.obtainMessage());
        if(type==WebView.HitTestResult.SRC_ANCHOR_TYPE)ui.postDelayed(()->{
            if(validLinkSource(tab,generation,document,ticket) && linkDialog==null)showLinkActions(tab,generation,document,ticket,extra,"",false);
        },1500);
        return true;
    }
    private void showLinkActions(SessionTab tab,long generation,String document,int ticket,String href,String title,boolean image){
        if(!validLinkSource(tab,generation,document,ticket))return;
        final String link;
        try{link=LinkActions.resolve(href,document);}catch(IllegalArgumentException invalid){toast(invalid.getMessage());return;}
        final String label=LinkActions.label(title);
        ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);box.setPadding(dp(16),0,dp(16),dp(8));scroll.addView(box);
        if(!label.isEmpty()){TextView name=text(label,16,INK);name.setMaxLines(2);box.addView(name);}
        TextView preview=text(link,12,MUTED);preview.setTextDirection(View.TEXT_DIRECTION_LTR);preview.setMaxLines(3);preview.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);box.addView(preview);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(image?"گزینه‌های تصویر":"گزینه‌های لینک").setView(scroll).setNegativeButton("بستن",null).create();
        java.util.function.BiConsumer<String,Runnable> action=(name,run)->box.addView(button(name,v->{
            if(!validLinkSource(tab,generation,document,ticket)){dismissLinkMenu();return;}
            dismissLinkMenu();run.run();
        }),new LinearLayout.LayoutParams(-1,dp(48)));
        action.accept("باز کردن در تب تازه",()->openLinkTab(link,false));
        action.accept("افزودن به تب پس‌زمینه",()->openLinkTab(link,true));
        action.accept("باز کردن در همین تب",()->navigate(link));
        action.accept(image?"کپی آدرس تصویر":"کپی لینک",()->copyLinkText(link));
        if(!label.isEmpty())action.accept("کپی متن لینک",()->copyLinkText(label));
        action.accept("اشتراک لینک",()->shareLink(link));
        dialog.setOnDismissListener(d->{if(linkDialog==dialog){linkDialog=null;linkMenuEpoch++;}});
        linkDialog=dialog;dialog.show();
    }
    private void openLinkTab(String href,boolean background){
        final String link;try{link=LinkActions.resolve(href,null);}catch(IllegalArgumentException invalid){toast(invalid.getMessage());return;}
        if(tabs.size()>=BrowserStore.MAX_TABS){toast("حداکثر ۸ تب باز می‌ماند؛ ابتدا یک تب را ببندید.");return;}
        BrowserStore.Tab data=browser.add();if(data==null)return;
        if(background){
            data.url=link;data.home=false;data.safeResume=true;SessionTab tab=new SessionTab(data);tabs.add(tab);
            saveSession();updateTabCount();toast("لینک به تب پس‌زمینه اضافه شد؛ از «تب‌ها» آن را باز کن.");
        }else{
            SessionTab tab=new SessionTab(data);tabs.add(tab);selectTab(tab);navigate(link);
        }
    }
    private void copyLinkText(String value){
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard!=null){clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DarsBridge",value));toast("کپی شد.");}
    }
    private void shareLink(String link){
        Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");send.putExtra(Intent.EXTRA_TEXT,link);
        try{startActivity(Intent.createChooser(send,"اشتراک لینک"));}catch(Exception unavailable){toast("برنامه‌ای برای اشتراک پیدا نشد.");}
    }
    private void recordVisit(SessionTab tab,String url){
        if(!alive || !tabs.contains(tab) || !UrlTools.isHttps(url))return;
        try{tab.data.url=UrlTools.normalize(url);}catch(Exception invalid){return;}
        tab.data.title=BrowserStore.title(tab.view.getTitle());tab.needsLoad=false;
        browser.visit(tab.data.url,tab.data.title,System.currentTimeMillis());saveSession();updateTabCount();
    }
    private void detail(String s){
        detailText.setText(s);
        if(visible && ((connectionWanted && !networkAllowed && !changing && !embedded.isStarting()) || pageFailed))setConnectionPanel(true);
    }
    private void toggleConnectionPanel(){setConnectionPanel(connectionPanel.getVisibility()!=View.VISIBLE);}
    private void setConnectionPanel(boolean expanded){
        if(connectionPanel==null)return;
        connectionPanel.setVisibility(expanded?View.VISIBLE:View.GONE);connectionInfoButton.setText(expanded?"⌃":"⌄");
        connectionInfoButton.setContentDescription(expanded?"بستن جزئیات اتصال":"نمایش جزئیات اتصال");
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private boolean hasVpn(){
        try {Network n=connectivity.getActiveNetwork();NetworkCapabilities c=connectivity.getNetworkCapabilities(n);return c!=null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN);}
        catch(Exception e){return false;}
    }
    private String networkIdentity(){try{return String.valueOf(connectivity.getActiveNetwork())+":"+hasVpn();}catch(Exception e){return "unknown";}}
    private void watchNetwork(){
        networkCallback=new ConnectivityManager.NetworkCallback(){
            private void changed(){ui.post(()->networkChanged());}
            @Override public void onAvailable(Network n){changed();}
            @Override public void onLost(Network n){changed();}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){changed();}
        };
        try{connectivity.registerDefaultNetworkCallback(networkCallback);}catch(Exception ignored){ }
    }
    private void networkChanged(){
        if(!alive)return;
        String key=networkIdentity();if(key.equals(networkKey))return;networkKey=key;dismissLinkMenu();
        if(ipScanDialog!=null)ipScanDialog.networkChanged();
        verification="";verifiedAt=0;stopProbe();routeApplied=false;
        routeEpoch++;changing=false;waitingForEmbedded=null;
        pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;
        if(embedded.isStarting())embedded.refreshHealth();
        refreshRouteText();
        if(connectionWanted){
            recovering=true;
            detail(networkAvailable()?"شبکه عوض شد؛ مسیر اتصال دوباره بررسی می‌شود.":"شبکه در دسترس نیست؛ منتظر برگشت اتصال هستیم.");
            scheduleRecovery();
        }
    }

    private void refreshMode(){modeButton.setText(mode==ConnectionPolicy.Mode.AUTO?"مسیر: خودکار":mode==ConnectionPolicy.Mode.SYSTEM?"مسیر: شبکهٔ گوشی":mode==ConnectionPolicy.Mode.LOCAL?"مسیر: برنامهٔ کمکی":mode==ConnectionPolicy.Mode.SNOWFLAKE?"مسیر: پل داخلی":"مسیر: تور مستقیم");}
    private void refreshRouteText(){
        if(routeText==null)return;
        String name=route==ConnectionPolicy.Route.SOCKS?"پراکسی محلی":route==ConnectionPolicy.Route.INTERNAL?(embedded.isSnowflake()?"پل داخلی آماده است":"تور مستقیم آماده است"):route==ConnectionPolicy.Route.SYSTEM?(hasVpn()?"مسیر وی‌پی‌ان گوشی":"مسیر عادی گوشی"):embedded.isStarting()?"در حال آماده‌سازی اتصال":"اتصال برقرار نیست";
        String checked=networkAllowed && verifiedAt>0 && SystemClock.elapsedRealtime()-verifiedAt<300000?verification:"آزمایش خروجی انجام نشده یا منقضی است";
        routeText.setText(name+"\n"+checked);if(connectionSummary!=null)connectionSummary.setText(name);
        if(connectionButton!=null){
            connectionButton.setText(connectionWanted?"قطع اتصال":"اتصال");
            connectionButton.setTextColor(connectionWanted?ACTION_TEXT:INK);
            connectionButton.setBackgroundTintList(ColorStateList.valueOf(connectionWanted?TEAL:ACTION));
        }
    }
    private boolean networkAvailable(){try{return connectivity.getActiveNetwork()!=null;}catch(Exception e){return false;}}
    private void userConnectionRequest(){
        connectionWanted=true;prefs().edit().putBoolean("connectionWanted",true).apply();recovering=false;recovery.reset();cancelRecovery();refreshRouteText();
    }
    private boolean canReuseRoute(){
        if(!routeApplied || !networkAllowed || changing || appliedMode!=mode || !appliedNetwork.equals(networkIdentity()))return false;
        if(route==ConnectionPolicy.Route.INTERNAL)return embedded.isReady() && embedded.port()==appliedPort && (!hasVpn() || mode==ConnectionPolicy.Mode.AUTO);
        if(route==ConnectionPolicy.Route.SOCKS)return appliedPort==socksPort;
        return route==ConnectionPolicy.Route.SYSTEM && (mode==ConnectionPolicy.Mode.SYSTEM || (mode==ConnectionPolicy.Mode.AUTO && hasVpn()));
    }
    private void cancelRecovery(){recoveryScheduled=false;ui.removeCallbacks(recoveryTask);}
    private void scheduleRecovery(){
        if(recoveryScheduled || !alive || !autoReconnect || !connectionWanted || !visible || !networkAvailable())return;
        long delay=recovery.delayMillis();
        if(delay<0){detail("سه تلاش خودکار انجام شد؛ برای تلاش تازه، قطع و وصل را بزنید.");return;}
        recoveryScheduled=true;ui.postDelayed(recoveryTask,delay);
    }
    private void attemptRecovery(){
        if(!alive || !visible || !connectionWanted || !autoReconnect || !networkAvailable())return;
        if(recovery.nextDelayMillis()<0)return;
        recovering=true;
        prepareRoute(()->{
            recovering=false;
            if(pendingUrl!=null)loadPendingUrl();
            else detail("مسیر دوباره آماده شد؛ اگر صفحه ادامه نداد، تازه‌سازی را بزنید.");
        });
    }
    private void stopConnection(){
        dismissLinkMenu();connectionWanted=false;prefs().edit().putBoolean("connectionWanted",false).apply();recovering=false;recovery.reset();cancelRecovery();
        routeEpoch++;changing=false;waitingForEmbedded=null;pendingUrl=null;
        stopProbe();verification="";verifiedAt=0;routeApplied=false;
        pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;
        embedded.stop();saveSession();refreshRouteText();
        detail("اتصال متوقف شد. تلاش خودکار جدیدی انجام نمی‌شود.");
    }

    private void blockWorkers(boolean blocked){
        if(WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) && WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS))
            ServiceWorkerControllerCompat.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(blocked);
    }
    private void pauseNetwork(){
        proxyRoutes.invalidate();networkAllowed=false;blockWorkers(true);
        for(SessionTab tab:tabs)if(tab.view!=null){tab.allowed=false;tab.view.stopLoading();tab.view.getSettings().setBlockNetworkLoads(true);}
        if(probe!=null){probe.stopLoading();probe.getSettings().setBlockNetworkLoads(true);}
    }
    private boolean supportsProxy(){return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);}
    private boolean socksResponds(){
        try(Socket s=new Socket()){
            s.connect(new InetSocketAddress("127.0.0.1",socksPort),1500);s.setSoTimeout(1500);
            s.getOutputStream().write(new byte[]{5,1,0});s.getOutputStream().flush();
            return s.getInputStream().read()==5 && s.getInputStream().read()==0;
        }catch(Exception e){return false;}
    }
    /** Select/configure a route before loading. Failure never silently enables direct access. */
    private void prepareRoute(Runnable next){
        if(!visible){detail("پس از بازگشت به برنامه مسیر دوباره بررسی می‌شود.");return;}
        if(!contentGuardReady){pauseNetwork();detail("Android System WebView را به‌روز کنید؛ قابلیت محافظت اولیهٔ صفحات در دسترس نیست.");return;}
        if(!connectionWanted){detail("اتصال متوقف است؛ برای ادامه اتصال را بزنید.");return;}
        if(canReuseRoute()){next.run();return;}
        if(!networkAvailable()){
            waitingForEmbedded=null;routeApplied=false;pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;
            detail("اینترنت گوشی در دسترس نیست؛ با برگشت شبکه دوباره تلاش می‌کنیم.");refreshRouteText();return;
        }
        if(changing){toast("در حال آماده‌سازی مسیر هستیم.");return;}
        changing=true;int ticket=++routeEpoch;stopProbe();pauseNetwork();routeApplied=false;
        detail("در حال بررسی مسیر…");
        final ConnectionPolicy.Mode requested=mode;
        final String requestedNetwork=networkIdentity();
        worker.execute(()->{
            boolean vpn=hasVpn();boolean local=(requested==ConnectionPolicy.Mode.AUTO || requested==ConnectionPolicy.Mode.LOCAL) && !(requested==ConnectionPolicy.Mode.AUTO && vpn) && !embedded.isStarting() && socksResponds();
            ui.post(()->{
                if(!alive || !connectionWanted || ticket!=routeEpoch)return;
                boolean internalReady=embedded.isReady();
                if(internalReady && (requested==ConnectionPolicy.Mode.EMBEDDED || requested==ConnectionPolicy.Mode.SNOWFLAKE)
                    && embedded.isSnowflake()!=(requested==ConnectionPolicy.Mode.SNOWFLAKE)){
                    embedded.stop();internalReady=false;
                }
                ConnectionPolicy.Route choice=ConnectionPolicy.choose(requested,hasVpn(),local,supportsProxy(),internalReady);
                if((requested==ConnectionPolicy.Mode.EMBEDDED || requested==ConnectionPolicy.Mode.SNOWFLAKE) && hasVpn()){
                    changing=false;route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();
                    detail("VPN سیستم فعال است. برای اتصال فعلی حالت هوشمند را انتخاب کنید؛ Tor را دوباره درون VPN راه نمی‌اندازیم.");return;
                }
                if(choice==ConnectionPolicy.Route.BLOCKED){
                    changing=false;route=choice;refreshRouteText();
                    if((requested==ConnectionPolicy.Mode.EMBEDDED || requested==ConnectionPolicy.Mode.SNOWFLAKE || requested==ConnectionPolicy.Mode.AUTO) && supportsProxy()){
                        queueEmbedded(next);return;
                    }
                    detail("هیچ مسیر غیرمستقیمی آماده نیست و برنامه به اتصال مستقیم تغییر نمی‌کند.");if(recovering)scheduleRecovery();else showConnectionHelp();return;
                }
                final int desiredPort=choice==ConnectionPolicy.Route.INTERNAL?embedded.port():choice==ConnectionPolicy.Route.SOCKS?socksPort:-1;
                Runnable applied=()->{
                    if(!alive || !connectionWanted || ticket!=routeEpoch)return;
                    if(choice==ConnectionPolicy.Route.INTERNAL && (!embedded.isReady() || embedded.port()!=desiredPort)){
                        changing=false;routeApplied=false;pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;
                        waitingForEmbedded=next;refreshRouteText();embedded.refreshHealth();return;
                    }
                    if(!requestedNetwork.equals(networkIdentity())){
                        changing=false;routeApplied=false;pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;
                        verification="";verifiedAt=0;refreshRouteText();scheduleRecovery();return;
                    }
                    if(requested==ConnectionPolicy.Mode.AUTO && choice==ConnectionPolicy.Route.SYSTEM && !hasVpn()){
                        changing=false;route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();detail("VPN هنگام تنظیم مسیر قطع شد؛ دوباره تلاش کنید.");return;
                    }
                    if(route!=choice){verification="";verifiedAt=0;}
                    changing=false;recovering=false;route=choice;networkAllowed=true;blockWorkers(false);updateTabNetwork();
                    routeApplied=true;appliedMode=mode;appliedNetwork=requestedNetwork;
                    appliedPort=choice==ConnectionPolicy.Route.INTERNAL?embedded.port():choice==ConnectionPolicy.Route.SOCKS?socksPort:-1;
                    refreshRouteText();detail("مسیر آماده است. تأیید Tor نیاز به آزمایش جداگانه دارد.");next.run();
                };
                if((choice==ConnectionPolicy.Route.SOCKS || choice==ConnectionPolicy.Route.INTERNAL) && desiredPort<1){
                    changing=false;route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();scheduleRecovery();return;
                }
                proxyRoutes.apply(desiredPort,applied,error->{
                    if(!alive || ticket!=routeEpoch)return;
                    changing=false;pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();
                    detail("تنظیم مسیر موفق نبود؛ هیچ مسیر مستقیم جایگزین نشد.");scheduleRecovery();
                });
            });
        });
        ui.postDelayed(()->{if(alive && changing && ticket==routeEpoch){routeEpoch++;changing=false;pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();detail("تنظیم مسیر بیش از حد طول کشید. دوباره تلاش کنید.");scheduleRecovery();}},8000);
    }
    private void navigate(String input){
        final String link;
        try{link=Omnibox.resolve(input);}catch(Exception e){toast(e.getMessage());return;}
        userConnectionRequest();pendingUrl=link;pendingTabId=currentTab.data.id;address.setText(link);
        currentTab.data.url=link;currentTab.data.title="";currentTab.data.home=false;currentTab.data.safeResume=true;
        currentTab.needsLoad=true;saveSession();
        InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(address.getWindowToken(),0);
        prepareRoute(this::loadPendingUrl);
    }
    private void loadPendingUrl(){
        if(pendingUrl==null || !networkAllowed || !visible || !pendingTabId.equals(currentTab.data.id))return;
        String requested=pendingUrl;pendingUrl=null;pendingTabId="";
        currentTab.needsLoad=false;currentTab.data.safeResume=true;showWeb();web.loadUrl(requested);
    }
    private void refreshPage(){
        // Keep the existing history entry and reuse the already established route.
        if(!UrlTools.isHttps(web.getUrl()))return;
        userConnectionRequest();
        SessionTab requested=currentTab;prepareRoute(()->{if(active(requested)){showWeb();web.reload();}});
    }
    private void history(int delta){
        SessionTab requested=currentTab;userConnectionRequest();
        prepareRoute(()->{if(active(requested) && web.canGoBackOrForward(delta)){showWeb();web.goBackOrForward(delta);}});
    }
    private void updateTabNetwork(){
        for(SessionTab tab:tabs)if(tab.view!=null){
            tab.allowed=active(tab) && !tab.data.home && !tab.needsLoad && visible && networkAllowed && !changing;
            tab.view.getSettings().setBlockNetworkLoads(!tab.allowed);
        }
    }
    private void saveSession(){
        if(browser==null)return;
        browser.selected=currentTab.data.id;
        try{browser.save();}catch(Exception failure){if(!sessionSaveWarning){sessionSaveWarning=true;toast("ذخیرهٔ تب‌ها و تاریخچه ممکن نشد؛ فضای گوشی را بررسی کنید.");}}
    }
    private void updateTabCount(){if(tabsButton!=null)tabsButton.setText("تب‌ها ("+tabs.size()+")");}
    private void updateContinueTab(){
        boolean saved=!currentTab.data.url.isEmpty();
        continueTabText.setVisibility(saved?View.VISIBLE:View.GONE);continueTabButton.setVisibility(saved?View.VISIBLE:View.GONE);
        continueTabText.setText(saved?"این تب: "+(currentTab.data.title.isEmpty()?UrlTools.host(currentTab.data.url):currentTab.data.title):"");
    }
    private void displayCurrentTab(){
        boolean placeholder=currentTab.data.home || currentTab.needsLoad;
        pageArea.findViewWithTag("home").setVisibility(placeholder?View.VISIBLE:View.GONE);
        web.setVisibility(placeholder?View.GONE:View.VISIBLE);
        if(placeholder)web.onPause();else if(visible)web.onResume();
        address.setText(currentTab.data.home?"":currentTab.data.url);
        pageFailed=currentTab.failed;progress.setVisibility(View.GONE);updateTabNetwork();updateContinueTab();updateTabCount();
    }
    private void showHome(){
        dismissLinkMenu();pendingUrl=null;pendingTabId="";currentTab.data.home=true;web.stopLoading();web.onPause();
        displayCurrentTab();saveSession();detail("مطالعه آنلاین و متن‌های ذخیره‌شده، در یک جا.");
    }
    private void showWeb(){
        currentTab.data.home=false;pageArea.findViewWithTag("home").setVisibility(View.GONE);web.setVisibility(View.VISIBLE);
        if(visible)web.onResume();updateTabNetwork();saveSession();
    }
    private void queueRestoredPage(){
        if(currentTab.needsLoad && ResumePolicy.restorePage(connectionWanted,currentTab.data.home,currentTab.data.safeResume,currentTab.data.url)){
            pendingUrl=currentTab.data.url;pendingTabId=currentTab.data.id;
        }
    }
    private void newTab(){
        BrowserStore.Tab data=browser.add();
        if(data==null){toast("حداکثر ۸ تب باز می‌ماند؛ ابتدا یک تب را ببندید.");return;}
        SessionTab tab=new SessionTab(data);tabs.add(tab);selectTab(tab);address.requestFocus();
    }
    private void selectTab(SessionTab tab){
        if(!tabs.contains(tab))return;dismissLinkMenu();
        exitFull();
        if(web!=null){currentTab.allowed=false;web.stopLoading();web.getSettings().setBlockNetworkLoads(true);web.onPause();web.setVisibility(View.GONE);web.setTag(null);}
        pendingUrl=null;pendingTabId="";currentTab=tab;browser.selected=tab.data.id;
        web=tab.view==null?createTabWeb(tab):tab.view;web.setTag("web");
        displayCurrentTab();saveSession();
        if(connectionWanted){queueRestoredPage();if(pendingUrl!=null)prepareRoute(this::loadPendingUrl);}
    }
    private void closeTab(SessionTab tab){
        if(!tabs.contains(tab))return;dismissLinkMenu();
        boolean wasCurrent=tab==currentTab;
        if(wasCurrent){exitFull();pendingUrl=null;pendingTabId="";}
        tab.allowed=false;
        if(tab.view!=null){tab.view.stopLoading();tab.view.onPause();pageArea.removeView(tab.view);tab.view.destroy();tab.view=null;}
        tabs.remove(tab);browser.remove(tab.data.id);
        if(tabs.isEmpty())tabs.add(new SessionTab(browser.tabs.get(0)));
        if(wasCurrent){web=null;for(SessionTab candidate:tabs)if(candidate.data.id.equals(browser.selected)){selectTab(candidate);break;}}
        saveSession();updateTabCount();
    }
    private void showTabs(){
        ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(12),dp(8),dp(12),dp(8));scroll.addView(list);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("تب‌های باز").setView(scroll)
            .setPositiveButton("تب تازه",(d,w)->newTab()).setNegativeButton("بستن",null).create();
        fillTabs(list,dialog);dialog.show();
    }
    private void fillTabs(LinearLayout list,AlertDialog dialog){
        list.removeAllViews();
        for(SessionTab tab:new ArrayList<>(tabs)){
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);list.addView(row);
            String title=tab.data.title.isEmpty()?(tab.data.url.isEmpty()?"تب تازه":UrlTools.host(tab.data.url)):tab.data.title;
            Button select=button((tab==currentTab?"✓ ":"")+title,v->{dialog.dismiss();selectTab(tab);});select.setMaxLines(2);
            select.setContentDescription("باز کردن تب "+title);row.addView(select,new LinearLayout.LayoutParams(0,dp(64),1));
            Button close=button("×",v->{closeTab(tab);fillTabs(list,dialog);});close.setTextSize(24);close.setContentDescription("بستن تب "+title);
            row.addView(close,new LinearLayout.LayoutParams(dp(48),dp(64)));
        }
    }
    private void showBrowsingHistory(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);box.setPadding(dp(12),0,dp(12),0);
        EditText filter=new EditText(this);filter.setSingleLine(true);filter.setHint("جست‌وجو در تاریخچه");filter.setContentDescription("جست‌وجو در تاریخچه");filter.setTextColor(INK);filter.setHintTextColor(MUTED);box.addView(filter);
        TextView hint=text("تا ۲۰۰ صفحهٔ اخیر، فقط روی همین گوشی. متن پیام‌های چت ذخیره نمی‌شود.",13,MUTED);box.addView(hint);
        ListView list=new ListView(this);box.addView(list,new LinearLayout.LayoutParams(-1,dp(300)));
        ArrayList<BrowserStore.Visit> shown=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){
            @Override public View getView(int position,View convert,android.view.ViewGroup parent){TextView item=(TextView)super.getView(position,convert,parent);item.setTextColor(INK);item.setTextSize(14);item.setMaxLines(4);return item;}
        };list.setAdapter(adapter);
        Runnable refresh=()->{
            shown.clear();labels.clear();String query=filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            java.text.DateFormat date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT,new Locale("fa"));
            for(BrowserStore.Visit visit:browser.visits)if((visit.title+" "+visit.url).toLowerCase(Locale.ROOT).contains(query)){
                shown.add(visit);labels.add((visit.title.isEmpty()?UrlTools.host(visit.url):visit.title)+"\n"+visit.url+"\n"+date.format(new Date(visit.at)));
            }
            hint.setText(browser.visits.isEmpty()?"هنوز صفحه‌ای ثبت نشده است. صفحه‌های بعدی اینجا می‌مانند.":"تا ۲۰۰ صفحهٔ اخیر، فقط روی همین گوشی. متن پیام‌های چت ذخیره نمی‌شود.");adapter.notifyDataSetChanged();
        };
        filter.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){refresh.run();}public void afterTextChanged(android.text.Editable e){}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("تاریخچهٔ مرور").setView(box).setNegativeButton("بستن",null)
            .setNeutralButton("پاک‌کردن تاریخچه",null).create();
        list.setOnItemClickListener((parent,view,index,id)->{
            BrowserStore.Visit visit=shown.get(index);
            new AlertDialog.Builder(this).setTitle(visit.title.isEmpty()?"صفحهٔ قبلی":visit.title).setMessage(visit.url)
                .setPositiveButton("باز کردن",(d,w)->{dialog.dismiss();navigate(visit.url);})
                .setNeutralButton("در تب تازه",(d,w)->{if(tabs.size()>=BrowserStore.MAX_TABS){toast("ابتدا یک تب را ببندید.");return;}dialog.dismiss();newTab();navigate(visit.url);})
                .setNegativeButton("حذف این مورد",(d,w)->{browser.removeVisit(visit.url);saveSession();refresh.run();}).show();
        });
        refresh.run();dialog.show();dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->new AlertDialog.Builder(this)
            .setTitle("پاک‌کردن تاریخچه؟").setMessage("آدرس‌ها و عنوان‌های ثبت‌شده پاک می‌شوند؛ تب‌های باز و نشانک‌ها باقی می‌مانند.")
            .setPositiveButton("پاک کن",(d,w)->{browser.clearHistory();for(SessionTab tab:tabs)if(tab.view!=null)tab.view.clearHistory();saveSession();refresh.run();})
            .setNegativeButton("لغو",null).show());
    }
    /** Never switch to unproxied traffic while integrated Tor connects. */
    private void queueEmbedded(Runnable next){
        waitingForEmbedded=next;
        boolean snowflake=mode!=ConnectionPolicy.Mode.EMBEDDED;
        detail(embedded.isStarting()?"Tor داخلی در حال آماده‌شدن است.":(snowflake?"در حال راه‌اندازی Snowflake داخلی…":"Tor مستقیم در حال اتصال است."));
        embedded.start(snowflake);
    }
    private void onEmbeddedEvent(boolean ready,String message){
        if(!alive || !connectionWanted)return;
        embeddedMessage=message;
        if(message.startsWith("DIAG\n")){
            lastDiagnostic=message.substring(5);
            // No analytics or uploads. Show the local error and let the user copy it deliberately.
            if(visible && !recovering)showLocalDiagnostic();
        }
        if(!ready && route==ConnectionPolicy.Route.INTERNAL){
            pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;routeApplied=false;verification="";verifiedAt=0;
            recovering=true;waitingForEmbedded=()->detail("مسیر داخلی دوباره آماده شد؛ در صورت نیاز صفحه را تازه‌سازی کنید.");
        }
        refreshRouteText();
        if(ready && waitingForEmbedded!=null && visible){
            Runnable action=waitingForEmbedded;waitingForEmbedded=null;
            prepareRoute(action);
        }else if(waitingForEmbedded!=null && !embedded.isStarting()){
            waitingForEmbedded=null;
            detail(message+" از منو می‌توانید مسیر دیگر را انتخاب کنید.");
            recovering=true;scheduleRecovery();
        }else if(route==ConnectionPolicy.Route.BLOCKED){
            detail(message);
        }
    }
    private void useSnowflake(){selectEmbeddedMode(ConnectionPolicy.Mode.SNOWFLAKE);}
    private void useEmbedded(){selectEmbeddedMode(ConnectionPolicy.Mode.EMBEDDED);}
    private void selectEmbeddedMode(ConnectionPolicy.Mode selected){
        if(hasVpn()){
            new AlertDialog.Builder(this).setTitle("VPN فعال است")
                .setMessage("Tor داخلی را همزمان روی VPN دیگر راه نمی‌اندازیم. برای VPN فعلی حالت هوشمند را استفاده کنید؛ یا VPN را قطع کنید.")
                .setPositiveButton("حالت هوشمند",(d,w)->{
                    mode=ConnectionPolicy.Mode.AUTO;refreshMode();
                    prefs().edit().putString("mode",mode.name()).apply();connectAndCheck();
                }).setNegativeButton("بستن",null).show();return;
        }
        userConnectionRequest();mode=selected;
        prefs().edit().putString("mode",mode.name()).apply();
        routeEpoch++;changing=false;stopProbe();pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;
        refreshMode();refreshRouteText();
        connectAndCheck();
    }

    private void chooseMode(){
        if(changing)return;
        String[] options={"هوشمند: VPN، Orbot، سپس Snowflake داخلی","شبکه گوشی / VPN: ممکن است مستقیم باشد","پراکسی محلی Orbot","Tor مستقیم داخلی (اغلب مسدود است)","Snowflake داخلی (آزمایشی)"};
        new AlertDialog.Builder(this).setTitle("انتخاب مسیر")
            .setSingleChoiceItems(options,mode.ordinal(),(d,which)->{
                stopConnection();mode=ConnectionPolicy.Mode.values()[which];prefs().edit().putString("mode",mode.name()).apply();
                routeEpoch++;changing=false;waitingForEmbedded=null;stopProbe();pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;verification="";verifiedAt=0;
                refreshMode();refreshRouteText();d.dismiss();
                detail(mode==ConnectionPolicy.Mode.SYSTEM?"این حالت ممکن است مستقیم باشد؛ خودِ برنامه فیلترینگ را دور نمی‌زند.":mode==ConnectionPolicy.Mode.EMBEDDED?"این حالت Tor مستقیم است و ممکن است فیلتر شود.":mode==ConnectionPolicy.Mode.SNOWFLAKE?"Snowflake داخلی را بدون Orbot آزمایش می‌کنیم.":"برای اعمال مسیر «اتصال / بررسی» را بزنید.");
            }).setNegativeButton("بستن",null).show();
    }
    private void showConnectionHelp(){
        new AlertDialog.Builder(this).setTitle("مسیر اتصال آماده نیست")
            .setMessage("اگر Snowflake داخلی وصل نشد، Orbot را با پل موفق قبلی راه‌اندازی کنید. در حالت انتخاب برنامه‌ها، «درس‌گذر» را هم انتخاب کنید. هیچ‌یک در قطع کامل همه مسیرهای خارجی دسترسی نمی‌سازند.")
            .setPositiveButton("باز کردن Orbot",(d,w)->openOrbot()).setNeutralButton("تنظیم پورت",(d,w)->setPort())
            .setNegativeButton("فعلاً نه",null).show();
    }
    private void openOrbot(){
        for(String pkg:new String[]{"org.torproject.android","org.torproject.android.debug"}){
            Intent intent=getPackageManager().getLaunchIntentForPackage(pkg);
            if(intent!=null){
                returnFromOrbot=true;
                try{startActivity(intent);return;}catch(Exception e){returnFromOrbot=false;}
            }
        }
        new AlertDialog.Builder(this).setTitle("Orbot پیدا نشد")
            .setMessage("این نسخه Snowflake داخلی دارد، اما Orbot برای روش‌های دیگر مثل obfs4 و WebTunnel گزینه جایگزین است.")
            .setPositiveButton("وب‌سایت رسمی",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://orbot.app/")));}catch(Exception e){toast("مرورگر دیگری پیدا نشد.");}})
            .setNegativeButton("بستن",null).show();
    }
    private void setPort(){
        if(changing){toast("ابتدا اجازه دهید تنظیم مسیر تمام شود.");return;}
        EditText field=new EditText(this);field.setInputType(InputType.TYPE_CLASS_NUMBER);field.setText(String.valueOf(socksPort));
        new AlertDialog.Builder(this).setTitle("پورت SOCKS محلی Orbot").setMessage("فقط روی 127.0.0.1؛ پیش‌فرض 9050 است.").setView(field)
            .setPositiveButton("ثبت",(d,w)->{try{socksPort=ConnectionPolicy.parsePort(field.getText().toString());prefs().edit().putInt("port",socksPort).apply();stopProbe();verifiedAt=0;verification="";pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();}catch(Exception e){toast(e.getMessage());}})
            .setNegativeButton("لغو",null).show();
    }
    private void connectAndCheck(){
        if(changing)return;
        userConnectionRequest();
        if(!prefs().getBoolean("checkConsent",false)){
            new AlertDialog.Builder(this).setTitle("بررسی اتصال Tor")
                .setMessage("برای این آزمایش، یک درخواست HTTPS از همین مسیر مرورگر به check.torproject.org فرستاده می‌شود. آن سایت IP خروجی را می‌بیند؛ آدرس صفحه‌های شما ارسال نمی‌شود. نتیجه فقط همین آزمایش را تأیید می‌کند، نه ناشناس‌بودن کامل مرورگر.")
                .setPositiveButton("بررسی کن",(d,w)->{prefs().edit().putBoolean("checkConsent",true).apply();prepareRoute(()->startProbe());})
                .setNegativeButton("لغو",null).show();return;
        }
        prepareRoute(()->startProbe());
    }
    private void stopProbe(){probeEpoch++;probeActive=false;lastCheck=ConnectionReport.TorCheck.NOT_RUN;if(probe!=null)probe.stopLoading();}
    private void startProbe(){
        stopProbe();final int ticket=probeEpoch;probeActive=true;lastCheck=ConnectionReport.TorCheck.CHECKING;
        if(probe!=null){probe.destroy();probe=null;}
        probe=new WebView(this);secureSettings(probe);probe.getSettings().setBlockNetworkLoads(false);
        probe.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);probe.getSettings().setDomStorageEnabled(false);
        final String check="https://check.torproject.org/api/ip?darsbridge="+SystemClock.elapsedRealtime();
        detail("در حال آزمایش HTTPS از مسیر همین مرورگر…");
        probe.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !check.equals(r.getUrl().toString());}
            @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError e){h.cancel();probeFailed(ticket,"گواهی سرویس آزمایش معتبر نیست.");}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())probeFailed(ticket,"سرویس آزمایش پاسخ نداد؛ این به‌تنهایی به معنی قطع کل اینترنت نیست.");}
            @Override public void onFormResubmission(WebView v,android.os.Message dontResend,android.os.Message resend){
                dontResend.sendToTarget();
                detail("فرم خودکار دوباره ارسال نشد؛ برای ارسال تازه از خود صفحه اقدام کنید.");
            }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){if(r.isForMainFrame())probeFailed(ticket,"سرویس آزمایش خطای HTTP داد.");}
            @Override public void onPageFinished(WebView v,String url){
                if(!alive || !probeActive || ticket!=probeEpoch || !check.equals(url) || !check.equals(v.getUrl()))return;
                v.evaluateJavascript("(function(){return document.body?document.body.innerText:'';})()",value->{
                    if(!alive || !probeActive || ticket!=probeEpoch)return;
                    try{
                        Object body=new JSONTokener(value).nextValue();if(!(body instanceof String))throw new Exception();
                        JSONObject data=new JSONObject((String)body);Object isTor=data.get("IsTor");
                        if(!(isTor instanceof Boolean))throw new Exception();
                        String ip=data.optString("IP","");if(!ip.matches("[0-9a-fA-F:.]{3,64}"))throw new Exception();
                        probeActive=false;verifiedAt=SystemClock.elapsedRealtime();
                        lastCheck=(Boolean)isTor?ConnectionReport.TorCheck.TOR:ConnectionReport.TorCheck.NOT_TOR;
                        verification=((Boolean)isTor)?"Tor در آخرین آزمایش تأیید شد":"آخرین آزمایش از Tor نبود";
                        refreshRouteText();detail("IP خروجی آزمایش: "+ip+" • نتیجه محدود به همین درخواست است.");
                    }catch(Exception e){probeFailed(ticket,"پاسخ قابل‌تأییدی از سرویس آزمایش دریافت نشد.");}
                });
            }
        });
        probe.loadUrl(check,Collections.singletonMap("Cache-Control","no-cache, no-store"));
        ui.postDelayed(()->probeFailed(ticket,"زمان آزمایش تمام شد؛ Tor تأیید نشده است. امکان بازشدن خود سایت‌ها را جدا بررسی کنید."),30000);
    }
    private void probeFailed(int ticket,String message){
        if(!alive || !probeActive || ticket!=probeEpoch)return;
        stopProbe();lastCheck=ConnectionReport.TorCheck.FAILED;verifiedAt=0;verification="";refreshRouteText();detail(message);
    }
    private void showLocalDiagnostic(){
        if(lastDiagnostic.isEmpty()){
            new AlertDialog.Builder(this).setTitle("خطای ذخیره‌شده وجود ندارد")
                .setMessage("ابتدا Snowflake داخلی را آزمایش کنید. گزارش فقط روی همین گوشی نمایش داده می‌شود.")
                .setPositiveButton("بستن",null).show();
            return;
        }
        final String report=lastDiagnostic;
        new AlertDialog.Builder(this).setTitle("گزارش محلی Snowflake")
            .setMessage(report+"\n\nگزارش فقط روی همین دستگاه است و خودکار ارسال نمی‌شود.")
            .setPositiveButton("بستن",null)
            .setNeutralButton("کپی گزارش",(d,w)->{
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(clipboard!=null){
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DarsBridge Snowflake diagnostic",report));
                    toast("متن خطا کپی شد.");
                }
            }).show();
    }

    private void showMenu(){
        String[] items={"نشانک این صفحه","نشانک‌ها","مطالعهٔ متن صفحه","ذخیرهٔ متن آفلاین","کتابخانهٔ آفلاین","باز کردن Orbot","تنظیم پورت Orbot","قطع اتصال","آخرین خطای اتصال","بررسی خروجی تور","پشتیبان‌گیری و بازیابی","پاک‌کردن داده‌های مرور","اتصال مجدد خودکار: "+(autoReconnect?"روشن":"خاموش"),"راهنما و بررسی برنامه","جست‌وجوی IP مستقیم","تب تازه","تب‌های باز","تاریخچهٔ مرور","جزئیات اتصال"};
        PopupMenu popup=new PopupMenu(this,menuButton,Gravity.END);
        int[] order={15,16,17,1,4,0,2,3,10,12,18,14,9,8,6,5,11,7,13};
        for(int i=0;i<order.length;i++)popup.getMenu().add(0,order[i],i,items[order[i]]);
        popup.setOnMenuItemClickListener(item->{menuAction(item.getItemId());return true;});popup.show();
    }
    private void menuAction(int which){
        switch(which){case 0:addBookmark();break;case 1:showBookmarks();break;case 2:readText(false);break;
            case 3:readText(true);break;case 4:showLibrary();break;case 5:openOrbot();break;case 6:setPort();break;
            case 7:stopConnection();break;case 8:showLocalDiagnostic();break;case 9:connectAndCheck();break;case 10:backupMenu();break;
            case 11:clearBrowsing();break;
            case 12:autoReconnect=!autoReconnect;prefs().edit().putBoolean("autoReconnect",autoReconnect).apply();if(!autoReconnect)cancelRecovery();toast(autoReconnect?"اتصال خودکار فعال شد.":"اتصال خودکار خاموش شد.");break;
            case 14:showIpScanner();break;case 15:newTab();break;case 16:showTabs();break;case 17:showBrowsingHistory();break;
            case 18:toggleConnectionPanel();break;default:guide();
        }
    }
    private String current(){String url=web.getUrl();return UrlTools.normalize(UrlTools.isHttps(url)?url:address.getText().toString());}
    private void addBookmark(){
        if(!canChangeLibrary())return;
        try{Set<String> all=new HashSet<>(prefs().getStringSet("marks",Collections.emptySet()));all.add(current());if(all.size()>BackupData.MAX_MARKS){toast("حداکثر ۱۰۰ نشانک؛ ابتدا یکی را حذف کنید.");return;}prefs().edit().putStringSet("marks",all).apply();toast("نشانک ذخیره شد.");}
        catch(Exception e){toast("ابتدا یک سایت باز کنید.");}
    }
    private void showBookmarks(){
        ArrayList<String> all=new ArrayList<>(prefs().getStringSet("marks",Collections.emptySet()));Collections.sort(all);
        if(all.isEmpty()){toast("هنوز نشانکی ذخیره نشده است.");return;}
        new AlertDialog.Builder(this).setTitle("نشانک‌ها").setItems(all.toArray(new String[0]),(d,i)->
            new AlertDialog.Builder(this).setMessage(all.get(i)).setPositiveButton("باز کردن",(a,b)->navigate(all.get(i)))
                .setNeutralButton("حذف نشانک",(a,b)->{if(!canChangeLibrary())return;Set<String> keep=new HashSet<>(all);keep.remove(all.get(i));prefs().edit().putStringSet("marks",keep).apply();})
                .setNegativeButton("بستن",null).show()).setNegativeButton("بستن",null).show();
    }
    private void readText(boolean save){
        if(save && !canChangeLibrary())return;
        if(pageFailed || !UrlTools.isHttps(web.getUrl()) || web.getVisibility()!=View.VISIBLE){toast("ابتدا یک صفحه را با موفقیت باز کنید.");return;}
        String url=web.getUrl(),title=web.getTitle()==null?url:web.getTitle();
        // Read only visible text. No external reader service and no JavaScript bridge.
        web.evaluateJavascript("(function(){var n=document.querySelector('article')||document.querySelector('main')||document.body;return n?n.innerText.slice(0,100000):'';})()",json->{
            if(!alive)return;
            try{
                Object parsed=new JSONTokener(json).nextValue();if(!(parsed instanceof String) || ((String)parsed).trim().length()<5)throw new Exception();
                String value=(String)parsed;
                if(save){if(!canChangeLibrary())return;worker.execute(()->{try{store.add(title,url,value);ui.post(()->{if(alive)toast("متن روی گوشی ذخیره شد؛ ویدئو ذخیره نشده است.");});}catch(Exception e){ui.post(()->{if(alive)toast("ذخیره انجام نشد: "+e.getMessage());});}});}
                else showReading(title,url,value,false);
            }catch(Exception e){toast("متن قابل ذخیره‌ای در این صفحه پیدا نشد.");}
        });
    }
    private void showLibrary(){
        worker.execute(()->{try{
            JSONArray pages=store.read();ui.post(()->{
                if(!alive)return;if(pages.length()==0){toast("کتابخانه خالی است. از منو، متن صفحه را ذخیره کنید.");return;}
                String[] names=new String[pages.length()];for(int i=0;i<names.length;i++)names[i]=pages.optJSONObject(i).optString("title","متن");
                new AlertDialog.Builder(this).setTitle("کتابخانه آفلاین • فقط متن").setItems(names,(d,i)->{
                    JSONObject p=pages.optJSONObject(i);showReading(p.optString("title"),p.optString("url"),p.optString("text"),true);
                }).setNegativeButton("بستن",null).show();
            });
        }catch(Exception e){ui.post(()->{if(alive)toast("خواندن کتابخانه ممکن نشد؛ داده‌ها حذف نشده‌اند.");});}});
    }
    private void showReading(String title,String url,String body,boolean saved){
        TextView content=text(body,17,INK);content.setTextIsSelectable(true);content.setPadding(dp(18),dp(14),dp(18),dp(14));
        content.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setPositiveButton("بستن",null).setNeutralButton("خروجی متن",(d,w)->{
                exportText=title+"\n"+url+"\n\n"+body;
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE,"DarsBridge-reading.txt");
                try{startActivityForResult(intent,EXPORT_TEXT);}catch(Exception e){toast("انتخابگر فایل در دسترس نیست.");}
            });
        if(saved)dialog.setNegativeButton("حذف",(d,w)->new AlertDialog.Builder(this).setMessage("این متن از کتابخانه حذف شود؟")
            .setPositiveButton("حذف",(a,b)->{if(!canChangeLibrary())return;worker.execute(()->{try{store.remove(url);}catch(Exception e){ui.post(()->{if(alive)toast("حذف انجام نشد.");});}});})
            .setNegativeButton("لغو",null).show());
        dialog.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result==RESULT_OK && data!=null && data.getData()!=null){
            if(request==EXPORT_BACKUP){writeBackup(data.getData());return;}
            if(request==IMPORT_BACKUP){readBackup(data.getData());return;}
        }
        if(request==EXPORT_TEXT && result==RESULT_OK && data!=null && data.getData()!=null){
            Uri uri=data.getData();String value=exportText;exportText="";
            worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new Exception();out.write(value.getBytes(StandardCharsets.UTF_8));ui.post(()->{if(alive)toast("فایل متن ذخیره شد.");});
            }catch(Exception e){ui.post(()->{if(alive)toast("خروجی ذخیره نشد.");});}});
        }
    }
    private void backupMenu(){
        new AlertDialog.Builder(this).setTitle("پشتیبان‌گیری محلی")
            .setMessage("پشتیبان شامل نشانک‌ها و متن‌های آفلاین همین نسخه است؛ رمزگذاری نشده و خودکار جایی ارسال نمی‌شود. فایل را در جای مطمئن ذخیره کنید.")
            .setPositiveButton("ذخیره پشتیبان",(d,w)->{
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"DarsBridge-backup.json");
                try{startActivityForResult(intent,EXPORT_BACKUP);}catch(Exception e){toast("انتخابگر فایل در دسترس نیست.");}
            }).setNeutralButton("بازیابی",(d,w)->{
                openBackupFile();
            }).setNegativeButton("بستن",null).show();
    }
    private void writeBackup(Uri uri){
        Set<String> marks=new HashSet<>(prefs().getStringSet("marks",Collections.emptySet()));
        worker.execute(()->{
            try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new java.io.IOException();
                out.write(BackupData.encode(store.read(),marks,prefs().getBoolean("autoReconnect",true)).getBytes(StandardCharsets.UTF_8));
                ui.post(()->{if(alive)toast("پشتیبان محلی ذخیره شد.");});
            }catch(Exception e){ui.post(()->{if(alive)toast("ساخت پشتیبان ناموفق بود؛ فایل مقصد را بررسی کنید.");});}
        });
    }
    private void openBackupFile(){
        if(!canChangeLibrary())return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(intent,IMPORT_BACKUP);}catch(Exception e){toast("انتخابگر فایل در دسترس نیست.");}
    }
    private void migrationHelp(){
        new AlertDialog.Builder(this).setTitle("انتقال از نسخهٔ ۰.۹")
            .setMessage("۱. در درس‌گذر ۰.۹ از منو، پشتیبان‌گیری و ذخیرهٔ پشتیبان را بزنید.\n۲. به این نسخه برگردید و همان فایل JSON را انتخاب کنید.\n۳. «افزودن بدون حذف» نشانک‌ها و متن‌ها را منتقل می‌کند و متن‌های موجود این نسخه را نگه می‌دارد.\n\nنسخهٔ ۰.۹ را تا بررسی انتقال نگه دارید. فایل پشتیبان رمزگذاری نشده است. تنظیم مسیر اتصال و ورود به حساب سایت‌ها از آن منتقل نمی‌شود.")
            .setPositiveButton("انتخاب فایل پشتیبان",(d,w)->openBackupFile()).setNegativeButton("بستن",null).show();
    }
    private void readBackup(Uri uri){
        if(!canChangeLibrary())return;
        worker.execute(()->{
            try(java.io.InputStream input=getContentResolver().openInputStream(uri)){
                if(input==null)throw new java.io.IOException();
                BackupData data=BackupData.parse(RestoreTransaction.readBounded(input));
                ui.post(()->{
                    if(!alive || !visible)return;
                    new AlertDialog.Builder(this).setTitle("انتقال و بازیابی اطلاعات")
                        .setMessage("این فایل "+data.pages.length()+" متن و "+data.marks.size()+" نشانک دارد. با افزودن بدون حذف، اطلاعات موجود حفظ می‌شود و در نشانی‌های تکراری متن فعلی این نسخه باقی می‌ماند. تنظیم اتصال مجدد خودکار، اگر در فایل باشد، بازیابی می‌شود؛ انتخاب مسیر اتصال تغییر نمی‌کند.")
                        .setPositiveButton("افزودن بدون حذف",(d,w)->applyBackup(data,false))
                        .setNeutralButton("جایگزین کردن",(d,w)->new AlertDialog.Builder(this).setTitle("جایگزینی کتابخانه؟")
                            .setMessage("نشانک‌ها و متن‌های این نسخه با فایل انتخابی جایگزین می‌شوند. ابتدا از اطلاعات فعلی پشتیبان بگیرید.")
                            .setPositiveButton("جایگزین کن",(a,b)->applyBackup(data,true)).setNegativeButton("لغو",null).show())
                        .setNegativeButton("لغو",null).show();
                });
            }catch(Exception e){ui.post(()->{if(alive)toast("فایل خوانده یا تأیید نشد؛ اطلاعات فعلی تغییر نکرد.");});}
        });
    }
    private void applyBackup(BackupData incoming,boolean replace){
        if(!canChangeLibrary())return;restoreBusy=true;
        worker.execute(()->{
            try{
                synchronized(PageStore.DATA_LOCK){
                    if(!alive || Thread.currentThread().isInterrupted())return;
                    BackupData target=incoming;
                    if(!replace){
                        BackupData current=BackupData.parse(BackupData.encode(store.read(),new HashSet<>(prefs().getStringSet("marks",Collections.emptySet())),prefs().getBoolean("autoReconnect",true)));
                        target=BackupData.merge(current,incoming);
                    }
                    if(!alive || Thread.currentThread().isInterrupted())return;
                    restore.restore(target);
                }
                ui.post(()->{
                    if(!alive)return;restoreBusy=false;restoreBlocked=false;
                    autoReconnect=prefs().getBoolean("autoReconnect",true);if(!autoReconnect)cancelRecovery();
                    toast("انتقال اطلاعات انجام شد؛ نشانک‌ها و کتابخانه را بررسی کنید.");
                });
            }catch(Exception e){
                final boolean pending=restore.pending();
                ui.post(()->{
                    if(!alive)return;restoreBusy=false;restoreBlocked=pending;
                    if(pending)resolvePendingRestore();
                    else toast("انتقال انجام نشد؛ ظرفیت کتابخانه یا فایل را بررسی کنید. اطلاعات فعلی حفظ شده است.");
                });
            }
        });
    }
    private void resolvePendingRestore(){
        if(restoreBusy){toast("بازیابی در حال انجام است.");return;}
        if(!restore.pending()){restoreBlocked=false;toast("بازیابی ناتمامی وجود ندارد.");return;}
        new AlertDialog.Builder(this).setTitle("بازیابی ناتمام")
            .setMessage("بازیابی قبلی کامل نشده است. فضای خالی گوشی را بررسی کنید و دوباره تلاش کنید. با لغو، اطلاعات فعلی باقی می‌ماند؛ ممکن است بخشی از فایل قبلی هنوز منتقل نشده باشد. فایل پشتیبان را نگه دارید.")
            .setPositiveButton("تلاش دوباره",(d,w)->{
                restoreBusy=true;worker.execute(()->{
                    boolean ok=false;try{restore.recover();ok=true;}catch(Exception ignored){}
                    final boolean success=ok;ui.post(()->{if(!alive)return;restoreBusy=false;restoreBlocked=!success;
                        if(success){autoReconnect=prefs().getBoolean("autoReconnect",true);if(!autoReconnect)cancelRecovery();}
                        toast(success?"بازیابی کامل شد.":"بازیابی هنوز کامل نشد؛ فایل پشتیبان را نگه دارید.");});
                });
            }).setNeutralButton("لغو بازیابی معلق",(d,w)->{
                restore.discardPending();restoreBlocked=restore.pending();
                toast(restoreBlocked?"لغو کامل نشد؛ فضای گوشی را بررسی کنید.":"بازیابی معلق لغو شد؛ اطلاعات فعلی نگه داشته شد.");
            }).setNegativeButton("بستن",null).show();
    }
    private void clearBrowsing(){
        new AlertDialog.Builder(this).setTitle("پاک‌کردن داده‌های مرور")
            .setMessage("تب‌ها، تاریخچه، کوکی‌ها و حافظهٔ موقت این نسخه پاک می‌شوند و از حساب‌های سایت‌ها خارج می‌شوید. نشانک‌ها و کتابخانهٔ آفلاین باقی می‌مانند.")
            .setPositiveButton("پاک کن",(d,w)->{
                stopConnection();getSharedPreferences(IpScanDialog.CACHE_PREFS,MODE_PRIVATE).edit().clear().apply();
                for(SessionTab tab:new ArrayList<>(tabs)){tab.allowed=false;if(tab.view!=null){tab.view.stopLoading();tab.view.onPause();pageArea.removeView(tab.view);tab.view.destroy();}}
                tabs.clear();browser.tabs.clear();browser.visits.clear();BrowserStore.Tab fresh=browser.add();
                currentTab=new SessionTab(fresh);tabs.add(currentTab);web=createTabWeb(currentTab);web.setTag("web");web.clearCache(true);displayCurrentTab();saveSession();WebStorage.getInstance().deleteAllData();
                CookieManager.getInstance().removeAllCookies(done->CookieManager.getInstance().flush());
                detail("درخواست پاک‌سازی داده‌های مرور انجام شد؛ کتابخانه محفوظ است.");
            }).setNegativeButton("لغو",null).show();
    }
    private ScrollView helpText(String value){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView body=text(value,16,INK);body.setPadding(dp(20),dp(12),dp(20),dp(12));
        body.setTextIsSelectable(true);scroll.addView(body);
        return scroll;
    }
    private void guide(){
        String message="در نسخهٔ "+displayVersion()+" منوی لینک و نوارهای جمع‌وجور مرورگر اضافه شده است. این نسخه روی ۰.۱۳ نصب می‌شود؛ برای آپدیت، برنامه را حذف نکن.\n\n"+
            "انگشتت را روی لینک نگه دار؛ «باز کردن در تب تازه» صفحهٔ مقصد را جدا باز می‌کند و نتیجهٔ گوگل می‌ماند. با «افزودن به تب پس‌زمینه» در صفحهٔ فعلی می‌مانی و تب تازه هنگام انتخاب بارگذاری می‌شود. کپی لینک، کپی متن لینک و اشتراک لینک هم در همین منو هستند. برای انتخاب و کپی متن معمولی، روی متن نگه دار.\n\n"+
            "علامت + برای تب تازه و سه‌نقطه برای منو است. وضعیت اتصال در نوار باریک دیده می‌شود؛ با فلش کنارش یا لمس وضعیت، جزئیات و انتخاب مسیر باز می‌شود.\n\n"+
            "از «تب تازه» بالای صفحه یک جست‌وجوی جدا باز کن. از «تب‌ها» میان صفحه‌ها جابه‌جا شو یا آن‌ها را ببند. تا ۸ تب و ۲۰۰ صفحهٔ اخیر روی گوشی می‌ماند. تاریخچه در خانه و منو است و جست‌وجو و حذف دارد.\n\n"+
            "پس از آپدیت یک بار اتصال را برقرار کن. با روشن بودن «اتصال مجدد خودکار»، هنگام ورود دوباره برای برقراری مسیر انتخاب‌شده تلاش می‌شود. «قطع اتصال» این یادآوری را خاموش می‌کند تا خودت دوباره وصل شوی. برنامه در پس‌زمینه اتصال تازه‌ای شروع نمی‌کند.\n\n"+
            "تب‌های ذخیره‌شده با همان آدرس برمی‌گردند؛ پیام‌ها و فرم‌ها ذخیره یا دوباره ارسال نمی‌شوند. برای چت‌های قدیمی، ورود به همان حساب سایت لازم است. تاریخچهٔ نسخه‌های قدیمی که ثبت نشده قابل بازیابی نیست.\n\n"+
            "برای آزمایش معمول، همین چند کار کافی است:\n\n"+
            "۱. اتصال داخلی را بزن و یک سایت یا جست‌وجو را باز کن.\n\n"+
            "۲. یک ویدئوی YouTube را پخش کن و تمام‌صفحه را امتحان کن.\n\n"+
            "۳. یک بار به برنامهٔ دیگری برو و برگرد. اگر صفحه ادامه نداد، تازه‌سازی را بزن.\n\n"+
            "۴. اگر از قبل نشانک یا متن ذخیره داشتی، بررسی کن هنوز باز می‌شوند. اگر چیزی ذخیره نکرده بودی، این مرحله لازم نیست.\n\n"+
            "اگر این کارها انجام شد، گفتن «درست کار کرد» کافی است. لازم نیست دنبال ایراد فنی بگردی.\n\n"+
            "اگر مشکلی دیدی، بگو کدام کار انجام نشد و از Wi-Fi یا دادهٔ همراه استفاده می‌کردی. «وضعیت اتصال» را باز کن؛ اگر خواستی متن آن را کپی کن و همراه توضیحت بفرست. برنامه چیزی خودکار ارسال نمی‌کند.\n\n"+
            "برای آزمایش جداگانهٔ خروجی Tor از گزینهٔ «بررسی خروجی تور» در منو استفاده کن. بازشدن سایت به‌تنهایی تأیید Tor نیست. اطلاعات خصوصی یا رمز حساب‌ها را نفرست.";
        new AlertDialog.Builder(this).setTitle("راهنمای نسخهٔ "+displayVersion()).setView(helpText(message))
            .setPositiveButton("بستن",null)
            .setNeutralButton("وضعیت اتصال",(d,w)->showConnectionReport())
            .setNegativeButton("حریم خصوصی",(d,w)->privacyGuide()).show();
    }
    private String connectionReport(){
        ConnectionReport.Snapshot snapshot=new ConnectionReport.Snapshot();
        snapshot.version=BuildConfig.VERSION_NAME;snapshot.versionCode=BuildConfig.VERSION_CODE;
        snapshot.androidApi=Build.VERSION.SDK_INT;
        try{android.content.pm.PackageInfo provider=WebView.getCurrentWebViewPackage();
            if(provider!=null)snapshot.webViewVersion=provider.versionName;
        }catch(RuntimeException ignored){}
        try{
            Network active=connectivity.getActiveNetwork();
            if(active!=null){
                NetworkCapabilities caps=connectivity.getNetworkCapabilities(active);
                snapshot.network=caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?ConnectionReport.NetworkKind.WIFI:
                    caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?ConnectionReport.NetworkKind.MOBILE:ConnectionReport.NetworkKind.OTHER;
            }
        }catch(RuntimeException ignored){}
        snapshot.mode=mode;snapshot.route=route;snapshot.vpn=hasVpn();
        snapshot.allowed=networkAllowed;snapshot.wanted=connectionWanted;snapshot.changing=changing;snapshot.recovering=recovering;
        snapshot.embeddedStarting=embedded.isStarting();snapshot.embeddedReady=embedded.isReady();snapshot.snowflake=embedded.isSnowflake();
        snapshot.check=ConnectionReport.currentCheck(lastCheck,networkAllowed,verifiedAt,SystemClock.elapsedRealtime());
        snapshot.autoReconnect=autoReconnect;snapshot.contentGuard=contentGuardReady;
        snapshot.restoreBlocked=restoreBlocked;snapshot.restoreBusy=restoreBusy;snapshot.hasDiagnostic=!lastDiagnostic.isEmpty();
        return ConnectionReport.render(snapshot);
    }
    private void showConnectionReport(){
        final String report=connectionReport();
        new AlertDialog.Builder(this).setTitle("وضعیت اتصال؛ همین لحظه").setView(helpText(report))
            .setPositiveButton("بستن",null)
            .setNeutralButton("کپی وضعیت",(d,w)->{
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(clipboard!=null){
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DarsBridge local status",report));
                    toast("وضعیت کپی شد؛ فقط اگر خواستی آن را همراه توضیح مشکل بفرست.");
                }
            }).show();
    }
    private void showIpScanner(){
        if(ipScanDialog!=null)ipScanDialog.close();
        ipScanDialog=new IpScanDialog(this);ipScanDialog.show();
    }
    private void privacyGuide(){
        new AlertDialog.Builder(this).setTitle("درس‌گذر "+displayVersion())
            .setMessage("نسخهٔ ۰.۱۴ روی ۰.۱۳ به‌روزرسانی می‌شود؛ برنامه را برای آپدیت حذف نکنید. برای انتقال اطلاعات از ۰.۹، فایل پشتیبان لازم است. از حالت شب و روز گوشی پیروی می‌کند و متن بدون دامنه را در Google جست‌وجو می‌کند. پشتیبان‌گیری فقط داده‌های همین نسخه را ذخیره می‌کند. محافظ اولیهٔ WebRTC در شروع صفحات فعال است؛ تماس‌های وب ممکن است کار نکنند. این محافظ تضمین ضدنشت نیست. تب‌ها و تاریخچه (آدرس، عنوان و زمان؛ بدون متن صفحه یا پیام) در فضای خصوصی همین گوشی می‌مانند و در پشتیبان کتابخانه نیستند. با «پاک‌کردن داده‌های مرور» حذف می‌شوند. با روشن بودن اتصال مجدد خودکار، درخواست اتصال قبلی هنگام ورود دوباره اجرا می‌شود؛ قطع دستی آن را لغو می‌کند. پس از بازگشت به برنامه مسیر دوباره بررسی می‌شود. فرم‌ها خودکار دوباره ارسال نمی‌شوند. تلاش مجدد خودکار فقط هنگامی انجام می‌شود که برنامه جلوی صفحه است و با قطع اتصال لغو می‌شود. هیچ آزمون سرعتی افزایش سرعت شبکه را تأیید نکرده است.\n\nهوشمند: VPN، Orbot و Snowflake داخلی را بررسی می‌کند. اگر هیچ مسیر مناسبی نبود، اتصال مستقیم را جایگزین نمی‌کند. حالت «شبکه گوشی» انتخاب دستی برای اینترنت عادی است.\n\nوجود VPN به معنی Tor نیست. آزمایش اتصال فقط یک درخواست HTTPS را بررسی می‌کند؛ این مرورگر محافظت‌های Tor Browser یا تضمین جلوگیری از تمام نشت‌ها را ندارد.\n\nبرای جلوگیری قطعی از اتصال بدون VPN، تنظیم Always-on / Block connections without VPN خود اندروید لازم است. این گزینه‌ها ممکن است بقیه اتصال‌های گوشی را هم محدود کنند.\n\nSnowflake داخلی آزمایشی اضافه شده، اما obfs4 و WebTunnel داخلی هنوز نیستند. اگر Snowflake متصل نشد، Orbot با Bridge را امتحان کنید. دامنه جعلی .ir یا اتصال هنگام قطع کامل شبکه خارجی وجود ندارد.\n\nمطالعه متن محلی است؛ لینک یا متن به Jina یا سرویس دیگری ارسال نمی‌شود. آزمایش Tor فقط با اقدام شما انجام می‌شود. سایت‌ها و سرویس‌های مرورگر ممکن است درخواست‌های خودشان را داشته باشند.\n\nجست‌وجوی IP مستقیم فقط با شروع روشن شما انجام می‌شود؛ از Tor و Snowflake استفاده نمی‌کند و VPN فعال مانع شروع است. IP پاسخ‌گو پروکسی عمومی نیست. گواهی و نام دامنه در آزمون HTTPS بررسی می‌شوند. IPهای موفق قبلی برای آزمون دوباره روی همین گوشی ذخیره می‌شوند و در پشتیبان کتابخانه نیستند.\n\nحذف برنامه، کتابخانه آن را پاک می‌کند. خروجی متن بدون رمزگذاری است. ورود به حساب‌های حساس در این مرورگر آزمایشی توصیه نمی‌شود.")
            .setPositiveButton("بستن",null).show();
    }
    private void enterFull(View v,WebChromeClient.CustomViewCallback callback){
        if(customView!=null){callback.onCustomViewHidden();return;}
        customView=v;fullCallback=callback;fullscreen.addView(v,new FrameLayout.LayoutParams(-1,-1));
        fullscreen.setVisibility(View.VISIBLE);shell.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        if(Build.VERSION.SDK_INT>=30 && getWindow().getInsetsController()!=null)getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    private void exitFull(){
        if(customView==null)return;
        fullscreen.removeView(customView);customView=null;fullscreen.setVisibility(View.GONE);shell.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if(Build.VERSION.SDK_INT>=30 && getWindow().getInsetsController()!=null)getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        styleSystemBars();
        if(fullCallback!=null){WebChromeClient.CustomViewCallback c=fullCallback;fullCallback=null;c.onCustomViewHidden();}
    }
    @Override protected void onResume(){
        super.onResume();visible=true;if(web==null)return;
        applySystemAppearance(getResources().getConfiguration().uiMode);
        if(connectionWanted){
            routeEpoch++;changing=false;routeApplied=false;waitingForEmbedded=null;
            stopProbe();pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;verification="";verifiedAt=0;
        }
        embedded.setForeground(true);
        networkChanged();refreshRouteText();if(web.getVisibility()==View.VISIBLE)web.onResume();
        if(connectionWanted && !networkAllowed){
            queueRestoredPage();
            // First launch of a remembered session starts once; ordinary foreground returns
            // retain the bounded recovery schedule and never replay forms or loaded pages.
            if(prefs().getBoolean("connectionWanted",false) && currentTab.needsLoad)prepareRoute(this::loadPendingUrl);
            else scheduleRecovery();
        }
        if(returnFromOrbot){returnFromOrbot=false;ui.postDelayed(()->{if(!alive)return;if(pendingUrl!=null)navigate(pendingUrl);else connectAndCheck();},350);}
    }
    @Override protected void onPause(){
        dismissLinkMenu();
        if(ipScanDialog!=null){ipScanDialog.close();ipScanDialog=null;}
        visible=false;cancelRecovery();
        routeEpoch++;changing=false;routeApplied=false;waitingForEmbedded=null;stopProbe();
        pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;verification="";verifiedAt=0;
        embedded.setForeground(false);
        saveSession();CookieManager.getInstance().flush();
        for(SessionTab tab:tabs)if(tab.view!=null)tab.view.onPause();super.onPause();
    }
    @Override public void onBackPressed(){if(customView!=null)exitFull();else if(web.getVisibility()==View.VISIBLE && web.canGoBack())history(-1);else if(web.getVisibility()==View.VISIBLE)showHome();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle out){
        out.putString("typedAddress",address.getText().toString());super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy(){
        dismissLinkMenu();
        if(ipScanDialog!=null){ipScanDialog.close();ipScanDialog=null;}
        alive=false;connectionWanted=false;routeEpoch++;proxyRoutes.invalidate();stopProbe();ui.removeCallbacksAndMessages(null);worker.shutdownNow();
        if(networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){ }
        embedded.destroy();
        for(SessionTab tab:tabs)if(tab.view!=null){tab.allowed=false;tab.view.stopLoading();pageArea.removeView(tab.view);tab.view.destroy();}if(probe!=null)probe.destroy();super.onDestroy();
    }
}
