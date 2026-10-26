package com.darsbridge.app;

import android.view.View;
import android.webkit.*;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.Lifecycle;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Controlled browser requests. No external target, CA exception or TLS bypass is needed. */
@RunWith(AndroidJUnit4.class)
public class NetworkSafetyTest {
    @org.junit.Before public void resetBrowserBefore(){BrowserTestState.reset();}
    @org.junit.After public void resetBrowserAfter(){BrowserTestState.reset();}
    private static final String ORIGIN="https://darsbridge-safety.invalid";
    private static Object invoke(MainActivity a,String name,Class<?>[] types,Object... args) {
        try {java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(a,args);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static Object call(MainActivity a,String name){return invoke(a,name,new Class<?>[0]);}
    private static void field(MainActivity a,String name,Object value) {
        try {java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static Object field(MainActivity a,String name) {
        try {java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static WebView web(MainActivity a){return a.getWindow().getDecorView().findViewWithTag("web");}
    private static void await(String what,BooleanSupplier condition)throws Exception {
        long end=SystemClock.elapsedRealtime()+12000;
        while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;Thread.sleep(40);}
        fail("Timed out: "+what);
    }
    private static String evaluate(ActivityScenario<MainActivity> scenario,String script)throws Exception {
        AtomicReference<String> result=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        scenario.onActivity(a->web(a).evaluateJavascript(script,value->{result.set(value);done.countDown();}));
        assertTrue("JS result timeout",done.await(5,TimeUnit.SECONDS));return result.get();
    }
    private static void ready(ActivityScenario<MainActivity> scenario)throws Exception {
        AtomicBoolean allowed=new AtomicBoolean();
        await("configured proxy route",()->{scenario.onActivity(a->allowed.set((Boolean)field(a,"networkAllowed")));return allowed.get();});
    }
    private static void localMode(ActivityScenario<MainActivity> scenario,int port) {
        scenario.onActivity(a->{field(a,"mode",ConnectionPolicy.Mode.LOCAL);field(a,"socksPort",port);});
    }
    private static void navigate(ActivityScenario<MainActivity> scenario,String url) {
        scenario.onActivity(a->invoke(a,"navigate",new Class<?>[]{String.class},url));
    }
    /** Keep the production callbacks, adding only a local HTTPS fixture response. */
    private static void fixture(ActivityScenario<MainActivity> scenario,String html,AtomicInteger posts) {
        scenario.onActivity(a->{
            WebView w=web(a);WebViewClient real=w.getWebViewClient();
            w.setWebViewClient(new WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                    WebResourceResponse blocked=real.shouldInterceptRequest(v,r);if(blocked!=null)return blocked;
                    if(r.getUrl().toString().startsWith(ORIGIN+"/")){
                        if(r.getUrl().getPath().equals("/submitted")){
                            assertEquals("POST",r.getMethod());posts.incrementAndGet();
                            return response("<html><body>submitted<script>window.submitted=true</script></body></html>");
                        }
                        if(r.getUrl().getPath().equals("/frame")){
                            return response("<script>parent.postMessage(typeof RTCPeerConnection,'*')</script>");
                        }
                        return response(html);
                    }
                    return real.shouldInterceptRequest(v,r);
                }
                @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return real.shouldOverrideUrlLoading(v,r);}
                @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){real.onPageStarted(v,url,icon);}
                @Override public void onPageFinished(WebView v,String url){real.onPageFinished(v,url);}
                @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){real.onReceivedError(v,r,e);}
                @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){real.onReceivedHttpError(v,r,e);}
                @Override public void onReceivedSslError(WebView v,SslErrorHandler handler,android.net.http.SslError e){real.onReceivedSslError(v,handler,e);}
                @Override public void onFormResubmission(WebView v,Message dont,Message resend){real.onFormResubmission(v,dont,resend);}
            });
        });
    }
    private static WebResourceResponse response(String html){
        return new WebResourceResponse("text/html","UTF-8",200,"OK",Collections.singletonMap("Cache-Control","no-store"),
            new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void unresolvedHostnameIsPassedToSocksAsDomain()throws Exception {
        try(Socks proxy=new Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            localMode(scenario,proxy.port());navigate(scenario,"https://darsbridge-dns-probe.invalid/");
            await("SOCKS domain address",()->proxy.hosts.contains("darsbridge-dns-probe.invalid"));
            assertTrue(proxy.domainRequests.get()>0);
            scenario.onActivity(a->call(a,"stopConnection"));
        }
    }
    @Test public void httpsAndWebsocketUseProxyAndNeverTouchDirectTrap()throws Exception {
        try(Socks proxy=new Socks();Trap trap=new Trap();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            localMode(scenario,proxy.port());fixture(scenario,"<html><body>ready<script>window.fixture=true</script></body></html>",new AtomicInteger());
            navigate(scenario,ORIGIN+"/page");ready(scenario);
            await("fixture loaded",()->{try{return "true".equals(evaluate(scenario,"window.fixture===true"));}catch(Exception e){return false;}});
            String target="127.0.0.1:"+trap.port();
            evaluate(scenario,"fetch('https://"+target+"/fetch').catch(()=>{});new WebSocket('wss://"+target+"/ws');true");
            await("two proxied requests",()->proxy.destinations.get()>=2);
            assertTrue(proxy.hosts.contains("127.0.0.1"));assertEquals(0,trap.accepted.get());
            scenario.onActivity(a->call(a,"stopConnection"));
        }
    }
    @Test public void deadProxyDoesNotFallBackToDirectHttpsOrWebsocket()throws Exception {
        try(Socks proxy=new Socks();Trap trap=new Trap();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            localMode(scenario,proxy.port());fixture(scenario,"<script>window.fixture=true</script>",new AtomicInteger());
            navigate(scenario,ORIGIN+"/page");ready(scenario);
            await("fixture",()->{try{return "true".equals(evaluate(scenario,"window.fixture===true"));}catch(Exception e){return false;}});
            proxy.close();
            String target="127.0.0.1:"+trap.port();
            evaluate(scenario,"window.failed=false;fetch('https://"+target+"/dead').catch(()=>window.failed=true);new WebSocket('wss://"+target+"/dead');true");
            await("dead proxy failure reported to page",()->{try{return "true".equals(evaluate(scenario,"window.failed"));}catch(Exception e){return false;}});
            assertEquals(0,trap.accepted.get());
            scenario.onActivity(a->call(a,"stopConnection"));
        }
    }
    @Test public void documentStartGuardBlocksCommonWebrtcBeforePageAndInNavigatedFrame()throws Exception {
        try(Socks proxy=new Socks();DatagramSocket stun=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));
            ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            stun.setSoTimeout(1200);AtomicInteger posts=new AtomicInteger();
            localMode(scenario,proxy.port());
            String html="<script>window.firstType=typeof RTCPeerConnection;window.blocked=false;try{var p=new RTCPeerConnection({iceServers:[{urls:'stun:127.0.0.1:"+stun.getLocalPort()+"'}]});p.createDataChannel('x');p.createOffer().then(o=>p.setLocalDescription(o))}catch(e){window.blocked=true}"+
                "window.addEventListener('message',e=>window.frameType=e.data);</script><iframe src='"+ORIGIN+"/frame'></iframe>";
            fixture(scenario,html,posts);navigate(scenario,ORIGIN+"/rtc");ready(scenario);
            await("frame guard",()->{try{return "\"undefined\"".equals(evaluate(scenario,"window.frameType"));}catch(Exception e){return false;}});
            assertEquals("\"undefined\"",evaluate(scenario,"window.firstType"));assertEquals("true",evaluate(scenario,"window.blocked"));
            assertEquals("\"undefined\"",evaluate(scenario,"try{Object.defineProperty(window,'RTCPeerConnection',{value:function(){}})}catch(e){};typeof RTCPeerConnection"));
            try{stun.receive(new DatagramPacket(new byte[1024],1024));fail("Unexpected WebRTC STUN packet");}catch(SocketTimeoutException expected){}
            scenario.onActivity(a->call(a,"stopConnection"));
        }
    }
    @Test public void recoveryAndForegroundReturnDoNotResubmitPost()throws Exception {
        try(Socks proxy=new Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicInteger posts=new AtomicInteger();localMode(scenario,proxy.port());
            fixture(scenario,"<form method='post' action='"+ORIGIN+"/submitted'><input name='value' value='one'></form><script>document.forms[0].submit()</script>",posts);
            navigate(scenario,ORIGIN+"/form");await("exactly one form submission",()->posts.get()==1);
            await("submitted page",()->{try{return "true".equals(evaluate(scenario,"window.submitted===true"));}catch(Exception e){return false;}});
            scenario.onActivity(a->{field(a,"networkKey","old-test-network");call(a,"networkChanged");call(a,"cancelRecovery");call(a,"attemptRecovery");});
            ready(scenario);Thread.sleep(300);assertEquals(1,posts.get());
            scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(a->{assertFalse((Boolean)field(a,"networkAllowed"));assertTrue(web(a).getSettings().getBlockNetworkLoads());});
            ready(scenario);assertEquals(1,posts.get());
            scenario.onActivity(a->{
                AtomicInteger no=new AtomicInteger(),yes=new AtomicInteger();
                Handler handler=new Handler(Looper.getMainLooper(),m->{if(m.what==1)no.incrementAndGet();else yes.incrementAndGet();return true;});
                web(a).getWebViewClient().onFormResubmission(web(a),handler.obtainMessage(1),handler.obtainMessage(2));
                handler.post(()->{assertEquals(1,no.get());assertEquals(0,yes.get());});
            });
            scenario.onActivity(a->call(a,"stopConnection"));
        }
    }
    @Test public void newAddressWhileRouteIsPreparingLoadsOnlyLatestRequest()throws Exception {
        try(Socks proxy=new Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            localMode(scenario,proxy.port());
            scenario.onActivity(a->{
                invoke(a,"navigate",new Class<?>[]{String.class},"https://darsbridge-old.invalid/");
                invoke(a,"navigate",new Class<?>[]{String.class},"https://darsbridge-new.invalid/");
            });
            await("latest pending address",()->proxy.hosts.contains("darsbridge-new.invalid"));
            assertFalse(proxy.hosts.contains("darsbridge-old.invalid"));
            scenario.onActivity(a->call(a,"stopConnection"));
        }
    }
    @Test public void manualStopCancelsPendingRecoveryAndBlocksWorkers()throws Exception {
        try(Socks proxy=new Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            localMode(scenario,proxy.port());fixture(scenario,"<script>window.fixture=true</script>",new AtomicInteger());
            navigate(scenario,ORIGIN+"/page");ready(scenario);
            scenario.onActivity(a->{
                field(a,"networkKey","old-test-network");call(a,"networkChanged");
                assertTrue((Boolean)field(a,"recoveryScheduled"));
                call(a,"stopConnection");assertFalse((Boolean)field(a,"recoveryScheduled"));
                call(a,"attemptRecovery");assertFalse((Boolean)field(a,"connectionWanted"));assertFalse((Boolean)field(a,"networkAllowed"));
                assertTrue(web(a).getSettings().getBlockNetworkLoads());
                if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS))
                    assertTrue(androidx.webkit.ServiceWorkerControllerCompat.getInstance().getServiceWorkerWebSettings().getBlockNetworkLoads());
            });
        }
    }
    private static final class Trap implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        final AtomicInteger accepted=new AtomicInteger();volatile boolean closed;
        Trap()throws Exception{Thread t=new Thread(()->{while(!closed)try(Socket s=listener.accept()){accepted.incrementAndGet();}catch(IOException ignored){}});t.setDaemon(true);t.start();}
        int port(){return listener.getLocalPort();}
        public void close()throws IOException{closed=true;listener.close();}
    }
    static final class Socks implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        final Set<String> hosts=ConcurrentHashMap.newKeySet();
        final AtomicInteger destinations=new AtomicInteger(),domainRequests=new AtomicInteger();
        volatile boolean closed;
        Socks()throws Exception{
            Thread t=new Thread(()->{while(!closed)try{Socket s=listener.accept();Thread client=new Thread(()->handle(s));client.setDaemon(true);client.start();}catch(IOException ignored){}});
            t.setDaemon(true);t.start();
        }
        int port(){return listener.getLocalPort();}
        private void handle(Socket s){
            try(Socket socket=s){
                socket.setSoTimeout(2500);DataInputStream in=new DataInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();
                if(in.readUnsignedByte()!=5)return;int count=in.readUnsignedByte();byte[] methods=new byte[count];in.readFully(methods);
                out.write(new byte[]{5,0});out.flush();
                if(in.readUnsignedByte()!=5)return;in.readUnsignedByte();in.readUnsignedByte();int type=in.readUnsignedByte();
                String host;
                if(type==3){byte[] bytes=new byte[in.readUnsignedByte()];in.readFully(bytes);host=new String(bytes,StandardCharsets.US_ASCII);domainRequests.incrementAndGet();}
                else if(type==1){byte[] bytes=new byte[4];in.readFully(bytes);host=InetAddress.getByAddress(bytes).getHostAddress();}
                else if(type==4){byte[] bytes=new byte[16];in.readFully(bytes);host=InetAddress.getByAddress(bytes).getHostAddress();}
                else return;
                in.readUnsignedShort();hosts.add(host);destinations.incrementAndGet();
                // Refuse after recording CONNECT. No TLS interception and no direct target dial.
                out.write(new byte[]{5,5,0,1,0,0,0,0,0,0});out.flush();
            }catch(IOException ignored){}
        }
        public void close()throws IOException{closed=true;listener.close();}
    }
}
