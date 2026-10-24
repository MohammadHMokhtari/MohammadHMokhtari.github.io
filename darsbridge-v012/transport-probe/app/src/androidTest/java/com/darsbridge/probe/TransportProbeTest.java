package com.darsbridge.probe;

import android.content.Context;
import android.os.Build;
import IPtProxy.Controller;
import IPtProxy.IPtProxy;
import IPtProxy.OnTransportEvents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Exercises the shipped AAR, not a mock. No bridge address or SOCKS CONNECT is sent. */
@RunWith(AndroidJUnit4.class)
public class TransportProbeTest {
    private static Controller controller;
    private static final JSONArray results=new JSONArray();
    private static Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}

    @BeforeClass public static void createSingleController()throws Exception{
        File state=new File(context().getCacheDir(),"transport-probe");
        assertTrue(state.mkdirs() || state.isDirectory());
        controller=new Controller(state.getAbsolutePath(),false,false,"ERROR",new OnTransportEvents(){
            @Override public void connected(String name){}
            @Override public void error(String name,Exception error){}
            @Override public void stopped(String name,Exception error){}
        });
    }
    @After public void stopAll(){
        controller.stop(IPtProxy.Obfs4);controller.stop(IPtProxy.Webtunnel);
    }
    @AfterClass public static void writeActualProof()throws Exception{
        JSONObject report=new JSONObject().put("fixture_application_id",context().getPackageName())
            .put("dependency","com.netzarchitekten:IPtProxy:5.7.0")
            .put("lyrebird_version",IPtProxy.lyrebirdVersion())
            .put("android_api",Build.VERSION.SDK_INT).put("runtime_abi",Build.SUPPORTED_ABIS[0])
            .put("controller_instances",1).put("results",results)
            .put("all_assertions_reached",results.length()==3)
            .put("tor_end_to_end_tested",false).put("production_browser_modified",false);
        Files.write(new File(context().getFilesDir(),"transport-probe.json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8));
    }
    private int start(String transport)throws Exception{
        assertEquals(0,controller.port(transport));
        controller.start(transport,"");
        long port=controller.port(transport);assertTrue(port>0 && port<=65535);
        assertEquals("127.0.0.1:"+port,controller.localAddress(transport));
        return (int)port;
    }
    private void negotiate(int port)throws Exception{
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1",port),2000);socket.setSoTimeout(2000);
            // Only negotiate a SOCKS method; neither authentication nor CONNECT is sent.
            socket.getOutputStream().write(new byte[]{5,2,0,2});socket.getOutputStream().flush();
            assertEquals("SOCKS protocol",5,socket.getInputStream().read());
            int method=socket.getInputStream().read();
            assertTrue("Listener must negotiate SOCKS5, not merely accept TCP",method==0 || method==2);
        }
    }
    private void stopAndCheck(String transport,int port)throws Exception{
        controller.stop(transport);
        assertEquals(0,controller.port(transport));assertEquals("",controller.localAddress(transport));
        try(Socket socket=new Socket()){
            try{socket.connect(new InetSocketAddress("127.0.0.1",port),1000);fail("Stopped listener still accepts connections");}
            catch(IOException expected){}
        }
    }
    private void record(String transport,boolean restart,boolean unknownRejected)throws Exception{
        results.put(new JSONObject().put("transport",transport).put("loopback_only",true)
            .put("socks5_negotiated",true).put("listener_closed_on_stop",true)
            .put("restart_verified",restart).put("unknown_transport_rejected",unknownRejected)
            .put("socks_connect_requests_sent",0).put("passed",true));
    }
    @Test public void obfs4StartsNegotiatesAndCloses()throws Exception{
        int port=start(IPtProxy.Obfs4);negotiate(port);stopAndCheck(IPtProxy.Obfs4,port);
        record("obfs4",false,false);
    }
    @Test public void webtunnelStartsNegotiatesAndCloses()throws Exception{
        int port=start(IPtProxy.Webtunnel);negotiate(port);stopAndCheck(IPtProxy.Webtunnel,port);
        record("webtunnel",false,false);
    }
    @Test public void unknownTransportCannotReplaceListenerAndRestartWorks()throws Exception{
        int first=start(IPtProxy.Obfs4);
        try{controller.start("darsbridge-unknown-transport","");fail("Unknown transport was accepted");}
        catch(Exception expected){}
        assertEquals(0,controller.port("darsbridge-unknown-transport"));
        assertEquals(first,controller.port(IPtProxy.Obfs4));negotiate(first);stopAndCheck(IPtProxy.Obfs4,first);
        int second=start(IPtProxy.Obfs4);negotiate(second);stopAndCheck(IPtProxy.Obfs4,second);
        record("obfs4-restart-and-invalid-method",true,true);
    }
}
