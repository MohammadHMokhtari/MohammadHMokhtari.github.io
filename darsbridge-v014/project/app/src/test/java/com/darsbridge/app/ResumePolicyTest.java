package com.darsbridge.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class ResumePolicyTest {
    @Test public void rememberedRequestResumesOnlyWithSettingEnabled(){assertTrue(ResumePolicy.reconnect(true,true));assertFalse(ResumePolicy.reconnect(false,true));}
    @Test public void explicitStopAndFirstUseRemainOff(){assertFalse(ResumePolicy.reconnect(true,false));assertFalse(ResumePolicy.reconnect(false,false));}
    @Test public void savedGetCanResume(){assertTrue(ResumePolicy.restorePage(true,false,true,"https://example.org/chat/123"));}
    @Test public void stoppedOrHomeDoesNotOpenSavedPage(){assertFalse(ResumePolicy.restorePage(false,false,true,"https://example.org/"));assertFalse(ResumePolicy.restorePage(true,true,true,"https://example.org/"));}
    @Test public void postOrUnknownRequestIsNotRestored(){assertFalse(ResumePolicy.restorePage(true,false,false,"https://example.org/submitted"));}
    @Test public void scriptsAndInsecureAddressesCannotResume(){assertFalse(ResumePolicy.restorePage(true,false,true,"javascript:alert(1)"));assertFalse(ResumePolicy.restorePage(true,false,true,"http://example.org/"));}
}
