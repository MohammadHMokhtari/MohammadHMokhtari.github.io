package com.darsbridge.app;

import android.content.*;
import android.os.SystemClock;
import android.webkit.*;
import android.view.View;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Real WebViews with controlled HTTPS responses; no external websites or production CA exceptions. */
@RunWith(AndroidJUnit4.class)
public class BrowserSessionTest {
    static final String ORIGIN="https://session.darsbridge.invalid";
    @Before public void resetBefore(){BrowserTestState.reset();}
    @After public void resetAfter(){BrowserTestState.reset();}
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private Object call(MainActivity activity,String name,Class<?>[] types,Object... args){
        try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private Object call(MainActivity activity,String name){return call(activity,name,new Class<?>[0]);}
    private Object field(MainActivity a,String name){try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new AssertionError(e);}}
    private void field(MainActivity a,String name,Object value){try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}catch(Exception e){throw new AssertionError(e);}}
    private WebView web(MainActivity a){return a.getWindow().getDecorView().findViewWithTag("web");}
    private BrowserStore store(MainActivity a){return (BrowserStore)field(a,"browser");}
    private void await(String what,BooleanSupplier condition)throws Exception{
        long end=SystemClock.elapsedRealtime()+12000;
        while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;Thread.sleep(40);}fail("Timed out: "+what);
    }
    private String js(ActivityScenario<MainActivity> scenario,String script)throws Exception{
        AtomicReference<String> result=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        scenario.onActivity(a->web(a).evaluateJavascript(script,v->{result.set(v);done.countDown();}));
        assertTrue(done.await(5,TimeUnit.SECONDS));return result.get();
    }
    private void select(MainActivity a,int index){
        Object tab=((List<?>)field(a,"tabs")).get(index);
        call(a,"selectTab",new Class<?>[]{tab.getClass()},tab);
    }
    private void close(MainActivity a,int index){Object tab=((List<?>)field(a,"tabs")).get(index);call(a,"closeTab",new Class<?>[]{tab.getClass()},tab);}
    private void fixture(MainActivity a,List<String> requests,AtomicInteger posts){
        WebView view=web(a);WebViewClient real=view.getWebViewClient();
        view.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                WebResourceResponse blocked=real.shouldInterceptRequest(v,r);if(blocked!=null)return blocked;
                if(!r.getUrl().toString().startsWith(ORIGIN+"/"))return blocked;
                if(r.isForMainFrame()){requests.add(r.getMethod()+" "+r.getUrl().getPath());if("POST".equals(r.getMethod()))posts.incrementAndGet();}
                String path=r.getUrl().getPath();
                String html="<html><head><title>صفحه "+path+"</title></head><body><input id='draft'><script>window.path="+org.json.JSONObject.quote(path)+"</script></body></html>";
                if("/form".equals(path))html="<form method='post' action='"+ORIGIN+"/submitted'><input name='body' value='PRIVATE-FORM-BODY'></form><script>document.forms[0].submit()</script>";
                return new WebResourceResponse("text/html","UTF-8",200,"OK",Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return real.shouldOverrideUrlLoading(v,r);}
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap b){real.onPageStarted(v,url,b);}
            @Override public void onPageFinished(WebView v,String url){real.onPageFinished(v,url);}
            @Override public void doUpdateVisitedHistory(WebView v,String url,boolean reload){real.doUpdateVisitedHistory(v,url,reload);}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){real.onReceivedError(v,r,e);}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){real.onReceivedHttpError(v,r,e);}
            @Override public void onFormResubmission(WebView v,android.os.Message dont,android.os.Message resend){real.onFormResubmission(v,dont,resend);}
        });
    }
    private void local(MainActivity a,int port){field(a,"mode",ConnectionPolicy.Mode.LOCAL);field(a,"socksPort",port);}
    private void open(MainActivity a,String path){call(a,"navigate",new Class<?>[]{String.class},ORIGIN+path);}
    private void loaded(ActivityScenario<MainActivity> scenario,String path)throws Exception{
        await("page "+path,()->{try{return path.equals(new org.json.JSONTokener(js(scenario,"window.path")).nextValue());}catch(Exception e){return false;}});
        await("history saved",()->new BrowserStore(context()).visits.stream().anyMatch(v->v.url.equals(ORIGIN+path)));
    }
    @Test public void independentTabsKeepDomAndBackStacksAndUseSameProxy()throws Exception{
        List<String> requests=new CopyOnWriteArrayList<>();AtomicInteger posts=new AtomicInteger();AtomicReference<WebView> first=new AtomicReference<>();
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{local(a,proxy.port());fixture(a,requests,posts);first.set(web(a));open(a,"/one");});loaded(scenario,"/one");
            js(scenario,"document.querySelector('#draft').value='keep-one';true");
            scenario.onActivity(a->{call(a,"newTab");assertEquals(2,store(a).tabs.size());assertTrue(first.get().getSettings().getBlockNetworkLoads());assertNull(web(a).getUrl());fixture(a,requests,posts);open(a,"/two");});loaded(scenario,"/two");
            scenario.onActivity(a->open(a,"/three"));loaded(scenario,"/three");
            scenario.onActivity(a->{select(a,0);assertSame(first.get(),web(a));assertFalse(web(a).canGoBack());});
            assertEquals("\"keep-one\"",js(scenario,"document.querySelector('#draft').value"));
            scenario.onActivity(a->{select(a,1);assertTrue(web(a).canGoBack());call(a,"history",new Class<?>[]{int.class},-1);});loaded(scenario,"/two");
            js(scenario,"fetch('https://tab-proxy-only.invalid/').catch(()=>{});true");await("same SOCKS route",()->proxy.hosts.contains("tab-proxy-only.invalid"));
            scenario.onActivity(a->call(a,"stopConnection"));
        }
    }
    @Test public void closeTabCancelsItsPendingNavigationAndLastCloseCreatesBlankTab()throws Exception{
        List<String> requests=new CopyOnWriteArrayList<>();
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{local(a,proxy.port());fixture(a,requests,new AtomicInteger());open(a,"/closed-before-ready");call(a,"newTab");close(a,0);});
            Thread.sleep(1800);
            scenario.onActivity(a->{assertEquals(1,store(a).tabs.size());assertNull(web(a).getUrl());assertFalse(requests.contains("GET /closed-before-ready"));
                close(a,0);assertEquals(1,store(a).tabs.size());assertTrue(store(a).tabs.get(0).url.isEmpty());assertNull(web(a).getUrl());call(a,"stopConnection");});
        }
    }
    @Test public void reopeningAppRestoresTabsAndReconnectsRequestedLocalRoute()throws Exception{
        List<String> requests=new CopyOnWriteArrayList<>();
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks()){
            context().getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE).edit().putString("mode","LOCAL").putInt("port",proxy.port()).putBoolean("autoReconnect",true).commit();
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
                scenario.onActivity(a->{fixture(a,requests,new AtomicInteger());open(a,"/saved");});loaded(scenario,"/saved");
                scenario.onActivity(a->{call(a,"newTab");fixture(a,requests,new AtomicInteger());open(a,"/active");});loaded(scenario,"/active");
            }
            assertTrue(context().getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE).getBoolean("connectionWanted",false));
            try(ActivityScenario<MainActivity> reopened=ActivityScenario.launch(MainActivity.class)){
                // The recording SOCKS server rejects CONNECT after observing it. A restored
                // hostname proves the route applied and the saved GET was attempted automatically.
                await("restored active GET via SOCKS",()->proxy.hosts.contains("session.darsbridge.invalid"));
                reopened.onActivity(a->{assertEquals(2,store(a).tabs.size());assertEquals(ORIGIN+"/active",store(a).find(store(a).selected).url);assertTrue((Boolean)field(a,"connectionWanted"));call(a,"stopConnection");});
            }
        }
    }
    @Test public void manualStopAndDisabledAutoReconnectPersistAcrossFullRelaunch()throws Exception{
        for(boolean enabled:Arrays.asList(true,false)){
            context().getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE).edit().putBoolean("autoReconnect",enabled).putBoolean("connectionWanted",!enabled).commit();
            if(enabled)try(ActivityScenario<MainActivity> first=ActivityScenario.launch(MainActivity.class)){first.onActivity(a->call(a,"stopConnection"));}
            try(ActivityScenario<MainActivity> reopened=ActivityScenario.launch(MainActivity.class)){
                reopened.onActivity(a->{assertFalse((Boolean)field(a,"connectionWanted"));assertFalse((Boolean)field(a,"networkAllowed"));assertTrue(web(a).getSettings().getBlockNetworkLoads());assertNull(web(a).getUrl());});
            }
        }
    }
    @Test public void postIsNotRestoredAndItsBodyNeverEntersSessionFile()throws Exception{
        AtomicInteger posts=new AtomicInteger();List<String> requests=new CopyOnWriteArrayList<>();
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks()){
            context().getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE).edit().putString("mode","LOCAL").putInt("port",proxy.port()).putBoolean("autoReconnect",true).commit();
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
                scenario.onActivity(a->{fixture(a,requests,posts);open(a,"/form");});loaded(scenario,"/submitted");assertEquals(1,posts.get());
            }
            BrowserStore saved=new BrowserStore(context());assertFalse(saved.find(saved.selected).safeResume);
            String file=new String(Files.readAllBytes(new File(context().getFilesDir(),BrowserStore.FILE_NAME).toPath()),StandardCharsets.UTF_8);
            assertFalse(file.contains("PRIVATE-FORM-BODY"));assertFalse(file.contains("<html"));
            int destinationsBefore=proxy.destinations.get();
            try(ActivityScenario<MainActivity> reopened=ActivityScenario.launch(MainActivity.class)){
                AtomicBoolean allowed=new AtomicBoolean();await("route restored without form",()->{reopened.onActivity(a->allowed.set((Boolean)field(a,"networkAllowed")));return allowed.get();});
                reopened.onActivity(a->{assertNull(web(a).getUrl());assertEquals(View.VISIBLE,a.getWindow().getDecorView().findViewWithTag("continue-tab").getVisibility());call(a,"stopConnection");});
                assertEquals(destinationsBefore,proxy.destinations.get());assertEquals(1,posts.get());
            }
        }
    }
    @Test public void chatPushStateIsRetainedAndHistoryIsDeduplicatedAndBounded()throws Exception{
        List<String> requests=new CopyOnWriteArrayList<>();
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{local(a,proxy.port());fixture(a,requests,new AtomicInteger());open(a,"/chat/start");});loaded(scenario,"/chat/start");
            js(scenario,"history.pushState({},'', '/chat/previous-123');true");
            await("single-page chat address",()->new BrowserStore(context()).visits.stream().anyMatch(v->v.url.endsWith("/chat/previous-123")));
            scenario.onActivity(a->{
                BrowserStore b=store(a);for(int i=0;i<220;i++)b.visit(ORIGIN+"/history/"+i,"جست‌وجو "+i,i);
                b.visit(ORIGIN+"/history/219","آخرین عنوان",999);assertEquals(200,b.visits.size());assertEquals("آخرین عنوان",b.visits.get(0).title);
                call(a,"saveSession");call(a,"showBrowsingHistory");
            });
            UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            java.util.List<androidx.test.uiautomator.UiObject2> edits=device.findObjects(androidx.test.uiautomator.By.clazz("android.widget.EditText"));
            assertFalse(edits.isEmpty());androidx.test.uiautomator.UiObject2 input=edits.get(edits.size()-1);input.setText("219");
            androidx.test.uiautomator.UiObject2 item=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.textContains("آخرین عنوان")),5000);
            assertNotNull(item);device.executeShellCommand("screencap -p /sdcard/Download/DarsBridge-history.png");
            item.click();androidx.test.uiautomator.UiObject2 remove=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("حذف این مورد")),5000);
            assertNotNull(remove);remove.click();await("delete history row",()->new BrowserStore(context()).visits.size()==199);
            scenario.onActivity(a->call(a,"stopConnection"));
        }
    }
    @Test public void tabLimitAndClearingHistoryKeepBookmarksAndOfflinePages()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                for(int i=0;i<10;i++)call(a,"newTab");assertEquals(8,store(a).tabs.size());
                store(a).visit(ORIGIN+"/example","نمونه",1);call(a,"saveSession");call(a,"showTabs");
            });
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("screencap -p /sdcard/Download/DarsBridge-tabs.png");
        }
        BrowserStore data=new BrowserStore(context());assertEquals(8,data.tabs.size());assertEquals(1,data.visits.size());
        String pages=new PageStore(context()).read().toString();Set<String> marks=new HashSet<>(context().getSharedPreferences(MainActivity.PREFS_NAME,0).getStringSet("marks",Collections.emptySet()));
        data.clearHistory();data.save();assertEquals(0,new BrowserStore(context()).visits.size());assertEquals(8,new BrowserStore(context()).tabs.size());
        assertEquals(pages,new PageStore(context()).read().toString());assertEquals(marks,context().getSharedPreferences(MainActivity.PREFS_NAME,0).getStringSet("marks",Collections.emptySet()));
    }
    @Test public void corruptOrExecutableSessionCannotNavigate()throws Exception{
        Files.write(new File(context().getFilesDir(),BrowserStore.FILE_NAME).toPath(),"{broken".getBytes(StandardCharsets.UTF_8));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{assertTrue(store(a).readFailed);assertEquals(1,store(a).tabs.size());assertNull(web(a).getUrl());assertTrue(web(a).getSettings().getBlockNetworkLoads());});
        }
        BrowserStore b=new BrowserStore(context());b.tabs.get(0).url="javascript:alert(1)";
        try{b.save();fail("Executable URL saved");}catch(IllegalArgumentException expected){}
    }
}
