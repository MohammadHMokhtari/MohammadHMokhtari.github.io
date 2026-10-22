package com.darsbridge.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class UrlToolsTest {
    @Test public void bareHost(){assertEquals("https://youtube.com/",UrlTools.normalize("youtube.com"));}
    @Test public void trim(){assertEquals("https://youtube.com/",UrlTools.normalize("  youtube.com  "));}
    @Test public void mobilePath(){assertEquals("https://m.youtube.com/watch?v=abc#top",UrlTools.normalize("https://m.youtube.com/watch?v=abc#top"));}
    @Test public void httpUpgrade(){assertEquals("https://example.com/",UrlTools.normalize("http://example.com"));}
    @Test public void caseNormalize(){assertEquals("https://example.com/PATH",UrlTools.normalize("HTTPS://EXAMPLE.COM/PATH"));}
    @Test public void port(){assertEquals("https://example.com:8443/",UrlTools.normalize("https://example.com:8443"));}
    @Test public void ipv6(){assertEquals("https://[::1]/",UrlTools.normalize("https://[::1]"));}
    @Test public void preserveEscape(){assertEquals("https://example.com/a%20b?q=%2F",UrlTools.normalize("https://example.com/a%20b?q=%2F"));}
    @Test public void idn(){assertEquals("https://xn--bcher-kva.de/",UrlTools.normalize("https://bücher.de"));}
    @Test public void unicodePath(){assertTrue(UrlTools.normalize("example.com/سلام").startsWith("https://example.com/%"));}
    @Test public void rejectSchemes(){for(String s:new String[]{"javascript:alert(1)","file:///etc/passwd","content://data","intent://foo","data:text/html,hi","ftp://a.org"})bad(s);}
    @Test public void rejectCredentials(){bad("https://a:b@example.com");bad("https://user@example.com");bad("https://user%40example.com");}
    @Test public void rejectWhitespace(){bad("example.com/a b");bad("https://example.com/\nfoo");}
    @Test public void rejectBidi(){bad("https://example.com/\u202eevil");}
    @Test public void rejectEmpty(){bad("");bad(" ");bad(null);bad("https://");}
    @Test public void rejectPorts(){bad("https://example.com:0");bad("https://example.com:99999");bad("https://example.com:-3");}
    @Test public void rejectBrokenHosts(){bad("https://example..com");bad("https://.example.com");bad("https://example.com\\@evil.com");}
    @Test public void httpsDetection(){assertTrue(UrlTools.isHttps("https://example.com"));assertFalse(UrlTools.isHttps("https://user@example.com"));assertFalse(UrlTools.isHttps("http://example.com"));assertFalse(UrlTools.isHttps(null));}
    private void bad(String s){try{UrlTools.normalize(s);fail("Accepted: "+s);}catch(IllegalArgumentException expected){}}
}
