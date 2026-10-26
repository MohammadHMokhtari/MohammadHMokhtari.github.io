package com.darsbridge.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Fixed, bounded storage. The process lock also covers old/new Activity instances during recreation. */
final class PageStore {
    static final int MAX_PAGES=40,MAX_TEXT=100000;
    static final Object DATA_LOCK=new Object();
    private final File file;
    PageStore(Context context){file=new File(context.getFilesDir(),"reading-v03.json");}
    JSONArray read()throws Exception{
        synchronized(DATA_LOCK){
            android.util.AtomicFile safe=new android.util.AtomicFile(file);
            try(InputStream input=safe.openRead()){return new JSONArray(RestoreTransaction.readBounded(input));}
            catch(FileNotFoundException e){
                if(!file.exists() && !new File(file.getPath()+".bak").exists())return new JSONArray();
                throw e;
            }
        }
    }
    void write(JSONArray pages)throws Exception{
        synchronized(DATA_LOCK){
            if(pages.length()>MAX_PAGES)throw new IllegalStateException("کتابخانه پر است؛ ابتدا یک متن را حذف کنید.");
            byte[] bytes=pages.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>BackupData.MAX_BYTES)throw new IllegalStateException("اندازه کتابخانه بیش از حد مجاز است.");
            android.util.AtomicFile safe=new android.util.AtomicFile(file);FileOutputStream out=null;
            try{out=safe.startWrite();out.write(bytes);safe.finishWrite(out);}
            catch(Exception e){if(out!=null)safe.failWrite(out);throw e;}
        }
    }
    void add(String title,String url,String text)throws Exception{
        synchronized(DATA_LOCK){
            JSONArray pages=read();
            JSONObject item=new JSONObject().put("title",title.substring(0,Math.min(title.length(),500))).put("url",url)
                .put("text",text.substring(0,Math.min(text.length(),MAX_TEXT))).put("at",System.currentTimeMillis());
            for(int i=0;i<pages.length();i++){
                if(url.equals(pages.getJSONObject(i).optString("url"))){pages.put(i,item);write(pages);return;}
            }
            pages.put(item);write(pages);
        }
    }
    void remove(String url)throws Exception{
        synchronized(DATA_LOCK){
            JSONArray old=read(),keep=new JSONArray();
            for(int i=0;i<old.length();i++)if(!url.equals(old.getJSONObject(i).optString("url")))keep.put(old.get(i));
            write(keep);
        }
    }
}
