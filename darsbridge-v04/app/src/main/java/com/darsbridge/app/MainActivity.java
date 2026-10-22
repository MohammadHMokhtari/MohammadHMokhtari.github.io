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
    private static final int BG=0xfff3f6f5, INK=0xff192e36, TEAL=0xff11685d, MUTED=0xff53636a;
    private static final int EXPORT_TEXT=80;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private FrameLayout root, pageArea, fullscreen;
    private LinearLayout shell, home;
    private WebView web, probe;
    private EditText address;
    private TextView routeText, detailText;
    private Button modeButton;
    private ProgressBar progress;
    private PageStore store;
    private EmbeddedTor embedded;
    private Runnable waitingForEmbedded;
    private String embeddedMessage="Tor داخلی هنوز راه‌اندازی نشده";
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
        store=new PageStore(this);
        embedded=new EmbeddedTor(this,(ready,message)->onEmbeddedEvent(ready,message));
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        mode=ConnectionPolicy.parse(prefs().getString("mode","AUTO"));
        socksPort=prefs().getInt("port",9050);
        if(socksPort<1 || socksPort>65535) socksPort=9050;
        buildUi();
        configureWebView();
        networkKey=networkIdentity();
        watchNetwork();
        showHome();
        refreshRouteText();
        if(Intent.ACTION_SEND.equals(getIntent().getAction())) {
            String shared=getIntent().getStringExtra(Intent.EXTRA_TEXT);
            if(shared!=null) address.setText(shared.trim());
        }
    }
    private SharedPreferences prefs(){return getSharedPreferences("darsbridge04",MODE_PRIVATE);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        t.setGravity(Gravity.START);t.setPadding(dp(4),dp(4),dp(4),dp(4));return t;
    }
    private GradientDrawable background(int color){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;
    }
    private Button button(String label,View.OnClickListener listener){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(INK);
        b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setPadding(dp(10),dp(2),dp(10),dp(2));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffe3eeea));
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
        TextView title=text("درس‌گذر ۰.۴",22,INK);title.setTypeface(null,Typeface.BOLD);
        title.setPadding(dp(4),dp(12),dp(4),dp(6));shell.addView(title);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);shell.addView(bar);
        address=new EditText(this);address.setTag("address");address.setHint("آدرس سایت، مثلاً youtube.com");
        address.setTextSize(14);address.setSingleLine(true);address.setSelectAllOnFocus(true);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);address.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        bar.addView(address,new LinearLayout.LayoutParams(0,dp(52),1));
        bar.addView(button("برو",v->navigate(address.getText().toString())),new LinearLayout.LayoutParams(dp(62),dp(48)));
        address.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_GO){navigate(address.getText().toString());return true;}return false;});
        LinearLayout controls=new LinearLayout(this);shell.addView(controls);
        modeButton=button("",v->chooseMode());modeButton.setTag("mode");
        controls.addView(modeButton,new LinearLayout.LayoutParams(0,dp(48),1.2f));
        controls.addView(button("اتصال / بررسی",v->connectAndCheck()),new LinearLayout.LayoutParams(0,dp(48),1.2f));
        controls.addView(button("منو",v->showMenu()),new LinearLayout.LayoutParams(0,dp(48),0.65f));
        routeText=text("",12,TEAL);routeText.setTag("route");routeText.setMaxLines(2);shell.addView(routeText);
        detailText=text("",12,MUTED);detailText.setMaxLines(2);shell.addView(detailText);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setVisibility(View.GONE);
        shell.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        pageArea=new FrameLayout(this);pageArea.setBackground(background(Color.WHITE));
        shell.addView(pageArea,new LinearLayout.LayoutParams(-1,0,1));
        web=new WebView(this);web.setTag("web");pageArea.addView(web,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);
        home=new LinearLayout(this);home.setOrientation(LinearLayout.VERTICAL);home.setPadding(dp(16),dp(18),dp(16),dp(18));
        scroll.addView(home);scroll.setTag("home");pageArea.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        TextView hello=text("راه کوتاه‌تر به یادگیری",24,INK);hello.setTypeface(null,Typeface.BOLD);home.addView(hello);
        home.addView(text("مسیر هوشمند از VPN، سپس Tor داخلیِ آماده یا Orbot استفاده می‌کند. اگر هیچ‌کدام آماده نباشد، Tor داخلی را امتحان می‌کند؛ هرگز خودکار به اینترنت مستقیم برنمی‌گردد.",15,MUTED));
        home.addView(button("باز کردن YouTube",v->navigate("https://m.youtube.com/")));
        home.addView(button("Tor داخلی، بدون Orbot (آزمایشی)",v->useEmbedded()));
        home.addView(button("Orbot با پل‌های ضدسانسور",v->openOrbot()));
        home.addView(button("کتابخانه آفلاین",v->showLibrary()));
        home.addView(button("نشانک‌ها",v->showBookmarks()));
        home.addView(text("Tor داخلی فعلاً فقط اتصال مستقیم به Tor دارد؛ در شبکه‌های سخت‌گیر ممکن است Orbot با Bridge لازم شود. هیچ‌کدام هنگام قطع کامل مسیرهای خارجی اینترنت تازه ایجاد نمی‌کنند. ذخیره آفلاین صرفاً متن است.",13,MUTED));
        LinearLayout nav=new LinearLayout(this);shell.addView(nav);
        nav.addView(button("برگشت",v->{if(web.canGoBack())history(-1);else showHome();}),new LinearLayout.LayoutParams(0,dp(48),1));
        nav.addView(button("جلو",v->history(1)),new LinearLayout.LayoutParams(0,dp(48),1));
        nav.addView(button("تازه‌سازی",v->{String u=web.getUrl();if(UrlTools.isHttps(u))navigate(u);}),new LinearLayout.LayoutParams(0,dp(48),1));
        nav.addView(button("خانه",v->showHome()),new LinearLayout.LayoutParams(0,dp(48),1));
        fullscreen=new FrameLayout(this);fullscreen.setBackgroundColor(Color.BLACK);fullscreen.setVisibility(View.GONE);
        root.addView(fullscreen,new FrameLayout.LayoutParams(-1,-1));
        refreshMode();
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void secureSettings(WebView w){
        WebSettings s=w.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSafeBrowsingEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);s.setBlockNetworkLoads(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w,false);
    }
    private void configureWebView(){
        secureSettings(web);
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
                if(!pageFailed && UrlTools.isHttps(url)){address.setText(url);detail("صفحه آماده است • "+UrlTools.host(url));}
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){
                if(!r.isForMainFrame())return;
                pageFailed=true;detail("صفحه باز نشد. اتصال را بررسی کنید؛ خطا به‌تنهایی علت فیلترینگ را مشخص نمی‌کند.");
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
        verification="";verifiedAt=0;stopProbe();
        if((mode==ConnectionPolicy.Mode.AUTO && route==ConnectionPolicy.Route.SYSTEM) || route==ConnectionPolicy.Route.INTERNAL){
            pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;verification="";verifiedAt=0;
            detail("شبکه تغییر کرده است؛ بارگذاری متوقف شد. دوباره مسیر را بررسی کنید.");
        }
        refreshRouteText();
    }
    private void refreshMode(){modeButton.setText(mode==ConnectionPolicy.Mode.AUTO?"مسیر: هوشمند":mode==ConnectionPolicy.Mode.SYSTEM?"مسیر: گوشی / VPN":mode==ConnectionPolicy.Mode.LOCAL?"مسیر: Orbot":"مسیر: Tor داخلی");}
    private void refreshRouteText(){
        String name=route==ConnectionPolicy.Route.SOCKS?"پراکسی محلی Orbot":route==ConnectionPolicy.Route.INTERNAL?"Tor داخلی (آزمایشی)":route==ConnectionPolicy.Route.SYSTEM?(hasVpn()?"VPN سیستم":"اینترنت گوشی، بدون VPN شناسایی‌شده"):"هنوز مسیری آماده نیست";
        String checked=verifiedAt>0 && SystemClock.elapsedRealtime()-verifiedAt<300000?verification:"Tor هنوز تأیید نشده";
        routeText.setText(name+" • "+checked);
    }
    private void blockWorkers(boolean blocked){
        if(WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS))
            ServiceWorkerControllerCompat.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(blocked);
    }
    private void pauseNetwork(){
        networkAllowed=false;blockWorkers(true);web.stopLoading();web.getSettings().setBlockNetworkLoads(true);
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
        if(changing){toast("در حال آماده‌سازی مسیر هستیم.");return;}
        changing=true;int ticket=++routeEpoch;stopProbe();pauseNetwork();
        detail("در حال بررسی مسیر…");
        final ConnectionPolicy.Mode requested=mode;
        worker.execute(()->{
            boolean vpn=hasVpn();boolean local=(requested==ConnectionPolicy.Mode.AUTO || requested==ConnectionPolicy.Mode.LOCAL) && !(requested==ConnectionPolicy.Mode.AUTO && vpn) && !embedded.isStarting() && socksResponds();
            ui.post(()->{
                if(!alive || ticket!=routeEpoch)return;
                ConnectionPolicy.Route choice=ConnectionPolicy.choose(requested,hasVpn(),local,supportsProxy(),embedded.isReady());
                if(requested==ConnectionPolicy.Mode.EMBEDDED && hasVpn()){
                    changing=false;route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();
                    detail("VPN سیستم فعال است. برای اتصال فعلی حالت هوشمند را انتخاب کنید؛ Tor را دوباره درون VPN راه نمی‌اندازیم.");return;
                }
                if(choice==ConnectionPolicy.Route.BLOCKED){
                    changing=false;route=choice;refreshRouteText();
                    if((requested==ConnectionPolicy.Mode.EMBEDDED || requested==ConnectionPolicy.Mode.AUTO) && supportsProxy()){
                        queueEmbedded(next);return;
                    }
                    detail("هیچ مسیر غیرمستقیمی آماده نیست و برنامه به اتصال مستقیم تغییر نمی‌کند.");showConnectionHelp();return;
                }
                Runnable applied=()->{
                    if(!alive || ticket!=routeEpoch)return;
                    if(requested==ConnectionPolicy.Mode.AUTO && choice==ConnectionPolicy.Route.SYSTEM && !hasVpn()){
                        changing=false;route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();detail("VPN هنگام تنظیم مسیر قطع شد؛ دوباره تلاش کنید.");return;
                    }
                    if(route!=choice){verification="";verifiedAt=0;}
                    changing=false;route=choice;networkAllowed=true;blockWorkers(false);web.getSettings().setBlockNetworkLoads(false);
                    refreshRouteText();detail("مسیر آماده است. تأیید Tor نیاز به آزمایش جداگانه دارد.");next.run();
                };
                try{
                    if(choice==ConnectionPolicy.Route.SOCKS || choice==ConnectionPolicy.Route.INTERNAL){
                        int chosenPort=choice==ConnectionPolicy.Route.INTERNAL?embedded.port():socksPort;
                        if(chosenPort<1)throw new IllegalStateException("پورت SOCKS آماده نیست");
                        ProxyConfig config=new ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:"+chosenPort).removeImplicitRules().build();
                        ProxyController.getInstance().setProxyOverride(config,ui::post,applied);
                    }else if(supportsProxy()) ProxyController.getInstance().clearProxyOverride(ui::post,applied);
                    else applied.run();
                }catch(Exception e){changing=false;route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();detail("تنظیم مسیر موفق نبود؛ هیچ مسیر مستقیم جایگزین نشد.");}
            });
        });
        ui.postDelayed(()->{if(alive && changing && ticket==routeEpoch){routeEpoch++;changing=false;pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();detail("تنظیم مسیر بیش از حد طول کشید. دوباره تلاش کنید.");}},8000);
    }
    private void navigate(String input){
        final String link;
        try{link=UrlTools.normalize(input);}catch(Exception e){toast(e.getMessage());return;}
        pendingUrl=link;address.setText(link);
        InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(address.getWindowToken(),0);
        prepareRoute(()->{pendingUrl=null;showWeb();web.loadUrl(link);});
    }
    private void history(int delta){prepareRoute(()->{if(web.canGoBackOrForward(delta)){showWeb();web.goBackOrForward(delta);}});}
    private void showHome(){web.stopLoading();web.onPause();pageArea.findViewWithTag("home").setVisibility(View.VISIBLE);web.setVisibility(View.GONE);detail("مطالعه آنلاین و متن‌های ذخیره‌شده، در یک جا.");}
    private void showWeb(){pageArea.findViewWithTag("home").setVisibility(View.GONE);web.setVisibility(View.VISIBLE);web.onResume();}
    /** Never switch to unproxied traffic while integrated Tor connects. */
    private void queueEmbedded(Runnable next){
        waitingForEmbedded=next;
        detail(embedded.isStarting()?"Tor داخلی در حال آماده‌شدن است؛ ممکن است روی این شبکه مسدود باشد.":"Tor داخلی در حال اتصال است. اگر کار نکرد از Orbot و Bridge استفاده کنید.");
        if(!embedded.isStarting())embedded.start();
    }
    private void onEmbeddedEvent(boolean ready,String message){
        if(!alive)return;
        embeddedMessage=message;
        if(!ready && route==ConnectionPolicy.Route.INTERNAL){
            pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;verification="";verifiedAt=0;
        }
        refreshRouteText();
        if(ready && waitingForEmbedded!=null){
            Runnable action=waitingForEmbedded;waitingForEmbedded=null;
            prepareRoute(action);
        }else if(waitingForEmbedded!=null && !embedded.isStarting()){
            waitingForEmbedded=null;
            detail(message+" برای اتصال در شرایط فیلترینگ از Orbot با Bridge استفاده کنید.");
        }else if(route==ConnectionPolicy.Route.BLOCKED){
            detail(message);
        }
    }
    private void useEmbedded(){
        if(hasVpn()){
            new AlertDialog.Builder(this).setTitle("VPN فعال است")
                .setMessage("Tor داخلی را همزمان روی VPN دیگر راه نمی‌اندازیم. برای VPN فعلی حالت هوشمند را استفاده کنید؛ یا VPN را قطع کنید.")
                .setPositiveButton("حالت هوشمند",(d,w)->{
                    mode=ConnectionPolicy.Mode.AUTO;refreshMode();
                    prefs().edit().putString("mode",mode.name()).apply();connectAndCheck();
                }).setNegativeButton("بستن",null).show();return;
        }
        mode=ConnectionPolicy.Mode.EMBEDDED;
        prefs().edit().putString("mode",mode.name()).apply();
        routeEpoch++;changing=false;stopProbe();pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;
        refreshMode();refreshRouteText();
        connectAndCheck();
    }

    private void chooseMode(){
        if(changing)return;
        String[] options={"هوشمند: VPN، Orbot و Tor داخلی؛ بدون بازگشت خودکار به مستقیم","شبکه گوشی / VPN: استفاده از مسیر عادی اندروید","فقط پراکسی Orbot روی همین گوشی","فقط Tor داخلی: بدون پل Snowflake / obfs4"};
        new AlertDialog.Builder(this).setTitle("انتخاب مسیر")
            .setSingleChoiceItems(options,mode.ordinal(),(d,which)->{
                mode=ConnectionPolicy.Mode.values()[which];prefs().edit().putString("mode",mode.name()).apply();
                routeEpoch++;changing=false;waitingForEmbedded=null;stopProbe();pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;verification="";verifiedAt=0;
                refreshMode();refreshRouteText();d.dismiss();
                detail(mode==ConnectionPolicy.Mode.SYSTEM?"این حالت ممکن است مستقیم باشد؛ خودِ برنامه فیلترینگ را دور نمی‌زند.":mode==ConnectionPolicy.Mode.EMBEDDED?"Tor داخلی تنها اتصال مستقیم Tor را دارد.":"برای اعمال مسیر «اتصال / بررسی» را بزنید.");
            }).setNegativeButton("بستن",null).show();
    }
    private void showConnectionHelp(){
        new AlertDialog.Builder(this).setTitle("مسیر اتصال آماده نیست")
            .setMessage("Tor داخلی به شبکه مستقیم Tor وابسته است و پل داخلی ندارد. اگر کار نکرد، Orbot را با Bridge موفق قبلی وصل کنید و در حالت انتخاب برنامه‌ها «درس‌گذر ۰.۴» را نیز انتخاب کنید.")
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
            .setMessage("Tor داخلی ممکن است به‌تنهایی کار کند، اما برای Bridge های Snowflake/obfs4 همچنان به Orbot نیاز داریم.")
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
    private void showMenu(){
        String[] items={"نشانک این صفحه","نشانک‌ها","مطالعه متن روی گوشی","ذخیره متن آفلاین","کتابخانه آفلاین","باز کردن Orbot","تنظیم پورت Orbot","توقف Tor داخلی","راهنما و حریم خصوصی"};
        new AlertDialog.Builder(this).setTitle("ابزارها").setItems(items,(d,which)->{
            switch(which){case 0:addBookmark();break;case 1:showBookmarks();break;case 2:readText(false);break;
                case 3:readText(true);break;case 4:showLibrary();break;case 5:openOrbot();break;case 6:setPort();break;
                case 7:waitingForEmbedded=null;embedded.stop();pauseNetwork();route=ConnectionPolicy.Route.BLOCKED;refreshRouteText();detail("Tor داخلی متوقف شد.");break;
                default:guide();}
        }).setNegativeButton("بستن",null).show();
    }
    private String current(){String url=web.getUrl();return UrlTools.normalize(UrlTools.isHttps(url)?url:address.getText().toString());}
    private void addBookmark(){
        try{Set<String> all=new HashSet<>(prefs().getStringSet("marks",Collections.emptySet()));all.add(current());prefs().edit().putStringSet("marks",all).apply();toast("نشانک ذخیره شد.");}
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
        if(request==EXPORT_TEXT && result==RESULT_OK && data!=null && data.getData()!=null){
            Uri uri=data.getData();String value=exportText;exportText="";
            worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new Exception();out.write(value.getBytes(StandardCharsets.UTF_8));ui.post(()->{if(alive)toast("فایل متن ذخیره شد.");});
            }catch(Exception e){ui.post(()->{if(alive)toast("خروجی ذخیره نشد.");});}});
        }
    }
    private void guide(){
        new AlertDialog.Builder(this).setTitle("درس‌گذر ۰.۴ • نسخه آزمایشی")
            .setMessage("این نسخه کنار ۰.۲ نصب می‌شود و داده‌های آن را تغییر نمی‌دهد.\n\nهوشمند: VPN سیستم را ترجیح می‌دهد؛ در نبود VPN، پراکسی SOCKS محلی را بررسی می‌کند. نبود این دو مسیر مانع بازشدن سایت می‌شود؛ حالت «شبکه گوشی» انتخاب دستی برای اینترنت عادی است.\n\nوجود VPN به معنی Tor نیست. آزمایش اتصال فقط یک درخواست HTTPS را بررسی می‌کند؛ این مرورگر محافظت‌های Tor Browser یا تضمین جلوگیری از تمام نشت‌ها را ندارد.\n\nبرای جلوگیری قطعی از اتصال بدون VPN، تنظیم Always-on / Block connections without VPN خود اندروید لازم است. این گزینه‌ها ممکن است بقیه اتصال‌های گوشی را هم محدود کنند.\n\nTor داخلی آزمایشی اضافه شده اما Snowflake و obfs4 داخل برنامه نیستند. برای فیلترینگ شدید ممکن است Orbot با Bridge ضروری باشد. دامنه جعلی .ir یا اتصال هنگام قطع کامل شبکه خارجی وجود ندارد.\n\nمطالعه متن محلی است؛ لینک یا متن به Jina یا سرویس دیگری ارسال نمی‌شود. آزمایش Tor فقط با اقدام شما انجام می‌شود. سایت‌ها و سرویس‌های مرورگر ممکن است درخواست‌های خودشان را داشته باشند.\n\nحذف برنامه، کتابخانه آن را پاک می‌کند. خروجی متن بدون رمزگذاری است. ورود به حساب‌های حساس در این مرورگر آزمایشی توصیه نمی‌شود.")
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
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if(fullCallback!=null){WebChromeClient.CustomViewCallback c=fullCallback;fullCallback=null;c.onCustomViewHidden();}
    }
    @Override protected void onResume(){
        super.onResume();if(web==null)return;
        networkChanged();refreshRouteText();if(web.getVisibility()==View.VISIBLE)web.onResume();
        if(returnFromOrbot){returnFromOrbot=false;ui.postDelayed(()->{if(!alive)return;if(pendingUrl!=null)navigate(pendingUrl);else connectAndCheck();},350);}
    }
    @Override protected void onPause(){if(web!=null)web.onPause();super.onPause();}
    @Override public void onBackPressed(){if(customView!=null)exitFull();else if(web.getVisibility()==View.VISIBLE && web.canGoBack())history(-1);else if(web.getVisibility()==View.VISIBLE)showHome();else super.onBackPressed();}
    @Override protected void onDestroy(){
        alive=false;routeEpoch++;stopProbe();ui.removeCallbacksAndMessages(null);worker.shutdownNow();
        if(networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){ }
        embedded.destroy();
        if(web!=null){web.stopLoading();web.destroy();}if(probe!=null)probe.destroy();super.onDestroy();
    }
}
