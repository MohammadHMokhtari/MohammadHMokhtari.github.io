package com.darsbridge.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class OmniboxTest {
    @Test public void englishSearch(){assertEquals("https://www.google.com/search?q=multiple%20sclerosis",Omnibox.resolve("multiple sclerosis"));}
    @Test public void plainWord(){assertEquals("https://www.google.com/search?q=tennis",Omnibox.resolve("tennis"));}
    @Test public void persianSearch(){assertEquals("https://www.google.com/search?q=%D8%A2%D9%85%D9%88%D8%B2%D8%B4%20%D8%AA%D9%86%DB%8C%D8%B3",Omnibox.resolve("آموزش تنیس"));}
    @Test public void googleOperator(){assertEquals("https://www.google.com/search?q=site%3Aedu",Omnibox.resolve("site:edu"));}
    @Test public void preservePlusAndAmpersand(){assertEquals("https://www.google.com/search?q=C%2B%2B%20%26%20AI",Omnibox.resolve("C++ & AI"));}
    @Test public void noDomainLocalWord(){assertEquals("https://www.google.com/search?q=localhost",Omnibox.resolve("localhost"));}
    @Test public void bareDotDomain(){assertEquals("https://youtube.com/",Omnibox.resolve("youtube.com"));}
    @Test public void universityDotIr(){assertEquals("https://ut.ac.ir/",Omnibox.resolve("ut.ac.ir"));}
    @Test public void pagePath(){assertEquals("https://example.com/a?x=1",Omnibox.resolve("example.com/a?x=1"));}
    @Test public void explicitHttps(){assertEquals("https://example.com/",Omnibox.resolve("https://example.com"));}
    @Test public void upgradeHttp(){assertEquals("https://example.com/",Omnibox.resolve("http://example.com"));}
    @Test public void ipv4(){assertEquals("https://127.0.0.1/",Omnibox.resolve("127.0.0.1"));}
    @Test public void ipv6(){assertEquals("https://[::1]/",Omnibox.resolve("[::1]"));}
    @Test public void unicodeDomain(){assertEquals("https://xn--bcher-kva.de/",Omnibox.resolve("bücher.de"));}
    @Test public void domainWithSpacesBecomesSearch(){assertEquals("https://www.google.com/search?q=youtube.com%20tennis",Omnibox.resolve("youtube.com tennis"));}
    @Test public void searchStartsWithNumbers(){assertEquals("https://www.google.com/search?q=12345",Omnibox.resolve("12345"));}
    @Test public void rejectExecutableSchemes(){for(String s:new String[]{"javascript:alert(1)","data:text/html,abc","file:///etc/passwd","intent://test","ftp://test.com","content://test"})reject(s);}
    @Test public void rejectMalformedExplicit(){reject("https://invalid host");reject("https://user:secret@example.com");}
    @Test public void rejectEmpty(){reject("");reject(" ");reject(null);}
    @Test public void rejectTooLong(){reject("a".repeat(8193));}
    private void reject(String s){try{Omnibox.resolve(s);fail("Accepted: "+s);}catch(IllegalArgumentException expected){}}
}
