# garminbadges-android

Android client for [Garmin Badge Database](https://garminbadges.com/) that syncs your badge and challenge data from Garmin Connect to the site — a native Android equivalent of the [garminbadges-updater](https://github.com/e7andy/garminbadges-updater) Chrome extension.

## How to use

1. Install the APK on your device.
2. Sign in to [garminbadges.com](https://garminbadges.com), go to your **Dashboard**, and copy your **API key**.
3. Open the app, paste your API key into the field.
4. Tap **Sign In** — a WebView opens Garmin Connect. Log in normally, then tap **Done — I'm signed in**.
5. Tap **Sync Now**. Progress is shown in real time.

## What it syncs

- All earned badges (including full repeat history for repeatable badges)
- Active challenges with current progress
- In-progress virtual challenges
- Available badges with progress targets (not yet earned)

## Building

Requires Android Studio or the Android SDK with Gradle.

```bash
# Debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Lint
./gradlew lint
```

The debug APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

## Tech stack

- **minSdk 26** (Android 8.0), **targetSdk 36**, Java 11
- **WebView** for Garmin Connect authentication
- **OkHttp 4** for all API calls
- Material3 UI
