package com.darsbridge.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecoveryPolicyTest {
 @Test public void retryBudgetIsBounded(){RecoveryPolicy r=new RecoveryPolicy();assertEquals(2500,r.nextDelayMillis());assertEquals(7000,r.nextDelayMillis());assertEquals(15000,r.nextDelayMillis());for(int i=0;i<50;i++)assertEquals(-1,r.nextDelayMillis());}
 @Test public void manualRequestResetsBudget(){RecoveryPolicy r=new RecoveryPolicy();r.nextDelayMillis();r.nextDelayMillis();r.reset();assertEquals(0,r.attempts());assertEquals(2500,r.nextDelayMillis());}
 @Test public void progressFromActualTorReply(){assertEquals(45,RecoveryPolicy.bootstrapPercent("NOTICE BOOTSTRAP PROGRESS=45 TAG=requesting_descriptors SUMMARY=loading"));}
 @Test public void hundredIsValid(){assertEquals(100,RecoveryPolicy.bootstrapPercent("NOTICE BOOTSTRAP PROGRESS=100"));}
 @Test public void zeroIsValid(){assertEquals(0,RecoveryPolicy.bootstrapPercent("PROGRESS=0 TAG=starting"));}
 @Test public void rejectInvalidProgress(){for(String s:new String[]{null,"","PROGRESS=-1","PROGRESS=101","PROGRESS=1000","XPROGRESS=42","PROGRESS=1x","hello"})assertEquals(-1,RecoveryPolicy.bootstrapPercent(s));}
 @Test public void excessiveControlReplyRejected(){assertEquals(-1,RecoveryPolicy.bootstrapPercent(new String(new char[4097]).replace('\0','x')));}
}
