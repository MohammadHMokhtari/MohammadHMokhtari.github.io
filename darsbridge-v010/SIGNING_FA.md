# امضای ثابت درس‌گذر

شناسهٔ انتشار ثابت `com.darsbridge.browser` است. گواهی عمومی در signing/release-cert.pem و SHA256 آن در signing/PUBLIC_SIGNING.json قرار دارد. این‌ها اطلاعات عمومی‌اند و برای امضا کافی نیستند.

کلید خصوصی در مخزن، APK، artifactهای CI یا گزارش‌ها قرار نمی‌گیرد. فایل بازیابی خصوصی با نام دقیق `DarsBridge_Release_Signing_Recovery_PRIVATE.zip` خارج از مخزن نگهداری می‌شود. برای ساخت انتشار بعدی، فایل با همین نام را از فایل‌های خصوصی کاربر و با راهنمای Library پیدا و در مسیری خصوصی آماده کنید؛ محتوای آن را در خروجی ابزار، پیام یا فایل عمومی چاپ نکنید. این بسته شامل کلید و رمزهای آن است و خودِ ZIP رمزگذاری نشده؛ دسترسی به آن اختیار امضای برنامه را می‌دهد. کلید تازه نسازید و گواهی ثابت را تغییر ندهید.

## مسیر انتشار

۱. در شاخهٔ مجزا توسعه دهید؛ main و آخرین نسخهٔ کارکرده حفظ شوند.
۲. applicationId، نام تنظیمات و مسیر فایل‌ها ثابت بمانند. versionCode افزایش یابد؛ قالب‌های ذخیره‌شده فقط با مهاجرت صریح تغییر کنند.
۳. CI باید unit، lint، emulator و آزمون آپدیت release را موفق اجرا کند. ورودی unsigned و RELEASE_INPUT.json را از همان commit بگیرید و digest artifact را بررسی کنید.
۴. ابزار sign_release.py ورودی را با SHA کد، hash APK و hash ابزار امضا تطبیق می‌دهد. کلید را از بستهٔ خصوصی در پوشهٔ موقت محدود آماده و رمزها را از طریق متغیر محیطی به apksigner می‌دهد. رمزها چاپ نمی‌شوند.
۵. APK امضاشده باید با apksigner verify، v2 و v3، و SHA256 گواهی ثابت تأیید شود. خروجی واقعی در SIGNED_RELEASE.json ثبت شود.
۶. فایل نصب آماده را فقط پس از این مراحل تحویل دهید. کلید و رمزها هرگز همراه APK یا سورس تحویل عمومی نشوند.

فرمان امضای نسخهٔ حاضر، با مسیرهای واقعیِ محیط خصوصی:

```text
python3 tools/sign_release.py --input-dir INPUT_DIR --recovery PRIVATE_RECOVERY_ZIP --public-config signing/PUBLIC_SIGNING.json --expected-code-sha TESTED_SHA --expected-version-code 10 --output DarsBridge-0.10.apk
```

آزمون CI یک کلید موقت جدا با عمر کوتاه می‌سازد. APKهای آن فقط روی شبیه‌ساز نصب می‌شوند و خروجی انتشار نیستند. کلید موقت CI برای آینده استفاده نشود.

امضای ۰.۹ از نوع debug و شناسهٔ آن جداست؛ انتقال اولیه از ۰.۹ با پشتیبان انجام می‌شود. هیچ ادعایی دربارهٔ دراختیارداشتن کلید قبلی یا تبدیل نصب قبلی به آپدیت مستقیم وجود ندارد.

مرجع: https://developer.android.com/studio/publish/app-signing و https://developer.android.com/tools/apksigner
