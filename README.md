# WriteX

An Android app that rates handwritten work. Snap or pick a photo of handwritten text and get an instant score with a visual breakdown.

## Features

- Take a photo or pick from the gallery
- AI-powered handwriting scoring
- Interactive score ring visualization
- Share results easily
- Ads support via AdManager
- Try-limit management

## Build

Requires Android Studio and JDK 17+.

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/`

## Tech Stack

- Java (Android)
- Gradle build system
- XML layouts with dark mode support
- Custom view drawing (ScoreRingView)