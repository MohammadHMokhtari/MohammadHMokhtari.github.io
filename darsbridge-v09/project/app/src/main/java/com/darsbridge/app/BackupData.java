package com.darsbridge.app;
import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
/** Local, versioned backup format. Values never execute as HTML or scripts. */
final class BackupData {
    static final int MAX_BYTES=16000000, MAX_MARKS=100;
    final JSONArray pages;
    final Set<String> marks;
    BackupData(JSONArray pages,Set<String> marks){this.pages=pages;this.marks=marks;}
    static BackupData parse(String text)throws Exception {
        if(text==null || text.length()>MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("فایل بیش از حد بزرگ است.");
        JSONObject data=new JSONObject(text);
        if(!"DarsBridge-backup".equals(data.optString("format")) || data.optInt("version",-1)!=1)throw new IllegalArgumentException("قالب پشتیبان پشتیبانی نمی‌شود.");
        JSONArray input=data.getJSONArray("pages"), saved=new JSONArray(), bm=data.getJSONArray("bookmarks");
        if(input.length()>40 || bm.length()>MAX_MARKS)throw new IllegalArgumentException("تعداد متن‌ها یا نشانک‌ها بیش از حد مجاز است.");
        Set<String> marks=new LinkedHashSet<>();
        for(int i=0;i<bm.length();i++)marks.add(UrlTools.normalize(bm.getString(i)));
        Set<String> seen=new HashSet<>();
        for(int i=0;i<input.length();i++){
            JSONObject p=input.getJSONObject(i);String url=UrlTools.normalize(p.getString("url"));
            String title=p.getString("title"),body=p.getString("text");
            if(title.length()>500 || body.length()>100000)throw new IllegalArgumentException("اندازه یکی از متن‌ها نامعتبر است.");
            if(seen.add(url))saved.put(new JSONObject().put("title",title).put("url",url).put("text",body).put("at",p.optLong("at",0)));
        }
        return new BackupData(saved,marks);
    }
    static String encode(JSONArray pages,Set<String> marks)throws Exception {
        if(pages.length()>40 || marks.size()>MAX_MARKS)throw new IllegalArgumentException("کتابخانه بیش از حد مجاز است.");
        String encoded=new JSONObject().put("format","DarsBridge-backup").put("version",1).put("pages",pages)
            .put("bookmarks",new JSONArray(new TreeSet<>(marks))).toString();
        parse(encoded);return encoded;
    }
}
