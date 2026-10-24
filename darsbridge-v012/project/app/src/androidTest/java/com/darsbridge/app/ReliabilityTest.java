package com.darsbridge.app;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import org.json.*;
import java.util.*;
@RunWith(AndroidJUnit4.class)
public class ReliabilityTest {
 @Test public void newStartIsOfflineAndStopCannotReconnect()throws Exception{
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   scenario.onActivity(a->{
    View root=a.getWindow().getDecorView();Button b=root.findViewWithTag("connect");assertNotNull(b);
    assertEquals("اتصال",b.getText().toString());
    try{java.lang.reflect.Method stop=MainActivity.class.getDeclaredMethod("stopConnection");stop.setAccessible(true);stop.invoke(a);}catch(Exception e){throw new AssertionError(e);}
    assertTrue(((WebView)root.findViewWithTag("web")).getSettings().getBlockNetworkLoads());
    assertEquals("اتصال",b.getText().toString());
   });
  }
 }
 @Test public void recreationDoesNotStartUnrequestedTraffic()throws Exception{
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   scenario.recreate();scenario.onActivity(a->{WebView w=a.getWindow().getDecorView().findViewWithTag("web");assertTrue(w.getSettings().getBlockNetworkLoads());});
  }
 }
 @Test public void backupPersianTextRoundTrip()throws Exception{
  JSONArray pages=new JSONArray().put(new JSONObject().put("title","آموزش").put("url","https://example.org/").put("text","متن فارسی"));
  BackupData result=BackupData.parse(BackupData.encode(pages,new HashSet<>(Arrays.asList("https://example.com/"))));
  assertEquals("متن فارسی",result.pages.getJSONObject(0).getString("text"));assertTrue(result.marks.contains("https://example.com/"));
 }
 @Test public void invalidBackupCannotContainScriptNavigation()throws Exception{
  try{BackupData.parse("{\"format\":\"DarsBridge-backup\",\"version\":1,\"pages\":[],\"bookmarks\":[\"javascript:alert(1)\"]}");fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void invalidVersionRejected()throws Exception{
  try{BackupData.parse("{\"format\":\"DarsBridge-backup\",\"version\":99,\"pages\":[],\"bookmarks\":[]}");fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void duplicateBackupUrlsDeduplicated()throws Exception{
  JSONObject page=new JSONObject().put("url","https://example.org").put("title","x").put("text","y");
  JSONObject doc=new JSONObject().put("format","DarsBridge-backup").put("version",1).put("pages",new JSONArray().put(page).put(page)).put("bookmarks",new JSONArray());
  assertEquals(1,BackupData.parse(doc.toString()).pages.length());
 }
 @Test public void oversizedPageRejected()throws Exception{
  JSONObject page=new JSONObject().put("url","https://example.org/").put("title","x").put("text",new String(new char[100001]).replace('\0','x'));
  try{BackupData.encode(new JSONArray().put(page),Collections.emptySet());fail();}catch(IllegalArgumentException expected){}
 }
}
