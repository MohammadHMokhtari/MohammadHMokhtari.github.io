package com.darsbridge.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RouteSerialiserTest {
    @Test public void onlyLatestProxyCanEnableTrafficAndWritesAreSerial() {
        List<Integer> ports=new ArrayList<>();List<Runnable> completions=new ArrayList<>();
        RouteSerialiser r=new RouteSerialiser((port,done)->{ports.add(port);completions.add(done);});
        AtomicInteger enabled=new AtomicInteger();
        r.apply(9050,()->enabled.set(1),e->fail());
        r.apply(-1,()->enabled.set(2),e->fail());
        r.apply(9051,()->enabled.set(3),e->fail());
        assertEquals(Arrays.asList(9050),ports);completions.get(0).run();
        assertEquals(0,enabled.get());assertEquals(Arrays.asList(9050,9051),ports);
        completions.get(1).run();assertEquals(3,enabled.get());
    }
    @Test public void disconnectCancelsQueuedAndInflightCallbacks() {
        List<Runnable> completions=new ArrayList<>();AtomicInteger enabled=new AtomicInteger();
        RouteSerialiser r=new RouteSerialiser((port,done)->completions.add(done));
        r.apply(9050,enabled::incrementAndGet,e->fail());r.apply(-1,enabled::incrementAndGet,e->fail());
        r.invalidate();completions.get(0).run();assertEquals(0,enabled.get());assertEquals(1,completions.size());
        r.apply(9052,enabled::incrementAndGet,e->fail());completions.get(1).run();assertEquals(1,enabled.get());
    }
    @Test public void backendFailureDoesNotRunAppliedCallback() {
        AtomicInteger applied=new AtomicInteger(),failed=new AtomicInteger();
        RouteSerialiser r=new RouteSerialiser((port,done)->{throw new IllegalStateException();});
        r.apply(9050,applied::incrementAndGet,e->failed.incrementAndGet());
        assertEquals(0,applied.get());assertEquals(1,failed.get());
    }
}
