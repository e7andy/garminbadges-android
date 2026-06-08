# Garmin Badge Database — Android App

A native Android client for [Garmin Badge Database](https://garminbadges.com/) that syncs your earned badges, challenges, and progress from Garmin Connect to the site.

This is the Android equivalent of the [garminbadges-updater](https://github.com/e7andy/garminbadges-updater) Chrome extension — same sync logic, no browser required.

---

## Features

- **Full badge sync** — earned badges, repeatable badge history, in-progress challenges, virtual challenges, and available badges with progress targets
- **MFA support** — works with both standard and two-factor Garmin accounts
- **Persistent session** — signs in once and remembers your credentials between launches
- **Real-time progress log** — see exactly what's happening during each sync

## Requirements

- Android 8.0 or later (API 26+)
- A [Garmin Badge Database](https://garminbadges.com/) account with an API key
- A Garmin Connect account

## Setup

1. Install the APK on your device.
2. Sign in to [garminbadges.com](https://garminbadges.com), go to your [Dashboard](https://garminbadges.com/dashboard), and copy your **API key**.
3. Open the app and paste your API key into the **API Key** field.
4. Tap **Sign In** and enter your Garmin Connect credentials. If your account uses two-factor authentication you will be prompted for a verification code.
5. Tap **Sync Now**. Progress is shown in real time and a confirmation appears when the sync is complete.

Your API key and Garmin session are saved locally so you only need to set them up once.

## What gets synced

| Data | Description |
|---|---|
| Earned badges | All badges you have earned, including full repeat history for repeatable badges |
| Active challenges | Badge challenges currently in progress with current progress value |
| Virtual challenges | In-progress virtual distance/activity challenges |
| Available badges | Badges not yet earned that have a progress target |

## Building from source

Requires Android Studio or the Android command-line tools.

```bash
# Debug APK
./gradlew assembleDebug

# Release AAB for Play Store (requires signing config — see PUBLISHING.md)
./gradlew bundleRelease

# Run unit tests
./gradlew test

# Lint
./gradlew lint
```

The debug APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

**Minimum requirements:** JDK 11, Android SDK with API 36 build tools.

## Tech stack

| Component | Technology |
|---|---|
| Language | Java 11 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |
| HTTP client | OkHttp 4.12.0 |
| JSON | org.json (Android built-in) |
| UI | Material3 |
| Auth | Garmin SSO OAuth2 via `diauth.garmin.com` |

## How it works

Authentication uses Garmin's SSO widget flow (`sso.garmin.com`) to obtain an OAuth2 Bearer token from `diauth.garmin.com` — the same approach used by Garmin's own mobile apps. No WebView or browser session is required.

The sync logic is a Java port of [`sync.js`](https://github.com/e7andy/garminbadges-updater) from the Chrome extension, calling Garmin's internal `connectapi.garmin.com` endpoints to collect badge and challenge data, then uploading it to `api.garminbadges.com`.

## Publishing

See [PUBLISHING.md](PUBLISHING.md) for a step-by-step guide to signing and releasing to the Google Play Store.

## Disclaimer

This project is not affiliated with or endorsed by Garmin Ltd. It uses Garmin's internal APIs in the same way as third-party clients and the official Garmin Connect mobile app. Use at your own risk.
