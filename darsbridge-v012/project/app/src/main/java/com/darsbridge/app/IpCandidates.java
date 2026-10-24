package com.darsbridge.app;

import java.net.IDN;
import java.net.InetAddress;
import java.util.*;

/** Numeric IPv4 parsing and bounded sampling. Never resolves a name or enumerates a large range. */
final class IpCandidates {
    static final int MAX=256;
    static final String GOOGLE="216.239.38.120\n142.250.0.0/15\n172.217.0.0/16\n216.239.32.0/19";
    static final String CLOUDFLARE="173.245.48.0/20\n103.21.244.0/22\n103.22.200.0/22\n103.31.4.0/22\n141.101.64.0/18\n108.162.192.0/18\n190.93.240.0/20\n188.114.96.0/20\n197.234.240.0/22\n198.41.128.0/17\n162.158.0.0/15\n104.16.0.0/13\n104.24.0.0/14\n172.64.0.0/13\n131.0.72.0/22";
    private IpCandidates(){}
    static String host(String input){
        if(input==null)throw new IllegalArgumentException("نام دامنه لازم است.");
        String value=input.trim();
        if(value.startsWith("https://"))value=value.substring(8);
        if(value.endsWith("/"))value=value.substring(0,value.length()-1);
        if(value.endsWith("."))value=value.substring(0,value.length()-1);
        if(value.contains("/") || value.contains(":") || value.contains("?") || value.contains("#") || value.contains("@"))
            throw new IllegalArgumentException("فقط نام دامنهٔ HTTPS را بنویس؛ بدون مسیر، پورت یا اطلاعات ورود.");
        try{value=IDN.toASCII(value,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);}catch(IllegalArgumentException e){throw new IllegalArgumentException("نام دامنه معتبر نیست.");}
        if(value.length()>253 || !value.contains(".") || value.matches("[0-9.]+"))throw new IllegalArgumentException("نام دامنه معتبر نیست.");
        for(String label:value.split("\\.",-1))if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))throw new IllegalArgumentException("نام دامنه معتبر نیست.");
        return value;
    }
    static long number(String value){
        if(value==null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))throw new IllegalArgumentException("نشانی IPv4 معتبر نیست.");
        long out=0;for(String item:value.split("\\.")){int n=Integer.parseInt(item);if(n>255)throw new IllegalArgumentException("نشانی IPv4 معتبر نیست.");out=(out<<8)|n;}return out;
    }
    static String text(long value){return ((value>>>24)&255)+"."+((value>>>16)&255)+"."+((value>>>8)&255)+"."+(value&255);}
    static InetAddress address(String value)throws java.net.UnknownHostException{
        long n=number(value);return InetAddress.getByAddress(new byte[]{(byte)(n>>>24),(byte)(n>>>16),(byte)(n>>>8),(byte)n});
    }
    private static boolean within(long n,String base,int prefix){long mask=(0xffffffffL<<(32-prefix))&0xffffffffL;return (n&mask)==(number(base)&mask);}
    static boolean isPublic(long n){
        int first=(int)(n>>>24);
        return first!=0 && first!=10 && first!=127 && first<224
            && !within(n,"100.64.0.0",10) && !within(n,"169.254.0.0",16) && !within(n,"172.16.0.0",12)
            && !within(n,"192.168.0.0",16) && !within(n,"192.0.0.0",24) && !within(n,"192.0.2.0",24)
            && !within(n,"192.88.99.0",24) && !within(n,"198.18.0.0",15) && !within(n,"198.51.100.0",24)
            && !within(n,"203.0.113.0",24);
    }
    static List<String> build(String input,long seed){
        if(input==null || input.trim().isEmpty())throw new IllegalArgumentException("حداقل یک IPv4 یا محدوده لازم است.");
        if(input.length()>16384)throw new IllegalArgumentException("فهرست بیش از حد طولانی است.");
        String[] tokens=input.trim().split("[\\s,;]+");if(tokens.length>64)throw new IllegalArgumentException("حداکثر ۶۴ نشانی یا محدوده وارد کن.");
        LinkedHashSet<String> out=new LinkedHashSet<>();List<long[]> ranges=new ArrayList<>();
        // Validate the entire input before generating anything.
        for(String token:tokens){
            String[] parts=token.split("/",-1);if(parts.length>2)throw new IllegalArgumentException("محدوده معتبر نیست.");
            long n=number(parts[0]);int prefix=32;
            if(parts.length==2){if(!parts[1].matches("0|[1-9][0-9]?"))throw new IllegalArgumentException("پیشوند محدوده معتبر نیست.");prefix=Integer.parseInt(parts[1]);if(prefix>32)throw new IllegalArgumentException("پیشوند محدوده باید بین ۰ تا ۳۲ باشد.");}
            if(prefix==32){if(!isPublic(n))throw new IllegalArgumentException("فقط IPv4 عمومی قابل آزمایش است.");out.add(text(n));}
            else{long size=1L<<(32-prefix);ranges.add(new long[]{n&~(size-1),size});}
        }
        Random random=new Random(seed);int each=ranges.isEmpty()?0:Math.min(64,Math.max(1,(MAX-out.size())/ranges.size()));
        for(long[] range:ranges){
            long begin=range[0]+(range[1]>2?1:0),count=range[1]-(range[1]>2?2:0);int samples=(int)Math.min(count,each);
            for(int i=0;i<samples && out.size()<MAX;i++){
                long lo=count*i/samples,hi=count*(i+1)/samples;
                long value=begin+lo+Math.floorMod(random.nextLong(),hi-lo);
                if(isPublic(value))out.add(text(value));
            }
        }
        if(out.isEmpty())throw new IllegalArgumentException("در این فهرست نشانی عمومی برای آزمایش پیدا نشد.");
        return new ArrayList<>(out);
    }
}
