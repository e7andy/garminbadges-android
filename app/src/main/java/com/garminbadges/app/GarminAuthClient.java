package com.garminbadges.app;

import android.util.Base64;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

public class GarminAuthClient {

    public static class AuthResult {
        public final String accessToken;
        public final String refreshToken;
        AuthResult(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    public static class PendingAuth {
        public final boolean mfaRequired;
        public final AuthResult result; // non-null when mfaRequired == false
        PendingAuth(boolean mfaRequired, AuthResult result) {
            this.mfaRequired = mfaRequired;
            this.result = result;
        }
    }

    private static final String DOMAIN = "garmin.com";
    private static final String SSO_EMBED =
        "https://sso.garmin.com/sso/embed?id=gauth-widget&embedWidget=true" +
        "&gauthHost=https://sso.garmin.com/sso";
    private static final String SSO_SIGNIN = "https://sso.garmin.com/sso/signin";
    private static final String MFA_URL =
        "https://sso.garmin.com/sso/verifyMFA/loginEnterMfaCode";
    private static final String DI_TOKEN_URL =
        "https://diauth.garmin.com/di-oauth2-service/oauth/token";
    private static final String GRANT_TYPE_TICKET =
        "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket";

    private static final String[] CLIENT_IDS = {
        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
        "GARMIN_CONNECT_MOBILE_ANDROID_DI",
    };

    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final List<Cookie> cookieStore = new ArrayList<>();
    private final OkHttpClient client;

    // State kept between startAuthentication() and submitMfa()
    private String savedCsrf;
    private String mfaActualUrl; // actual URL (with query params) the redirect landed on
    private String savedFromPage;

    public GarminAuthClient() {
        client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(new CookieJar() {
                @Override
                public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                    for (Cookie incoming : cookies) {
                        cookieStore.removeIf(c -> c.name().equals(incoming.name())
                                              && c.domain().equals(incoming.domain()));
                    }
                    cookieStore.addAll(cookies);
                }
                @Override
                public List<Cookie> loadForRequest(HttpUrl url) {
                    return cookieStore.stream()
                        .filter(c -> c.matches(url))
                        .collect(Collectors.toList());
                }
            })
            .build();
    }

    public PendingAuth startAuthentication(String email, String password) throws IOException {
        // Step 1: GET embed page to seed SSO cookies
        get(SSO_EMBED, "https://connect.garmin.com/");

        // Step 2: GET sign-in page (with embed params) to get _csrf token
        String signinUrl = SSO_SIGNIN
            + "?gauthHost=" + encode(SSO_EMBED)
            + "&service=" + encode(SSO_EMBED)
            + "&source=" + encode(SSO_EMBED)
            + "&redirectAfterAccountLoginUrl=" + encode(SSO_EMBED);
        String signinHtml = get(signinUrl, SSO_EMBED);
        if (signinHtml.contains("_cf_chl_opt")) throw new IOException("CF_BLOCKED");

        String csrf = parseField(signinHtml, "_csrf");
        if (csrf == null) csrf = "";
        savedCsrf = csrf;

        // Step 3: POST credentials
        FormBody.Builder form = new FormBody.Builder()
            .add("username", email)
            .add("password", password)
            .add("embed", "true")
            .add("_eventId", "submit");
        if (!csrf.isEmpty()) form.add("_csrf", csrf);

        String postHtml;
        String postFinalUrl;
        try (Response r = client.newCall(new Request.Builder()
                .url(signinUrl)
                .header("User-Agent", UA)
                .header("Referer", signinUrl)
                .post(form.build())
                .build()).execute()) {
            postHtml = r.body().string();
            postFinalUrl = r.request().url().toString();
        }

        if (postHtml.contains("Invalid credentials") || postHtml.contains("invalid.password")
                || postHtml.contains("Your credentials are incorrect")
                || postHtml.contains("wrong.password")
                || postHtml.contains("badCredentials")) {
            throw new IOException("INVALID_CREDENTIALS");
        }
        if (postHtml.contains("_cf_chl_opt")) throw new IOException("CF_BLOCKED");

        // Try to extract service ticket from response
        String ticket = extractTicket(postHtml);
        if (ticket != null) {
            android.util.Log.d("GarminAuth", "Got service ticket, exchanging for OAuth tokens");
            return new PendingAuth(false, exchangeTicket(ticket));
        }

        // Detect MFA
        if (postHtml.contains("loginEnterMfaCode") || postHtml.contains("verifyMFA")
                || postHtml.contains("mfa-code") || postHtml.contains("mfaMethod")) {
            // Refresh csrf from MFA page if present
            String mfaCsrf = parseField(postHtml, "_csrf");
            if (mfaCsrf != null) savedCsrf = mfaCsrf;
            // Track the actual URL we landed on (may have ?gauthHost=... etc.)
            mfaActualUrl = postFinalUrl;
            savedFromPage = parseField(postHtml, "fromPage");
            android.util.Log.d("GarminAuth", "MFA required, url=" + mfaActualUrl
                + " fromPage=" + savedFromPage + " csrf=" + savedCsrf);
            return new PendingAuth(true, null);
        }

        throw new IOException("AUTH_FAILED");
    }

    public AuthResult submitMfa(String code) throws IOException {
        String submitUrl = (mfaActualUrl != null && !mfaActualUrl.isEmpty())
            ? mfaActualUrl : MFA_URL;
        String fromPage = (savedFromPage != null && !savedFromPage.isEmpty())
            ? savedFromPage : "setupEnterMfaCode";

        FormBody.Builder form = new FormBody.Builder()
            .add("mfa-code", code)
            .add("embed", "true")
            .add("_eventId", "submit")
            .add("fromPage", fromPage);
        if (savedCsrf != null && !savedCsrf.isEmpty()) form.add("_csrf", savedCsrf);

        android.util.Log.d("GarminAuth", "Submitting MFA to: " + submitUrl);

        String mfaHtml;
        String mfaFinalUrl;
        try (Response r = client.newCall(new Request.Builder()
                .url(submitUrl)
                .header("User-Agent", UA)
                .header("Referer", submitUrl)
                .post(form.build())
                .build()).execute()) {
            mfaHtml = r.body().string();
            mfaFinalUrl = r.request().url().toString();
        }

        android.util.Log.d("GarminAuth", "MFA response final url: " + mfaFinalUrl);

        // Check for ticket in the response or final URL first (success case)
        String ticket = extractTicket(mfaHtml);
        if (ticket == null) ticket = extractTicket(mfaFinalUrl);

        if (ticket != null) {
            android.util.Log.d("GarminAuth", "MFA OK, exchanging service ticket");
            return exchangeTicket(ticket);
        }

        // Log enough to diagnose the failure
        android.util.Log.e("GarminAuth", "No ticket in MFA response (url=" + mfaFinalUrl
            + "), html snippet: " + mfaHtml.substring(0, Math.min(2000, mfaHtml.length())));

        if (mfaHtml.contains("incorrect") || mfaHtml.contains("invalidCode")
                || mfaHtml.contains("wrong") || mfaHtml.contains("expired")
                || mfaHtml.contains("Invalid verification")) {
            throw new IOException("INVALID_MFA_CODE");
        }

        throw new IOException("AUTH_FAILED");
    }

    private AuthResult exchangeTicket(String ticket) throws IOException {
        IOException lastError = null;
        for (String clientId : CLIENT_IDS) {
            try {
                String credentials = Base64.encodeToString(
                    (clientId + ":").getBytes("UTF-8"), Base64.NO_WRAP);
                String body = "grant_type=" + encode(GRANT_TYPE_TICKET)
                    + "&client_id=" + encode(clientId)
                    + "&service_ticket=" + encode(ticket)
                    + "&service_url=" + encode(SSO_EMBED);

                try (Response r = client.newCall(new Request.Builder()
                        .url(DI_TOKEN_URL)
                        .header("Authorization", "Basic " + credentials)
                        .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
                        .header("Cache-Control", "no-cache")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .post(RequestBody.create(body, MediaType.parse("application/x-www-form-urlencoded")))
                        .build()).execute()) {
                    if (!r.isSuccessful()) {
                        lastError = new IOException("DI token exchange failed: " + r.code()
                            + " with client " + clientId);
                        android.util.Log.w("GarminAuth", lastError.getMessage());
                        continue;
                    }
                    JSONObject json = new JSONObject(r.body().string());
                    String accessToken = json.getString("access_token");
                    String refreshToken = json.optString("refresh_token", "");
                    android.util.Log.d("GarminAuth", "OAuth token obtained with client " + clientId);
                    return new AuthResult(accessToken, refreshToken);
                }
            } catch (Exception e) {
                lastError = new IOException("Token exchange error with " + clientId + ": " + e.getMessage());
                android.util.Log.w("GarminAuth", lastError.getMessage());
            }
        }
        throw lastError != null ? lastError : new IOException("AUTH_FAILED");
    }

    private String get(String url, String referer) throws IOException {
        try (Response r = client.newCall(new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()).execute()) {
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code() + " for " + url);
            return r.body().string();
        }
    }

    private static String extractTicket(String html) {
        Matcher m = Pattern.compile("ticket=(ST-[^\"&\\s]+)").matcher(html);
        if (m.find()) return m.group(1);
        // Try JSON field
        m = Pattern.compile("\"(?:serviceTicketId|serviceTicket|ticket)\"\\s*:\\s*\"(ST-[^\"]+)\"").matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String parseField(String html, String name) {
        Matcher m = Pattern.compile(
            "<input[^>]+name=[\"']" + Pattern.quote(name) + "[\"'][^>]*value=[\"']([^\"']*)[\"']" +
            "|<input[^>]+value=[\"']([^\"']*)[\"'][^>]*name=[\"']" + Pattern.quote(name) + "[\"']"
        ).matcher(html);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
