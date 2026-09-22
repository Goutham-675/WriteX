# Handwriting Rater — Project Specification

## 1. Concept

A fun, non-serious Android app for university students (starting with one campus) that
rates a photo of someone's handwriting on a 0–100 "neatness/consistency" scale, purely
for entertainment and social sharing. Not a real handwriting-analysis tool — it measures
statistical *regularity* of strokes, which correlates well enough with perceived
neatness to feel legitimate and fun.

**Goal:** Low-pressure side project. Target is modest ad revenue (author's stated bar:
"even $10 is fine"), not a scaled business. No backend, no user accounts, no server —
everything runs on-device.

## 2. Monetization

- 4–8 free rating "tries" per day (tune generosity toward more sharing, not revenue
  extraction — the goal is virality within the campus, not squeezing each user).
- Extra tries: watch a short rewarded video ad (AdMob RewardedAd).
- Optional (v2, not v1 priority): a paid pack of ~50-60 tries for a limited time window.
  Skip this for v1 — adds payment integration complexity for little payoff at this scale.
- No subscriptions, no accounts, no server-side purchase validation needed for v1.

## 3. Scoring Algorithm (core logic — already prototyped and partially tested in Java)

### Pipeline
1. **Grayscale** the input image.
2. **Threshold** into binary ink/paper using **Otsu's method** (automatic threshold
   selection based on histogram variance).
3. **Connected components**: find groups of adjacent ink pixels via **8-directional BFS
   flood-fill**. Each component ≈ one letter or stroke cluster. Filter out components
   smaller than ~8 pixels (noise/artifacts).
4. Compute **five independent metrics** from the components (all measure *variance/
   regularity* — lower variance = better handwriting):

   | Metric | Method | Weight |
   |---|---|---|
   | Slant consistency | Per-component principal-axis angle via 2D covariance/PCA on pixel coordinates; measure spread (std dev) across components | 0.25 |
   | Baseline straightness | Least-squares line fit through each component's bottom-most y-coordinate vs. x-position; measure RMSE from fitted line | 0.25 |
   | Spacing uniformity | Sort components by x-position, measure horizontal gaps between neighbors, compute coefficient of variation | 0.20 |
   | Stroke width consistency | Multi-source BFS distance transform (distance from every ink pixel to nearest background pixel) to estimate local stroke thickness; measure variance of max distance per component | 0.15 |
   | Size uniformity | Variance of component bounding-box heights | 0.15 |

5. Normalize each metric to 0–100 (`100 - min(100, variance_measure * scaling_factor)`),
   combine via the weights above into a final 0–100 score.
6. Map score to a fun tier name for display:
   - 90+: "Font Incarnate"
   - 75–89: "Calligrapher"
   - 60–74: "Textbook Neat"
   - 40–59: "Chaotic Scribbler"
   - <40: "Doctor's Prescription"

### Slant metric (previously buggy — FIXED)
The original slant metric computed a PCA principal-axis angle on a *single letter's
pixels in isolation*, which is unreliable — round/short letters (e.g. "o", "s") have
no meaningful dominant axis, and the angle returned was dominated by letter *shape*,
not actual pen slant. Testing on synthetic images (identical, consistent 1.7° tilt
applied to every character) produced angles ranging from -89° to +88° with ~67°
standard deviation — pure noise, not signal.

**Fix (implemented in `HandwritingScorer.slantStd`)**:
- Compute slant only from tall/narrow components (height ≥ 12px and `height >
  1.5 × width` — e.g. ascenders/descenders like "l", "h", "t", "b", "d", "g") where
  a genuine dominant axis exists. Round or roughly-square components are skipped.
- Apply a robust-consistency step instead of raw std: compute the median angle, keep
  only angles within 25° of the median, and measure std of the survivors. Require at
  least 4 qualifying components, else fall back to a neutral score.
- Re-testing against synthetic neat vs. messy samples (see Section 8) should confirm
  low variance for consistent slant and high variance for random slant.

### Reference implementation
A working (except for the slant bug above) desktop Java prototype exists using
`BufferedImage` and was tested successfully on synthetic test images for all metrics
except slant. Port this logic almost directly to Android by swapping `BufferedImage`
for `Bitmap` (see Section 5, `getPixel`/`setPixel` are near drop-in replacements for
`getRGB`/`setRGB`). All core algorithms (Otsu, BFS connected components, BFS distance
transform, PCA, least-squares line fit) are pure pixel-array math and require no
changes beyond the image-access layer.

## 4. Practical Image Considerations
- Phone camera photos can be very large (e.g. 4000×3000px) — **downscale** the Bitmap
  (e.g. to ~1000px on the long edge via `Bitmap.createScaledBitmap`) before running
  pixel-loop algorithms, or processing will be slow on-device.
- Uneven lighting/shadows will confuse Otsu thresholding — consider a UI hint telling
  users to photograph in good, even light, and roughly perpendicular to the page.
- Crop hints/guidance to avoid picking up page edges, table surfaces, or notebook
  binding as false "ink" would improve reliability but are not required for v1.

## 5. Architecture (Android, local-first, no backend)

```
MainActivity  -->  TryManager (SharedPreferences)   [checks/decrements daily try count]
     |        -->  AdManager (AdMob RewardedAd)      [shows ad, grants extra try on completion]
     |
     v (Bitmap)
HandwritingScorer  [pure logic class, NO Android UI dependencies, only touches Bitmap]
     |
     v (score, tier, breakdown)
ResultActivity  [displays result, wires native Android share sheet]
```

- `HandwritingScorer` should have zero Android UI dependencies — only depends on
  `android.graphics.Bitmap`. This keeps it testable independently of the UI layer
  (can even be unit-tested with a small Bitmap constructed in a test).
- Data passes between `MainActivity` and `ResultActivity` via `Intent` extras
  (score as double/int, tier as String, per-metric breakdown as a small data
  structure or individual extras).
- No database, no network calls except AdMob's own SDK traffic.

## 6. File / Package Structure

```
HandwritingRaterApp/
├── app/
│   ├── build.gradle                     # AdMob SDK dependency, app config
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # camera permission, internet permission (ads)
│   │   ├── java/com/example/handwritingrater/
│   │   │   ├── MainActivity.java        # home screen: camera/gallery button, try counter
│   │   │   ├── ResultActivity.java      # score, tier, breakdown, share button
│   │   │   ├── HandwritingScorer.java   # ported scoring logic (Bitmap-based)
│   │   │   ├── TryManager.java          # SharedPreferences wrapper for daily try count
│   │   │   └── AdManager.java           # AdMob RewardedAd load/show wrapper
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── activity_main.xml
│   │       │   └── activity_result.xml
│   │       └── values/
│   │           └── strings.xml          # tier names, button labels, etc.
│   └── ...
├── build.gradle
└── settings.gradle
```

## 7. UI Requirements (v1, minimal)
- **MainActivity**: one button to take a photo (CameraX) or pick from gallery
  (`Intent.ACTION_PICK`), a text display of remaining free tries today, a "watch ad
  for +1 try" button (disabled/hidden if tries remain).
- **ResultActivity**: large score display, tier name, a simple per-metric breakdown
  (5 sub-scores), and a "Share" button using Android's standard share `Intent` so
  users can post to WhatsApp/Instagram/etc.

## 8. Testing Approach
- Before wiring up any UI, validate `HandwritingScorer` logic in isolation using
  synthetic test images: generate a "neat" sample (consistent character rotation,
  spacing, size) and a "messy" sample (randomized per-character jitter), and confirm
  the neat sample scores meaningfully higher across all five metrics. This is how the
  slant bug above was originally caught — synthetic ground-truth samples are more
  reliable for debugging than real handwriting photos, since you control the "correct"
  answer.
- Test on at least one real handwriting photo before considering v1 feature-complete.
- Test the AdMob integration using Google's official test ad unit IDs during
  development — never real ad unit IDs — until ready to publish, to avoid policy
  violations.

## 9. Publishing Checklist (Google Play)
1. Google Play Developer account ($25 one-time fee).
2. Generate a signed Android App Bundle (.aab) in Android Studio; **back up the
   signing keystore file in multiple places** — losing it means the app can never be
   updated again under the same listing.
3. Store listing: app icon (512×512), feature graphic (1024×500), 2+ screenshots,
   short + full description.
4. Content rating questionnaire, target audience, Data Safety form (declare: photos
   are processed on-device, not uploaded anywhere).
5. Privacy policy URL required even for simple apps (a Google Doc or GitHub Pages
   link is sufficient).
6. Upload to Internal Testing track first, verify install/function, then promote to
   Production.
7. Google review (hours to a few days for a new developer account).
8. On approval, share the Play Store link directly in campus social groups — this is
   the actual distribution strategy at this scale, no ad spend needed.

## 10. Explicit Non-Goals for v1
- No backend server or database.
- No user accounts or login.
- No paid subscription tier (defer to v2 if the app gains real traction).
- No real handwriting/OCR analysis — this is intentionally a regularity-based proxy
  for fun, not a scientifically rigorous grading tool.
