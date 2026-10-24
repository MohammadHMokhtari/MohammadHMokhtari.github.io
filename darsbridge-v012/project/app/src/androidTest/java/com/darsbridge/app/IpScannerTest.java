package com.darsbridge.app;

import android.content.*;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Local TLS fixtures only. No public IP is contacted by these tests. */
@RunWith(AndroidJUnit4.class)
public class IpScannerTest {
    private static final String HOST="scan.darsbridge.invalid";
    private static final class Observed implements IpScanner.Listener {
        final List<IpScanner.Result> results=new CopyOnWriteArrayList<>();final CountDownLatch done=new CountDownLatch(1);
        final AtomicInteger finishes=new AtomicInteger();volatile IpScanner.End end;
        @Override public void result(IpScanner.Result r,int completed,int total){results.add(r);}
        @Override public void finished(IpScanner.End why,int completed,int total){end=why;finishes.incrementAndGet();done.countDown();}
        void await()throws Exception{assertTrue("Scanner did not finish",done.await(10,TimeUnit.SECONDS));assertEquals(1,finishes.get());}
    }
    private static KeyStore store()throws Exception{
        KeyStore ks=KeyStore.getInstance("PKCS12");
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("scan-test.p12")){ks.load(in,"android".toCharArray());}return ks;
    }
    private static SSLSocketFactory trusted()throws Exception{
        KeyStore ca=KeyStore.getInstance(KeyStore.getDefaultType());ca.load(null,null);ca.setCertificateEntry("local-test",store().getCertificate("local-https-fixture"));
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(ca);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,tm.getTrustManagers(),null);return tls.getSocketFactory();
    }
    private static IpScanner.Connector mapped(int port,AtomicBoolean current){
        return new IpScanner.Connector(){
            @Override public boolean current(){return current.get();}
            @Override public void connect(Socket socket,InetAddress target,int requestedPort,int timeout)throws IOException{
                assertEquals("1.1.1.1",target.getHostAddress());assertEquals(443,requestedPort);
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),timeout);
            }
        };
    }
    private static final class Https implements AutoCloseable {
        final SSLServerSocket server;final ExecutorService pool=Executors.newCachedThreadPool();volatile String request="";
        Https(boolean validHttp)throws Exception{
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store(),"android".toCharArray());
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),null,null);
            server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0,8,InetAddress.getLoopbackAddress());
            pool.submit(()->{
                while(!server.isClosed())try{SSLSocket socket=(SSLSocket)server.accept();pool.submit(()->{
                    try(SSLSocket s=socket){s.setSoTimeout(4000);s.startHandshake();InputStream in=s.getInputStream();StringBuilder head=new StringBuilder();
                        while(head.length()<2048 && !head.toString().endsWith("\r\n\r\n")){int b=in.read();if(b<0)return;head.append((char)b);}request=head.toString();
                        s.getOutputStream().write((validHttp?"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n":"NOT HTTP\r\n").getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();
                    }catch(IOException ignored){}
                });}catch(IOException e){return;}
            });
        }
        int port(){return server.getLocalPort();}
        @Override public void close()throws Exception{server.close();pool.shutdownNow();}
    }
    private static final class Stall implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress());final ExecutorService worker=Executors.newSingleThreadExecutor();
        final CountDownLatch accepted=new CountDownLatch(1),eof=new CountDownLatch(1);volatile Socket client;
        Stall(boolean plaintext)throws IOException{worker.submit(()->{
            try(Socket socket=server.accept()){client=socket;accepted.countDown();socket.setSoTimeout(8000);
                if(plaintext){socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();return;}
                while(socket.getInputStream().read()!=-1){}eof.countDown();
            }catch(IOException ignored){eof.countDown();}
        });}
        int port(){return server.getLocalPort();}
        @Override public void close()throws Exception{server.close();if(client!=null)client.close();worker.shutdownNow();}
    }
    @Test public void validHttpsRequiresIdentityAndReturnsOnlyAStatus()throws Exception{
        try(Https server=new Https(true)){
            Observed out=new Observed();try(IpScanner scanner=new IpScanner(mapped(server.port(),new AtomicBoolean(true)),out,trusted())){
                scanner.start(HOST,Collections.singletonList("1.1.1.1"));out.await();assertEquals(1,out.results.size());
                assertEquals(IpScanner.Status.HTTPS_RESPONSE,out.results.get(0).status);assertEquals(200,out.results.get(0).http);
                assertTrue(server.request.startsWith("HEAD / HTTP/1.1\r\n"));assertTrue(server.request.contains("Host: "+HOST+"\r\n"));assertFalse(server.request.contains("Cookie:"));
            }
        }
    }
    @Test public void wrongHostnameIsRejectedEvenWithATrustedCertificate()throws Exception{
        try(Https server=new Https(true)){
            Observed out=new Observed();try(IpScanner scanner=new IpScanner(mapped(server.port(),new AtomicBoolean(true)),out,trusted())){
                scanner.start("wrong.darsbridge.invalid",Collections.singletonList("1.1.1.1"));out.await();assertEquals(IpScanner.Status.TLS_FAILED,out.results.get(0).status);assertEquals("",server.request);
            }
        }
    }
    @Test public void productTrustRejectsThePrivateFixtureCertificate()throws Exception{
        try(Https server=new Https(true)){
            Observed out=new Observed();try(IpScanner scanner=new IpScanner(mapped(server.port(),new AtomicBoolean(true)),out)){
                scanner.start(HOST,Collections.singletonList("1.1.1.1"));out.await();assertEquals(IpScanner.Status.TLS_FAILED,out.results.get(0).status);
            }
        }
    }
    @Test public void openTcpWithPlainHttpIsNotAUsableHttpsResult()throws Exception{
        try(Stall server=new Stall(true)){
            Observed out=new Observed();try(IpScanner scanner=new IpScanner(mapped(server.port(),new AtomicBoolean(true)),out,trusted())){
                scanner.start(HOST,Collections.singletonList("1.1.1.1"));out.await();assertEquals(IpScanner.Status.TLS_FAILED,out.results.get(0).status);
            }
        }
    }
    @Test public void tlsWithoutAnHttpResponseDoesNotCountAsSuccess()throws Exception{
        try(Https server=new Https(false)){
            Observed out=new Observed();try(IpScanner scanner=new IpScanner(mapped(server.port(),new AtomicBoolean(true)),out,trusted())){
                scanner.start(HOST,Collections.singletonList("1.1.1.1"));out.await();assertEquals(IpScanner.Status.TLS_NO_HTTP,out.results.get(0).status);
            }
        }
    }
    @Test public void cancellationClosesTheActualSocketAndFinishesOnce()throws Exception{
        try(Stall server=new Stall(false)){
            Observed out=new Observed();try(IpScanner scanner=new IpScanner(mapped(server.port(),new AtomicBoolean(true)),out,trusted())){
                scanner.start(HOST,Collections.singletonList("1.1.1.1"));assertTrue(server.accepted.await(5,TimeUnit.SECONDS));scanner.close();out.await();
                assertEquals(IpScanner.End.CANCELLED,out.end);assertTrue(server.eof.await(4,TimeUnit.SECONDS));assertTrue(out.results.isEmpty());
            }
        }
    }
    @Test public void networkChangeCancelsAndDiscardsLateResults()throws Exception{
        try(Stall server=new Stall(false)){
            AtomicBoolean current=new AtomicBoolean(true);Observed out=new Observed();
            try(IpScanner scanner=new IpScanner(mapped(server.port(),current),out,trusted())){
                scanner.start(HOST,Collections.singletonList("1.1.1.1"));assertTrue(server.accepted.await(5,TimeUnit.SECONDS));current.set(false);out.await();
                assertEquals(IpScanner.End.NETWORK_CHANGED,out.end);assertTrue(out.results.isEmpty());assertTrue(server.eof.await(4,TimeUnit.SECONDS));
            }
        }
    }
    @Test public void openingScannerDoesNotStartItOrChangeBrowserRoute()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<IpScanDialog> opened=new AtomicReference<>();AtomicReference<Object> selected=new AtomicReference<>();
            scenario.onActivity(a->{
                try{
                    java.lang.reflect.Field mode=MainActivity.class.getDeclaredField("mode");mode.setAccessible(true);selected.set(mode.get(a));
                    java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("showIpScanner");method.setAccessible(true);method.invoke(a);
                    java.lang.reflect.Field field=MainActivity.class.getDeclaredField("ipScanDialog");field.setAccessible(true);opened.set((IpScanDialog)field.get(a));
                    assertFalse(opened.get().isRunning());assertEquals(selected.get(),mode.get(a));
                    WebView web=a.getWindow().getDecorView().findViewWithTag("web");assertNull(web.getUrl());assertTrue(web.getSettings().getBlockNetworkLoads());
                }catch(Exception e){throw new AssertionError(e);}
            });
            UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());assertTrue(device.wait(Until.hasObject(By.text("شروع جست‌وجوی مستقیم")),10000));
            device.executeShellCommand("screencap -p /sdcard/Download/DarsBridge-ip-scan.png");
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);assertFalse(opened.get().isRunning());
        }
    }
}
