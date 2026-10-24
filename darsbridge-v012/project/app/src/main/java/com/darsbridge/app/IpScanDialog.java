package com.darsbridge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.net.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.net.*;
import java.util.*;

/** Explicit direct diagnostics, separate from WebView/Tor. Dismissal and backgrounding cancel it. */
final class IpScanDialog implements AutoCloseable {
    static final String CACHE_PREFS="darsbridge_ip_checks";
    private final Activity activity;private final ConnectivityManager connectivity;
    private AlertDialog dialog;private Spinner preset;private EditText host,ips;
    private LinearLayout advanced,results,tcpPanel;private TextView status,meaning,tcpAddresses;
    private Button showTcp;private final List<String> tcpOnlyIps=new ArrayList<>();private IpScanner.Connector lastConnector;
    private ProgressBar progress;private Button start;
    private IpScanner scanner;private boolean closed;private int sequence,tcp,hits;
    IpScanDialog(Activity activity){this.activity=activity;connectivity=(ConnectivityManager)activity.getSystemService(Context.CONNECTIVITY_SERVICE);}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private TextView text(String value){TextView t=new TextView(activity);t.setText(value);t.setTextSize(15);t.setPadding(0,dp(7),0,dp(7));t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);return t;}
    private Button button(String value){Button b=new Button(activity);b.setText(value);b.setAllCaps(false);b.setTextSize(14);return b;}
    void show(){
        ScrollView scroll=new ScrollView(activity);LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);body.setPadding(dp(18),dp(8),dp(18),dp(8));scroll.addView(body);
        body.addView(text("این آزمایش مستقیماً از اینترنت گوشی و بدون Tor و Snowflake انجام می‌شود. اگر VPN گوشی فعال است، ابتدا آن را خاموش کن. مسیر فعلی مرورگر خودکار تغییر نمی‌کند."));
        preset=new Spinner(activity);preset.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"جست‌وجوی گوگل","صفحهٔ YouTube","Cloudflare","دامنه و IP دلخواه"}));body.addView(preset);
        meaning=text("IP پاسخ‌گو برای همین سرویس جست‌وجو می‌شود. IP پیدا‌شده پروکسی عمومی یا تضمین دسترسی به کل اینترنت نیست.");body.addView(meaning);
        Button more=button("تنظیمات پیشرفته و فهرست IP");body.addView(more);
        advanced=new LinearLayout(activity);advanced.setOrientation(LinearLayout.VERTICAL);advanced.setVisibility(View.GONE);body.addView(advanced);
        advanced.addView(text("دامنهٔ HTTPS"));host=new EditText(activity);host.setSingleLine(true);host.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);host.setTextDirection(View.TEXT_DIRECTION_LTR);advanced.addView(host);
        advanced.addView(text("IPv4 یا CIDR؛ هر مورد در یک خط"));ips=new EditText(activity);ips.setMinLines(3);ips.setMaxLines(5);ips.setGravity(Gravity.TOP|Gravity.START);ips.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);ips.setTextDirection(View.TEXT_DIRECTION_LTR);advanced.addView(ips);
        advanced.addView(text("حداکثر ۲۵۶ نمونهٔ IPv4 آزمایش می‌شود. محدوده‌های بزرگ نمونه‌گیری می‌شوند؛ کل اینترنت اسکن نمی‌شود. فهرست‌های پیش‌فرض داخل برنامه ذخیره‌اند و تضمین دسترس‌پذیری نیستند."));
        more.setOnClickListener(v->advanced.setVisibility(advanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        preset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(AdapterView<?> parent){}
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(position==3){advanced.setVisibility(View.VISIBLE);return;}
                host.setText(position==0?"www.google.com":position==1?"www.youtube.com":"www.cloudflare.com");
                ips.setText(position==2?IpCandidates.CLOUDFLARE:IpCandidates.GOOGLE);
                meaning.setText(position==1?"این آزمایش پاسخ HTTPS صفحهٔ YouTube را بررسی می‌کند؛ پخش ویدئو و دامنه‌های دیگر جدا هستند.":"IP پاسخ‌گو برای همین سرویس جست‌وجو می‌شود. IP پیدا‌شده پروکسی عمومی یا تضمین دسترسی به کل اینترنت نیست.");
            }
        });
        host.setText("www.google.com");ips.setText(IpCandidates.GOOGLE);
        start=button("شروع جست‌وجوی مستقیم");body.addView(start);start.setOnClickListener(v->begin());
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1);body.addView(progress);
        status=text("آمادهٔ آزمایش؛ هنوز IP بررسی نشده است.");body.addView(status);
        results=new LinearLayout(activity);results.setOrientation(LinearLayout.VERTICAL);body.addView(results);
        showTcp=button("نمایش IPهای فقط TCP؛ بدون تأیید HTTPS");showTcp.setVisibility(View.GONE);body.addView(showTcp);
        tcpPanel=new LinearLayout(activity);tcpPanel.setOrientation(LinearLayout.VERTICAL);tcpPanel.setVisibility(View.GONE);body.addView(tcpPanel);
        tcpPanel.addView(text("این IPها فقط به اتصال TCP پاسخ داده‌اند؛ دامنه یا HTTPS آن‌ها تأیید نشده و پروکسی عمومی نیستند."));
        tcpAddresses=text("");tcpAddresses.setTextDirection(View.TEXT_DIRECTION_LTR);tcpAddresses.setTextIsSelectable(true);tcpPanel.addView(tcpAddresses);
        Button copyTcp=button("کپی فهرست IPهای فقط TCP");tcpPanel.addView(copyTcp);
        showTcp.setOnClickListener(v->tcpPanel.setVisibility(tcpPanel.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        copyTcp.setOnClickListener(v->{
            if(lastConnector==null || !lastConnector.current()){networkChanged();return;}
            ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("DarsBridge TCP-only, HTTPS unverified",String.join("\n",tcpOnlyIps)));
        });
        body.addView(text("فقط نتیجهٔ HTTPS معتبر برای نام دامنه، موفق شمرده می‌شود. پاسخ TCP به‌تنهایی کافی نیست. آزمایش با تغییر شبکه، خروج از برنامه، سه نتیجهٔ موفق یا رسیدن به سه دقیقه متوقف می‌شود."));
        dialog=new AlertDialog.Builder(activity).setTitle("جست‌وجوی IP مستقیم").setView(scroll)
            .setPositiveButton("بستن",(d,w)->close()).setNeutralButton("توقف",null).create();
        dialog.setOnDismissListener(d->close());dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{if(scanner!=null)scanner.close();});
    }
    private IpScanner.Connector connection(Network network){
        return new IpScanner.Connector(){
            @Override public boolean current(){
                try{NetworkCapabilities c=connectivity.getNetworkCapabilities(network);
                    return network.equals(connectivity.getActiveNetwork()) && c!=null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                }catch(RuntimeException e){return false;}
            }
            @Override public void connect(Socket socket,InetAddress address,int port,int timeout)throws IOException{
                if(!current())throw new IOException("Network changed");
                network.bindSocket(socket);socket.connect(new InetSocketAddress(address,port),timeout);
            }
        };
    }
    private void begin(){
        if(closed || scanner!=null)return;
        final String name;final List<String> candidates;
        try{
            name=IpCandidates.host(host.getText().toString());
            LinkedHashSet<String> selected=new LinkedHashSet<>();
            for(String previous:activity.getSharedPreferences(CACHE_PREFS,Context.MODE_PRIVATE).getStringSet(name,Collections.emptySet()))
                try{if(IpCandidates.isPublic(IpCandidates.number(previous)))selected.add(previous);}catch(IllegalArgumentException ignored){}
            selected.addAll(IpCandidates.build(ips.getText().toString(),System.nanoTime()));
            candidates=new ArrayList<>(selected);if(candidates.size()>IpCandidates.MAX)candidates.subList(IpCandidates.MAX,candidates.size()).clear();
        }catch(IllegalArgumentException e){status.setText(e.getMessage());return;}
        final Network network=connectivity.getActiveNetwork();
        if(network==null){status.setText("شبکهٔ فعالی در گوشی پیدا نشد.");return;}
        NetworkCapabilities caps=connectivity.getNetworkCapabilities(network);
        if(caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){status.setText("برای آزمایش مستقیم، VPN گوشی را خاموش کن. تنظیم VPN خودکار تغییر نکرد.");return;}
        final IpScanner.Connector connector=connection(network);
        if(!connector.current()){status.setText("شبکه برای این آزمایش آماده نیست.");return;}
        lastConnector=connector;
        final int ticket=++sequence;tcp=0;hits=0;tcpOnlyIps.clear();tcpAddresses.setText("");tcpPanel.setVisibility(View.GONE);showTcp.setVisibility(View.GONE);results.removeAllViews();progress.setMax(candidates.size());progress.setProgress(0);
        host.setText(name);host.setEnabled(false);ips.setEnabled(false);preset.setEnabled(false);start.setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);status.setText("در حال آزمایش مستقیم "+candidates.size()+" نشانی برای "+name+"…");
        scanner=new IpScanner(connector,new IpScanner.Listener(){
            @Override public void result(IpScanner.Result result,int done,int total){activity.runOnUiThread(()->{
                if(closed || ticket!=sequence)return;
                if(!connector.current()){networkChanged();return;}
                if(result.status!=IpScanner.Status.TCP_FAILED)tcp++;
                if(result.status!=IpScanner.Status.TCP_FAILED && result.status!=IpScanner.Status.HTTPS_RESPONSE){
                    tcpOnlyIps.add(result.ip);tcpAddresses.setText(String.join("\n",tcpOnlyIps));showTcp.setVisibility(View.VISIBLE);
                }
                if(result.status==IpScanner.Status.HTTPS_RESPONSE){
                    hits++;remember(name,result.ip);
                    results.addView(text(name+"\n"+result.ip+" • HTTP "+result.http+" • "+result.millis+" ms"));
                    Button copy=button("کپی IP "+result.ip);results.addView(copy);
                    copy.setOnClickListener(v->{
                        if(!connector.current()){networkChanged();return;}
                        ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("DarsBridge IP for "+name,result.ip));Toast.makeText(activity,"IP مربوط به همین سرویس کپی شد.",Toast.LENGTH_SHORT).show();}
                    });
                }
                progress.setProgress(done);status.setText("بررسی: "+done+" / "+total+" • TCP پاسخ‌گو: "+tcp+" • HTTPS معتبر: "+hits);
            });}
            @Override public void finished(IpScanner.End why,int done,int total){activity.runOnUiThread(()->{
                if(closed || ticket!=sequence)return;scanner=null;enable();
                String end=why==IpScanner.End.NETWORK_CHANGED?"شبکه تغییر کرد؛ نتیجهٔ تازه نیاز به جست‌وجوی دوباره دارد.":why==IpScanner.End.CANCELLED?"جست‌وجو متوقف شد.":why==IpScanner.End.TIME_LIMIT?"زمان سه‌دقیقه‌ای تمام شد.":why==IpScanner.End.FOUND_LIMIT?"سه پاسخ HTTPS معتبر پیدا شد و جست‌وجو متوقف شد.":"آزمایش این نمونه تمام شد.";
                if(why==IpScanner.End.NETWORK_CHANGED){results.removeAllViews();tcpOnlyIps.clear();showTcp.setVisibility(View.GONE);tcpPanel.setVisibility(View.GONE);}
                status.setText(end+"\nبررسی: "+done+" / "+total+" • TCP: "+tcp+" • HTTPS: "+hits+(hits==0?"\nدر نمونهٔ بررسی‌شده پاسخ HTTPS معتبر پیدا نشد؛ این نتیجه اسکن همهٔ IPها نیست.":"\nاین نتیجه فقط پاسخ همین سرویس در زمان آزمایش است؛ اینترنت عمومی یا پخش ویدئو تأیید نشده است."));
            });}
        });
        scanner.start(name,candidates);
    }
    private void remember(String name,String ip){
        SharedPreferences prefs=activity.getSharedPreferences(CACHE_PREFS,Context.MODE_PRIVATE);
        LinkedHashSet<String> values=new LinkedHashSet<>();values.add(ip);values.addAll(prefs.getStringSet(name,Collections.emptySet()));
        while(values.size()>5)values.remove(new ArrayList<>(values).get(values.size()-1));
        prefs.edit().putStringSet(name,values).apply();
    }
    private void enable(){host.setEnabled(true);ips.setEnabled(true);preset.setEnabled(true);start.setEnabled(true);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);}
    boolean isRunning(){return scanner!=null;}
    void networkChanged(){
        if(closed)return;++sequence;if(scanner!=null){scanner.close();scanner=null;}results.removeAllViews();tcpOnlyIps.clear();tcpAddresses.setText("");showTcp.setVisibility(View.GONE);tcpPanel.setVisibility(View.GONE);lastConnector=null;enable();
        status.setText("شبکه تغییر کرد؛ نتایج قبلی از نمایش حذف شدند. برای این شبکه دوباره جست‌وجو کن.");
    }
    @Override public void close(){
        if(closed)return;closed=true;++sequence;if(scanner!=null){scanner.close();scanner=null;}
        if(dialog!=null && dialog.isShowing())dialog.dismiss();
    }
}
