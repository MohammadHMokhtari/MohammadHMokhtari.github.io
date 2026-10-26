package com.darsbridge.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class LinkActionsTest {
    private void rejected(String value){try{LinkActions.resolve(value,"https://example.org/search");fail(value);}catch(IllegalArgumentException expected){}}
    @Test public void absoluteHrefKeepsQueryAndFragment(){assertEquals("https://example.org/post?q=one#part",LinkActions.resolve("https://example.org/post?q=one#part",null));}
    @Test public void relativeHrefUsesActualDocument(){assertEquals("https://example.org/article",LinkActions.resolve("../article","https://example.org/search/page"));}
    @Test public void schemeRelativeLinksUseHttps(){assertEquals("https://other.example/item",LinkActions.resolve("//other.example/item","https://example.org/"));}
    @Test public void executableAndExternalSchemesAreRejected(){for(String value:new String[]{"javascript:alert(1)","data:text/html,hi","file:///private","content://private/item","intent://host","mailto:a@example.org","http://example.org/"})rejected(value);}
    @Test public void credentialsAndAmbiguousCharactersAreRejected(){for(String value:new String[]{"https://name:pass@example.org/","https://example.org/ bad","https://example.org/\nfoo","https://example.org/\\evil","https://example.org/\u202eevil"})rejected(value);}
    @Test public void missingOrOversizedHrefIsRejected(){rejected(null);rejected("");rejected("x".repeat(8193));}
    @Test public void relativeHrefNeedsHttpsBase(){try{LinkActions.resolve("/page","http://example.org/");fail();}catch(IllegalArgumentException expected){}}
    @Test public void labelsAreBoundedAndRemoveControlCharacters(){assertEquals("a b c",LinkActions.label("a\nb\u202ec"));assertEquals(400,LinkActions.label("x".repeat(500)).length());assertEquals("",LinkActions.label(null));}
}
