package com.example.handwritingrater;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.File;
import java.util.Locale;

public class ResultActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        int score = getIntent().getIntExtra("score", 0);
        String tier = getIntent().getStringExtra("tier");
        int slant = getIntent().getIntExtra("slant", 0);
        int baseline = getIntent().getIntExtra("baseline", 0);
        int spacing = getIntent().getIntExtra("spacing", 0);
        int stroke = getIntent().getIntExtra("stroke", 0);
        int size = getIntent().getIntExtra("size", 0);

        ((ScoreRingView) findViewById(R.id.scoreRing)).setProgress(score / 100f, true);
        ((TextView) findViewById(R.id.scoreText)).setText(String.valueOf(score));
        TextView tierText = findViewById(R.id.tierText);
        tierText.setText(tier == null ? "" : tier);

        setBar("rowSlant", "barSlant", "Slant", slant);
        setBar("rowBaseline", "barBaseline", "Baseline", baseline);
        setBar("rowSpacing", "barSpacing", "Spacing", spacing);
        setBar("rowStroke", "barStroke", "Stroke", stroke);
        setBar("rowSize", "barSize", "Size", size);

        boolean printed = getIntent().getBooleanExtra("printed", false);
        TextView printedText = findViewById(R.id.printedText);
        if (printed) {
            printedText.setText("Detected: printed text — this is machine-printed, not handwriting.");
            printedText.setVisibility(View.VISIBLE);
        } else {
            feedback(score, slant, baseline, spacing, stroke, size);
        }

        if (BuildConfig.DEBUG) {
            int lines = getIntent().getIntExtra("lines", 0);
            int comps = getIntent().getIntExtra("comps", 0);
            double skew = getIntent().getDoubleExtra("skew", 0);
            if (lines > 0) {
                TextView debugText = findViewById(R.id.debugText);
                debugText.setText(String.format(Locale.US,
                        "[Debug] %d line(s) · %d comps · skew %.1f\u00b0", lines, comps, skew));
                debugText.setVisibility(View.VISIBLE);
            }
            String overlayPath = getIntent().getStringExtra("overlay");
            if (overlayPath != null && new File(overlayPath).exists()) {
                ImageView overlayView = findViewById(R.id.overlayView);
                overlayView.setImageBitmap(BitmapFactory.decodeFile(overlayPath));
                findViewById(R.id.overlayCard).setVisibility(View.VISIBLE);
            }
        }

        findViewById(R.id.againBtn).setOnClickListener(v -> finish());

        findViewById(R.id.shareBtn).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, score, tier));
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        });
    }

    private void setBar(String rowId, String barId, String name, int value) {
        TextView row = findViewById(getResources().getIdentifier(rowId, "id", getPackageName()));
        if (row != null) row.setText(String.format(Locale.US, "%s  %d", name, value));
        LinearProgressIndicator bar = findViewById(getResources().getIdentifier(barId, "id", getPackageName()));
        if (bar != null) {
            bar.setProgressCompat(Math.max(0, Math.min(100, value)), true);
        }
    }

    private void feedback(int score, int slant, int baseline, int spacing, int stroke, int size) {
        int min = Math.min(Math.min(slant, baseline), Math.min(Math.min(spacing, stroke), size));
        int metric;
        if (slant == min) metric = 0;
        else if (baseline == min) metric = 1;
        else if (spacing == min) metric = 2;
        else if (stroke == min) metric = 3;
        else metric = 4;

        String[][] bank = {
                {
                        "Your letters lean unevenly — keep a consistent tilt.",
                        "Slant wobbles between letters.",
                        "Watch that lean — your strokes tilt inconsistently."
                },
                {
                        "Lines drift — rest your letters on an even baseline.",
                        "Your baseline wanders a bit.",
                        "Letters float above the line at times."
                },
                {
                        "Letter rhythm is uneven — space your words more evenly.",
                        "Gaps between letters vary a lot.",
                        "Watch your spacing — it's inconsistent."
                },
                {
                        "Stroke thickness jumps around — keep pressure steady.",
                        "Some strokes are heavy, some light.",
                        "Line weight is inconsistent."
                },
                {
                        "Letter sizes jump up and down.",
                        "Your x-height is uneven.",
                        "Big and small letters are mixed together."
                }
        };
        String[] variants = bank[metric];
        int idx = Math.floorMod(score, variants.length);
        TextView feedbackText = findViewById(R.id.feedbackText);
        feedbackText.setText(variants[idx]);
        feedbackText.setVisibility(View.VISIBLE);
    }
}