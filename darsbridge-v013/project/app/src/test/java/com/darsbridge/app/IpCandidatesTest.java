package com.darsbridge.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class IpCandidatesTest {
    private void rejectsHost(String value){try{IpCandidates.host(value);fail(value);}catch(IllegalArgumentException expected){}}
    private void rejectsIps(String value){try{IpCandidates.build(value,1);fail(value);}catch(IllegalArgumentException expected){}}
    @Test public void normalizesDomainWithoutResolvingIt(){
        assertEquals("www.google.com",IpCandidates.host(" https://WWW.Google.COM/ "));
        assertTrue(IpCandidates.host("مثال.ir").startsWith("xn--"));
        assertEquals("test.darsbridge.invalid",IpCandidates.host("test.darsbridge.invalid."));
    }
    @Test public void rejectsHeadersCredentialsPathsAndNonHttps(){
        for(String s:new String[]{"http://example.org","example.org/path","user@example.org","example.org:443","example.org?token=x","example.org#part","example.org\r\nHost:other.org","1.1.1.1","localhost","-bad.example","bad..example"})rejectsHost(s);
    }
    @Test public void acceptsOnlyCanonicalNumericIpv4(){
        assertEquals(0x01010101L,IpCandidates.number("1.1.1.1"));
        for(String s:new String[]{"1.1.1","1.1.1.256","001.1.1.1","0x7f.1.1.1","2130706433","example.org","::1","1.1.1.1:443"})rejectsIps(s);
    }
    @Test public void excludesPrivateLocalAndDocumentationAddresses(){
        for(String s:new String[]{"0.1.2.3","10.0.0.1","100.64.0.1","127.0.0.1","169.254.1.1","172.16.1.1","192.168.1.1","192.0.2.1","198.51.100.1","203.0.113.1","224.1.1.1","255.255.255.255"})rejectsIps(s);
    }
    @Test public void validatesAllEntriesBeforeReturningPartialResults(){
        rejectsIps("1.1.1.1\nnot-an-ip");rejectsIps("1.1.1.1\n8.8.8.0/33");rejectsIps("1.1.1.1\n8.8.8.8/24/24");
    }
    @Test public void preservesLiteralsAndDeduplicatesHostRanges(){
        List<String> result=IpCandidates.build("1.1.1.1\n1.1.1.1/32\n8.8.8.8",12);
        assertEquals(Arrays.asList("1.1.1.1","8.8.8.8"),result);
        assertEquals(2,IpCandidates.build("1.1.1.0/31",1).size());
    }
    @Test public void samplesWithinTheRequestedPrefixAndCanVarySearch(){
        List<String> first=IpCandidates.build("142.250.0.0/15",7),second=IpCandidates.build("142.250.0.0/15",8);
        assertEquals(first,IpCandidates.build("142.250.0.0/15",7));assertNotEquals(first,second);
        for(String s:first)assertEquals(0x8efa0000L,IpCandidates.number(s)&0xfffe0000L);
    }
    @Test(timeout=1000) public void hugeRangesAreBoundedInsteadOfEnumerated(){
        List<String> all=IpCandidates.build("0.0.0.0/0",9);assertTrue(all.size()<=IpCandidates.MAX);
        for(String s:all)assertTrue(IpCandidates.isPublic(IpCandidates.number(s)));
    }
    @Test public void offlinePresetsAreBoundedPublicCandidates(){
        for(String preset:new String[]{IpCandidates.GOOGLE,IpCandidates.CLOUDFLARE}){
            List<String> values=IpCandidates.build(preset,45);assertFalse(values.isEmpty());assertTrue(values.size()<=IpCandidates.MAX);
            for(String value:values)assertTrue(IpCandidates.isPublic(IpCandidates.number(value)));
        }
        assertEquals("216.239.38.120",IpCandidates.build(IpCandidates.GOOGLE,4).get(0));
    }
    @Test public void oversizedInputIsRejectedBeforeWorkStarts(){
        rejectsIps(String.join("\n",Collections.nCopies(65,"1.1.1.1")));
        rejectsIps(String.join("",Collections.nCopies(17000,"1")));
    }
}
