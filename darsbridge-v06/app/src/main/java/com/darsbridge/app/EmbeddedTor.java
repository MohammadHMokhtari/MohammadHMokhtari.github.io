package com.darsbridge.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.torproject.jni.TorService;
import androidx.core.content.ContextCompat;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional local Tor client using the Guardian Project Android library.
 * DIRECT Tor only; no pluggable transports are bundled.
 * Not a VPN and not a hardened Tor Browser.
 */
final class EmbeddedTor {
    interface Observer { void changed(boolean ready, String message); }
    private final Activity activity;
    private final Observer observer;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean starting = false;
    private volatile boolean ready = false;
    private volatile int port = -1;
    private boolean bound = false, registered = false, checking = false, destroyed = false;
    private int attempt = 0;
    private TorService service;
    private final SnowflakeTransport bridge=new SnowflakeTransport();
    private volatile boolean snowflakeMode=false;
    private int lastProgress=-1;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (destroyed || !starting) return;
            if (!activity.getPackageName().equals(
                    intent.getStringExtra(TorService.EXTRA_SERVICE_PACKAGE_NAME))) return;
            if (TorService.ACTION_ERROR.equals(intent.getAction())) {
                fail("موتور Tor داخلی هنگام راه‌اندازی خطا داد.");
                return;
            }
            String state = intent.getStringExtra(TorService.EXTRA_STATUS);
            if (TorService.STATUS_STOPPING.equals(state) || TorService.STATUS_OFF.equals(state)) {
                fail("موتور Tor داخلی متوقف شد.");
            } else if (TorService.STATUS_STARTING.equals(state)) {
                observer.changed(false,snowflakeMode?"در حال برقراری مدار Tor با پل Snowflake…":"در حال اتصال مستقیم به شبکه Tor…");
            } else if (TorService.STATUS_ON.equals(state)) {
                pollReady();
            }
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder binder) {
            if (!starting || destroyed) return;
            try {
                service=((TorService.LocalBinder)binder).getService();
                pollReady();
            } catch (RuntimeException | LinkageError e) {
                fail("اجرای سرویس Tor داخلی روی این دستگاه ممکن نیست.");
            }
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            service=null;
            if(starting)fail("سرویس Tor داخلی قطع شد.");
        }
        @Override public void onBindingDied(ComponentName n) {
            service=null;
            if(starting)fail("سرویس Tor داخلی از کار افتاد.");
        }
    };

    EmbeddedTor(Activity activity, Observer observer) {
        this.activity=activity;
        this.observer=observer;
    }
    boolean isStarting(){ return starting; }
    boolean isReady(){ return ready; }
    int port(){ return ready ? port : -1; }
    boolean isSnowflake(){return snowflakeMode;}

    void start(boolean withSnowflake){
        if (destroyed) return;
        if (starting) {
            if(snowflakeMode==withSnowflake)return;
            stop();
            ui.postDelayed(()->{if(!destroyed)start(withSnowflake);},1200);
            return;
        }
        snowflakeMode=withSnowflake;
        starting=true;
        ready=false;port=-1;lastProgress=-1;
        final int ticket=++attempt;
        observer.changed(false,withSnowflake?"در حال آماده‌سازی پل Snowflake داخلی…":"در حال راه‌اندازی Tor مستقیم…");
        worker.execute(()->{
            String stage="STARTING";
            try {
                if(withSnowflake) {
                    stage="SNOWFLAKE_CLIENT";
                    int ptPort=bridge.start(activity);
                    stage="WRITE_TOR_CONFIG";
                    SnowflakeTransport.configureTor(activity,ptPort);
                } else {
                    stage="STOP_SNOWFLAKE";
                    bridge.stop();
                    stage="WRITE_DIRECT_CONFIG";
                    SnowflakeTransport.configureDirect(activity);
                }
                ui.post(()->{
                    if(destroyed || !starting || ticket!=attempt)return;
                    try {
                        IntentFilter filter=new IntentFilter();
                        filter.addAction(TorService.ACTION_STATUS);
                        filter.addAction(TorService.ACTION_ERROR);
                        ContextCompat.registerReceiver(activity,receiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED);
                        registered=true;
                        bound=activity.bindService(new Intent(activity,TorService.class),
                            connection,Context.BIND_AUTO_CREATE);
                        if(!bound){fail("سرویس Tor داخلی اجرا نشد.");return;}
                        ui.postDelayed(()->{
                            if(!destroyed && starting && !ready && ticket==attempt)
                                fail(withSnowflake?"Snowflake هم در این شبکه به مدار Tor متصل نشد؛ Orbot با Bridge دیگر را امتحان کنید.":"Tor مستقیم متصل نشد؛ Snowflake داخلی را امتحان کنید.");
                        },115000);
                    }catch(RuntimeException | LinkageError e){
                        fail("سرویس Tor داخلی قابل راه‌اندازی نیست.");
                    }
                });
            }catch(Exception | LinkageError e){
                String label=e instanceof SnowflakeTransport.SetupFailure
                    ? ((SnowflakeTransport.SetupFailure)e).userMessage()
                    : "کد خطا: "+stage+"\\nنوع: "+e.getClass().getSimpleName()
                        +"\\nشرح: "+safeMessage(e);
                android.util.Log.e("DarsBridgeTransport","Transport setup failure at "+stage,e);
                ui.post(()->{
                    if(!destroyed && starting && ticket==attempt)
                        fail("DIAG\\n"+label);
                });
            }
        });
    }

    private static String safeMessage(Throwable error) {
        String info=error.getMessage();
        if(info==null)return "";
        if(info.contains("/") || info.contains("://"))return "[مسیر داخلی یا نشانی حذف شد]";
        info=info.replace("\\r"," ").replace("\\n"," ");
        return info.length()>130?info.substring(0,130):info;
    }

    private void pollReady(){
        if(!starting || destroyed || checking || ready)return;
        if(service==null){ui.postDelayed(this::pollReady,1250);return;}
        checking=true;
        final int ticket=attempt;
        final TorService current=service;
        worker.execute(()->{
            boolean established=false;
            int socks=-1;
            try {
                // Tor listener alone is insufficient; require a Tor circuit.
                String circuit=current.getInfo("status/circuit-established");
                socks=current.getSocksPort();
                established="1".equals(circuit) && socks>0 && socksHandshake(socks);
            }catch(RuntimeException | LinkageError ignored){}
            final boolean ok=established;
            final int detectedPort=socks;
            ui.post(()->{
                checking=false;
                if(!starting || destroyed || ticket!=attempt)return;
                if(ok){
                    lastProgress=100;
                    ready=true;port=detectedPort;
                    observer.changed(true,"Tor داخلی متصل است؛ خروجی را با آزمایش Tor بررسی کنید.");
                }else {
                    if(lastProgress<0){lastProgress=0;observer.changed(false,snowflakeMode?"Snowflake روشن است؛ در حال ایجاد مدار Tor…":"Tor مستقیم در حال بوت‌استرپ…");}
                    ui.postDelayed(this::pollReady,1650);
                }
            });
        });
    }

    private static boolean socksHandshake(int p){
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1",p),2000);
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(new byte[]{5,1,0});
            socket.getOutputStream().flush();
            InputStream in=socket.getInputStream();
            return in.read()==5 && in.read()==0;
        }catch(Exception e){return false;}
    }
    private void fail(String why){
        stop();
        if(!destroyed)observer.changed(false,why);
    }
    void stop(){
        ++attempt;
        starting=false;ready=false;port=-1;service=null;
        // Disconnect the transport before allowing a different Tor routing configuration.
        try{bridge.stop();}catch(RuntimeException | LinkageError ignored){}
        if(bound){
            try{activity.unbindService(connection);}catch(IllegalArgumentException ignored){}
            bound=false;
        }
        if(registered){
            try{activity.unregisterReceiver(receiver);}catch(IllegalArgumentException ignored){}
            registered=false;
        }
    }
    void destroy(){
        destroyed=true;
        stop();
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }
}
