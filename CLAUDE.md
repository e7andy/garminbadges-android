# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goal

Android client for [Garmin Badge Database](https://garminbadges.com/) that replicates the Chrome extension (`garminbadges-updater`) in a native Android app. The core flow: authenticate with Garmin Connect via WebView → capture session cookies → use OkHttp to call Garmin's internal API endpoints → upload badge/challenge data to `api.garminbadges.com` using the user's API key.

## Build commands

```bash
# Assemble debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.garminbadges.app.ExampleUnitTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Lint
./gradlew lint
```

## Tech stack

- **minSdk 26**, **targetSdk 36**, Java 11, AGP 8.12.3
- **WebView** for Garmin Connect authentication (captures session cookies)
- **OkHttp** for all Garmin and garminbadges.com API calls (not yet added — needs to be added to `libs.versions.toml` and `app/build.gradle.kts`)
- Single-module app under `:app`

## Garmin API details (from the Chrome extension)

All Garmin calls go to `https://connect.garmin.com/gc-api<path>` with these headers:

```
Accept: application/json
di-backend: connectapi.garmin.com
nk: NT
connect-csrf-token: <token from <meta name="_token"> on the page>
```

Key endpoints:
- `GET /userprofile-service/socialProfile` — resolve `userName`
- `GET /badge-service/badge/earned` — all earned badges; use `badgeEarnedNumber` (not `earnedNumber`) for repeat count
- `GET /badgechallenge-service/badgeChallenge/non-completed?desc=true&start=1&includeExclusive=true&limit=10000`
- `GET /badgechallenge-service/virtualChallenge/inProgress`
- `GET /badge-service/badge/available` — badges with `badgeTargetValue != null` and `badgeCategoryId !== 4`
- `GET /badge-service/badge/detail/v3/{id}` — in-progress detail for a badge
- `GET /badge-service/badge/{username}/earned/detail/repeatable/v2/{id}?start=1&limit=1000` — full earn history

Upload endpoint: `POST https://api.garminbadges.com/api/sync` with `Authorization: Bearer <apiKey>` and body `{ user_badges: [...], garmin_username: "..." }`.

Record schema for each badge entry:
```json
{
  "badge_id": 123,
  "earned_number": 1,
  "earned_date": "2024-01-15",
  "progress_value": 42.0,
  "assoc_type_id": null,
  "assoc_data_id": null,
  "create_date": "2024-01-01"
}
```

## Android auth approach

The WebView must load `https://connect.garmin.com` and let the user log in normally. Once authenticated, extract the CSRF token (present in a `<meta name="_token">` tag) and the session cookies via `CookieManager`. These are then forwarded with every OkHttp request to the Garmin API.
