package com.darsbridge.app;

/** Main-thread health state. A result belongs to one run, network and foreground epoch. */
final class HealthState {
    static final class Check {
        private final long epoch;
        private Check(long epoch) { this.epoch=epoch; }
    }
    private long epoch;
    private boolean running, foreground=true, ready;
    private int port=-1;
    private Check pending;

    void start() { running=true; pending=null; invalidate(); }
    void stop() { running=false; pending=null; invalidate(); }
    void invalidate() { epoch++; ready=false; port=-1; }
    void foreground(boolean value) {
        if(foreground!=value) { foreground=value; invalidate(); }
    }
    boolean ready() { return running && foreground && ready; }
    int port() { return ready()?port:-1; }
    Check beginCheck() {
        if(!running || !foreground || pending!=null)return null;
        pending=new Check(epoch); return pending;
    }
    boolean complete(Check check, boolean connected, int detectedPort) {
        if(check==null || pending!=check)return false;
        pending=null;
        if(!running || !foreground || check.epoch!=epoch)return false;
        ready=connected && detectedPort>0 && detectedPort<=65535;
        port=ready?detectedPort:-1;
        return true;
    }
}
