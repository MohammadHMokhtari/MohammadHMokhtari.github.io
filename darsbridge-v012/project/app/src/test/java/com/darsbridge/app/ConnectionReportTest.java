package com.darsbridge.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionReportTest {
    private ConnectionReport.Snapshot ready(){
        ConnectionReport.Snapshot s=new ConnectionReport.Snapshot();s.network=ConnectionReport.NetworkKind.WIFI;
        s.route=ConnectionPolicy.Route.INTERNAL;s.allowed=true;s.wanted=true;s.embeddedReady=true;s.snowflake=true;
        return s;
    }
    @Test public void readyInternalRouteDoesNotImplyTorCheck(){
        String report=ConnectionReport.render(ready());
        assertTrue(report.contains("مسیر برای بارگذاری آماده است"));
        assertTrue(report.contains("انجام نشده؛ آماده‌بودن مسیر، تأیید خروجی Tor نیست"));
    }
    @Test public void staleTorSuccessExpiresWhenBlockedOrNetworkGone(){
        ConnectionReport.Snapshot s=ready();s.check=ConnectionReport.TorCheck.TOR;
        s.route=ConnectionPolicy.Route.BLOCKED;
        String report=ConnectionReport.render(s);
        assertTrue(report.contains("نتیجهٔ قبلی دیگر"));assertFalse(report.contains("فقط درخواست آزمایش از Tor عبور کرد"));
        s.route=ConnectionPolicy.Route.INTERNAL;s.network=ConnectionReport.NetworkKind.NONE;
        assertTrue(ConnectionReport.render(s).contains("شبکهٔ فعال در گوشی دیده نمی‌شود"));
    }
    @Test public void retryDoesNotShowOldRouteAsReady(){
        ConnectionReport.Snapshot s=ready();s.recovering=true;s.check=ConnectionReport.TorCheck.TOR;
        String report=ConnectionReport.render(s);
        assertTrue(report.contains("در حال بررسی یا بازیابی مسیر"));assertTrue(report.contains("نتیجهٔ قبلی دیگر"));
        assertFalse(report.contains("وضعیت: مسیر برای بارگذاری آماده است"));
    }
    @Test public void checkResultAndFailureAreDescribedWithoutBlanketClaims(){
        ConnectionReport.Snapshot s=ready();s.check=ConnectionReport.TorCheck.NOT_TOR;
        assertTrue(ConnectionReport.render(s).contains("درخواست آزمایش از Tor عبور نکرد"));
        s.check=ConnectionReport.TorCheck.FAILED;
        assertTrue(ConnectionReport.render(s).contains("سایت‌ها را جدا امتحان کنید"));
    }
    @Test public void checkFreshnessRequiresCurrentRouteAndUnexpiredClock(){
        assertEquals(ConnectionReport.TorCheck.TOR,ConnectionReport.currentCheck(ConnectionReport.TorCheck.TOR,true,1000,300999));
        assertEquals(ConnectionReport.TorCheck.EXPIRED,ConnectionReport.currentCheck(ConnectionReport.TorCheck.TOR,true,1000,301000));
        assertEquals(ConnectionReport.TorCheck.EXPIRED,ConnectionReport.currentCheck(ConnectionReport.TorCheck.NOT_TOR,false,1000,2000));
        assertEquals(ConnectionReport.TorCheck.EXPIRED,ConnectionReport.currentCheck(ConnectionReport.TorCheck.TOR,true,2000,1000));
    }
    @Test public void versionFieldsCannotInjectPrivateOrMultilineContent(){
        ConnectionReport.Snapshot s=ready();s.version="0.11.0\nhttps://private.example/path";
        s.webViewVersion="192.168.100.200\nSSID=private-network";
        String report=ConnectionReport.render(s);
        assertFalse(report.contains("private.example"));assertFalse(report.contains("192.168"));assertFalse(report.contains("private-network"));
        s.version="0.11.0";s.webViewVersion="146.0.7680.30";
        assertTrue(ConnectionReport.render(s).contains("WebView: 146.0.7680.30"));
    }
}
