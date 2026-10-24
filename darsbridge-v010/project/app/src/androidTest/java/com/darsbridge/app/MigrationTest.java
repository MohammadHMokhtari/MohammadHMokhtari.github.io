package com.darsbridge.app;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MigrationTest {
    private static final class Fixture {
        final Context context;
        final File directory;
        final PageStore store;
        final android.content.SharedPreferences prefs;
        Fixture() {
            Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
            String id="migration-"+UUID.randomUUID();directory=new File(app.getFilesDir(),id);assertTrue(directory.mkdirs());
            context=new ContextWrapper(app){@Override public File getFilesDir(){return directory;}};
            store=new PageStore(context);prefs=app.getSharedPreferences(id,Context.MODE_PRIVATE);
        }
        RestoreTransaction transaction(){return new RestoreTransaction(context,store,prefs);}
    }
    private static JSONObject page(String url,String text)throws Exception{
        return new JSONObject().put("url",url).put("title","متن فارسی").put("text",text).put("at",123456789L);
    }
    private static BackupData legacy(JSONArray pages,String... marks)throws Exception{
        return BackupData.parse(new JSONObject().put("format","DarsBridge-backup").put("version",1)
            .put("pages",pages).put("bookmarks",new JSONArray(Arrays.asList(marks))).toString());
    }
    @Test public void v09BackupMigratesPersianContentAndBookmarks()throws Exception{
        Fixture f=new Fixture();BackupData old=legacy(new JSONArray().put(page("https://example.org/","کتابخانهٔ نسخهٔ ۰.۹")),"https://example.com/");
        assertEquals(1,old.sourceVersion);f.transaction().restore(old);
        assertEquals("کتابخانهٔ نسخهٔ ۰.۹",f.store.read().getJSONObject(0).getString("text"));
        assertTrue(f.prefs.getStringSet("marks",Collections.emptySet()).contains("https://example.com/"));
        assertFalse(f.transaction().pending());
    }
    @Test public void mergeKeepsCurrentTextAndAddsNewContentAndMarks()throws Exception{
        BackupData current=legacy(new JSONArray().put(page("https://example.org/same","فعلی")),"https://example.org/");
        BackupData imported=legacy(new JSONArray().put(page("https://example.org/same","قدیمی")).put(page("https://example.org/new","جدید")),"https://example.com/");
        BackupData result=BackupData.merge(current,imported);
        assertEquals(2,result.pages.length());assertEquals("فعلی",result.pages.getJSONObject(0).getString("text"));assertEquals(2,result.marks.size());
        Fixture f=new Fixture();f.transaction().restore(result);
        BackupData round=BackupData.parse(BackupData.encode(f.store.read(),f.prefs.getStringSet("marks",Collections.emptySet()),true));
        assertEquals(2,round.sourceVersion);assertEquals(Boolean.TRUE,round.autoReconnect);assertEquals(2,round.pages.length());
    }
    @Test public void overCapacityMergeDoesNotChangeExistingStore()throws Exception{
        Fixture f=new Fixture();JSONArray pages=new JSONArray();
        for(int i=0;i<PageStore.MAX_PAGES;i++)pages.put(page("https://example.org/"+i,"موجود"));
        f.store.write(pages);BackupData current=legacy(f.store.read()),incoming=legacy(new JSONArray().put(page("https://example.org/extra","تازه")));
        try{f.transaction().restore(BackupData.merge(current,incoming));fail();}catch(IllegalArgumentException expected){}
        assertEquals(PageStore.MAX_PAGES,f.store.read().length());assertFalse(f.transaction().pending());
    }
    @Test public void interruptedRestoreRecoversBothFilesWithoutLosingJournal()throws Exception{
        Fixture f=new Fixture();assertTrue(f.prefs.edit().putStringSet("marks",new HashSet<>(Arrays.asList("https://old.example/"))).commit());
        BackupData target=legacy(new JSONArray().put(page("https://new.example/","انتقال")),"https://new.example/");
        File journal=new File(f.directory,"restore-pending.json");
        RestoreTransaction interrupted=new RestoreTransaction(journal,data->{f.store.write(data.pages);throw new IOException("simulated interrupted preference commit");});
        try{interrupted.restore(target);fail();}catch(IOException expected){}
        assertTrue(interrupted.pending());assertTrue(f.prefs.getStringSet("marks",Collections.emptySet()).contains("https://old.example/"));
        assertTrue(f.transaction().recover());assertFalse(f.transaction().pending());assertFalse(f.transaction().recover());
        assertTrue(f.prefs.getStringSet("marks",Collections.emptySet()).contains("https://new.example/"));
        assertEquals("انتقال",f.store.read().getJSONObject(0).getString("text"));
    }
    @Test public void pageStoreRestoresAtomicFileBackupInsteadOfReadingBrokenBase()throws Exception{
        Fixture f=new Fixture();f.store.add("آموزش","https://example.org/","متن محفوظ");
        File base=new File(f.directory,"reading-v03.json"),bak=new File(base.getPath()+".bak");
        assertTrue(base.renameTo(bak));Files.write(base.toPath(),"broken".getBytes(StandardCharsets.UTF_8));
        assertEquals("متن محفوظ",f.store.read().getJSONObject(0).getString("text"));assertFalse(bak.exists());
    }
    @Test public void legacyBackupCannotChangeConnectionChoiceOrAutoReconnect()throws Exception{
        Fixture f=new Fixture();assertTrue(f.prefs.edit().putString("mode","LOCAL").putBoolean("autoReconnect",false).commit());
        JSONObject input=new JSONObject().put("format","DarsBridge-backup").put("version",1).put("pages",new JSONArray()).put("bookmarks",new JSONArray())
            .put("settings",new JSONObject().put("mode","SYSTEM").put("autoReconnect",true));
        f.transaction().restore(BackupData.parse(input.toString()));
        assertEquals("LOCAL",f.prefs.getString("mode",""));assertFalse(f.prefs.getBoolean("autoReconnect",true));
    }
    @Test public void v2RestoresOnlySafeSettingAndRejectsWrongType()throws Exception{
        Fixture f=new Fixture();assertTrue(f.prefs.edit().putString("mode","SNOWFLAKE").putInt("port",9050).commit());
        JSONObject input=new JSONObject().put("format","DarsBridge-backup").put("version",2).put("pages",new JSONArray()).put("bookmarks",new JSONArray())
            .put("settings",new JSONObject().put("mode","SYSTEM").put("port",1234).put("autoReconnect",false));
        f.transaction().restore(BackupData.parse(input.toString()));
        assertEquals("SNOWFLAKE",f.prefs.getString("mode",""));assertEquals(9050,f.prefs.getInt("port",0));assertFalse(f.prefs.getBoolean("autoReconnect",true));
        input.getJSONObject("settings").put("autoReconnect","true");
        try{BackupData.parse(input.toString());fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void multipleStoreInstancesDoNotLoseConcurrentAdds()throws Exception{
        Fixture f=new Fixture();PageStore first=new PageStore(f.context),second=new PageStore(f.context);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> futures=new java.util.ArrayList<>();
        try{
            for(int n=0;n<2;n++){
                final int writer=n;final PageStore target=n==0?first:second;
                futures.add(pool.submit(()->{
                    try{start.await();for(int i=0;i<8;i++)target.add("هم‌زمان","https://example.org/concurrent-"+writer+"-"+i,"محفوظ");}
                    catch(Exception e){throw new RuntimeException(e);}
                }));
            }
            start.countDown();for(java.util.concurrent.Future<?> future:futures)future.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(16,f.store.read().length());
        }finally{pool.shutdownNow();}
    }
    @Test public void legacyFileImportThroughUiMergesWithoutRemovingExistingData()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PageStore store=new PageStore(app);JSONArray before=store.read();
        android.content.SharedPreferences prefs=app.getSharedPreferences(MainActivity.PREFS_NAME,Context.MODE_PRIVATE);
        Set<String> marksBefore=new HashSet<>(prefs.getStringSet("marks",Collections.emptySet()));
        String url="https://example.org/import-ui-"+UUID.randomUUID();
        File file=new File(app.getCacheDir(),"legacy-import-ui.json");
        String value=new JSONObject().put("format","DarsBridge-backup").put("version",1)
            .put("pages",new JSONArray().put(page(url,"انتقال از فایل قدیمی"))).put("bookmarks",new JSONArray().put(url)).toString();
        Files.write(file.toPath(),value.getBytes(StandardCharsets.UTF_8));
        try(androidx.test.core.app.ActivityScenario<MainActivity> scenario=androidx.test.core.app.ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                try{java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("readBackup",android.net.Uri.class);m.setAccessible(true);m.invoke(a,android.net.Uri.fromFile(file));}
                catch(Exception e){throw new AssertionError(e);}
            });
            androidx.test.uiautomator.UiDevice device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            androidx.test.uiautomator.UiObject2 add=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("افزودن بدون حذف")),10000);
            assertNotNull("Import preview was not shown",add);add.click();
            long end=android.os.SystemClock.elapsedRealtime()+10000;boolean found=false;
            while(android.os.SystemClock.elapsedRealtime()<end){
                JSONArray pages=store.read();
                for(int i=0;i<pages.length();i++)if(url.equals(pages.getJSONObject(i).optString("url")))found=true;
                if(found && prefs.getStringSet("marks",Collections.emptySet()).contains(url))break;
                Thread.sleep(40);
            }
            assertTrue(found);assertTrue(prefs.getStringSet("marks",Collections.emptySet()).containsAll(marksBefore));
            assertEquals(before.length()+1,store.read().length());
        }finally{store.write(before);assertTrue(prefs.edit().putStringSet("marks",marksBefore).commit());file.delete();}
    }
    @Test public void invalidJournalCannotMutateDataAndCanBeExplicitlyDiscarded()throws Exception{
        Fixture f=new Fixture();f.store.add("حفظ","https://example.org/","محفوظ");AtomicInteger applied=new AtomicInteger();
        File journal=new File(f.directory,"test-corrupt-journal.json");Files.write(journal.toPath(),"{".getBytes(StandardCharsets.UTF_8));
        RestoreTransaction transaction=new RestoreTransaction(journal,data->applied.incrementAndGet());
        try{transaction.recover();fail();}catch(JSONException expected){}
        assertEquals(0,applied.get());assertTrue(transaction.pending());
        transaction.discardPending();assertFalse(transaction.pending());assertEquals("محفوظ",f.store.read().getJSONObject(0).getString("text"));
    }
}
