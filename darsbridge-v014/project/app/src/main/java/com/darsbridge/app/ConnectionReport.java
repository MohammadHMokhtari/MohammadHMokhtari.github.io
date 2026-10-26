package com.darsbridge.app;

/** Local, allow-listed snapshot. Never accepts URLs, IPs, SSIDs, text, or exception logs. */
final class ConnectionReport {
    enum TorCheck { NOT_RUN, CHECKING, TOR, NOT_TOR, FAILED, EXPIRED }
    enum NetworkKind { NONE, WIFI, MOBILE, OTHER }
    static final class Snapshot {
        String version="",webViewVersion="";
        int versionCode,androidApi;
        ConnectionPolicy.Mode mode=ConnectionPolicy.Mode.AUTO;
        ConnectionPolicy.Route route=ConnectionPolicy.Route.BLOCKED;
        NetworkKind network=NetworkKind.NONE;
        TorCheck check=TorCheck.NOT_RUN;
        boolean vpn,allowed,wanted,changing,recovering,embeddedStarting,embeddedReady,snowflake;
        boolean autoReconnect,contentGuard,restoreBlocked,restoreBusy,hasDiagnostic;
    }
    private ConnectionReport() {}
    static TorCheck currentCheck(TorCheck check,boolean allowed,long verifiedAt,long now){
        if(check==TorCheck.TOR || check==TorCheck.NOT_TOR)
            return allowed && verifiedAt>0 && now>=verifiedAt && now-verifiedAt<300000?check:TorCheck.EXPIRED;
        return check==null?TorCheck.NOT_RUN:check;
    }
    private static String version(String value){
        return value!=null && value.matches("[0-9]{1,5}(?:\\.[0-9]{1,10}){0,4}")?value:"نامشخص";
    }
    private static String yes(boolean value){return value?"بله":"خیر";}
    static String modeName(ConnectionPolicy.Mode mode){
        if(mode==ConnectionPolicy.Mode.SYSTEM)return "شبکهٔ گوشی (انتخاب دستی)";
        if(mode==ConnectionPolicy.Mode.LOCAL)return "برنامهٔ کمکی";
        if(mode==ConnectionPolicy.Mode.SNOWFLAKE)return "پل داخلی Snowflake";
        if(mode==ConnectionPolicy.Mode.EMBEDDED)return "Tor مستقیم";
        return "خودکار";
    }
    private static String routeName(Snapshot s){
        if(s.route==ConnectionPolicy.Route.INTERNAL)return s.snowflake?"پل داخلی Snowflake":"Tor مستقیم داخلی";
        if(s.route==ConnectionPolicy.Route.SOCKS)return "پراکسی محلی برنامهٔ کمکی";
        if(s.route==ConnectionPolicy.Route.SYSTEM)return s.vpn?"شبکهٔ گوشی با VPN":"شبکهٔ عادی گوشی";
        return "مسیر مرورگر بسته است";
    }
    static String stage(Snapshot s){
        if(s.network==NetworkKind.NONE)return "شبکهٔ فعال در گوشی دیده نمی‌شود";
        if(s.changing || s.recovering)return "در حال بررسی یا بازیابی مسیر";
        if(s.allowed && s.route!=ConnectionPolicy.Route.BLOCKED)return "مسیر برای بارگذاری آماده است";
        if(s.embeddedStarting && !s.embeddedReady)return "اتصال داخلی هنوز در حال آماده‌شدن است";
        if(!s.wanted)return "درخواست اتصال فعال نیست";
        return "مسیر برای بارگذاری آماده نیست";
    }
    private static String checkName(TorCheck check){
        if(check==TorCheck.CHECKING)return "آزمایش در حال اجراست";
        if(check==TorCheck.TOR)return "فقط درخواست آزمایش از Tor عبور کرد";
        if(check==TorCheck.NOT_TOR)return "درخواست آزمایش از Tor عبور نکرد";
        if(check==TorCheck.FAILED)return "آزمایش پاسخ قابل‌تأیید نداد؛ سایت‌ها را جدا امتحان کنید";
        if(check==TorCheck.EXPIRED)return "نتیجهٔ قبلی دیگر برای وضعیت فعلی معتبر نیست";
        return "انجام نشده؛ آماده‌بودن مسیر، تأیید خروجی Tor نیست";
    }
    static String render(Snapshot s){
        TorCheck check=s.check;
        if(!s.allowed || s.route==ConnectionPolicy.Route.BLOCKED || s.network==NetworkKind.NONE || s.changing || s.recovering)
            if(check==TorCheck.TOR || check==TorCheck.NOT_TOR)check=TorCheck.EXPIRED;
        String net=s.network==NetworkKind.WIFI?"Wi-Fi":s.network==NetworkKind.MOBILE?"دادهٔ همراه":s.network==NetworkKind.NONE?"بدون شبکهٔ فعال":"سایر شبکه‌ها";
        return "وضعیت محلی درس‌گذر در زمان بازکردن گزارش\n"+
            "نسخه: "+version(s.version)+" ("+Math.max(0,s.versionCode)+")\n"+
            "Android API: "+Math.max(0,s.androidApi)+"\nWebView: "+version(s.webViewVersion)+"\n"+
            "شبکه: "+net+"\nVPN فعال: "+yes(s.vpn)+"\n"+
            "انتخاب مسیر: "+modeName(s.mode)+"\nمسیر فعلی: "+routeName(s)+"\n"+
            "وضعیت: "+stage(s)+"\nبارگذاری شبکه در مرورگر مجاز است: "+yes(s.allowed)+"\n"+
            "بررسی خروجی Tor: "+checkName(check)+"\n"+
            "اتصال مجدد خودکار: "+yes(s.autoReconnect)+"\nمحافظ اولیهٔ محتوای وب فعال است: "+yes(s.contentGuard)+"\n"+
            "انتقال اطلاعات در حال اجراست: "+yes(s.restoreBusy)+"\nبازیابی ناتمام نیاز به بررسی دارد: "+yes(s.restoreBlocked)+"\n"+
            "گزارش خطای داخلی جداگانه موجود است: "+yes(s.hasDiagnostic)+"\n\n"+
            "این گزارش نشانی سایت‌ها، IP، نام شبکه، نشانک‌ها یا متن‌های ذخیره‌شده را شامل نمی‌شود. " +
            "هیچ چیز خودکار ارسال نمی‌شود. آماده‌بودن مسیر تضمین بازشدن همهٔ سایت‌ها یا ناشناس‌ماندن نیست.";
    }
}
