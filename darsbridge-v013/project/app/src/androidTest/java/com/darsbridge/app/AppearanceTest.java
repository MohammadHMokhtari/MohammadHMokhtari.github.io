package com.darsbridge.app;

import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.webkit.WebView;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** System night switching must not recreate WebView or start an unwanted network request. */
@RunWith(AndroidJUnit4.class)
public class AppearanceTest {
    @org.junit.Before public void resetBrowserBefore(){BrowserTestState.reset();}
    @org.junit.After public void resetBrowserAfter(){BrowserTestState.reset();}
    @Test public void togglesUiWithoutReloadingOrStartingConnection() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(activity->{
                View decor=activity.getWindow().getDecorView();
                WebView web=decor.findViewWithTag("web");
                EditText address=decor.findViewWithTag("address");
                View root=((android.view.ViewGroup)decor.findViewById(android.R.id.content)).getChildAt(0);
                String savedUrl=web.getUrl();
                assertNull(savedUrl);
                assertTrue(web.getSettings().getBlockNetworkLoads());
                Configuration config=new Configuration(activity.getResources().getConfiguration());
                config.uiMode=(config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)|Configuration.UI_MODE_NIGHT_YES;
                activity.onConfigurationChanged(config);
                assertEquals(0xff10191f,((ColorDrawable)root.getBackground()).getColor());
                View homeScroll=decor.findViewWithTag("home");
                assertEquals(0xff19272d,((ColorDrawable)homeScroll.getBackground()).getColor());
                assertEquals(0xffe7f0f0,address.getCurrentTextColor());
                assertNull(web.getUrl());
                assertTrue(web.getSettings().getBlockNetworkLoads());
                config.uiMode=(config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)|Configuration.UI_MODE_NIGHT_NO;
                activity.onConfigurationChanged(config);
                assertEquals(0xfff3f6f5,((ColorDrawable)root.getBackground()).getColor());
                assertEquals(0xffffffff,((ColorDrawable)homeScroll.getBackground()).getColor());
                assertEquals(0xff192e36,address.getCurrentTextColor());
                assertNull(web.getUrl());
                assertTrue(web.getSettings().getBlockNetworkLoads());
            });
        }
    }
}
