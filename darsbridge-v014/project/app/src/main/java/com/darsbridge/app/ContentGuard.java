package com.darsbridge.app;

import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Defense in depth for common page WebRTC entry points, not an OS network firewall. */
final class ContentGuard {
    private ContentGuard() { }
    static boolean install(WebView view) {
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))return false;
        try(java.io.InputStream input=view.getResources().openRawResource(R.raw.web_policy)) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[2048];int n;
            while((n=input.read(buffer))!=-1)out.write(buffer,0,n);
            WebViewCompat.addDocumentStartJavaScript(view,out.toString(StandardCharsets.UTF_8.name()),Collections.singleton("*"));
            return true;
        }catch(java.io.IOException | RuntimeException e) { return false; }
    }
}
