package com.darsbridge.app;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.View;
import android.webkit.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import androidx.lifecycle.Lifecycle;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LinkMenuTest {
    static final String SOURCE="https://context.darsbridge.invalid/source";
    static final String TARGET="https://target.darsbridge.invalid/article?q=one#part";
    static final String IMAGE_TARGET="https://image-target.darsbridge.invalid/article";
    @Before public void before(){BrowserTestState.reset();}
    @After public void after(){BrowserTestState.reset();}
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private UiDevice device(){return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());}
    private Object field(MainActivity a,String name){try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new AssertionError(e);}}
    private void field(MainActivity a,String name,Object value){try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}catch(Exception e){throw new AssertionError(e);}}
    private Object call(MainActivity a,String name,Class<?>[] types,Object... args){try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(a,args);}catch(Exception e){throw new AssertionError(e);}}
    private Object call(MainActivity a,String name){return call(a,name,new Class<?>[0]);}
    private WebView web(MainActivity a){return a.getWindow().getDecorView().findViewWithTag("web");}
    private BrowserStore store(MainActivity a){return (BrowserStore)field(a,"browser");}
    private void select(MainActivity a,int index){Object tab=((List<?>)field(a,"tabs")).get(index);call(a,"selectTab",new Class<?>[]{tab.getClass()},tab);}
    private void await(String label,BooleanSupplier ready)throws Exception{long end=SystemClock.elapsedRealtime()+12000;while(SystemClock.elapsedRealtime()<end){if(ready.getAsBoolean())return;Thread.sleep(40);}fail("Timed out: "+label);}
    private String js(ActivityScenario<MainActivity> scenario,String script)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();scenario.onActivity(a->web(a).evaluateJavascript(script,result->{value.set(result);done.countDown();}));assertTrue(done.await(5,TimeUnit.SECONDS));return value.get();
    }
    private void fixture(MainActivity a,int port){
        field(a,"mode",ConnectionPolicy.Mode.LOCAL);field(a,"socksPort",port);
        WebView w=web(a);WebViewClient real=w.getWebViewClient();
        w.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                WebResourceResponse blocked=real.shouldInterceptRequest(v,r);if(blocked!=null)return blocked;
                if(!"context.darsbridge.invalid".equals(r.getUrl().getHost()))return null;
                String png="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jR1sAAAAASUVORK5CYII=";
                String html="<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>نتایج جست‌وجوی آزمایشی</title><style>body{font:20px sans-serif;padding:12px}a{display:block;padding:16px;margin:8px 0}img{width:160px;height:60px;background:#cfe7df}#selection{padding:20px 4px;user-select:text}</style></head><body>"+
                    "<a id='plain' href='"+TARGET+"'>نتیجهٔ آزمایشی</a><a id='image' href='"+IMAGE_TARGET+"'><img id='thumb' alt='پیوند تصویر' src='"+png+"'></a>"+
                    "<p id='selection'><span id='word'>Selectable</span> ordinary text for copying</p><input id='draft' value='keep-source'><script>window.fixture=true</script></body></html>";
                return new WebResourceResponse("text/html","UTF-8",200,"OK",Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return real.shouldOverrideUrlLoading(v,r);}
            @Override public void onPageStarted(WebView v,String u,android.graphics.Bitmap b){real.onPageStarted(v,u,b);}
            @Override public void onPageFinished(WebView v,String u){real.onPageFinished(v,u);}
            @Override public void doUpdateVisitedHistory(WebView v,String u,boolean reload){real.doUpdateVisitedHistory(v,u,reload);}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){real.onReceivedError(v,r,e);}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){real.onReceivedHttpError(v,r,e);}
            @Override public void onFormResubmission(WebView v,android.os.Message no,android.os.Message yes){real.onFormResubmission(v,no,yes);}
        });
        call(a,"navigate",new Class<?>[]{String.class},SOURCE);
    }
    private void loaded(ActivityScenario<MainActivity> scenario)throws Exception{await("controlled page",()->{try{return "true".equals(js(scenario,"window.fixture===true"));}catch(Exception e){return false;}});}
    private void touch(ActivityScenario<MainActivity> scenario,String id,boolean longPress)throws Exception{
        Object raw=new JSONTokener(js(scenario,"(function(){var r=document.getElementById('"+id+"').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}})()" )).nextValue();
        JSONObject point=(JSONObject)raw;int[] xy=new int[2];float[] density=new float[1];scenario.onActivity(a->{web(a).getLocationOnScreen(xy);density[0]=a.getResources().getDisplayMetrics().density;});
        int x=xy[0]+Math.round((float)point.getDouble("x")*density[0]),y=xy[1]+Math.round((float)point.getDouble("y")*density[0]);
        if(longPress)assertTrue(device().swipe(x,y,x,y,180));else assertTrue(device().click(x,y));
    }
    private void action(String text){
        assertNotNull(device().wait(Until.findObject(By.text(text)),5000));
        String target=context().getPackageName();
        for(android.view.accessibility.AccessibilityWindowInfo window:InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows()){
            android.view.accessibility.AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(text.contentEquals(node.getText()) && target.contentEquals(node.getPackageName())){
                assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));return;
            }
        }fail("Action missing: "+text);
    }
    @Test public void longPressOpensDestinationInNewTabAndKeepsSearchPage()throws Exception{
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<WebView> original=new AtomicReference<>();scenario.onActivity(a->{fixture(a,proxy.port());original.set(web(a));});loaded(scenario);
            touch(scenario,"plain",true);assertNotNull(device().wait(Until.findObject(By.text("باز کردن در تب تازه")),5000));device().executeShellCommand("screencap -p /sdcard/Download/DarsBridge-link-menu.png");
            action("باز کردن در تب تازه");await("new tab through existing SOCKS",()->proxy.hosts.contains("target.darsbridge.invalid"));
            scenario.onActivity(a->{assertEquals(2,store(a).tabs.size());assertNotSame(original.get(),web(a));select(a,0);assertSame(original.get(),web(a));assertEquals(SOURCE,web(a).getUrl());});
            assertEquals("\"keep-source\"",js(scenario,"document.querySelector('#draft').value"));scenario.onActivity(a->call(a,"stopConnection"));
        }
    }
    @Test public void backgroundTabIsLazyAndTabLimitKeepsCurrentSearch()throws Exception{
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->fixture(a,proxy.port()));loaded(scenario);touch(scenario,"plain",true);action("افزودن به تب پس‌زمینه");
            Thread.sleep(250);assertFalse(proxy.hosts.contains("target.darsbridge.invalid"));
            scenario.onActivity(a->{assertEquals(2,store(a).tabs.size());assertEquals(SOURCE,web(a).getUrl());assertEquals(store(a).tabs.get(0).id,store(a).selected);
                for(int i=0;i<8;i++)call(a,"openLinkTab",new Class<?>[]{String.class,boolean.class},TARGET,true);
                assertEquals(8,store(a).tabs.size());assertEquals(SOURCE,web(a).getUrl());select(a,1);});
            await("background GET only on selection",()->proxy.hosts.contains("target.darsbridge.invalid"));scenario.onActivity(a->call(a,"stopConnection"));
        }
    }
    @Test public void imageAnchorCopiesHrefRatherThanImageSourceAndOpeningMenuDoesNotCopy()throws Exception{
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{fixture(a,proxy.port());((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("test","sentinel"));});loaded(scenario);
            touch(scenario,"thumb",true);assertNotNull(device().wait(Until.findObject(By.text("کپی لینک")),5000));
            scenario.onActivity(a->{assertEquals(WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,web(a).getHitTestResult().getType());assertEquals("sentinel",((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString());});
            CountDownLatch copied=new CountDownLatch(1);ClipboardManager.OnPrimaryClipChangedListener listener=copied::countDown;
            scenario.onActivity(a->((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).addPrimaryClipChangedListener(listener));action("کپی لینک");assertTrue(copied.await(5,TimeUnit.SECONDS));
            scenario.onActivity(a->{ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);clipboard.removePrimaryClipChangedListener(listener);assertEquals(IMAGE_TARGET,clipboard.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(1,store(a).tabs.size());call(a,"stopConnection");});
            assertFalse(proxy.hosts.contains("image-target.darsbridge.invalid"));
        }
    }
    @Test public void ordinaryTapStillOpensInCurrentTab()throws Exception{
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->fixture(a,proxy.port()));loaded(scenario);touch(scenario,"plain",false);await("normal current-tab link",()->proxy.hosts.contains("target.darsbridge.invalid"));
            scenario.onActivity(a->{assertEquals(1,store(a).tabs.size());assertNull(field(a,"linkDialog"));call(a,"stopConnection");});
        }
    }
    @Test public void ordinaryTextRetainsNativeSelection()throws Exception{
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->fixture(a,proxy.port()));loaded(scenario);touch(scenario,"word",true);
            scenario.onActivity(a->android.util.Log.i("DarsBridgeSelectionTest","Native hit type="+web(a).getHitTestResult().getType()));
            await("native text selection",()->{try{Object value=new JSONTokener(js(scenario,"window.getSelection().toString()" )).nextValue();return value instanceof String && !((String)value).isEmpty();}catch(Exception e){return false;}});
            device().executeShellCommand("screencap -p /sdcard/Download/DarsBridge-selection.png");
            scenario.onActivity(a->{assertNull(field(a,"linkDialog"));assertEquals(1,store(a).tabs.size());call(a,"stopConnection");});
        }
    }
    @Test public void contextMenuIsDismissedOnTabChangeAndPause()throws Exception{
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->fixture(a,proxy.port()));loaded(scenario);touch(scenario,"plain",true);assertNotNull(device().wait(Until.findObject(By.text("باز کردن در تب تازه")),5000));
            scenario.onActivity(a->{call(a,"newTab");assertNull(field(a,"linkDialog"));select(a,0);});touch(scenario,"plain",true);assertNotNull(device().wait(Until.findObject(By.text("کپی لینک")),5000));
            scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(a->{assertNull(field(a,"linkDialog"));assertEquals(2,store(a).tabs.size());call(a,"stopConnection");});assertFalse(proxy.hosts.contains("target.darsbridge.invalid"));
        }
    }
    @Test public void shareActionPassesOnlyLinkToChooserWithoutSending()throws Exception{
        AtomicReference<Intent> shared=new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent){
                if(Intent.ACTION_CHOOSER.equals(intent.getAction())){shared.set((Intent)intent.getParcelableExtra(Intent.EXTRA_INTENT));return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;
            }
        };
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor);
        try(NetworkSafetyTest.Socks proxy=new NetworkSafetyTest.Socks();ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->fixture(a,proxy.port()));loaded(scenario);touch(scenario,"plain",true);action("اشتراک لینک");await("chooser intercepted",()->shared.get()!=null);
            assertEquals(Intent.ACTION_SEND,shared.get().getAction());assertEquals("text/plain",shared.get().getType());assertEquals(TARGET,shared.get().getStringExtra(Intent.EXTRA_TEXT));assertEquals(Collections.singleton(Intent.EXTRA_TEXT),shared.get().getExtras().keySet());
            scenario.onActivity(a->call(a,"stopConnection"));
        }finally{InstrumentationRegistry.getInstrumentation().removeMonitor(monitor);}
    }
    @Test public void compactToolbarAndOverflowWorkWithoutStartingConnection()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{View root=a.getWindow().getDecorView();assertEquals(View.GONE,root.findViewWithTag("connection-panel").getVisibility());
                assertTrue(root.findViewWithTag("connection-details").performClick());assertEquals(View.VISIBLE,root.findViewWithTag("connection-panel").getVisibility());
                assertTrue(root.findViewWithTag("connection-details").performClick());assertEquals(View.GONE,root.findViewWithTag("connection-panel").getVisibility());assertNull(web(a).getUrl());assertTrue(web(a).getSettings().getBlockNetworkLoads());});
            device().executeShellCommand("screencap -p /sdcard/Download/DarsBridge-toolbar.png");
            scenario.onActivity(a->a.getWindow().getDecorView().findViewWithTag("menu").performClick());assertNotNull(device().wait(Until.findObject(By.text("تب تازه")),5000));assertNotNull(device().findObject(By.text("تاریخچهٔ مرور")));
        }
    }
}
