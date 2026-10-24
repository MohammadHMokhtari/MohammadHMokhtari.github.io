package com.darsbridge.app;
/** Bounded retry budget. All state is owned by the Activity main thread. */
public final class RecoveryPolicy {
    private static final long[] DELAYS={2500L,7000L,15000L};
    private int used=0;
    public long delayMillis(){return used<DELAYS.length?DELAYS[used]:-1L;}
    public long nextDelayMillis(){long delay=delayMillis();if(delay>=0)used++;return delay;}
    public void reset(){used=0;}
    public int attempts(){return used;}
    public static int bootstrapPercent(String response){
        if(response==null || response.length()>4096)return -1;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:^|\\s)PROGRESS=([0-9]{1,3})(?:\\s|$)").matcher(response);
        if(!m.find())return -1;
        int n=Integer.parseInt(m.group(1));return n<=100?n:-1;
    }
}
