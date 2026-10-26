package com.darsbridge.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local URL/title metadata only. No page HTML, form bodies, passwords or WebView bundles. */
final class BrowserStore {
    static final int MAX_TABS=8, MAX_HISTORY=200, MAX_BYTES=2*1024*1024;
    static final String FILE_NAME="browser-v013.json";
    private static final Object LOCK=new Object();
    static final class Tab {
        final String id;
        String url="",title="";
        boolean home=true;
        volatile boolean safeResume=false;
        Tab(){this(UUID.randomUUID().toString());}
        Tab(String id){this.id=id;}
    }
    static final class Visit {
        final String url,title;final long at;
        Visit(String url,String title,long at){this.url=url;this.title=title;this.at=at;}
    }
    final ArrayList<Tab> tabs=new ArrayList<>();
    final ArrayList<Visit> visits=new ArrayList<>();
    String selected="";
    boolean readFailed;
    private final AtomicFile file;
    BrowserStore(Context context){file=new AtomicFile(new File(context.getFilesDir(),FILE_NAME));read();}
    private static String cleanUrl(String url){
        if(!UrlTools.isHttps(url))throw new IllegalArgumentException("HTTPS required");
        return UrlTools.normalize(url);
    }
    static String title(String value){
        if(value==null)return "";
        String clean=value.replaceAll("[\\p{Cc}\\p{Cf}]", " ");return clean.substring(0,Math.min(clean.length(),200));
    }
    private void read(){
        synchronized(LOCK){
            try(InputStream input=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] chunk=new byte[4096];int count;
                while((count=input.read(chunk))!=-1){if(out.size()+count>MAX_BYTES)throw new IOException("Too large");out.write(chunk,0,count);}
                JSONObject data=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                if(data.getInt("version")!=1)throw new IOException("Unknown session version");
                JSONArray saved=data.getJSONArray("tabs"),history=data.getJSONArray("history");
                if(saved.length()>MAX_TABS || history.length()>MAX_HISTORY)throw new IOException("Too many entries");
                HashSet<String> ids=new HashSet<>();
                for(int i=0;i<saved.length();i++){
                    JSONObject item=saved.getJSONObject(i);String id=item.getString("id");
                    if(!id.matches("[a-f0-9-]{36}") || !ids.add(id))throw new IOException("Invalid tab identity");
                    Tab tab=new Tab(id);String url=item.optString("url","");
                    tab.url=url.isEmpty()?"":cleanUrl(url);tab.title=title(item.optString("title",""));
                    tab.home=item.optBoolean("home",true);tab.safeResume=item.optBoolean("safeResume",false);tabs.add(tab);
                }
                for(int i=0;i<history.length();i++){
                    JSONObject item=history.getJSONObject(i);
                    visits.add(new Visit(cleanUrl(item.getString("url")),title(item.optString("title","")),Math.max(0,item.optLong("at",0))));
                }
                selected=data.optString("selected","");
            }catch(FileNotFoundException absent){/* First use. */}
            catch(Exception invalid){tabs.clear();visits.clear();selected="";readFailed=true;}
            if(tabs.isEmpty())tabs.add(new Tab());
            if(find(selected)==null)selected=tabs.get(0).id;
        }
    }
    Tab find(String id){for(Tab t:tabs)if(t.id.equals(id))return t;return null;}
    Tab add(){if(tabs.size()>=MAX_TABS)return null;Tab t=new Tab();tabs.add(t);selected=t.id;return t;}
    void remove(String id){
        int index=0;for(int i=0;i<tabs.size();i++)if(tabs.get(i).id.equals(id)){index=i;break;}
        tabs.removeIf(t->t.id.equals(id));if(tabs.isEmpty())tabs.add(new Tab());
        if(find(selected)==null)selected=tabs.get(Math.min(index,tabs.size()-1)).id;
    }
    void visit(String url,String title,long at){
        final String valid;try{valid=cleanUrl(url);}catch(Exception invalid){return;}
        visits.removeIf(v->v.url.equals(valid));visits.add(0,new Visit(valid,title(title),Math.max(0,at)));
        while(visits.size()>MAX_HISTORY)visits.remove(visits.size()-1);
    }
    void updateTitle(String url,String title){
        for(int i=0;i<visits.size();i++){
            Visit old=visits.get(i);if(old.url.equals(url)){visits.set(i,new Visit(old.url,title(title),old.at));return;}
        }
    }
    void removeVisit(String url){visits.removeIf(v->v.url.equals(url));}
    void clearHistory(){visits.clear();}
    void save()throws Exception{
        synchronized(LOCK){
            if(tabs.isEmpty() || tabs.size()>MAX_TABS || visits.size()>MAX_HISTORY || find(selected)==null)throw new IOException("Invalid session");
            JSONArray all=new JSONArray(),history=new JSONArray();
            for(Tab t:tabs)all.put(new JSONObject().put("id",t.id).put("url",t.url.isEmpty()?"":cleanUrl(t.url))
                .put("title",title(t.title)).put("home",t.home).put("safeResume",t.safeResume));
            for(Visit v:visits)history.put(new JSONObject().put("url",cleanUrl(v.url)).put("title",title(v.title)).put("at",v.at));
            byte[] bytes=new JSONObject().put("version",1).put("selected",selected).put("tabs",all).put("history",history).toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_BYTES)throw new IOException("Session too large");
            FileOutputStream out=null;
            try{out=file.startWrite();out.write(bytes);file.finishWrite(out);}
            catch(Exception failure){if(out!=null)file.failWrite(out);throw failure;}
        }
    }
    static void delete(Context context){synchronized(LOCK){new AtomicFile(new File(context.getFilesDir(),FILE_NAME)).delete();}}
}
