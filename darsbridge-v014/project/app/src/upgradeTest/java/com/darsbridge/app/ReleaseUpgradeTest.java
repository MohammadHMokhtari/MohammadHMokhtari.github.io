package com.darsbridge.app;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

/** Seed is run from the original 0.13 source; this verify runs after installing actual 0.14 source. */
@RunWith(AndroidJUnit4.class)
public class ReleaseUpgradeTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private String hash(byte[] value)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(value);StringBuilder result=new StringBuilder();
        for(byte b:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private String cert(Context c)throws Exception{
        PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
        Signature signature=Build.VERSION.SDK_INT>=28?info.signingInfo.getApkContentsSigners()[0]:info.signatures[0];
        return hash(signature.toByteArray());
    }
    private String export(Context c)throws Exception{
        android.content.SharedPreferences p=c.getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE);
        return BackupData.encode(new PageStore(c).read(),p.getStringSet("marks",Collections.emptySet()),p.getBoolean("autoReconnect",true));
    }
    private long code(Context c)throws Exception{
        PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
        return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
    }
    private void report(JSONObject data){
        Bundle result=new Bundle();result.putString("darsbridge_upgrade",data.toString());
        InstrumentationRegistry.getInstrumentation().sendStatus(0,result);
    }
    @Test public void seed()throws Exception{
        Context c=context();assertEquals("com.darsbridge.browser",c.getPackageName());assertEquals(13,code(c));
        assertEquals(0,c.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE);
        new PageStore(c).add("متنِ قبل از آپدیت","https://example.org/upgrade","این متن باید پس از نصب نسخهٔ جدید باقی بماند.");
        assertTrue(c.getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE).edit()
            .putStringSet("marks",new HashSet<>(Arrays.asList("https://example.org/upgrade","https://example.com/")))
            .putBoolean("autoReconnect",false).putString("mode","SNOWFLAKE").putInt("port",19050)
            .putBoolean("checkConsent",true).putInt("dataSchema",1).putString("upgradeSentinel","seed-version-13").commit());
        BrowserStore browser=new BrowserStore(c);browser.tabs.clear();browser.visits.clear();
        BrowserStore.Tab first=new BrowserStore.Tab();first.url="https://example.org/saved-tab";first.title="تب پیش از آپدیت";first.home=true;first.safeResume=true;browser.tabs.add(first);
        BrowserStore.Tab second=new BrowserStore.Tab();second.url="https://example.org/second-tab";second.title="تب دوم";second.home=false;second.safeResume=false;browser.tabs.add(second);browser.selected=second.id;
        browser.visit("https://example.org/saved-history","تاریخچهٔ پیشین",123);browser.save();
        assertTrue(c.getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE).edit().putBoolean("connectionWanted",true).commit());
        String browserBefore=hash(Files.readAllBytes(new java.io.File(c.getFilesDir(),BrowserStore.FILE_NAME).toPath()));
        JSONObject proof=new JSONObject().put("browser_sha_before",browserBefore).put("before_version_code",code(c)).put("data_sha_before",hash(export(c).getBytes(StandardCharsets.UTF_8)))
            .put("cert_sha_before",cert(c)).put("application_id",c.getPackageName());
        Files.write(new java.io.File(c.getFilesDir(),"upgrade-proof.json").toPath(),proof.toString().getBytes(StandardCharsets.UTF_8));report(proof);
    }
    @Test public void verify()throws Exception{
        Context c=context();assertEquals("com.darsbridge.browser",c.getPackageName());assertEquals(14,code(c));
        assertEquals("0.14.0",c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName);
        assertEquals(0,c.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE);
        JSONObject proof=new JSONObject(new String(Files.readAllBytes(new java.io.File(c.getFilesDir(),"upgrade-proof.json").toPath()),StandardCharsets.UTF_8));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                WebView w=a.getWindow().getDecorView().findViewWithTag("web");assertNull(w.getUrl());assertTrue(w.getSettings().getBlockNetworkLoads());
            });
        }
        android.content.SharedPreferences p=c.getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE);
        assertEquals("seed-version-13",p.getString("upgradeSentinel",""));assertEquals("SNOWFLAKE",p.getString("mode",""));
        assertEquals(19050,p.getInt("port",0));assertFalse(p.getBoolean("autoReconnect",true));assertTrue(p.getBoolean("checkConsent",false));
        assertEquals(1,p.getInt("dataSchema",0));assertEquals(2,p.getStringSet("marks",Collections.emptySet()).size());
        String after=hash(export(c).getBytes(StandardCharsets.UTF_8));assertEquals(proof.getString("data_sha_before"),after);
        String certAfter=cert(c);assertEquals(proof.getString("cert_sha_before"),certAfter);
        BrowserStore browser=new BrowserStore(c);assertEquals(2,browser.tabs.size());assertEquals(1,browser.visits.size());assertEquals("تب دوم",browser.find(browser.selected).title);
        assertTrue(p.getBoolean("connectionWanted",false));
        String browserAfter=hash(Files.readAllBytes(new java.io.File(c.getFilesDir(),BrowserStore.FILE_NAME).toPath()));assertEquals(proof.getString("browser_sha_before"),browserAfter);proof.put("browser_sha_after",browserAfter).put("tabs_history_and_connection_request_preserved",true);
        proof.put("after_version_code",code(c)).put("after_version_name","0.14.0").put("data_sha_after",after).put("cert_sha_after",certAfter)
            .put("non_debuggable",true).put("passed",true).put("signer_kind","disposable CI upgrade-test key; not the production release key");
        report(proof);
    }
}
