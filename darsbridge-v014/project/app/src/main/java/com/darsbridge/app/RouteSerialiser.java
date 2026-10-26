package com.darsbridge.app;

import java.util.function.Consumer;

/** Serialize process-wide proxy changes; obsolete completions cannot permit loading. UI thread only. */
final class RouteSerialiser {
    interface Backend { void apply(int port, Runnable complete); }
    private static final class Request {
        final long version;
        final int port;
        final Runnable applied;
        final Consumer<RuntimeException> failed;
        Request(long version,int port,Runnable applied,Consumer<RuntimeException> failed) {
            this.version=version;this.port=port;this.applied=applied;this.failed=failed;
        }
    }
    private final Backend backend;
    private long version;
    private Request pending;
    private boolean inFlight;
    RouteSerialiser(Backend backend) { this.backend=backend; }
    void invalidate() { version++;pending=null; }
    void apply(int port,Runnable applied,Consumer<RuntimeException> failed) {
        pending=new Request(++version,port,applied,failed);drain();
    }
    private void drain() {
        if(inFlight || pending==null)return;
        Request request=pending;pending=null;inFlight=true;
        try {
            backend.apply(request.port,()->{
                inFlight=false;
                if(request.version==version)request.applied.run();
                drain();
            });
        }catch(RuntimeException e) {
            inFlight=false;
            if(request.version==version)request.failed.accept(e);
            drain();
        }
    }
}
