package com.darsbridge.app;
import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Versioned, bounded local backup. Only explicitly allowed settings are restored. */
final class BackupData {
    static final int MAX_BYTES=16000000,MAX_MARKS=100;
    final JSONArray pages;
    final Set<String> marks;
    final Boolean autoReconnect;
    final int sourceVersion;
    private BackupData(JSONArray pages,Set<String> marks,Boolean autoReconnect,int sourceVersion){
        this.pages=pages;this.marks=marks;this.autoReconnect=autoReconnect;this.sourceVersion=sourceVersion;
    }
    static BackupData parse(String text)throws Exception{
        if(text==null || text.length()>MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new IllegalArgumentException("فایل بیش از حد بزرگ است.");
        JSONObject data=new JSONObject(text);int version=data.optInt("version",-1);
        if(!"DarsBridge-backup".equals(data.optString("format")) || (version!=1 && version!=2))
            throw new IllegalArgumentException("قالب پشتیبان پشتیبانی نمی‌شود.");
        JSONArray input=data.getJSONArray("pages"),saved=new JSONArray(),bm=data.getJSONArray("bookmarks");
        if(input.length()>PageStore.MAX_PAGES || bm.length()>MAX_MARKS)
            throw new IllegalArgumentException("تعداد متن‌ها یا نشانک‌ها بیش از حد مجاز است.");
        Set<String> marks=new LinkedHashSet<>(),seen=new HashSet<>();
        for(int i=0;i<bm.length();i++)marks.add(UrlTools.normalize(bm.getString(i)));
        for(int i=0;i<input.length();i++){
            JSONObject p=input.getJSONObject(i);String url=UrlTools.normalize(p.getString("url"));
            String title=p.getString("title"),body=p.getString("text");
            if(title.length()>500 || body.length()>PageStore.MAX_TEXT)
                throw new IllegalArgumentException("اندازه یکی از متن‌ها نامعتبر است.");
            if(seen.add(url))saved.put(new JSONObject().put("title",title).put("url",url).put("text",body).put("at",p.optLong("at",0)));
        }
        Boolean auto=null;
        if(version==2 && data.has("settings")){
            JSONObject settings=data.getJSONObject("settings");
            if(settings.has("autoReconnect")){
                Object value=settings.get("autoReconnect");
                if(!(value instanceof Boolean))throw new IllegalArgumentException("تنظیم پشتیبان نامعتبر است.");
                auto=(Boolean)value;
            }
        }
        // Mode, proxy port, cookies and consent are intentionally not imported from a file.
        return new BackupData(saved,marks,auto,version);
    }
    static String encode(JSONArray pages,Set<String> marks)throws Exception{return encode(pages,marks,null);}
    static String encode(JSONArray pages,Set<String> marks,Boolean autoReconnect)throws Exception{
        if(pages.length()>PageStore.MAX_PAGES || marks.size()>MAX_MARKS)
            throw new IllegalArgumentException("کتابخانه بیش از حد مجاز است.");
        JSONObject settings=new JSONObject();
        if(autoReconnect!=null)settings.put("autoReconnect",autoReconnect);
        String encoded=new JSONObject().put("format","DarsBridge-backup").put("version",2).put("pages",pages)
            .put("bookmarks",new JSONArray(new TreeSet<>(marks))).put("settings",settings).toString();
        parse(encoded);return encoded;
    }
    static BackupData merge(BackupData current,BackupData incoming)throws Exception{
        JSONArray merged=new JSONArray();Set<String> seen=new HashSet<>();
        for(JSONArray input:new JSONArray[]{current.pages,incoming.pages})
            for(int i=0;i<input.length();i++){
                JSONObject page=input.getJSONObject(i);
                if(seen.add(page.getString("url")))merged.put(page);
            }
        Set<String> marks=new LinkedHashSet<>(current.marks);marks.addAll(incoming.marks);
        // Keep existing text on URL conflicts. Only the safe imported preference may change.
        Boolean auto=incoming.autoReconnect!=null?incoming.autoReconnect:current.autoReconnect;
        return parse(encode(merged,marks,auto));
    }
}
