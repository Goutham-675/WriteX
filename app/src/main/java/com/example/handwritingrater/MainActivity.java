package com.example.handwritingrater;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private PreviewView previewView;
    private TextView triesText;
    private ImageCapture imageCapture;
    private TryManager tries;
    private AdManager ads;
    private SharedPreferences prefs;
    private final Executor bg = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> gallery =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                    Uri uri = r.getData().getData();
                    if (uri != null) scoreUri(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("ui", MODE_PRIVATE);
        boolean dark = prefs.getBoolean("dark", false);
        AppCompatDelegate.setDefaultNightMode(dark ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(android.R.id.content));
        previewView = findViewById(R.id.preview);
        triesText = findViewById(R.id.triesText);
        tries = new TryManager(this);
        ads = new AdManager();
        ads.init(this);

        Button capture = findViewById(R.id.captureBtn);
        Button galleryBtn = findViewById(R.id.galleryBtn);
        Button adBtn = findViewById(R.id.adBtn);
        ImageButton themeToggle = findViewById(R.id.themeToggle);
        themeToggle.setImageResource(isDark() ? R.drawable.ic_moon : R.drawable.ic_sun);
        themeToggle.setOnClickListener(v -> {
            boolean nowDark = !isDark();
            prefs.edit().putBoolean("dark", nowDark).apply();
            AppCompatDelegate.setDefaultNightMode(nowDark ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
            recreate();
        });

        capture.setOnClickListener(v -> takePhoto());
        galleryBtn.setOnClickListener(v -> gallery.launch(
                new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)));
        adBtn.setOnClickListener(v -> ads.show(this, new AdManager.RewardCallback() {
            @Override public void onEarned() { tries.grant(1); refresh(); }
            @Override public void onFailed() {
                Toast.makeText(MainActivity.this, "Ad not ready, try again", Toast.LENGTH_SHORT).show();
            }
        }));

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
        } else {
            startCamera();
        }
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] g) {
        super.onRequestPermissionsResult(code, p, g);
        if (code == 1 && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else Toast.makeText(this, "Camera denied — gallery still works", Toast.LENGTH_LONG).show();
    }

    private boolean isDark() {
    return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
}

private void refresh() {
        triesText.setText(getString(R.string.tries_left, tries.left()));
    }

    private void applySystemBarInsets(final View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().build();
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
            } catch (Exception e) {
                Toast.makeText(this, "Camera start failed", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!tries.use()) {
            Toast.makeText(this, "No tries left — watch an ad", Toast.LENGTH_SHORT).show();
            return;
        }
        refresh();
        File out = new File(getCacheDir(), "capture.jpg");
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(out).build();
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override public void onImageSaved(ImageCapture.OutputFileResults r) {
                        scoreFile(out);
                    }
                    @Override public void onError(ImageCaptureException e) {
                        tries.grant(1);
                        refresh();
                        Toast.makeText(MainActivity.this, "Capture failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void scoreUri(Uri uri) {
        if (!tries.use()) {
            Toast.makeText(this, "No tries left — watch an ad", Toast.LENGTH_SHORT).show();
            return;
        }
        refresh();
        bg.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] data = readAll(in);
                if (data.length < 64) throw new Exception("empty image");
                if (HandwritingScorer.isScreenshot(data)) {
                    runOnUiThread(() -> {
                        tries.grant(1);
                        refresh();
                        Toast.makeText(this,
                                "Looks like a screenshot, not a photo — photograph the paper instead",
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                Bitmap bmp = decodeSampled(data);
                if (bmp == null) throw new Exception("decode failed");
                scoreBitmap(bmp);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tries.grant(1);
                    refresh();
                    Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void scoreFile(File f) {
        bg.execute(() -> {
            Bitmap bmp = decodeFile(f);
            if (bmp == null) {
                runOnUiThread(() -> {
                    tries.grant(1);
                    refresh();
                    Toast.makeText(this, "Could not read photo", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            scoreBitmap(bmp);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        });
    }

    private void scoreBitmap(Bitmap bmp) {
        boolean dbg = BuildConfig.DEBUG;
        HandwritingScorer.Result r = HandwritingScorer.score(bmp, dbg);
        if (dbg) {
            // Determinism guard: identical bitmap must always yield the same
            // score. This never fires; it's a cheap net in case a future edit
            // introduces randomness or order-dependent accumulation.
            HandwritingScorer.Result r2 = HandwritingScorer.score(bmp, false);
            if (r2.score != r.score) {
                android.util.Log.w("WriteX", "Non-deterministic score: " + r.score + " vs " + r2.score);
            }
        }
        bmp.recycle();
        String overlayPath = null;
        if (dbg && r.overlay != null) {
            try {
                File f = new File(getCacheDir(), "debug_overlay.png");
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                    r.overlay.compress(Bitmap.CompressFormat.PNG, 100, os);
                    overlayPath = f.getAbsolutePath();
                }
                r.overlay.recycle();
            } catch (Exception ignore) {
            }
        }
        final String path = overlayPath;
        final boolean printed = r.printed;
        runOnUiThread(() -> {
            if (printed) {
                Toast.makeText(MainActivity.this,
                        "Looks machine-printed, not handwriting", Toast.LENGTH_LONG).show();
            }
            Intent i = new Intent(this, ResultActivity.class);
            i.putExtra("score", r.score);
            i.putExtra("tier", r.tier);
            i.putExtra("slant", r.slant);
            i.putExtra("baseline", r.baseline);
            i.putExtra("spacing", r.spacing);
            i.putExtra("stroke", r.stroke);
            i.putExtra("size", r.size);
            i.putExtra("slantStd", r.slantStd);
            i.putExtra("baseNorm", r.baseNorm);
            i.putExtra("spacingCv", r.spacingCv);
            i.putExtra("strokeCv", r.strokeCv);
            i.putExtra("sizeCv", r.sizeCv);
            i.putExtra("lines", r.lines);
            i.putExtra("comps", r.comps);
            i.putExtra("skew", r.skew);
            i.putExtra("printed", printed);
            i.putExtra("overlay", path);
            startActivity(i);
        });
    }

    private Bitmap decodeFile(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private Bitmap decodeSampled(byte[] data) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static int sampleSize(int w, int h) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= 1000) s *= 2;
        return s;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        byte[] buf = new byte[8192];
        int r;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return bos.toByteArray();
    }
}
