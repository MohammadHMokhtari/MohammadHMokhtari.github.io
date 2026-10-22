package com.darsbridge.app;
import android.view.View;
import android.widget.EditText;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.File;
@RunWith(AndroidJUnit4.class)
public class SmokeTest {
    @Test public void startsOfflineWithoutUnrequestedNavigation() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(activity->{
                View decor=activity.getWindow().getDecorView();
                assertNotNull(decor.findViewWithTag("address"));
                assertNotNull(decor.findViewWithTag("mode"));
                assertTrue(((EditText)decor.findViewWithTag("address")).getText().length()==0);
                WebView w=decor.findViewWithTag("web");
                assertTrue(w.getSettings().getBlockNetworkLoads());
                assertNull(w.getUrl());
                assertEquals(View.VISIBLE,decor.findViewWithTag("home").getVisibility());
            });
            UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            device.waitForIdle();
            File shot=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"home.png");
            assertTrue(device.takeScreenshot(shot));
        }
    }
    @Test public void offlineStoreRoundTripAndDeduplication() throws Exception {
        PageStore store=new PageStore(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String url="https://example.org/offline-test";
        store.add("تست",url,"این یک متن آفلاین است.");
        store.add("تست ویرایش",url,"متن جایگزین");
        org.json.JSONArray pages=store.read();
        int n=0;for(int i=0;i<pages.length();i++)if(url.equals(pages.getJSONObject(i).optString("url"))){n++;assertEquals("متن جایگزین",pages.getJSONObject(i).optString("text"));}
        assertEquals(1,n);store.remove(url);
        for(int i=0;i<store.read().length();i++)assertNotEquals(url,store.read().getJSONObject(i).optString("url"));
    }
}
