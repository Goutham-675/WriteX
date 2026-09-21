package com.example.handwritingrater;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.button.MaterialButton;

public class WelcomeActivity extends AppCompatActivity {
    private GoogleSignInClient googleSignInClient;
    private TryManager tries;
    private SharedPreferences prefs;
    private MaterialButton googleBtn, emailBtn, guestBtn;
    private TextView statusText;
    private Button signOutBtn;

    private final ActivityResultLauncher<Intent> signIn =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(r.getData());
                try {
                    GoogleSignInAccount acct = task.getResult(ApiException.class);
                    tries.setPremium(true);
                    Toast.makeText(this,
                            getString(R.string.sign_in_welcome, displayName(acct), tries.max()),
                            Toast.LENGTH_LONG).show();
                    goMain();
                } catch (ApiException e) {
                    Toast.makeText(this, R.string.sign_in_failed, Toast.LENGTH_SHORT).show();
                    updateAuthUi();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("ui", MODE_PRIVATE);
        boolean dark = prefs.getBoolean("dark", false);
        AppCompatDelegate.setDefaultNightMode(dark ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bar = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bar.left, bar.top, bar.right, bar.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        tries = new TryManager(this);
        googleBtn = findViewById(R.id.googleBtn);
        emailBtn = findViewById(R.id.emailBtn);
        guestBtn = findViewById(R.id.guestBtn);
        statusText = findViewById(R.id.statusText);
        signOutBtn = findViewById(R.id.signOutBtn);

        ImageButton themeToggle = findViewById(R.id.themeToggle);
        themeToggle.setImageResource(isDark() ? R.drawable.ic_moon : R.drawable.ic_sun);
        themeToggle.setOnClickListener(v -> {
            boolean nowDark = !isDark();
            prefs.edit().putBoolean("dark", nowDark).apply();
            AppCompatDelegate.setDefaultNightMode(nowDark ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
            recreate();
        });

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        googleBtn.setOnClickListener(v -> {
            if (tries.isPremium()) goMain();
            else signIn.launch(googleSignInClient.getSignInIntent());
        });
        emailBtn.setOnClickListener(v ->
                startActivity(new Intent(this, EmailAuthActivity.class)));
        guestBtn.setOnClickListener(v -> goMain());
        signOutBtn.setOnClickListener(v -> signOut());

        updateAuthUi();
    }

    private void signOut() {
        googleSignInClient.signOut().addOnCompleteListener(t -> {
            try {
                com.google.firebase.auth.FirebaseAuth.getInstance().signOut();
            } catch (Exception ignore) {
            }
            tries.setPremium(false);
            updateAuthUi();
            Toast.makeText(this, R.string.sign_out, Toast.LENGTH_SHORT).show();
        });
    }

    private void updateAuthUi() {
        boolean signedIn = tries.isPremium();
        signOutBtn.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (signedIn) {
            googleBtn.setText(nameOrContinue());
            googleBtn.setEnabled(true);
            emailBtn.setVisibility(View.GONE);
            guestBtn.setVisibility(View.GONE);
            statusText.setText(R.string.welcome_continue);
            return;
        }
        googleBtn.setText(R.string.sign_in);
        emailBtn.setVisibility(View.VISIBLE);
        guestBtn.setVisibility(View.VISIBLE);
        statusText.setText(R.string.welcome_subtitle);
    }

    private String nameOrContinue() {
        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(this);
        String name = acct != null ? displayName(acct) : firebaseEmail();
        return name != null ? getString(R.string.welcome_continue_as, name)
                : getString(R.string.welcome_continue);
    }

    private String firebaseEmail() {
        try {
            com.google.firebase.auth.FirebaseAuth auth =
                    com.google.firebase.auth.FirebaseAuth.getInstance();
            if (auth.getCurrentUser() != null
                    && auth.getCurrentUser().getEmail() != null) {
                return auth.getCurrentUser().getEmail();
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String displayName(GoogleSignInAccount acct) {
        if (acct.getDisplayName() != null && !acct.getDisplayName().isEmpty()) {
            return acct.getDisplayName();
        }
        return acct.getEmail() != null ? acct.getEmail() : "";
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}