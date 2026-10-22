package com.darsbridge.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Bounded, private on-device text storage; no web requests or third-party reader. */
final class PageStore {
    static final int MAX_PAGES = 40;
    static final int MAX_TEXT = 100000;
    private final File file;
    PageStore(Context context) { file = new File(context.getFilesDir(), "reading-v03.json"); }
    synchronized JSONArray read() throws Exception {
        if (!file.exists()) return new JSONArray();
        if (file.length() > 18000000) throw new IllegalStateException("اندازه کتابخانه نامعتبر است.");
        return new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    synchronized void write(JSONArray pages) throws Exception {
        if (pages.length() > MAX_PAGES) throw new IllegalStateException("کتابخانه پر است؛ ابتدا یک متن را حذف کنید.");
        android.util.AtomicFile safe = new android.util.AtomicFile(file);
        java.io.FileOutputStream out = null;
        try {
            out = safe.startWrite();
            out.write(pages.toString().getBytes(StandardCharsets.UTF_8));
            safe.finishWrite(out);
        } catch (Exception e) { if (out != null) safe.failWrite(out); throw e; }
    }
    synchronized void add(String title, String url, String text) throws Exception {
        JSONArray pages = read();
        JSONObject item = new JSONObject().put("title", title).put("url", url)
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
