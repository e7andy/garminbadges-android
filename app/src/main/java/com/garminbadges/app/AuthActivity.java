package com.garminbadges.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AuthActivity extends AppCompatActivity {

    public static final String EXTRA_ACCESS_TOKEN = "access_token";
    public static final String EXTRA_REFRESH_TOKEN = "refresh_token";
    public static final String EXTRA_EMAIL = "email";

    private View layoutCredentials, layoutMfa;
    private EditText etEmail, etPassword, etMfaCode;
    private View btnSignIn, btnSubmitMfa;
    private TextView tvStatus;
    private ProgressBar progressBar;

    private GarminAuthClient authClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        layoutCredentials = findViewById(R.id.layoutCredentials);
        layoutMfa = findViewById(R.id.layoutMfa);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etMfaCode = findViewById(R.id.etMfaCode);
        btnSignIn = findViewById(R.id.btnSignIn);
        btnSubmitMfa = findViewById(R.id.btnSubmitMfa);
        tvStatus = findViewById(R.id.tvStatus);
        progressBar = findViewById(R.id.progressBar);

        etPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { signIn(); return true; }
            return false;
        });
        etMfaCode.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitMfa(); return true; }
            return false;
        });

        btnSignIn.setOnClickListener(v -> { hideKeyboard(); signIn(); });
        btnSubmitMfa.setOnClickListener(v -> { hideKeyboard(); submitMfa(); });

        // Pre-fill saved email
        String savedEmail = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .getString("garmin_email", "");
        if (!savedEmail.isEmpty()) etEmail.setText(savedEmail);
    }

    private void signIn() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            showStatus("Enter your Garmin email and password.");
            return;
        }

        authClient = new GarminAuthClient();
        setLoading(true);
        showStatus("Signing in…");

        executor.execute(() -> {
            try {
                GarminAuthClient.PendingAuth pending = authClient.startAuthentication(email, password);
                if (pending.mfaRequired) {
                    runOnUiThread(this::showMfaStep);
                } else {
                    deliverResult(pending.result);
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showStatus(friendlyError(e.getMessage()));
                });
            }
        });
    }

    private void submitMfa() {
        String code = etMfaCode.getText().toString().trim();
        if (code.isEmpty()) {
            showStatus("Enter the verification code.");
            return;
        }

        setLoading(true);
        showStatus("Verifying…");

        executor.execute(() -> {
            try {
                GarminAuthClient.AuthResult result = authClient.submitMfa(code);
                deliverResult(result);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showStatus(friendlyError(e.getMessage()));
                });
            }
        });
    }

    private void showMfaStep() {
        setLoading(false);
        layoutCredentials.setVisibility(View.GONE);
        layoutMfa.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.GONE);
        etMfaCode.requestFocus();
    }

    private void deliverResult(GarminAuthClient.AuthResult result) {
        Intent intent = new Intent();
        intent.putExtra(EXTRA_ACCESS_TOKEN, result.accessToken);
        intent.putExtra(EXTRA_REFRESH_TOKEN, result.refreshToken);
        intent.putExtra(EXTRA_EMAIL, etEmail.getText().toString().trim());
        runOnUiThread(() -> {
            setResult(RESULT_OK, intent);
            finish();
        });
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
            getSystemService(INPUT_METHOD_SERVICE);
        android.view.View focus = getCurrentFocus();
        if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private String friendlyError(String raw) {
        if (raw == null) return "Unknown error.";
        switch (raw) {
            case "CF_BLOCKED":      return "Blocked by Cloudflare — try again later.";
            case "INVALID_CREDENTIALS": return "Incorrect email or password.";
            case "INVALID_MFA_CODE": return "Invalid or expired verification code.";
            case "AUTH_FAILED":     return "Authentication failed — could not obtain session. Try again.";
            default:                return "Error: " + raw;
        }
    }

    private void setLoading(boolean loading) {
        btnSignIn.setEnabled(!loading);
        btnSubmitMfa.setEnabled(!loading);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showStatus(String message) {
        tvStatus.setText(message);
        tvStatus.setVisibility(View.VISIBLE);
    }
}
