package com.darsbridge.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.FileNotFoundException;

/** Bounded, private on-device text storage; no web requests or third-party reader. */
final class PageStore {
    static final int MAX_PAGES = 40;
    static final int MAX_TEXT = 100000;
    private final File file;
    PageStore(Context context) { file = new File(context.getFilesDir(), "reading-v03.json"); }
    synchronized JSONArray read() throws Exception {
        android.util.AtomicFile safe=new android.util.AtomicFile(file);
        try(InputStream input=safe.openRead()) {
            return new JSONArray(RestoreTransaction.readBounded(input));
        }catch(FileNotFoundException e){
            if(!file.exists() && !new File(file.getPath()+".bak").exists())return new JSONArray();
            throw e;
        }
    }
    synchronized void write(JSONArray pages) throws Exception {
        if (pages.length() > MAX_PAGES) throw new IllegalStateException("کتابخانه پر است؛ ابتدا یک متن را حذف کنید.");
        android.util.AtomicFile safe = new android.util.AtomicFile(file);
        java.io.FileOutputStream out = null;
        try {
            out = safe.startWrite();
            byte[] bytes=pages.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>BackupData.MAX_BYTES)throw new IllegalStateException("اندازه کتابخانه بیش از حد مجاز است.");
            out.write(bytes);
            safe.finishWrite(out);
        } catch (Exception e) { if (out != null) safe.failWrite(out); throw e; }
    }
    synchronized void add(String title, String url, String text) throws Exception {
        JSONArray pages = read();
        JSONObject item = new JSONObject().put("title", title.substring(0, Math.min(title.length(), 500))).put("url", url)
            .put("text", text.substring(0, Math.min(text.length(), MAX_TEXT))).put("at", System.currentTimeMillis());
        for (int i = 0; i < pages.length(); i++) {
            if (url.equals(pages.getJSONObject(i).optString("url"))) { pages.put(i, item); write(pages); return; }
        }
        pages.put(item); write(pages);
    }
    synchronized void remove(String url) throws Exception {
        JSONArray old = read(), keep = new JSONArray();
        for (int i = 0; i < old.length(); i++) if (!url.equals(old.getJSONObject(i).optString("url"))) keep.put(old.get(i));
        write(keep);
    }
}
