package com.darsbridge.app;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.darsbridge.app.ConnectionPolicy.*;
public class ConnectionPolicyTest {
    @Test public void autoPrefersWorkingSystemVpn(){assertEquals(Route.SYSTEM,choose(Mode.AUTO,true,true,true));}
    @Test public void autoUsesLocalIfNoVpn(){assertEquals(Route.SOCKS,choose(Mode.AUTO,false,true,true));}
    @Test public void autoNeverFallsBackToDirect(){assertEquals(Route.BLOCKED,choose(Mode.AUTO,false,false,true));}
    @Test public void autoBlocksUnsupportedProxy(){assertEquals(Route.BLOCKED,choose(Mode.AUTO,false,true,false));}
    @Test public void localCannotUseSystemInstead(){assertEquals(Route.BLOCKED,choose(Mode.LOCAL,true,false,true));}
    @Test public void localNeedsSupport(){assertEquals(Route.BLOCKED,choose(Mode.LOCAL,true,true,false));}
    @Test public void localExplicit(){assertEquals(Route.SOCKS,choose(Mode.LOCAL,true,true,true));}
    @Test public void systemIsExplicitOptIn(){assertEquals(Route.SYSTEM,choose(Mode.SYSTEM,false,false,false));}
    @Test public void safeUnknownPreference(){assertEquals(Mode.AUTO,parse("broken"));assertEquals(Mode.AUTO,parse(null));}
    @Test public void portRange(){assertEquals(9050,parsePort(" 9050 "));assertEquals(1,parsePort("1"));assertEquals(65535,parsePort("65535"));}
    @Test public void invalidPorts(){for(String s:new String[]{"0","65536","-1","abc","",null})try{parsePort(s);fail();}catch(IllegalArgumentException expected){}}
    @Test public void exhaustiveNoUnsafeFallback(){
        for(boolean vpn:new boolean[]{false,true})for(boolean socks:new boolean[]{false,true})for(boolean supported:new boolean[]{false,true}){
            Route r=choose(Mode.AUTO,vpn,socks,supported);
            if(r==Route.SYSTEM)assertTrue(vpn);
            if(r==Route.SOCKS){assertTrue(socks);assertTrue(supported);}
        }
    }
}
