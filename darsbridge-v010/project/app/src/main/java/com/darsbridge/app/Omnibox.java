package com.darsbridge.app;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Resolve address-bar entries without ever evaluating executable URL schemes. */
public final class Omnibox {
    private Omnibox() { }
    public static String resolve(String entry) {
        if(entry==null || entry.trim().isEmpty())throw new IllegalArgumentException("یک عبارت جست‌وجو یا نشانی وب وارد کنید.");
        String input=entry.trim();
        if(input.length()>8192)throw new IllegalArgumentException("عبارت بیش از حد طولانی است.");
        String lower=input.toLowerCase(Locale.ROOT);
        if(lower.startsWith("https://") || lower.startsWith("http://") || dangerousScheme(lower) || input.matches("(?i)^[a-z][a-z0-9+.-]*://.*"))
            return UrlTools.normalize(input);
        if(looksLikeAddress(input))return UrlTools.normalize(input);
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(input,StandardCharsets.UTF_8.name()).replace("+","%20");
        }catch(java.io.UnsupportedEncodingException e){throw new IllegalStateException(e);}
    }
    private static boolean dangerousScheme(String input){
        for(String protocol:new String[]{"javascript:","file:","data:","intent:","content:","blob:","about:","mailto:","ftp:","chrome:","ws:","wss:"})
            if(input.startsWith(protocol))return true;
        return false;
    }
    private static boolean looksLikeAddress(String candidate){
        for(int i=0;i<candidate.length();i++){
            char c=candidate.charAt(i);
            if(Character.isWhitespace(c) || Character.isISOControl(c) || c=='@' || c=='\\')return false;
        }
        int end=candidate.length();
        for(char separator:new char[]{'/','?','#'}){
            int index=candidate.indexOf(separator);if(index>=0)end=Math.min(end,index);
        }
        String authority=candidate.substring(0,end);
        if(authority.startsWith("[") && authority.contains("]"))return true; // IPv6, validated by UrlTools
        String host=authority.replaceFirst(":\\d+$","");
        if(host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return true;
        int dot=host.lastIndexOf('.');
        if(dot<1 || dot==host.length()-1)return false;
        String suffix=host.substring(dot+1);
        if(suffix.startsWith("xn--") && suffix.length()>4)return true;
        if(suffix.length()<2)return false;
        for(int i=0;i<suffix.length();i++)if(!Character.isLetter(suffix.charAt(i)))return false;
        return true;
    }
}
