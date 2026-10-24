package com.darsbridge.app;

import android.app.Activity;
import android.content.*;
import android.os.*;
import androidx.core.content.ContextCompat;
import org.torproject.jni.TorService;
import java.net.*;
import java.util.concurrent.*;

/** Embedded Tor with run/network/foreground-scoped health results. Not a VPN. */
final class EmbeddedTor {
    interface Observer { void changed(boolean ready,String message); }
    private final Activity activity;
    private final Observer observer;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final HealthState health=new HealthState();
    private final SnowflakeTransport bridge;
    private volatile boolean starting,destroyed;
    private volatile int attempt;
    private boolean bound,registered,foreground=true,everReady;
    private volatile boolean snowflakeMode;
    private int lastProgress=-1;
    private long stoppedAt=-10000,unhealthySince=-1;
    private TorService service;
    private final Runnable healthTask=this::pollReady,stalledTask=this::checkStall;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(destroyed || !starting || !activity.getPackageName().equals(intent.getStringExtra(TorService.EXTRA_SERVICE_PACKAGE_NAME)))return;
            if(TorService.ACTION_ERROR.equals(intent.getAction())) {fail("موتور Tor داخلی خطا داد.");return;}
            String state=intent.getStringExtra(TorService.EXTRA_STATUS);
            if(TorService.STATUS_STOPPING.equals(state) || TorService.STATUS_OFF.equals(state))fail("موتور Tor داخلی متوقف شد.");
            else if(TorService.STATUS_ON.equals(state))requestPoll();
            else if(TorService.STATUS_STARTING.equals(state))observer.changed(false,"در حال برقراری مدار Tor…");
        }
    };
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(!starting || destroyed)return;
            try {service=((TorService.LocalBinder)binder).getService();requestPoll();}
            catch(RuntimeException | LinkageError e){fail("اجرای سرویس Tor داخلی ممکن نیست.");}
        }
        @Override public void onServiceDisconnected(ComponentName name){service=null;if(starting)fail("سرویس Tor داخلی قطع شد.");}
        @Override public void onBindingDied(ComponentName name){service=null;if(starting)fail("سرویس Tor داخلی از کار افتاد.");}
    };
    EmbeddedTor(Activity activity,Observer observer){
        this.activity=activity;this.observer=observer;
        bridge=new SnowflakeTransport((ticket,event)->ui.post(()->transportEvent(ticket,event)));
    }
    boolean isStarting(){return starting;}
    boolean isReady(){return health.ready();}
    int port(){return health.port();}
    boolean isSnowflake(){return snowflakeMode;}
    void setForeground(boolean value){
        foreground=value;health.foreground(value);ui.removeCallbacks(healthTask);ui.removeCallbacks(stalledTask);
        if(value && starting){armStallDeadline();requestPoll();}
        else unhealthySince=-1;
    }
    void refreshHealth(){health.invalidate();armStallDeadline();requestPoll();}
    private void transportEvent(long ticket,SnowflakeTransport.Event event){
        if(destroyed || !starting || !snowflakeMode || !bridge.isCurrent(ticket))return;
        // IPtProxy Stopped is per connection, not proof that the transport listener died.
        boolean wasReady=health.ready();
        health.invalidate();armStallDeadline();requestPoll();
        if(wasReady || event==SnowflakeTransport.Event.ERROR)
            observer.changed(false,event==SnowflakeTransport.Event.ERROR?
                "پل داخلی خطای ارتباط داد؛ در حال بررسی مجدد مدار…":"ارتباط پل تغییر کرد؛ در حال تأیید مجدد مسیر…");
    }
    private void requestPoll(){
        ui.removeCallbacks(healthTask);
        if(starting && !destroyed && foreground)ui.post(healthTask);
    }
    private void armStallDeadline(){
        if(!starting || !foreground || !everReady || health.ready())return;
        if(unhealthySince<0)unhealthySince=SystemClock.elapsedRealtime();
        ui.removeCallbacks(stalledTask);
        ui.postDelayed(stalledTask,Math.max(1,45000-(SystemClock.elapsedRealtime()-unhealthySince)));
    }
    private void checkStall(){
        if(starting && foreground && everReady && !health.ready() && unhealthySince>=0) {
            long remaining=45000-(SystemClock.elapsedRealtime()-unhealthySince);
            if(remaining>0)ui.postDelayed(stalledTask,remaining);
            else fail("مسیر داخلی بازیابی نشد؛ اتصال با بودجهٔ تلاش مجدد بررسی می‌شود.");
        }
    }
    void start(boolean withSnowflake){
        if(destroyed)return;
        if(starting){
            if(snowflakeMode==withSnowflake){requestPoll();return;}
            stop();final int expected=attempt;
            ui.postDelayed(()->{if(!destroyed && expected==attempt)start(withSnowflake);},2600);return;
        }
        long remaining=2500-(SystemClock.elapsedRealtime()-stoppedAt);
        if(remaining>0){final int expected=attempt;ui.postDelayed(()->{if(!destroyed && expected==attempt)start(withSnowflake);},remaining);return;}
        starting=true;snowflakeMode=withSnowflake;everReady=false;lastProgress=-1;unhealthySince=-1;
        health.start();health.foreground(foreground);
        final int ticket=++attempt;
        observer.changed(false,withSnowflake?"در حال آماده‌سازی پل Snowflake داخلی…":"در حال راه‌اندازی Tor مستقیم…");
        worker.execute(()->{
            if(destroyed || !starting || ticket!=attempt)return;
            String stage="STARTING";
            try {
                if(withSnowflake){stage="SNOWFLAKE_CLIENT";int port=bridge.start(activity);stage="WRITE_TOR_CONFIG";SnowflakeTransport.configureTor(activity,port);}
                else {stage="WRITE_DIRECT_CONFIG";bridge.stop();SnowflakeTransport.configureDirect(activity);}
                ui.post(()->{
                    if(destroyed || !starting || ticket!=attempt)return;
                    try {
                        IntentFilter filter=new IntentFilter();filter.addAction(TorService.ACTION_STATUS);filter.addAction(TorService.ACTION_ERROR);
                        ContextCompat.registerReceiver(activity,receiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;
                        bound=activity.bindService(new Intent(activity,TorService.class),connection,Context.BIND_AUTO_CREATE);
                        if(!bound){fail("سرویس Tor داخلی اجرا نشد.");return;}
                        ui.postDelayed(()->{
                            if(!destroyed && starting && !everReady && ticket==attempt)
                                fail(withSnowflake?"Snowflake در زمان تعیین‌شده متصل نشد؛ مسیر دوباره بررسی می‌شود.":"Tor مستقیم متصل نشد؛ Snowflake را امتحان کنید.");
                        },115000);
                    }catch(RuntimeException | LinkageError e){fail("سرویس Tor داخلی قابل راه‌اندازی نیست.");}
                });
            }catch(Exception | LinkageError e){
                String label=e instanceof SnowflakeTransport.SetupFailure?((SnowflakeTransport.SetupFailure)e).userMessage():
                    "کد خطا: "+stage+"\nنوع: "+e.getClass().getSimpleName()+"\nشرح: "+safeMessage(e);
                ui.post(()->{if(!destroyed && starting && ticket==attempt)fail("DIAG\n"+label);});
            }
        });
    }
    private static String safeMessage(Throwable e){
        String info=e.getMessage();if(info==null)return "";
        if(info.contains("/") || info.contains("://"))return "[نشانی یا مسیر حذف شد]";
        info=info.replace('\r',' ').replace('\n',' ');return info.length()>130?info.substring(0,130):info;
    }
    private void pollReady(){
        if(!starting || destroyed || !foreground)return;
        if(service==null){ui.postDelayed(healthTask,1250);return;}
        final HealthState.Check check=health.beginCheck();if(check==null)return;
        final int ticket=attempt;final TorService current=service;
        worker.execute(()->{
            boolean established=false;int socks=-1,progress=-1;
            try {
                String circuit=current.getInfo("status/circuit-established");
                progress=RecoveryPolicy.bootstrapPercent(current.getInfo("status/bootstrap-phase"));
                socks=current.getSocksPort();
                established="1".equals(circuit) && "up".equals(current.getInfo("network-liveness")) && socks>0 && socksHandshake(socks);
            }catch(RuntimeException | LinkageError ignored){}
            final boolean ok=established;final int detectedPort=socks,actualProgress=progress;
            ui.post(()->{
                if(!starting || destroyed || ticket!=attempt)return;
                boolean wasReady=health.ready();
                if(!health.complete(check,ok,detectedPort)){requestPoll();return;}
                if(health.ready()){
                    everReady=true;unhealthySince=-1;lastProgress=100;ui.removeCallbacks(stalledTask);
                    if(!wasReady)observer.changed(true,"مدار و پراکسی داخلی آماده‌اند؛ تأیید خروجی از منو قابل انجام است.");
                }else{
                    if(wasReady)observer.changed(false,"مدار داخلی قطع شد؛ در حال بازیابی مسیر…");
                    else if(actualProgress>=0 && actualProgress!=lastProgress){lastProgress=actualProgress;observer.changed(false,"پیشرفت واقعی آماده‌سازی تور: "+actualProgress+"٪");}
                    armStallDeadline();
                }
                ui.removeCallbacks(healthTask);
                if(foreground)ui.postDelayed(healthTask,health.ready()?15000:2000);
            });
        });
    }
    private static boolean socksHandshake(int port){
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1",port),2000);socket.setSoTimeout(2000);
            socket.getOutputStream().write(new byte[]{5,1,0});socket.getOutputStream().flush();
            return socket.getInputStream().read()==5 && socket.getInputStream().read()==0;
        }catch(Exception e){return false;}
    }
    private void fail(String why){stop();if(!destroyed)observer.changed(false,why);}
    void stop(){
        ++attempt;stoppedAt=SystemClock.elapsedRealtime();final TorService old=service;
        starting=false;health.stop();service=null;unhealthySince=-1;
        ui.removeCallbacks(healthTask);ui.removeCallbacks(stalledTask);
        worker.execute(()->{
            try{if(old!=null && old.getTorControlConnection()!=null)old.getTorControlConnection().shutdownTor("HALT");}catch(Exception | LinkageError ignored){}
            try{bridge.stop();}catch(RuntimeException | LinkageError ignored){}
        });
        if(bound){try{activity.unbindService(connection);}catch(IllegalArgumentException ignored){}bound=false;}
        if(registered){try{activity.unregisterReceiver(receiver);}catch(IllegalArgumentException ignored){}registered=false;}
    }
    void destroy(){destroyed=true;stop();ui.removeCallbacksAndMessages(null);worker.shutdown();}
}
