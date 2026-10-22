package com.darsbridge.app;

import android.content.Context;
import IPtProxy.Controller;
import IPtProxy.IPtProxy;
import IPtProxy.OnTransportEvents;
import org.torproject.jni.TorService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Self-contained Snowflake client for optional Tor integration.
 * Official bridges copied from guardianproject/orbot-android/assets/builtin-bridges.json
 * on 2026-10-23. Public bridges are not guarantees of availability.
 */
final class SnowflakeTransport {
    private static final String[] BRIDGES = new String[]{
        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn"
    };
    private Controller controller;

    static String option(String line, String key) {
        for (String token : line.split(" ")) if (token.startsWith(key+"=")) return token.substring(key.length()+1);
        return "";
    }
    /** Code and cause stay local; this app does not upload diagnostic data. */
    static final class SetupFailure extends Exception {
        final String step;
        SetupFailure(String step, Throwable cause) { super(step+": "+cause.getClass().getSimpleName(),cause); this.step=step; }
        String userMessage() {
            Throwable base=getCause();
            if(base==null)return "مرحله "+step+" ناموفق بود.";
            String extra=base.getMessage();
            // Avoid displaying long internal paths or URLs from third-party native errors.
            if(extra!=null && (extra.contains("/") || extra.contains("://")))extra="[جزئیات مسیر داخلی پنهان شد]";
            if(extra==null)extra="";
            extra=extra.replace("\n"," ").replace("\r"," ");
            if(extra.length()>110)extra=extra.substring(0,110);
            return "کد خطا: "+step+"\\nنوع: "+base.getClass().getSimpleName()+"\\nشرح: "+extra;
        }
    }

    synchronized int start(Context context) throws SetupFailure {
        if (controller != null) {
            try {
                int active=(int)controller.port(IPtProxy.Snowflake);
                if(active>0)return active;
            }catch(Exception | LinkageError ignored){}
            stop();
        }
        String step="STORAGE";
        Controller c=null;
        try {
            File dir=new File(context.getCacheDir(),"snowflake_state");
            if(!dir.exists() && !dir.mkdirs())throw new IllegalStateException("Snowflake state folder failed");

            step="GO_BINDINGS";
            c=new Controller(dir.getAbsolutePath(),false,false,"ERROR",new OnTransportEvents() {
                @Override public void connected(String name){}
                @Override public void error(String name, Exception exception){}
                @Override public void stopped(String name, Exception exception){}
            });
            step="SNOWFLAKE_OPTIONS";
            c.setSnowflakeIceServers(option(BRIDGES[0],"ice"));
            c.setSnowflakeBrokerUrl(option(BRIDGES[0],"url"));
            c.setSnowflakeFrontDomains(option(BRIDGES[0],"fronts"));
            c.setSnowflakeAmpCacheUrl("");
            c.setSnowflakeSqsUrl("");
            c.setSnowflakeSqsCreds("");

            step="SNOWFLAKE_START";
            c.start(IPtProxy.Snowflake,null);
            step="SOCKS_LISTENER";
            int port=(int)c.port(IPtProxy.Snowflake);
            if(port<1 || port>65535)throw new IllegalStateException("Snowflake local port missing");
            controller=c;
            return port;
        }catch(Exception | LinkageError err){
            if(c!=null)try{c.stop(IPtProxy.Snowflake);}catch(Exception | LinkageError ignored){}
            throw new SetupFailure(step,err);
        }
    }

    static void configureTor(Context context,int clientPort) throws Exception {
        if(clientPort<1||clientPort>65535)throw new IllegalArgumentException("Invalid bridge port");
        StringBuilder torrc=new StringBuilder();
        torrc.append("AvoidDiskWrites 1\n");
        torrc.append("UseBridges 1\n");
        torrc.append("ClientTransportPlugin snowflake socks5 127.0.0.1:").append(clientPort).append("\n");
        for(String bridge:BRIDGES)torrc.append("Bridge ").append(bridge).append("\n");
        File file=TorService.getTorrc(context);
        File folder=file.getParentFile();
        if(folder==null || (!folder.exists() && !folder.mkdirs()))throw new IllegalStateException("Tor config directory missing");
        Files.write(file.toPath(),torrc.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void configureDirect(Context context) throws Exception {
        File file=TorService.getTorrc(context);
        File folder=file.getParentFile();
        if(folder==null || (!folder.exists() && !folder.mkdirs()))throw new IllegalStateException("Tor config directory missing");
        Files.write(file.toPath(),"UseBridges 0\n".getBytes(StandardCharsets.UTF_8));
    }

    synchronized void stop() {
        if(controller!=null){
            try{controller.stop(IPtProxy.Snowflake);}catch(Exception ignored){}
            controller=null;
        }
    }
}
