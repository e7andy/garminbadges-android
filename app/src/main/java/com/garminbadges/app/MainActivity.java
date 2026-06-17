package com.garminbadges.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

public class MainActivity extends AppCompatActivity {

    static final String PREFS_NAME = "app_prefs";
    private static final String PREF_API_KEY = "api_key";
    private static final String PREF_GARMIN_EMAIL = "garmin_email";
    private static final String PREF_GARMIN_ACCESS_TOKEN = "garmin_access_token";
    private static final String PREF_GARMIN_REFRESH_TOKEN = "garmin_refresh_token";

    private EditText etApiKey;
    private TextView tvAuthStatus;
    private TextView tvLog;
    private ScrollView scrollLog;
    private Button btnSync;

    private String accessToken = "";
    private String refreshToken = "";
    private Button btnSignIn;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final OkHttpClient httpClient = new OkHttpClient();

    private final ActivityResultLauncher<Intent> authLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        this::onAuthResult
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View header = findViewById(R.id.header);
        // Capture XML padding before the inset listener overwrites it
        int padL = header.getPaddingLeft();
        int padT = header.getPaddingTop();
        int padR = header.getPaddingRight();
        int padB = header.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Add status bar height on top of the header's own padding
            header.setPadding(padL + systemBars.left, padT + systemBars.top, padR + systemBars.right, padB);
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom);
            return insets;
        });

        etApiKey = findViewById(R.id.etApiKey);
        findViewById(R.id.tvDashboardLink).setOnClickListener(v ->
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://garminbadges.com/dashboard"))));
        tvAuthStatus = findViewById(R.id.tvAuthStatus);
        tvLog = findViewById(R.id.tvLog);
        scrollLog = findViewById(R.id.scrollLog);
        btnSync = findViewById(R.id.btnSync);
        btnSignIn = findViewById(R.id.btnSignIn);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etApiKey.setText(prefs.getString(PREF_API_KEY, ""));

        // Restore saved session
        accessToken = prefs.getString(PREF_GARMIN_ACCESS_TOKEN, "");
        refreshToken = prefs.getString(PREF_GARMIN_REFRESH_TOKEN, "");
        String garminEmail = prefs.getString(PREF_GARMIN_EMAIL, "");
        if (!accessToken.isEmpty()) {
            String label = garminEmail.isEmpty() ? getString(R.string.signed_in_garmin)
                                                 : getString(R.string.signed_in_as, garminEmail);
            tvAuthStatus.setText(label);
            btnSync.setEnabled(true);
            btnSignIn.setText(R.string.sign_out);
        }

        btnSignIn.setOnClickListener(v -> {
            hideKeyboard();
            if (!accessToken.isEmpty()) {
                signOut();
            } else {
                authLauncher.launch(new Intent(this, AuthActivity.class));
            }
        });
        btnSync.setOnClickListener(v -> { hideKeyboard(); startSync(); });
    }

    private void onAuthResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

        accessToken = result.getData().getStringExtra(AuthActivity.EXTRA_ACCESS_TOKEN);
        refreshToken = result.getData().getStringExtra(AuthActivity.EXTRA_REFRESH_TOKEN);
        String email = result.getData().getStringExtra(AuthActivity.EXTRA_EMAIL);
        if (accessToken == null) accessToken = "";
        if (refreshToken == null) refreshToken = "";
        if (email == null) email = "";

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_GARMIN_ACCESS_TOKEN, accessToken)
            .putString(PREF_GARMIN_REFRESH_TOKEN, refreshToken)
            .putString(PREF_GARMIN_EMAIL, email)
            .apply();

        if (!accessToken.isEmpty()) {
            String label = email.isEmpty() ? getString(R.string.signed_in_garmin)
                                           : getString(R.string.signed_in_as, email);
            tvAuthStatus.setText(label);
            btnSignIn.setText(R.string.sign_out);
        } else {
            tvAuthStatus.setText(R.string.sign_in_incomplete);
            btnSignIn.setText(R.string.sign_in);
        }
        btnSync.setEnabled(!accessToken.isEmpty());
    }

    private void signOut() {
        accessToken = "";
        refreshToken = "";
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(PREF_GARMIN_ACCESS_TOKEN)
            .remove(PREF_GARMIN_REFRESH_TOKEN)
            .remove(PREF_GARMIN_EMAIL)
            .apply();
        tvAuthStatus.setText(R.string.not_signed_in);
        btnSignIn.setText(R.string.sign_in);
        btnSync.setEnabled(false);
    }

    private void handleAuthExpired() {
        accessToken = "";
        refreshToken = "";
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(PREF_GARMIN_ACCESS_TOKEN)
            .remove(PREF_GARMIN_REFRESH_TOKEN)
            .apply();
        tvAuthStatus.setText(R.string.not_signed_in);
        btnSignIn.setText(R.string.sign_in);
        btnSync.setEnabled(false);

        appendLog(getString(R.string.auth_expired));
        com.google.android.material.snackbar.Snackbar
            .make(findViewById(R.id.main), R.string.auth_expired,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show();

        authLauncher.launch(new Intent(this, AuthActivity.class));
    }

    private void startSync() {
        String apiKey = etApiKey.getText().toString().trim();
        if (apiKey.isEmpty()) {
            appendLog("No API key set. Enter your API key from the garminbadges.com dashboard.");
            return;
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_API_KEY, apiKey)
            .apply();

        btnSync.setEnabled(false);
        tvLog.setText("");

        GarminApiClient garminClient = new GarminApiClient(httpClient, accessToken);
        GarminBadgesApiClient badgesClient = new GarminBadgesApiClient(httpClient, apiKey);
        SyncManager syncManager = new SyncManager(garminClient, badgesClient);

        syncExecutor.execute(() -> syncManager.sync(new SyncManager.Callback() {
            @Override
            public void onProgress(String message) {
                mainHandler.post(() -> appendLog(message));
            }

            @Override
            public void onComplete(int recordCount, JSONObject response) {
                mainHandler.post(() -> {
                    appendLog("Done — " + recordCount + " records uploaded.");
                    btnSync.setEnabled(true);
                    com.google.android.material.snackbar.Snackbar
                        .make(findViewById(R.id.main),
                            "✓ Sync complete — " + recordCount + " records uploaded",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .setBackgroundTint(getColor(R.color.snackbar_success))
                        .setTextColor(getColor(android.R.color.white))
                        .show();
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    appendLog("Error: " + message);
                    btnSync.setEnabled(true);
                    com.google.android.material.snackbar.Snackbar
                        .make(findViewById(R.id.main),
                            "Sync failed — " + message,
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .show();
                });
            }

            @Override
            public void onAuthExpired() {
                mainHandler.post(() -> handleAuthExpired());
            }
        }));
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
            getSystemService(INPUT_METHOD_SERVICE);
        android.view.View focus = getCurrentFocus();
        if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private void appendLog(String message) {
        String current = tvLog.getText().toString();
        tvLog.setText(current.isEmpty() ? message : current + "\n" + message);
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }
}
