package com.darsbridge.app;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
/** Each test owns its browsing session; bookmarks/offline library remain available to migration tests. */
final class BrowserTestState {
    static void reset(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        BrowserStore.delete(context);
        if(!context.getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE).edit().remove("connectionWanted").putBoolean("autoReconnect",true).putString("mode","AUTO").putInt("port",9050).commit())throw new AssertionError("Reset preferences failed");
    }
}
