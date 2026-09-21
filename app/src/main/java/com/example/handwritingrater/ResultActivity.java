package com.example.handwritingrater;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.File;
import java.util.Locale;

public class ResultActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        applySystemBarInsets(findViewById(android.R.id.content));

        int score = getIntent().getIntExtra("score", 0);
        String tier = getIntent().getStringExtra("tier");
        int slant = getIntent().getIntExtra("slant", 0);
        int baseline = getIntent().getIntExtra("baseline", 0);
        int spacing = getIntent().getIntExtra("spacing", 0);
        int stroke = getIntent().getIntExtra("stroke", 0);
        int size = getIntent().getIntExtra("size", 0);

        ScoreRingView ring = findViewById(R.id.scoreRing);
        ring.setProgress(0f, false);
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
            double slantStd = getIntent().getDoubleExtra("slantStd", -1);
            double baseNorm = getIntent().getDoubleExtra("baseNorm", -1);
            double spacingCv = getIntent().getDoubleExtra("spacingCv", -1);
            double strokeCv = getIntent().getDoubleExtra("strokeCv", -1);
            double sizeCv = getIntent().getDoubleExtra("sizeCv", -1);
            if (lines > 0) {
                TextView debugText = findViewById(R.id.debugText);
                debugText.setText(String.format(Locale.US,
                        "[Debug] %d line(s) \u00b7 %d comps \u00b7 skew %.1f\u00b0\n"
                                + "slant=%.1f base=%.3f spacing=%.2f stroke=%.2f size=%.2f",
                        lines, comps, skew, slantStd, baseNorm, spacingCv, strokeCv, sizeCv));
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

        revealScore(ring, score);
    }

    private void revealScore(ScoreRingView ring, int score) {
        View overlay = findViewById(R.id.analyzingOverlay);
        View pill = findViewById(R.id.analyzingText);
        pill.animate()
                .alpha(0.5f)
                .setDuration(500)
                .setInterpolator(new LinearInterpolator())
                .setStartDelay(250)
                .withEndAction(() -> pill.animate().alpha(1f)
                        .setDuration(500)
                        .setInterpolator(new LinearInterpolator())
                        .start());
        overlay.postDelayed(() -> {
            pill.animate().cancel();
            overlay.animate()
                    .alpha(0f)
                    .setDuration(450)
                    .withEndAction(() -> {
                        overlay.setVisibility(View.GONE);
                        ring.setScaleX(0.85f);
                        ring.setScaleY(0.85f);
                        ring.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(550)
                                .setInterpolator(new OvershootInterpolator())
                                .start();
                        ring.setProgress(score / 100f, true);
                    })
                    .start();
        }, 1400);
    }

    private void applySystemBarInsets(final View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
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
        TextView feedbackText = findViewById(R.id.feedbackText);
        int[] v = {slant, baseline, spacing, stroke, size};
        String[] msg = reasons(v, score);
        feedbackText.setText(msg[0]);
        if (msg.length > 1) {
            feedbackText.append(" ");
            feedbackText.append(msg[1]);
        }
        feedbackText.setVisibility(View.VISIBLE);
    }

    private String[] reasons(int[] v, int score) {
        int w1 = 0;
        for (int i = 1; i < v.length; i++) if (v[i] < v[w1]) w1 = i;
        if (v[w1] >= 60) {
            String good = GOOD[Math.floorMod(score, GOOD.length)];
            return new String[]{good};
        }
        String primary = pick(v[w1], w1, score);
        int w2 = -1;
        for (int i = 0; i < v.length; i++) {
            if (i == w1) continue;
            if (w2 == -1 || v[i] < v[w2]) w2 = i;
        }
        if (v[w2] >= 60) return new String[]{primary};
        String secondary = pick(v[w2], w2, score);
        return new String[]{primary, secondary};
    }

    private String pick(int metric, int index, int score) {
        String[][] pool = metric < 45 ? MAJOR : MINOR;
        String[] bank = pool[index];
        return bank[Math.floorMod(metric + score * 3, bank.length)];
    }

    private static final String[][] MAJOR = {
            { // slant, hardest
                    "Your lettering tilts every which way.",
                    "The lean drifts dramatically from letter to letter.",
                    "Overall tilt is all over the place.",
                    "Letters point in all directions.",
                    "A steady slant is missing entirely.",
                    "Your strokes rake in conflicting angles.",
                    "The lean is wildly unstable.",
                    "Upright letters crash into leaning ones.",
                    "Angles vary so much it is hard to read.",
                    "The tilt changes direction mid-word.",
                    "This scrawl canters hard to one side.",
                    "Nothing stays upright long enough to settle."
            },
            { // baseline
                    "Lines rise and fall like a wave.",
                    "The baseline drops mid-sentence.",
                    "Words float off the line.",
                    "Every line wanders downhill.",
                    "Baselines almost disappear.",
                    "The page drifts off its rung.",
                    "Sentences sag then climb again.",
                    "Nothing settles on a straight line.",
                    "Your writing rides a bumpy road.",
                    "The baseline is everywhere but level.",
                    "Words skate above and below the line.",
                    "Peaks and valleys ruin the rhythm."
            },
            { // spacing
                    "Word gaps swing wildly.",
                    "Letters bump and then fly apart.",
                    "The rhythm of spacing is broken.",
                    "Gaps jump from crushed to huge.",
                    "Spacing has no consistent pulse.",
                    "Letters collide then scatter.",
                    "Even spacing is nowhere to be found.",
                    "The line is a mix of squeeze and stretch.",
                    "Every gap measures something different.",
                    "Spacing changes mood mid-line.",
                    "Dense jams and sudden gaps.",
                    "Pacing between letters is erratic."
            },
            { // stroke
                    "Line weight whips between heavy and faint.",
                    "Pressure is wildly uneven.",
                    "Strokes alternate feather-light and lead-bold.",
                    "Pen pressure has no control.",
                    "Thick jabs and hairline strokes are mixed.",
                    "Stroke width jumps around violently.",
                    "The pen digs in, then skips.",
                    "Weight varies across every word.",
                    "Inconsistent pressure ruins the flow.",
                    "Strokes look hacked at random widths.",
                    "Heavy downstrokes, ghost-light upstrokes.",
                    "Pressure control has left the building."
            },
            { // size
                    "Letter sizes swing from tiny to huge.",
                    "The x-height is all over the place.",
                    "Big and small letters collide.",
                    "Sizes change every few letters.",
                    "The scale of letters is not controlled.",
                    "Capitals tower while lowercase vanish.",
                    "Sizes jump around mid-word.",
                    "No two letters share a height.",
                    "No consistent letter proportions.",
                    "Letters balloon then shrink.",
                    "Size control has broken down.",
                    "Mixed-up heights destroy the grid."
            }
    };

    private static final String[][] MINOR = {
            { // slant
                    "Lean is a bit inconsistent.",
                    "A gentle wobble in the tilt.",
                    "Mostly upright, but some letters drift.",
                    "Slant flickers between light and heavy.",
                    "Your tilt needs a touch more discipline.",
                    "A few leaners break the even rhythm.",
                    "Consistency of tilt could improve.",
                    "Occasional leaning letters slip in.",
                    "The angle is close but not locked.",
                    "A steadier lean would help.",
                    "Some letters tilt while others stay upright.",
                    "Just a small wobble in the verticals."
            },
            { // baseline
                    "The baseline sways a little.",
                    "Most lines sit level, a few sag.",
                    "The even line is nearly there.",
                    "A couple of lines wander off.",
                    "Baseline needs a small straightening.",
                    "Occasional dips appear mid-line.",
                    "Lines hold mostly, then drift.",
                    "A touch more evenness would fix it.",
                    "Slight downhill drift at line ends.",
                    "Level for the most part.",
                    "A few letters float above the line.",
                    "Just a gentle undulation."
            },
            { // spacing
                    "Spacing is a little uneven.",
                    "Some words are crammed, some loose.",
                    "Empty gaps vary more than they should.",
                    "Rhythm is close but not steady.",
                    "A more even space would help.",
                    "Occasional wide gaps between words.",
                    "Letter intervals drift slightly.",
                    "Spacing is mostly steady.",
                    "A couple of lines breathe unevenly.",
                    "Gaps are a touch irregular.",
                    "Pacing is almost uniform.",
                    "Minor spacing jitter here and there."
            },
            { // stroke
                    "Pressure is a little inconsistent.",
                    "Some strokes are heavier than others.",
                    "Mostly even, with a few heavy spots.",
                    "A steadier pen pressure would help.",
                    "Stroke width wavers slightly.",
                    "Occasional thick spots in the line.",
                    "Weight is nearly uniform.",
                    "Light and dark alternate now and then.",
                    "Pressure varies just a touch.",
                    "Almost steady stroke width.",
                    "A few wobbles in pen weight.",
                    "Minor pressure bumps."
            },
            { // size
                    "Letter sizes vary a little.",
                    "Most letters match, some do not.",
                    "The x-height is close to even.",
                    "A few outliers break the size.",
                    "Sizes are nearly uniform.",
                    "One or two letters jump out.",
                    "Height rhythm is mostly steady.",
                    "A touch more even sizing would help.",
                    "Letter scale drifts slightly.",
                    "Almost consistent proportions.",
                    "Occasional size outliers.",
                    "Minor variation in letter height."
            }
    };

    private static final String[] GOOD = {
            "Clean, steady and easy to read - great work!",
            "Consistent rhythm with a confident hand.",
            "Every letter sits where it should - lovely.",
            "Neat, even and genuinely pleasant to read.",
            "Solid writing with real control behind it.",
            "A steady hand with good habits all around.",
            "Even strokes, even spacing, even success.",
            "Readable, tidy and dependable.",
            "The kind of handwriting grades are made of.",
            "Well-kept letterforms, top to bottom."
    };
}