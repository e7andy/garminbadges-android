package com.garminbadges.app;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class AuthActivity extends AppCompatActivity {

    public static final String EXTRA_CSRF_TOKEN = "csrf_token";

    private WebView webView;
    private volatile String csrfToken = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        webView = findViewById(R.id.webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onToken(String token) {
                if (token != null && !token.isEmpty()) {
                    csrfToken = token;
                }
            }
        }, "GarminBadges");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains("connect.garmin.com")) {
                    view.evaluateJavascript(
                        "(function() {" +
                        "  var m = document.querySelector(\"meta[name='_token']\") ||" +
                        "          document.querySelector(\"meta[name='csrf-token']\");" +
                        "  window.GarminBadges.onToken(m ? m.content : '');" +
                        "})()",
                        null
                    );
                }
            }
        });

        webView.loadUrl("https://connect.garmin.com");

        findViewById(R.id.btnDone).setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_CSRF_TOKEN, csrfToken);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
