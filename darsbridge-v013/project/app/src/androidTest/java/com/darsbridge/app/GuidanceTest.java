package com.darsbridge.app;

import android.content.*;
import android.webkit.WebView;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GuidanceTest {
    @org.junit.Before public void resetBrowserBefore(){BrowserTestState.reset();}
    @org.junit.After public void resetBrowserAfter(){BrowserTestState.reset();}
    private void invoke(MainActivity activity,String name){
        try{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private UiDevice device(){return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());}
    private UiObject2 waitFor(String text){
        UiObject2 item=device().wait(Until.findObject(By.text(text)),10000);assertNotNull(text,item);return item;
    }
    private void assertOffline(MainActivity activity){
        WebView web=activity.getWindow().getDecorView().findViewWithTag("web");
        assertNull(web.getUrl());assertTrue(web.getSettings().getBlockNetworkLoads());
    }
    private void accessibilityClick(String text){
        String target=InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        for(android.view.accessibility.AccessibilityWindowInfo window:InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows()){
            android.view.accessibility.AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)){
                if(text.contentEquals(node.getText()) && target.contentEquals(node.getPackageName())){
                    assertTrue("App button must accept explicit accessibility click",node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));return;
                }
            }
        }
        fail("App button not found: "+text);
    }
    @Test public void homeHelpAndStatusDoNotStartConnectionOrMutateLibrary()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String pagesBefore=new PageStore(c).read().toString();
        Set<String> marksBefore=new HashSet<>(c.getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE).getStringSet("marks",Collections.emptySet()));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                android.view.View decor=a.getWindow().getDecorView();
                java.util.ArrayList<android.view.View> views=new java.util.ArrayList<>();
                decor.findViewsWithText(views,"راهنما و بررسی برنامه",android.view.View.FIND_VIEWS_WITH_TEXT);
                assertFalse(views.isEmpty());assertTrue(views.get(0).performClick());assertOffline(a);
            });
            waitFor("وضعیت اتصال");device().executeShellCommand("screencap -p /sdcard/Download/DarsBridge-help.png");
            waitFor("وضعیت اتصال").click();waitFor("کپی وضعیت");
            device().executeShellCommand("screencap -p /sdcard/Download/DarsBridge-status.png");
            scenario.onActivity(this::assertOffline);
        }
        assertEquals(pagesBefore,new PageStore(c).read().toString());
        assertEquals(marksBefore,c.getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE).getStringSet("marks",Collections.emptySet()));
    }
    @Test public void reportOmitsAddressAndRawDiagnostic()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                EditText address=a.getWindow().getDecorView().findViewWithTag("address");
                address.setText("https://private.example/secret-token");
                try{
                    Field field=MainActivity.class.getDeclaredField("lastDiagnostic");field.setAccessible(true);
                    field.set(a,"PRIVATE-ERROR 192.168.3.17 /data/private-file");
                    Method method=MainActivity.class.getDeclaredMethod("connectionReport");method.setAccessible(true);
                    String report=(String)method.invoke(a);
                    assertFalse(report.contains("private.example"));assertFalse(report.contains("secret-token"));
                    assertFalse(report.contains("192.168.3.17"));assertFalse(report.contains("PRIVATE-ERROR"));assertFalse(report.contains("private-file"));
                    assertTrue(report.contains("گزارش خطای داخلی جداگانه موجود است: بله"));
                }catch(Exception e){throw new AssertionError(e);}
                assertOffline(a);
            });
        }
    }
    @Test public void openingReportLeavesClipboardUntouchedUntilExplicitCopy()throws Exception{
        java.util.concurrent.CountDownLatch copied=new java.util.concurrent.CountDownLatch(1);
        ClipboardManager.OnPrimaryClipChangedListener listener=copied::countDown;
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("test","clipboard-sentinel"));
                invoke(a,"showConnectionReport");
                assertEquals("clipboard-sentinel",clipboard.getPrimaryClip().getItemAt(0).getText().toString());assertOffline(a);
                clipboard.addPrimaryClipChangedListener(listener);
            });
            waitFor("کپی وضعیت");
            // Android's clipboard preview can cover the button's screen coordinates.
            // Invoke the actual app button through accessibility, then await its clipboard change.
            accessibilityClick("کپی وضعیت");
            assertTrue("Explicit copy did not update clipboard",copied.await(5,java.util.concurrent.TimeUnit.SECONDS));
            scenario.onActivity(a->{
                ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.removePrimaryClipChangedListener(listener);
                String value=clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                assertTrue("Copied text must be the local report",value.contains("وضعیت محلی درس‌گذر"));
                assertTrue("Report must identify actual app version",value.contains("نسخه: 0.13.0 (13)"));assertOffline(a);
                clipboard.setPrimaryClip(ClipData.newPlainText("test",""));
            });
        }
    }
}
