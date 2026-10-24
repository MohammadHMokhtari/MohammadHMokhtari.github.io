package com.darsbridge.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** A durable restore journal makes interrupted multi-file imports resumable and idempotent. */
final class RestoreTransaction {
    interface Sink {void apply(BackupData data)throws Exception;}
    private final AtomicFile journal;
    private final Sink sink;
    RestoreTransaction(Context context,PageStore store,SharedPreferences prefs){
        this(new File(context.getFilesDir(),"restore-pending.json"),data->{
            store.write(data.pages);
            SharedPreferences.Editor editor=prefs.edit().putStringSet("marks",new HashSet<>(data.marks));
            if(data.autoReconnect!=null)editor.putBoolean("autoReconnect",data.autoReconnect);
            editor.putInt("dataSchema",1);
            if(!editor.commit())throw new IOException("تنظیمات بازیابی ذخیره نشد.");
        });
    }
    RestoreTransaction(File file,Sink sink){this.journal=new AtomicFile(file);this.sink=sink;}
    boolean pending(){
        synchronized(PageStore.DATA_LOCK){
            File file=journal.getBaseFile();
            return file.exists() || new File(file.getPath()+".bak").exists();
        }
    }
    void restore(BackupData data)throws Exception{
        synchronized(PageStore.DATA_LOCK){
            if(pending())throw new IOException("ابتدا بازیابی معلق قبلی را تکمیل یا لغو کنید.");
            String encoded=BackupData.encode(data.pages,data.marks,data.autoReconnect);
            byte[] bytes=encoded.getBytes(StandardCharsets.UTF_8);
            FileOutputStream out=null;
            try{out=journal.startWrite();out.write(bytes);journal.finishWrite(out);}
            catch(Exception e){if(out!=null)journal.failWrite(out);throw e;}
            apply(BackupData.parse(encoded));
        }
    }
    boolean recover()throws Exception{
        synchronized(PageStore.DATA_LOCK){
            if(!pending())return false;
            try(InputStream input=journal.openRead()){
                apply(BackupData.parse(readBounded(input)));return true;
            }
        }
    }
    private void apply(BackupData data)throws Exception{
        sink.apply(data);
        journal.delete();
        if(pending())throw new IOException("ثبت پایان بازیابی کامل نشد.");
    }
    void discardPending(){
        synchronized(PageStore.DATA_LOCK){journal.delete();}
    }
    static String readBounded(InputStream input)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){
            if(bytes.size()+n>BackupData.MAX_BYTES)throw new IOException("فایل بیش از حد بزرگ است.");
            bytes.write(buffer,0,n);
        }
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
}
