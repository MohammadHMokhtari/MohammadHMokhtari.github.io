package com.darsbridge.app;

import java.net.URI;

/** Context actions accept real HTTPS links, never omnibox searches or executable schemes. */
final class LinkActions {
    private LinkActions(){}
    static String resolve(String href,String base){
        if(href==null || href.isEmpty() || href.length()>8192)throw bad();
        try{
            for(int i=0;i<href.length();i++)if(Character.isWhitespace(href.charAt(i)) || Character.isISOControl(href.charAt(i)))throw bad();
            URI uri=new URI(href);
            if(uri.getScheme()==null){if(!UrlTools.isHttps(base))throw bad();uri=new URI(base).resolve(uri);}
            if(!"https".equalsIgnoreCase(uri.getScheme()))throw bad();
            String result=UrlTools.normalize(uri.toString());
            if(result.length()>8192)throw bad();
            return result;
        }catch(Exception invalid){throw bad();}
    }
    static String label(String text){
        if(text==null)return "";
        String value=text.replaceAll("[\\p{Cc}\\p{Cf}]"," ").trim();
        return value.substring(0,Math.min(400,value.length()));
    }
    private static IllegalArgumentException bad(){return new IllegalArgumentException("این نسخه فقط لینک معتبر HTTPS را باز می‌کند.");}
}
