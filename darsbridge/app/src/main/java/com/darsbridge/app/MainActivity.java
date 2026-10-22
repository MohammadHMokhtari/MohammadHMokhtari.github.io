package com.darsbridge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int BACKGROUND=Color.rgb(246,249,253);
    private static final int BLUE=Color.rgb(28,76,157);
    private static final String PROXY="socks5://127.0.0.1:9050";
    private EditText address;
    private WebView web;
    private TextView status;
    private ProgressBar bar;
    private Button directButton, torButton;
    private boolean tor=false;
    private boolean routeChanging=false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        configureWebView();
        setStatus("ابتدا لینک را وارد کنید. حالت Tor به Orbot متصل نیاز دارد.");
        if(getIntent()!=null && Intent.ACTION_VIEW.equals(getIntent().getAction())
                && getIntent().getData()!=null) openUrl(getIntent().getData().toString());
    }

    private void buildUi() {
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(8),dp(8),dp(8),dp(8));
        setContentView(root);

        TextView headline=new TextView(this);
        headline.setText("درس‌گذر  |  مرورگر آموزشی شخصی");
        headline.setTextSize(19);
        headline.setTextColor(BLUE);
        headline.setGravity(Gravity.RIGHT);
        headline.setPadding(dp(6),dp(8),dp(6),dp(10));
        root.addView(headline);

        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row,new LinearLayout.LayoutParams(-1,dp(52)));
        address=new EditText(this);
        address.setHint("youtube.com یا آدرس سایت دانشگاه");
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setSingleLine(true);
        address.setSelectAllOnFocus(true);
        address.setTextSize(14);
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        row.addView(address,new LinearLayout.LayoutParams(0,-1,1f));
        row.addView(button("باز کن",v->openUrl(address.getText().toString())),
                new LinearLayout.LayoutParams(dp(84),-1));
        address.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_GO){openUrl(address.getText().toString());return true;}
            return false;
        });

        LinearLayout mode=new LinearLayout(this);
        root.addView(mode,new LinearLayout.LayoutParams(-1,dp(48)));
        directButton=button("اتصال مستقیم",v->setRoute(false));
        torButton=button("Tor / Orbot",v->setRoute(true));
        mode.addView(directButton,new LinearLayout.LayoutParams(0,-1,1));
        mode.addView(torButton,new LinearLayout.LayoutParams(0,-1,1));
        updateModeButtons();

        HorizontalScrollView actions=new HorizontalScrollView(this);
        actions.setHorizontalScrollBarEnabled(false);
        LinearLayout small=new LinearLayout(this);
        small.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(small);
        root.addView(actions,new LinearLayout.LayoutParams(-1,dp(52)));
        addSmall(small,"آزمایش Tor",v->openUrl("https://check.torproject.org/"));
        addSmall(small,"مطالعه متن",v->openReader());
        addSmall(small,"نشانک +",v->addBookmark());
        addSmall(small,"نشانک‌ها",v->showBookmarks());
        addSmall(small,"ذخیره آفلاین",v->saveOffline());
        addSmall(small,"آفلاین‌های من",v->listOffline());
        addSmall(small,"راهنمای Orbot",v->showGuide());

        status=new TextView(this);
        status.setTextSize(12);
        status.setTextColor(Color.rgb(44,59,77));
        status.setMaxLines(3);
        status.setPadding(dp(7),dp(4),dp(7),dp(5));
        root.addView(status);
        bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(3)));

        LinearLayout nav=new LinearLayout(this);
        root.addView(nav,new LinearLayout.LayoutParams(-1,dp(43)));
        nav.addView(button("❮",v->{if(web.canGoBack())web.goBack();}),new LinearLayout.LayoutParams(0,-1,1));
        nav.addView(button("❯",v->{if(web.canGoForward())web.goForward();}),new LinearLayout.LayoutParams(0,-1,1));
        nav.addView(button("تازه‌سازی",v->web.reload()),new LinearLayout.LayoutParams(0,-1,2));

        web=new WebView(this);
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1f));
    }

    private Button button(String label,View.OnClickListener click) {
        Button b=new Button(this);
        b.setAllCaps(false); b.setText(label); b.setTextSize(12);
        b.setPadding(dp(4),0,dp(4),0);
        b.setOnClickListener(click);
        return b;
    }
    private void addSmall(LinearLayout row,String s,View.OnClickListener click) {
        row.addView(button(s,click),new LinearLayout.LayoutParams(-2,dp(44)));
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void setStatus(String s){if(status!=null)status.setText(s);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    private void configureWebView() {
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSaveFormData(false);
        s.setSafeBrowsingEnabled(true);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView view,int value) {
                bar.setProgress(value);
                bar.setVisibility(value==100?View.GONE:View.VISIBLE);
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                String link=request.getUrl().toString();
                if(UrlTools.isWebLink(link)) return false;
                setStatus("فقط لینک‌های http/https داخل مرورگر باز می‌شوند.");
                return true;
            }
            @Override public void onPageFinished(WebView view,String url) {
                if(UrlTools.isWebLink(url)) {
                    address.setText(url);
                    setStatus((tor?"Tor / Orbot":"مستقیم")+" | "+url);
                }
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest req,WebResourceError e) {
                if(req.isForMainFrame()) setStatus("خطا در اتصال: "+e.getDescription()+
                    " | بررسی کنید که Orbot و مسیر شبکه فعال باشند.");
            }
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error) {
                handler.cancel();
                setStatus("خطای گواهی امنیتی سایت؛ اتصال لغو شد.");
            }
        });
    }

    private void openUrl(String typed) {
        if(routeChanging){toast("در حال تنظیم مسیر ارتباطی هستیم.");return;}
        try {
            String link=UrlTools.normalize(typed);
            address.setText(link);
            web.loadUrl(link);
        } catch(IllegalArgumentException ex){toast(ex.getMessage());}
    }

    private void updateModeButtons() {
        if(directButton==null)return;
        directButton.setTextColor(tor?Color.DKGRAY:Color.WHITE);
        torButton.setTextColor(tor?Color.WHITE:Color.DKGRAY);
        directButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tor?0xffd5dfe9:BLUE));
        torButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tor?BLUE:0xffd5dfe9));
    }

    private void setRoute(boolean wantsTor) {
        if(routeChanging)return;
        if(wantsTor==tor){setStatus(wantsTor?"Tor انتخاب شده؛ وضعیت اتصال را با آزمایش Tor بررسی کنید.":"اتصال مستقیم انتخاب شده است.");return;}
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){
            if(wantsTor){setStatus("WebView این گوشی از تغییر پراکسی داخلی پشتیبانی نمی‌کند.");return;}
            tor=false;updateModeButtons();return;
        }
        routeChanging=true;
        web.stopLoading();
        web.loadUrl("about:blank");
        setStatus("در حال بررسی مسیر ارتباطی…");
        if(wantsTor) {
            new Thread(()->{
                boolean live=false;
                try(Socket socket=new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1",9050),1700);
                    live=true;
                }catch(Exception ignore){}
                final boolean open=live;
                runOnUiThread(()->{
                    if(!open){
                        routeChanging=false;
                        setStatus("پورت Orbot باز نیست. Orbot را وصل و پروکسی SOCKS را فعال کنید.");
                        return;
                    }
                    try {
                        ProxyConfig config=new ProxyConfig.Builder().addProxyRule(PROXY).build();
                        ProxyController.getInstance().setProxyOverride(config,
                            task->runOnUiThread(task),()->{
                                routeChanging=false;tor=true;updateModeButtons();
                                setStatus("پراکسی Orbot فعال شد. برای اطمینان «آزمایش Tor» را بزنید.");
                            });
                    }catch(Exception e){
                        routeChanging=false;setStatus("تنظیم پراکسی ممکن نشد: "+e.getMessage());
                    }
                });
            }).start();
        } else {
            try {
                ProxyController.getInstance().clearProxyOverride(task->runOnUiThread(task),()->{
                    routeChanging=false;tor=false;updateModeButtons();
                    setStatus("مسیر مستقیم فعال شد.");
                });
            }catch(Exception e){
                routeChanging=false;setStatus("بازگرداندن شبکه موفق نشد: "+e.getMessage());
            }
        }
    }

    private android.content.SharedPreferences prefs(){return getSharedPreferences("darsbridge",MODE_PRIVATE);}
    private String currentUrl(){
        String link=web.getUrl();
        if(!UrlTools.isWebLink(link)) link=address.getText().toString();
        return UrlTools.normalize(link);
    }
    private void addBookmark(){
        try{
            String u=currentUrl();
            Set<String> links=new HashSet<>(prefs().getStringSet("marks",Collections.emptySet()));
            links.add(u);prefs().edit().putStringSet("marks",links).apply();
            toast("لینک به نشانک‌ها اضافه شد.");
        }catch(Exception e){toast("ابتدا لینک یک سایت را وارد کنید.");}
    }
    private void showBookmarks(){
        ArrayList<String> links=new ArrayList<>(prefs().getStringSet("marks",Collections.emptySet()));
        Collections.sort(links);
        if(links.isEmpty()){toast("نشانکی ثبت نشده است.");return;}
        new AlertDialog.Builder(this).setTitle("نشانک‌ها")
            .setItems(links.toArray(new String[0]),(dialog,which)->openUrl(links.get(which)))
            .setNegativeButton("بستن",null).show();
    }
    private void openReader(){
        try{
            String url=currentUrl();
            if(url.contains("r.jina.ai")){toast("صفحه هم‌اکنون از Reader باز شده است.");return;}
            new AlertDialog.Builder(this).setTitle("ارسال لینک به سرویس خارجی")
                .setMessage("لینک عمومی این صفحه برای استخراج متن به r.jina.ai ارسال می‌شود. برای لینک خصوصی یا شامل اطلاعات شخصی استفاده نکنید.")
                .setNegativeButton("لغو",null)
                .setPositiveButton("تأیید", (d,w)->openUrl("https://r.jina.ai/"+url)).show();
        }catch(Exception e){toast("لینک معتبری وجود ندارد.");}
    }

    private JSONArray getSaved() {
        try{return new JSONArray(prefs().getString("offline","[]"));}
        catch(Exception e){return new JSONArray();}
    }
    private void saveOffline() {
        if(!UrlTools.isWebLink(web.getUrl())){toast("اول یک صفحه باز کنید.");return;}
        String pageUrl=web.getUrl();
        String title=web.getTitle()==null?pageUrl:web.getTitle();
        web.evaluateJavascript("(function(){return document.body ? document.body.innerText.slice(0,90000) : '';})()",json->{
            try{
                Object txt=new JSONTokener(json).nextValue();
                String content=String.valueOf(txt);
                if(content.length()<5){toast("متنی برای ذخیره پیدا نشد.");return;}
                JSONArray saved=getSaved();
                JSONObject item=new JSONObject();
                item.put("title",title);item.put("url",pageUrl);
                item.put("text",content);item.put("at",System.currentTimeMillis());
                saved.put(item);
                prefs().edit().putString("offline",saved.toString()).apply();
                toast("متن صفحه برای مطالعه آفلاین ذخیره شد.");
            }catch(Exception e){toast("ذخیره آفلاین موفق نبود.");}
        });
    }
    private void listOffline() {
        JSONArray saved=getSaved();
        if(saved.length()==0){toast("هنوز صفحه‌ای ذخیره نشده است.");return;}
        String[] titles=new String[saved.length()];
        for(int i=0;i<saved.length();i++){
            JSONObject obj=saved.optJSONObject(i);
            titles[i]=obj==null?"صفحه":obj.optString("title","صفحه");
        }
        new AlertDialog.Builder(this).setTitle("متن‌های آفلاین")
            .setItems(titles,(d,pos)->showOffline(saved.optJSONObject(pos)))
            .setNegativeButton("بستن",null).show();
    }
    private void showOffline(JSONObject page) {
        if(page==null)return;
        TextView body=new TextView(this);
        body.setText(page.optString("url")+"\n\n"+page.optString("text"));
        body.setTextIsSelectable(true);
        body.setTextSize(15);
        body.setPadding(dp(14),dp(14),dp(14),dp(14));
        ScrollView wrap=new ScrollView(this);
        wrap.addView(body);
        new AlertDialog.Builder(this).setTitle(page.optString("title","صفحه آفلاین"))
            .setView(wrap).setPositiveButton("بستن",null).show();
    }
    private void showGuide() {
        new AlertDialog.Builder(this).setTitle("راهنمای دسترسی رایگان")
            .setMessage("۱. ابتدا Orbot رسمی را نصب و راه‌اندازی کنید.\n۲. در تنظیمات Orbot، Bridge مناسب شبکه خود را امتحان کنید.\n۳. پروکسی SOCKS روی 127.0.0.1:9050 باید فعال باشد.\n۴. در درس‌گذر Tor / Orbot را انتخاب کنید و سپس آزمایش Tor را بزنید.\n\nاین روش به وجود مسیر خارجی نیاز دارد؛ پسوند .ir به‌تنهایی فیلترینگ را دور نمی‌زند. این مرورگر جایگزین امن Tor Browser نیست.")
            .setNegativeButton("بستن",null)
            .setNeutralButton("وب‌سایت Orbot",(d,w)->openUrl("https://orbot.app/"))
            .show();
    }
    @Override public void onBackPressed() {
        if(web!=null&&web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(web!=null){web.stopLoading();web.destroy();}
        super.onDestroy();
    }
}
