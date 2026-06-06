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

- **minSdk 26**, **targetSdk 36**, Java 11, AGP 9.2.1
- **WebView** for Garmin Connect authentication (captures session cookies)
- **OkHttp 4.12.0** for all Garmin and garminbadges.com API calls
- **org.json** (Android built-in) for JSON parsing — no extra dependency
- Material3 (`Theme.Material3.DayNight.NoActionBar`)
- Single-module app under `:app`

## Architecture

The sync flow is a Java port of `sync.js` from the sibling `garminbadges-updater` Chrome extension repo. Keep the two in sync when Garmin's API behaviour changes.

### Key classes

| Class | Responsibility |
|---|---|
| `AuthActivity` | WebView that loads `connect.garmin.com`; injects JS to extract the CSRF token from `<meta name="_token">`; returns token via `Intent` extra on "Done" |
| `GarminApiClient` | OkHttp wrapper for all `https://connect.garmin.com/gc-api` calls; attaches the four required headers on every request |
| `GarminBadgesApiClient` | OkHttp wrapper for `https://api.garminbadges.com/api`; handles `/badges` (GET) and `/sync` (POST) |
| `SyncManager` | Orchestrates the full sync; runs on a caller-supplied background thread; uses an internal 8-thread pool for parallel badge-detail and repeatable-earn fetches; reports progress via `Callback` |
| `MainActivity` | UI: API key field (persisted in `SharedPreferences`), Sign In button, Sync Now button, scrolling progress log; runs sync on a single-thread `ExecutorService`, posts UI updates via `Handler(Looper.getMainLooper())` |

### Auth flow

1. `MainActivity` launches `AuthActivity` via `ActivityResultLauncher`.
2. `AuthActivity` enables JavaScript and third-party cookies on the WebView (required for Garmin's SSO redirect through `sso.garmin.com`).
3. On each `onPageFinished` for a `connect.garmin.com` URL, JS is injected to call `window.GarminBadges.onToken(token)` — a `@JavascriptInterface` that stores the token.
4. User taps "Done"; `AuthActivity` returns the CSRF token in an `Intent` extra.
5. `MainActivity` reads cookies for `https://connect.garmin.com` from `CookieManager` and enables the Sync button.

### Sync logic

`SyncManager.sync()` mirrors `sync.js` step by step:

1. `GET /userprofile-service/socialProfile` → `garminUsername`
2. `GET /badge-service/badge/earned` → earned records
3. `GET /badgechallenge-service/badgeChallenge/non-completed` + `virtualChallenge/inProgress` + `badge/available` → challenges/available
4. `GET https://api.garminbadges.com/api/badges` → site catalogue (used for name deduplication and identifying repeatable badges)
5. Parallel fetch of `badge/detail/v3/{id}` for challenges and earned repeatables
6. Parallel fetch of `badge/{username}/earned/detail/repeatable/v2/{id}` for all earned repeatables — provides accurate historical earn dates
7. Deduplication of numbered badge series (e.g. "Badge Name 1", "Badge Name 2" → keep lowest-numbered active one)
8. `POST https://api.garminbadges.com/api/sync` with the assembled records array

## Garmin API details

All Garmin calls go to `https://connect.garmin.com/gc-api<path>` with these headers:

```
Accept: application/json
di-backend: connectapi.garmin.com
nk: NT
connect-csrf-token: <token from <meta name="_token"> on the page>
Cookie: <session cookies from CookieManager>
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
