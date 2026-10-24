package com.darsbridge.app;
import org.junit.Test;
import static org.junit.Assert.*;

public class HealthStateTest {
    @Test public void networkChangeRejectsInflightResultAndRequiresNewCheck() {
        HealthState h=new HealthState();h.start();HealthState.Check old=h.beginCheck();
        h.invalidate();assertNull(h.beginCheck());
        assertFalse(h.complete(old,true,9050));assertFalse(h.ready());assertEquals(-1,h.port());
        assertTrue(h.complete(h.beginCheck(),true,9051));assertTrue(h.ready());assertEquals(9051,h.port());
    }
    @Test public void oldRunCannotReleaseNewRunsCheck() {
        HealthState h=new HealthState();h.start();HealthState.Check old=h.beginCheck();h.stop();h.start();
        HealthState.Check fresh=h.beginCheck();assertFalse(h.complete(old,true,9050));
        assertNull(h.beginCheck());assertTrue(h.complete(fresh,true,9051));assertEquals(9051,h.port());
    }
    @Test public void foregroundReturnCannotReusePreviousReadyOrOldResult() {
        HealthState h=new HealthState();h.start();h.complete(h.beginCheck(),true,9050);
        HealthState.Check old=h.beginCheck();h.foreground(false);assertFalse(h.ready());assertNull(h.beginCheck());
        h.foreground(true);assertFalse(h.complete(old,true,9050));assertFalse(h.ready());
        assertTrue(h.complete(h.beginCheck(),true,9050));assertTrue(h.ready());
    }
    @Test public void stoppedStateNeverAcceptsReadiness() {
        HealthState h=new HealthState();h.start();HealthState.Check check=h.beginCheck();h.stop();
        assertFalse(h.complete(check,true,9050));assertFalse(h.ready());assertNull(h.beginCheck());
    }
    @Test public void failedCircuitAndInvalidPortsAreNeverReady() {
        HealthState h=new HealthState();h.start();
        for(int port:new int[]{-1,0,65536}) {assertTrue(h.complete(h.beginCheck(),true,port));assertFalse(h.ready());}
        assertTrue(h.complete(h.beginCheck(),false,9050));assertFalse(h.ready());
        h.complete(h.beginCheck(),true,65535);assertTrue(h.ready());
    }
}
