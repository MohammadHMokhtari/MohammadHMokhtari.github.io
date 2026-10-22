package com.darsbridge.app;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
public final class UrlTools {
    private UrlTools() {}
    public static String normalize(String input){
        if(input==null)throw new IllegalArgumentException("آدرس خالی است.");
        String s=input.trim();
        if(s.isEmpty()||s.matches(".*\\s+.*"))throw new IllegalArgumentException("لینک معتبر نیست.");
        String lower=s.toLowerCase(Locale.ROOT);
        if(!lower.startsWith("https://")&&!lower.startsWith("http://")){
            if(s.matches("(?i)^[a-z][a-z0-9+.-]*:.*"))
                throw new IllegalArgumentException("فقط لینک‌های وب پذیرفته می‌شوند.");
            s="https://"+s;
        }
        try{
            URI u=new URI(s);
            if(u.getHost()==null||u.getHost().isEmpty()||u.getUserInfo()!=null)
                throw new IllegalArgumentException("نام سایت معتبر نیست.");
            if(!"http".equalsIgnoreCase(u.getScheme())&&!"https".equalsIgnoreCase(u.getScheme()))
                throw new IllegalArgumentException("پروتکل لینک معتبر نیست.");
            return u.toASCIIString();
        }catch(URISyntaxException e){throw new IllegalArgumentException("فرمت آدرس نامعتبر است.");}
    }
    public static boolean isWebLink(String u){return u!=null&&(u.startsWith("https://")||u.startsWith("http://"));}
}
