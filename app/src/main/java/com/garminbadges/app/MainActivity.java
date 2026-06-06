package com.garminbadges.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
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

    private static final String PREF_API_KEY = "api_key";

    private EditText etApiKey;
    private TextView tvAuthStatus;
    private TextView tvLog;
    private ScrollView scrollLog;
    private Button btnSync;

    private String csrfToken = "";
    private String cookies = "";

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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        etApiKey = findViewById(R.id.etApiKey);
        tvAuthStatus = findViewById(R.id.tvAuthStatus);
        tvLog = findViewById(R.id.tvLog);
        scrollLog = findViewById(R.id.scrollLog);
        btnSync = findViewById(R.id.btnSync);

        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        etApiKey.setText(prefs.getString(PREF_API_KEY, ""));

        findViewById(R.id.btnSignIn).setOnClickListener(v ->
            authLauncher.launch(new Intent(this, AuthActivity.class))
        );
        btnSync.setOnClickListener(v -> startSync());
    }

    private void onAuthResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

        csrfToken = result.getData().getStringExtra(AuthActivity.EXTRA_CSRF_TOKEN);
        if (csrfToken == null) csrfToken = "";

        cookies = CookieManager.getInstance().getCookie("https://connect.garmin.com");
        if (cookies == null) cookies = "";

        if (!csrfToken.isEmpty()) {
            tvAuthStatus.setText("Signed in to Garmin Connect");
        } else {
            tvAuthStatus.setText("Sign-in incomplete — CSRF token not found. Try again.");
        }
        btnSync.setEnabled(!csrfToken.isEmpty() && !cookies.isEmpty());
    }

    private void startSync() {
        String apiKey = etApiKey.getText().toString().trim();
        if (apiKey.isEmpty()) {
            appendLog("No API key set. Enter your API key from the garminbadges.com dashboard.");
            return;
        }

        getPreferences(MODE_PRIVATE).edit().putString(PREF_API_KEY, apiKey).apply();

        btnSync.setEnabled(false);
        tvLog.setText("");

        GarminApiClient garminClient = new GarminApiClient(httpClient, cookies, csrfToken);
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
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    appendLog("Error: " + message);
                    btnSync.setEnabled(true);
                });
            }
        }));
    }

    private void appendLog(String message) {
        String current = tvLog.getText().toString();
        tvLog.setText(current.isEmpty() ? message : current + "\n" + message);
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }
}
