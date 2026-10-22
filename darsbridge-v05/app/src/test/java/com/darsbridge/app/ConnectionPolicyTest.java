package com.darsbridge.app;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.darsbridge.app.ConnectionPolicy.*;

public class ConnectionPolicyTest {
    @Test public void autoVpnFirst(){assertEquals(Route.SYSTEM,choose(Mode.AUTO,true,true,true,true));}
    @Test public void autoEmbeddedWhenReady(){assertEquals(Route.INTERNAL,choose(Mode.AUTO,false,true,true,true));}
    @Test public void autoExternalSocks(){assertEquals(Route.SOCKS,choose(Mode.AUTO,false,true,true,false));}
    @Test public void autoBlocksIfNoRoutes(){assertEquals(Route.BLOCKED,choose(Mode.AUTO,false,false,true,false));}
    @Test public void autoNeedsProxySupport(){assertEquals(Route.BLOCKED,choose(Mode.AUTO,false,true,false,true));}
    @Test public void localCannotUseInternal(){assertEquals(Route.BLOCKED,choose(Mode.LOCAL,false,false,true,true));}
    @Test public void localRequiresProxy(){assertEquals(Route.BLOCKED,choose(Mode.LOCAL,true,true,false,true));}
    @Test public void snowflakeExplicitNoDirectFallback(){assertEquals(Route.BLOCKED,choose(Mode.SNOWFLAKE,true,true,true,false));}
    @Test public void snowflakeOnlyReadyInternal(){assertEquals(Route.INTERNAL,choose(Mode.SNOWFLAKE,false,false,true,true));}
    @Test public void snowflakeNeedsProxyOverride(){assertEquals(Route.BLOCKED,choose(Mode.SNOWFLAKE,false,true,false,true));}
    @Test public void embeddedCannotUseExternal(){assertEquals(Route.BLOCKED,choose(Mode.EMBEDDED,true,true,true,false));}
    @Test public void embeddedNeedsProxy(){assertEquals(Route.BLOCKED,choose(Mode.EMBEDDED,false,true,false,true));}
    @Test public void embeddedReady(){assertEquals(Route.INTERNAL,choose(Mode.EMBEDDED,false,false,true,true));}
    @Test public void explicitNetworkOptIn(){assertEquals(Route.SYSTEM,choose(Mode.SYSTEM,false,false,false,false));}
    @Test public void parseModes(){assertEquals(Mode.AUTO,parse("no"));assertEquals(Mode.AUTO,parse(null));assertEquals(Mode.EMBEDDED,parse("EMBEDDED"));}
    @Test public void ports(){assertEquals(9050,parsePort("9050"));assertEquals(1,parsePort("1"));assertEquals(65535,parsePort("65535"));}
    @Test public void invalidPorts(){for(String bad:new String[]{"0","65536","-1","xyz",null,""})try{parsePort(bad);fail();}catch(IllegalArgumentException expected){}}
    @Test public void noUnsafeAutoFallback(){
        for(boolean vpn:new boolean[]{false,true})
        for(boolean socks:new boolean[]{false,true})
        for(boolean internal:new boolean[]{false,true})
        for(boolean proxy:new boolean[]{false,true}){
            Route r=choose(Mode.AUTO,vpn,socks,proxy,internal);
            if(!vpn)assertNotEquals(Route.SYSTEM,r);
            if(r==Route.INTERNAL){assertTrue(internal);assertTrue(proxy);}
            if(r==Route.SOCKS){assertTrue(socks);assertTrue(proxy);}
        }
    }
}
