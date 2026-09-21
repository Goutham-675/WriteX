package com.example.handwritingrater;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;

public class EmailAuthActivity extends AppCompatActivity {
    private TextInputEditText emailField, passwordField;
    private MaterialButton actionBtn, toggleModeBtn;
    private TextView statusText;
    private FirebaseAuth auth;
    private TryManager tries;
    private boolean signUpMode = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
        boolean dark = prefs.getBoolean("dark", false);
        AppCompatDelegate.setDefaultNightMode(dark ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_email_auth);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bar = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bar.left, bar.top, bar.right, bar.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        tries = new TryManager(this);
        emailField = findViewById(R.id.email);
        passwordField = findViewById(R.id.password);
        actionBtn = findViewById(R.id.actionBtn);
        toggleModeBtn = findViewById(R.id.toggleModeBtn);
        statusText = findViewById(R.id.statusText);

        if (!FirebaseApp.getApps(this).isEmpty()) {
            auth = FirebaseAuth.getInstance();
        } else {
            statusText.setText(R.string.auth_not_configured);
            actionBtn.setEnabled(false);
            return;
        }

        actionBtn.setOnClickListener(v -> submit());
        toggleModeBtn.setOnClickListener(v -> {
            signUpMode = !signUpMode;
            applyMode();
        });

        applyMode();
    }

    private void applyMode() {
        actionBtn.setText(signUpMode ? R.string.email_sign_up : R.string.email_login);
        toggleModeBtn.setText(signUpMode ? R.string.email_switch_to_login
                : R.string.email_switch_to_signup);
        if (signUpMode) {
            statusText.setText(R.string.email_signup_note);
        } else {
            statusText.setText("");
        }
    }

    private void submit() {
        String email = emailField.getText().toString().trim();
        String pass = passwordField.getText().toString();
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(pass)) {
            statusText.setText(R.string.email_enter_both);
            return;
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            statusText.setText(R.string.email_invalid_format);
            return;
        }
        if (pass.length() < 6) {
            statusText.setText(R.string.email_weak_password);
            return;
        }
        actionBtn.setEnabled(false);
        statusText.setText(getString(R.string.email_working));
        if (signUpMode) {
            auth.createUserWithEmailAndPassword(email, pass)
                    .addOnCompleteListener(t -> onResult(t.isSuccessful(), t.getException()));
        } else {
            auth.signInWithEmailAndPassword(email, pass)
                    .addOnCompleteListener(t -> onResult(t.isSuccessful(), t.getException()));
        }
    }

    private void onResult(boolean ok, Exception e) {
        actionBtn.setEnabled(true);
        if (!ok) {
            statusText.setText(friendlyError(e));
            return;
        }
        tries.setPremium(true);
        Toast.makeText(this, getString(R.string.sign_in_welcome,
                emailField.getText().toString().trim(), tries.max()), Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private String friendlyError(Exception e) {
        if (e instanceof FirebaseAuthUserCollisionException) {
            return getString(R.string.email_exists);
        }
        if (e instanceof FirebaseAuthInvalidUserException) {
            return getString(R.string.email_no_account);
        }
        if (e instanceof FirebaseAuthInvalidCredentialsException) {
            return getString(R.string.email_bad_password);
        }
        if (e instanceof FirebaseAuthException) {
            return e.getMessage();
        }
        return getString(R.string.email_generic_failure);
    }
}