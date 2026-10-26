package com.darsbridge.app;

/** A saved user request is different from a live/verified connection. */
final class ResumePolicy {
    private ResumePolicy(){}
    static boolean reconnect(boolean enabled,boolean lastRequested){return enabled && lastRequested;}
    static boolean restorePage(boolean requested,boolean home,boolean safeGet,String url){
        return requested && !home && safeGet && UrlTools.isHttps(url);
    }
}
