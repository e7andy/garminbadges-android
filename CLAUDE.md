# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goal

Native Android app for [Garmin Badge Database](https://garminbadges.com/) that replicates the Chrome extension (`garminbadges-updater`) as a mobile client. Core flow: authenticate with Garmin SSO → exchange service ticket for OAuth2 tokens via `diauth.garmin.com` → call Garmin's internal API → upload badge/challenge data to `api.garminbadges.com` using the user's API key.

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
- **OkHttp 4.12.0** for all network calls (Garmin SSO, OAuth token exchange, Garmin API, garminbadges.com API)
- **org.json** (Android built-in) for JSON parsing — no extra dependency
- Material3 (`Theme.Material3.DayNight.NoActionBar`)
- Single-module app under `:app`

## Architecture

The sync flow is a Java port of `sync.js` from the sibling `garminbadges-updater` Chrome extension repo. Keep the two in sync when Garmin's API behaviour changes.

### Key classes

| Class | Responsibility |
|---|---|
| `GarminAuthClient` | Programmatic SSO login via `sso.garmin.com` embed widget; handles MFA; exchanges the service ticket for OAuth2 tokens at `diauth.garmin.com` |
| `AuthActivity` | Native two-step login UI (email/password → optional MFA code); delegates all network work to `GarminAuthClient`; returns `accessToken` + `refreshToken` via `Intent` extras |
| `GarminApiClient` | OkHttp wrapper for `https://connectapi.garmin.com` calls; attaches `Authorization: Bearer <accessToken>` on every request |
| `GarminBadgesApiClient` | OkHttp wrapper for `https://api.garminbadges.com/api`; handles `/badges` (GET) and `/sync` (POST) |
| `SyncManager` | Orchestrates the full sync; runs on a caller-supplied background thread; uses an internal 8-thread pool for parallel badge-detail and repeatable-earn fetches; reports progress via `Callback` |
| `MainActivity` | UI: API key field, Sign In/Sign Out button, Sync Now button, scrolling progress log; persists state in `SharedPreferences`; posts UI updates via `Handler(Looper.getMainLooper())` |

### Auth flow

Authentication uses Garmin's SSO embed widget flow — the same approach used by third-party Python libraries such as [garth-ng](https://github.com/cyberfossa/garth-ng).

1. `MainActivity` launches `AuthActivity` via `ActivityResultLauncher`.
2. `AuthActivity` creates a `GarminAuthClient` and calls `startAuthentication(email, password)`.
3. `GarminAuthClient` performs three HTTP steps:
   - `GET https://sso.garmin.com/sso/embed?...` — seeds SSO cookies
   - `GET https://sso.garmin.com/sso/signin?service=<embedUrl>&...` — retrieves the `_csrf` token
   - `POST https://sso.garmin.com/sso/signin` with credentials and `_csrf`
4. If the response contains a service ticket (`ST-...`), authentication proceeds to token exchange.
5. If MFA is required (response contains `loginEnterMfaCode`), `AuthActivity` shows the MFA code input. The user's code is submitted via `POST https://sso.garmin.com/sso/verifyMFA/loginEnterMfaCode`.
6. The service ticket is exchanged for OAuth2 tokens at `https://diauth.garmin.com/di-oauth2-service/oauth/token`, trying three Android client IDs in order: `GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2`, `GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4`, `GARMIN_CONNECT_MOBILE_ANDROID_DI`.
7. `AuthActivity` returns `accessToken` and `refreshToken` in the result `Intent`.
8. `MainActivity` persists both tokens in `SharedPreferences` and enables the Sync button.

### Sync logic

`SyncManager.sync()` mirrors `sync.js` step by step:

1. `GET /userprofile-service/socialProfile` → `garminUsername`
2. `GET /badge-service/badge/earned` → all earned badge records
3. `GET /badgechallenge-service/badgeChallenge/non-completed` + `virtualChallenge/inProgress` + `badge/available` → challenges and available badges
4. `GET https://api.garminbadges.com/api/badges` → site catalogue (name deduplication, repeatable badge identification)
5. Parallel fetch of `badge/detail/v3/{id}` for challenges and earned repeatables
6. Parallel fetch of `badge/{username}/earned/detail/repeatable/v2/{id}` for all earned repeatables — provides accurate historical earn dates
7. Deduplication of numbered badge series (e.g. "Badge Name 1", "Badge Name 2" → keep lowest-numbered active one)
8. `POST https://api.garminbadges.com/api/sync` with the assembled records array

## Garmin API details

All Garmin API calls go directly to `https://connectapi.garmin.com<path>` with:

```
Authorization: Bearer <accessToken>
Accept: application/json
```

> **Note:** The `connect.garmin.com/gc-api` proxy (with `di-backend`/`nk`/CSRF headers) is the web-app approach and does **not** work with OAuth Bearer tokens.

### Key endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/userprofile-service/socialProfile` | Resolves `userName` |
| GET | `/badge-service/badge/earned` | All earned badges; use `badgeEarnedNumber` (not `earnedNumber`) for repeat count |
| GET | `/badgechallenge-service/badgeChallenge/non-completed?desc=true&start=1&includeExclusive=true&limit=10000` | Active challenges |
| GET | `/badgechallenge-service/virtualChallenge/inProgress` | In-progress virtual challenges |
| GET | `/badge-service/badge/available` | Badges where `badgeTargetValue != null` and `badgeCategoryId !== 4` |
| GET | `/badge-service/badge/detail/v3/{id}` | In-progress detail for a single badge |
| GET | `/badge-service/badge/{username}/earned/detail/repeatable/v2/{id}?start=1&limit=1000` | Full earn history for a repeatable badge |

### Upload endpoint

`POST https://api.garminbadges.com/api/sync`

Headers: `Authorization: Bearer <apiKey>`

Body:
```json
{
  "user_badges": [ ... ],
  "garmin_username": "username"
}
```

Record schema:
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

## SharedPreferences keys

All stored under `"app_prefs"`:

| Key | Value |
|---|---|
| `api_key` | User's garminbadges.com API key |
| `garmin_email` | Last used Garmin email (pre-fills the sign-in form) |
| `garmin_access_token` | OAuth2 access token |
| `garmin_refresh_token` | OAuth2 refresh token |
