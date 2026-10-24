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
    private Button modeButton, connectionButton;
    private ProgressBar progress;
    private PageStore store;
    private EmbeddedTor embedded;
    private Runnable waitingForEmbedded;
    private String embeddedMessage="Tor داخلی هنوز راه‌اندازی نشده";
    private String lastDiagnostic="";
    private final RecoveryPolicy recovery=new RecoveryPolicy();
    private boolean visible=false, connectionWanted=false, recovering=false, autoReconnect=true;
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
    private boolean changing=false, alive=true, pageFailed=false, returnFromOrbot=false, probeActive=false;
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
        embedded=new EmbeddedTor(this,(ready,message)->onEmbeddedEvent(ready,message));
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        mode=ConnectionPolicy.parse(prefs().getString("mode","AUTO"));
        autoReconnect=prefs().getBoolean("autoReconnect",true);
        socksPort=prefs().getInt("port",9050);
        if(socksPort<1 || socksPort>65535) socksPort=9050;
        buildUi();
        configureWebView();
        networkKey=networkIdentity();
        watchNetwork();
        showHome();
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
            if(b!=null){b.setTextColor(INK);b.setBackgroundTintList(ColorStateList.valueOf(ACTION));}
        }
        if(address!=null){
            address.setTextColor(INK);address.setHintTextColor(MUTED);
            address.setBackground(background(SURFACE));
        }
        if(web!=null){web.setBackgroundColor(SURFACE);styleWebContent(web);web.invalidate();}
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
    private SharedPreferences prefs(){return getSharedPreferences("darsbridge09",MODE_PRIVATE);}
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
        TextView title=text("درس‌گذر ۰.۹",20,INK);title.setTypeface(null,Typeface.BOLD);
        title.setPadding(dp(4),dp(5),dp(4),dp(3));shell.addView(title);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);shell.addView(bar);
        address=new EditText(this);address.setTag("address");address.setHint("جست‌وجوی گوگل یا آدرس سایت");
        address.setTextSize(14);address.setSingleLine(true);address.setSelectAllOnFocus(true);
        address.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);address.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        address.setTextColor(INK);address.setHintTextColor(MUTED);address.setBackground(background(SURFACE));address.setPadding(dp(12),0,dp(12),0);
        bar.addView(address,new LinearLayout.LayoutParams(0,dp(52),1));
        bar.addView(button("بگرد",v->navigate(address.getText().toString())),new LinearLayout.LayoutParams(dp(72),dp(48)));
        address.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_SEARCH || id==EditorInfo.IME_ACTION_GO){navigate(address.getText().toString());return true;}return false;});
        LinearLayout controls=new LinearLayout(this);shell.addView(controls);
        modeButton=button("",v->chooseMode());modeButton.setTag("mode");
        controls.addView(modeButton,new LinearLayout.LayoutParams(0,dp(48),1.2f));
        connectionButton=button("اتصال",v->{
            if(connectionWanted)stopConnection();else{userConnectionRequest();prepareRoute(()->detail("مسیر آماده است؛ می‌توانید سایت را باز کنید."));}
        });
        connectionButton.setTag("connect");
        controls.addView(connectionButton,new LinearLayout.LayoutParams(0,dp(48),1.2f));
        controls.addView(button("منو",v->showMenu()),new LinearLayout.LayoutParams(0,dp(48),0.65f));
        routeText=text("",12,TEAL);routeText.setTag("route");routeText.setMaxLines(2);shell.addView(routeText);
        detailText=text("",12,MUTED);detailText.setTag("detail");detailText.setMaxLines(2);shell.addView(detailText);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setVisibility(View.GONE);
        shell.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        pageArea=new FrameLayout(this);pageArea.setBackground(background(SURFACE));
        shell.addView(pageArea,new LinearLayout.LayoutParams(-1,0,1));
        web=new WebView(this);web.setTag("web");web.setBackgroundColor(SURFACE);pageArea.addView(web,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);
        home=new LinearLayout(this);home.setOrientation(LinearLayout.VERTICAL);home.setPadding(dp(16),dp(18),dp(16),dp(18));
        scroll.addView(home);scroll.setTag("home");scroll.setBackgroundColor(SURFACE);pageArea.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        TextView hello=text("آمادهٔ مطالعه",24,INK);hello.setTypeface(null,Typeface.BOLD);home.addView(hello);
        home.addView(text("در نوار بالا عبارت دلخواه را در گوگل جست‌وجو کن یا آدرس یک سایت را بنویس. ظاهر برنامه خودکار با حالت شب یا روز گوشی هماهنگ می‌شود.",15,MUTED));
        home.addView(button("باز کردن یوتیوب",v->navigate("https://m.youtube.com/")));
        home.addView(button("اتصال داخلی بدون برنامهٔ دیگر",v->useSnowflake()));
        home.addView(button("نشانک‌ها",v->showBookmarks()));
        home.addView(button("کتابخانهٔ آفلاین",v->showLibrary()));
        home.addView(button("پشتیبان‌گیری از نشانک‌ها و متن‌ها",v->backupMenu()));
        home.addView(text("اتصال موفق Snowflake حفظ شده است. کارکرد جست‌وجوی گوگل به دسترسی همان مسیر بستگی دارد. نسخه ۰.۸ را نگه دار. برای حساب‌های حساس از Tor Browser رسمی استفاده کن.",13,MUTED));
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
    private void configureWebView(){
        contentGuardReady=secureSettings(web);
        if(WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(new ServiceWorkerClientCompat(){
                @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request){
                    return networkAllowed?null:new WebResourceResponse("text/plain","utf-8",503,"Connection paused",Collections.emptyMap(),new java.io.ByteArrayInputStream(new byte[0]));
                }
            });
        }
        blockWorkers(true);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView v,int p){progress.setProgress(p);progress.setVisibility(p>=100?View.GONE:View.VISIBLE);}
            @Override public void onPermissionRequest(PermissionRequest r){r.deny();}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
            @Override public void onShowCustomView(View v,CustomViewCallback cb){enterFull(v,cb);}
            @Override public void onHideCustomView(){exitFull();}
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                if(!UrlTools.isHttps(req.getUrl().toString())){detail("فقط صفحات HTTPS در این نسخه باز می‌شوند.");return true;}
                if(!networkAllowed || changing){detail("ابتدا «اتصال / بررسی» را بزنید.");return true;}
                return false;
            }
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){
                if(!UrlTools.isHttps(url))return;
                pageFailed=false;address.setText(url);detail("در حال بارگذاری «"+UrlTools.host(url)+"»…");
            }
            @Override public void onPageFinished(WebView v,String url){
                if(!pageFailed && networkAllowed && UrlTools.isHttps(url)){recovery.reset();address.setText(url);detail("بارگذاری صفحه پایان یافت • "+UrlTools.host(url));}
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){
                if(!r.isForMainFrame())return;
                pageFailed=true;detail("صفحه باز نشد. اتصال را بررسی کنید؛ خطا به‌تنهایی علت فیلترینگ را مشخص نمی‌کند.");
                int code=e.getErrorCode();
                if(connectionWanted && !recovering && (code==ERROR_CONNECT || code==ERROR_HOST_LOOKUP || code==ERROR_TIMEOUT || code==ERROR_IO)){
                    recovering=true;routeApplied=false;verification="";verifiedAt=0;
                    pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();scheduleRecovery();
                }
            }
            @Override public void onFormResubmission(WebView v,android.os.Message dontResend,android.os.Message resend){
                dontResend.sendToTarget();
                detail("فرم خودکار دوباره ارسال نشد؛ برای ارسال تازه از خود صفحه اقدام کنید.");
            }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){
                if(r.isForMainFrame()){pageFailed=true;detail("پاسخ سایت: "+e.getStatusCode()+"؛ مشکل ممکن است از سایت یا مسیر اتصال باشد.");}
            }
            @Override public void onReceivedSslError(WebView v,SslErrorHandler handler,SslError error){
                handler.cancel();pageFailed=true;detail("گواهی HTTPS معتبر نیست؛ برای حفظ امنیت اتصال لغو شد.");
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if(!networkAllowed && ("https".equals(r.getUrl().getScheme()) || "http".equals(r.getUrl().getScheme())))
                    return new WebResourceResponse("text/plain","utf-8",503,"Connection paused",Collections.emptyMap(),new java.io.ByteArrayInputStream(new byte[0]));
                return null;
            }
        });
        web.setDownloadListener((url,ua,disposition,mime,size)->new AlertDialog.Builder(this)
            .setTitle("دریافت فایل")
            .setMessage("دانلود مستقیم فایل در این نسخه پیاده‌سازی نشده است. برای متن صفحه از «ذخیره متن آفلاین» استفاده کنید؛ آدرس فایل به برنامه دیگری فرستاده نشد.")
            .setPositiveButton("باشه",null).show());
    }
    private void detail(String s){detailText.setText(s);}
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
        String key=networkIdentity();if(key.equals(networkKey))return;networkKey=key;
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
        routeText.setText(name+"\n"+checked);
        if(connectionButton!=null){
            connectionButton.setText(connectionWanted?"قطع اتصال":"اتصال");
            connectionButton.setTextColor(connectionWanted?ACTION_TEXT:INK);
            connectionButton.setBackgroundTintList(ColorStateList.valueOf(connectionWanted?TEAL:ACTION));
        }
    }
    private boolean networkAvailable(){try{return connectivity.getActiveNetwork()!=null;}catch(Exception e){return false;}}
    private void userConnectionRequest(){
        connectionWanted=true;recovering=false;recovery.reset();cancelRecovery();refreshRouteText();
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
            if(pendingUrl!=null){String requested=pendingUrl;pendingUrl=null;showWeb();web.loadUrl(requested);}
            else detail("مسیر دوباره آماده شد؛ اگر صفحه ادامه نداد، تازه‌سازی را بزنید.");
        });
    }
    private void stopConnection(){
        connectionWanted=false;recovering=false;recovery.reset();cancelRecovery();
        routeEpoch++;changing=false;waitingForEmbedded=null;pendingUrl=null;
        stopProbe();verification="";verifiedAt=0;routeApplied=false;
        pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;
        web.loadUrl("about:blank");embedded.stop();showHome();refreshRouteText();
        detail("اتصال متوقف شد. تلاش خودکار جدیدی انجام نمی‌شود.");
    }

    private void blockWorkers(boolean blocked){
        if(WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) && WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS))
            ServiceWorkerControllerCompat.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(blocked);
    }
    private void pauseNetwork(){
        proxyRoutes.invalidate();networkAllowed=false;blockWorkers(true);web.stopLoading();web.getSettings().setBlockNetworkLoads(true);
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
                    changing=false;route=choice;networkAllowed=true;blockWorkers(false);web.getSettings().setBlockNetworkLoads(false);
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
        userConnectionRequest();pendingUrl=link;address.setText(link);
        InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(address.getWindowToken(),0);
        prepareRoute(()->{pendingUrl=null;showWeb();web.loadUrl(link);});
    }
    private void refreshPage(){
        // Keep the existing history entry and reuse the already established route.
        if(!UrlTools.isHttps(web.getUrl()))return;
        userConnectionRequest();
        prepareRoute(()->{showWeb();web.reload();});
    }
    private void history(int delta){userConnectionRequest();prepareRoute(()->{if(web.canGoBackOrForward(delta)){showWeb();web.goBackOrForward(delta);}});}
    private void showHome(){web.stopLoading();web.onPause();pageArea.findViewWithTag("home").setVisibility(View.VISIBLE);web.setVisibility(View.GONE);detail("مطالعه آنلاین و متن‌های ذخیره‌شده، در یک جا.");}
    private void showWeb(){pageArea.findViewWithTag("home").setVisibility(View.GONE);web.setVisibility(View.VISIBLE);web.onResume();}
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
            .setMessage("اگر Snowflake داخلی وصل نشد، Orbot را با پل موفق قبلی راه‌اندازی کنید. در حالت انتخاب برنامه‌ها، «درس‌گذر ۰.۹» را هم انتخاب کنید. هیچ‌یک در قطع کامل همه مسیرهای خارجی دسترسی نمی‌سازند.")
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
    private void stopProbe(){probeEpoch++;probeActive=false;if(probe!=null)probe.stopLoading();}
    private void startProbe(){
        stopProbe();final int ticket=probeEpoch;probeActive=true;
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
        stopProbe();verifiedAt=0;verification="";refreshRouteText();detail(message);
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
        String[] items={"نشانک این صفحه","نشانک‌ها","مطالعه متن روی گوشی","ذخیره متن آفلاین","کتابخانه آفلاین","باز کردن Orbot","تنظیم پورت Orbot","قطع اتصال","آخرین خطای اتصال","بررسی خروجی تور","پشتیبان‌گیری و بازیابی","پاک‌کردن داده‌های مرور","اتصال مجدد خودکار: "+(autoReconnect?"روشن":"خاموش"),"راهنما و حریم خصوصی"};
        new AlertDialog.Builder(this).setTitle("ابزارها").setItems(items,(d,which)->{
            switch(which){case 0:addBookmark();break;case 1:showBookmarks();break;case 2:readText(false);break;
                case 3:readText(true);break;case 4:showLibrary();break;case 5:openOrbot();break;case 6:setPort();break;
                case 7:stopConnection();break;
                case 8:showLocalDiagnostic();break;
                case 9:connectAndCheck();break;
                case 10:backupMenu();break;
                case 11:clearBrowsing();break;
                case 12:autoReconnect=!autoReconnect;prefs().edit().putBoolean("autoReconnect",autoReconnect).apply();
                    if(!autoReconnect)cancelRecovery();toast(autoReconnect?"تلاش مجدد خودکار فعال شد.":"تلاش مجدد خودکار خاموش شد.");break;
                default:guide();}
        }).setNegativeButton("بستن",null).show();
    }
    private String current(){String url=web.getUrl();return UrlTools.normalize(UrlTools.isHttps(url)?url:address.getText().toString());}
    private void addBookmark(){
        try{Set<String> all=new HashSet<>(prefs().getStringSet("marks",Collections.emptySet()));all.add(current());if(all.size()>BackupData.MAX_MARKS){toast("حداکثر ۱۰۰ نشانک؛ ابتدا یکی را حذف کنید.");return;}prefs().edit().putStringSet("marks",all).apply();toast("نشانک ذخیره شد.");}
        catch(Exception e){toast("ابتدا یک سایت باز کنید.");}
    }
    private void showBookmarks(){
        ArrayList<String> all=new ArrayList<>(prefs().getStringSet("marks",Collections.emptySet()));Collections.sort(all);
        if(all.isEmpty()){toast("هنوز نشانکی ذخیره نشده است.");return;}
        new AlertDialog.Builder(this).setTitle("نشانک‌ها").setItems(all.toArray(new String[0]),(d,i)->
            new AlertDialog.Builder(this).setMessage(all.get(i)).setPositiveButton("باز کردن",(a,b)->navigate(all.get(i)))
                .setNeutralButton("حذف نشانک",(a,b)->{Set<String> keep=new HashSet<>(all);keep.remove(all.get(i));prefs().edit().putStringSet("marks",keep).apply();})
                .setNegativeButton("بستن",null).show()).setNegativeButton("بستن",null).show();
    }
    private void readText(boolean save){
        if(pageFailed || !UrlTools.isHttps(web.getUrl()) || web.getVisibility()!=View.VISIBLE){toast("ابتدا یک صفحه را با موفقیت باز کنید.");return;}
        String url=web.getUrl(),title=web.getTitle()==null?url:web.getTitle();
        // Read only visible text. No external reader service and no JavaScript bridge.
        web.evaluateJavascript("(function(){var n=document.querySelector('article')||document.querySelector('main')||document.body;return n?n.innerText.slice(0,100000):'';})()",json->{
            if(!alive)return;
            try{
                Object parsed=new JSONTokener(json).nextValue();if(!(parsed instanceof String) || ((String)parsed).trim().length()<5)throw new Exception();
                String value=(String)parsed;
                if(save){worker.execute(()->{try{store.add(title,url,value);ui.post(()->{if(alive)toast("متن روی گوشی ذخیره شد؛ ویدئو ذخیره نشده است.");});}catch(Exception e){ui.post(()->{if(alive)toast("ذخیره انجام نشد: "+e.getMessage());});}});}
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
            .setPositiveButton("حذف",(a,b)->worker.execute(()->{try{store.remove(url);}catch(Exception e){ui.post(()->{if(alive)toast("حذف انجام نشد.");});}}))
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
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                try{startActivityForResult(intent,IMPORT_BACKUP);}catch(Exception e){toast("انتخابگر فایل در دسترس نیست.");}
            }).setNegativeButton("بستن",null).show();
    }
    private void writeBackup(Uri uri){
        Set<String> marks=new HashSet<>(prefs().getStringSet("marks",Collections.emptySet()));
        worker.execute(()->{
            try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new java.io.IOException();
                out.write(BackupData.encode(store.read(),marks).getBytes(StandardCharsets.UTF_8));
                ui.post(()->{if(alive)toast("پشتیبان محلی ذخیره شد.");});
            }catch(Exception e){ui.post(()->{if(alive)toast("ساخت پشتیبان ناموفق بود؛ فایل مقصد را بررسی کنید.");});}
        });
    }
    private void readBackup(Uri uri){
        worker.execute(()->{
            try(java.io.InputStream input=getContentResolver().openInputStream(uri)){
                if(input==null)throw new java.io.IOException();
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;
                while((n=input.read(chunk))!=-1){if(bytes.size()+n>BackupData.MAX_BYTES)throw new IllegalArgumentException();bytes.write(chunk,0,n);}
                BackupData data=BackupData.parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
                ui.post(()->{
                    if(!alive || !visible)return;
                    new AlertDialog.Builder(this).setTitle("تأیید جایگزینی کتابخانه")
                        .setMessage("این فایل "+data.pages.length()+" متن و "+data.marks.size()+" نشانک دارد. کتابخانه و نشانک‌های همین نسخه جایگزین می‌شوند؛ نسخه‌های قبلی تغییر نمی‌کنند. ابتدا از اطلاعات فعلی پشتیبان بگیرید.")
                        .setPositiveButton("جایگزین کن",(d,w)->worker.execute(()->{
                            try{store.write(data.pages);prefs().edit().putStringSet("marks",data.marks).commit();ui.post(()->{if(alive)toast("بازیابی انجام شد.");});}
                            catch(Exception e){ui.post(()->{if(alive)toast("بازیابی کامل نشد؛ پشتیبان را نگه دارید.");});}
                        })).setNegativeButton("لغو",null).show();
                });
            }catch(Exception e){ui.post(()->{if(alive)toast("فایل معتبر نیست یا بیش از حد بزرگ است؛ اطلاعات موجود تغییری نکرد.");});}
        });
    }
    private void clearBrowsing(){
        new AlertDialog.Builder(this).setTitle("پاک‌کردن داده‌های مرور")
            .setMessage("کوکی‌ها، حافظهٔ موقت و سابقهٔ مرور این نسخه پاک می‌شوند و از حساب‌های سایت‌ها خارج می‌شوید. نشانک‌ها و کتابخانهٔ آفلاین باقی می‌مانند.")
            .setPositiveButton("پاک کن",(d,w)->{
                stopConnection();web.clearCache(true);web.clearHistory();WebStorage.getInstance().deleteAllData();
                CookieManager.getInstance().removeAllCookies(done->CookieManager.getInstance().flush());
                detail("درخواست پاک‌سازی داده‌های مرور انجام شد؛ کتابخانه محفوظ است.");
            }).setNegativeButton("لغو",null).show();
    }
    private void guide(){
        new AlertDialog.Builder(this).setTitle("درس‌گذر ۰.۹ • نسخه آزمایشی")
            .setMessage("نسخه ۰.۹ کنار ۰.۸ نصب می‌شود. از حالت شب و روز گوشی پیروی می‌کند و متن بدون دامنه را در Google جست‌وجو می‌کند. پشتیبان‌گیری فقط داده‌های همین نسخه را ذخیره می‌کند. محافظ اولیهٔ WebRTC در شروع صفحات فعال است؛ تماس‌های وب ممکن است کار نکنند. این محافظ تضمین ضدنشت نیست. پس از بازگشت به برنامه مسیر دوباره تأیید می‌شود. فرم‌ها خودکار دوباره ارسال نمی‌شوند. تلاش مجدد خودکار فقط هنگامی انجام می‌شود که برنامه جلوی صفحه است و با قطع اتصال لغو می‌شود. هیچ آزمون سرعتی افزایش سرعت شبکه را تأیید نکرده است.\n\nهوشمند: VPN، Orbot و Snowflake داخلی را بررسی می‌کند. اگر هیچ مسیر مناسبی نبود، اتصال مستقیم را جایگزین نمی‌کند. حالت «شبکه گوشی» انتخاب دستی برای اینترنت عادی است.\n\nوجود VPN به معنی Tor نیست. آزمایش اتصال فقط یک درخواست HTTPS را بررسی می‌کند؛ این مرورگر محافظت‌های Tor Browser یا تضمین جلوگیری از تمام نشت‌ها را ندارد.\n\nبرای جلوگیری قطعی از اتصال بدون VPN، تنظیم Always-on / Block connections without VPN خود اندروید لازم است. این گزینه‌ها ممکن است بقیه اتصال‌های گوشی را هم محدود کنند.\n\nSnowflake داخلی آزمایشی اضافه شده، اما obfs4 و WebTunnel داخلی هنوز نیستند. اگر Snowflake متصل نشد، Orbot با Bridge را امتحان کنید. دامنه جعلی .ir یا اتصال هنگام قطع کامل شبکه خارجی وجود ندارد.\n\nمطالعه متن محلی است؛ لینک یا متن به Jina یا سرویس دیگری ارسال نمی‌شود. آزمایش Tor فقط با اقدام شما انجام می‌شود. سایت‌ها و سرویس‌های مرورگر ممکن است درخواست‌های خودشان را داشته باشند.\n\nحذف برنامه، کتابخانه آن را پاک می‌کند. خروجی متن بدون رمزگذاری است. ورود به حساب‌های حساس در این مرورگر آزمایشی توصیه نمی‌شود.")
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
        if(connectionWanted && !networkAllowed)scheduleRecovery();
        if(returnFromOrbot){returnFromOrbot=false;ui.postDelayed(()->{if(!alive)return;if(pendingUrl!=null)navigate(pendingUrl);else connectAndCheck();},350);}
    }
    @Override protected void onPause(){
        visible=false;cancelRecovery();
        routeEpoch++;changing=false;routeApplied=false;waitingForEmbedded=null;stopProbe();
        pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;verification="";verifiedAt=0;
        embedded.setForeground(false);
        if(web!=null)web.onPause();super.onPause();
    }
    @Override public void onBackPressed(){if(customView!=null)exitFull();else if(web.getVisibility()==View.VISIBLE && web.canGoBack())history(-1);else if(web.getVisibility()==View.VISIBLE)showHome();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle out){
        out.putString("typedAddress",address.getText().toString());super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy(){
        alive=false;connectionWanted=false;routeEpoch++;proxyRoutes.invalidate();stopProbe();ui.removeCallbacksAndMessages(null);worker.shutdownNow();
        if(networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){ }
        embedded.destroy();
        if(web!=null){web.stopLoading();web.destroy();}if(probe!=null)probe.destroy();super.onDestroy();
    }
}
