# Publishing to Google Play Store

## Prerequisites

- A [Google Play Console](https://play.google.com/console) developer account ($25 one-time fee)
- JDK 11 or later
- Android SDK with build tools for API 36

---

## 1. Create a signing keystore

Release builds must be signed with a private key. Create one with the `keytool` utility bundled with the JDK:

```bash
keytool -genkey -v \
  -keystore release.keystore \
  -alias garminbadges-alias \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

You will be prompted for a keystore password, your name, organisation, and location. Fill these in — they are embedded in the certificate but not shown to users.

> **Keep `release.keystore` safe.** If you lose it you cannot publish updates to the same Play Store listing. Back it up somewhere secure and never commit it to source control.

Add it to `.gitignore`:

```
release.keystore
keystore.properties
```

---

## 2. Configure signing in Gradle

Create a `keystore.properties` file in the project root (next to `gradlew`):

```properties
storeFile=../release.keystore
storePassword=your_keystore_password
keyAlias=garminbadges-alias
keyPassword=your_key_password
```

Then add the signing config to `app/build.gradle.kts`:

```kotlin
import java.util.Properties

val keystoreProps = Properties().also { props ->
    val f = rootProject.file("keystore.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

---

## 3. Build the release bundle

Google Play requires an **Android App Bundle** (`.aab`) rather than an APK for new apps.

```bash
./gradlew bundleRelease
```

The output is at:
```
app/build/outputs/bundle/release/app-release.aab
```

To also build a standalone signed APK (for sideloading or testing):

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

---

## 4. Set up GitHub Actions CI/CD

The repository includes a workflow at `.github/workflows/build.yml` that automatically:

- Builds a **debug APK** on every push and pull request (no secrets required).
- Builds and signs a **release AAB** on every push to `main`, provided the signing secrets are configured.

### 4a. Encode the keystore

GitHub Secrets cannot store binary files, so the keystore is stored as a base64 string.

**macOS / Linux:**
```bash
base64 -w 0 release.keystore
```

**Windows (PowerShell):**
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))
```

Copy the entire output — it will be a long single line.

### 4b. Add secrets in GitHub

1. Open your repository on GitHub.
2. Go to **Settings → Secrets and variables → Actions**.
3. Click **New repository secret** for each of the following:

| Secret name | Value |
|---|---|
| `KEYSTORE_BASE64` | The base64 string from step 4a |
| `KEYSTORE_PASSWORD` | The keystore password you set when running `keytool` |
| `KEY_ALIAS` | The key alias (e.g. `garminbadges`) |
| `KEY_PASSWORD` | The key password (same as keystore password if you used the same one) |

### 4c. Trigger the workflow

Push any commit to `main`. The workflow will:

1. Build the debug APK unconditionally.
2. Decode the keystore from `KEYSTORE_BASE64` into a temp file.
3. Pass the keystore path and credentials as environment variables to `bundleRelease`.
4. Upload both artifacts.

### 4d. Download the artifacts

After the workflow completes:

1. Go to **Actions** → click the latest workflow run.
2. Scroll to the **Artifacts** section at the bottom.
3. Download `debug-apk` or `release-aab`.

Artifacts are retained for 14 days.

> **Note:** The signing config in `app/build.gradle.kts` reads credentials from environment variables (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Local release builds still work using those same variables in your shell, or you can set them directly in the Gradle run configuration.

---

## 5. Create the app in Play Console

1. Go to [Google Play Console](https://play.google.com/console) and click **Create app**.
2. Fill in:
   - **App name:** Garmin Badge Database
   - **Default language:** English (or your preferred default)
   - **App or game:** App
   - **Free or paid:** Free
3. Accept the declarations and click **Create app**.

---

## 6. Complete the store listing

Navigate to **Store presence → Main store listing** and fill in:

| Field | Suggested content |
|---|---|
| Short description | Sync your Garmin badges and challenges to Garmin Badge Database |
| Full description | See below |
| App icon | 512×512 PNG (use the logo from `app/src/main/res/drawable-nodpi/logo.png` on a solid background) |
| Feature graphic | 1024×500 PNG |
| Screenshots | At least 2 phone screenshots (required) |

**Suggested full description:**

```
Garmin Badge Database for Android keeps your badge and challenge data in sync between Garmin Connect and garminbadges.com — no browser or Chrome extension required.

Sign in once with your Garmin account and tap Sync Now to upload:
• All earned badges, including full repeat history
• Active and virtual challenges with current progress
• Available badges with progress targets

Requires a free garminbadges.com account and API key.
```

---

## 7. Fill in required sections

Play Console will show a checklist of required items before you can publish. Work through each section:

**App content** (`Policy → App content`)
- **Privacy policy:** Required. Host a simple privacy policy page and link to it. At minimum it should state what data the app collects (Garmin credentials entered by the user, stored locally on-device) and that no data is shared with third parties beyond garminbadges.com.
- **Ads:** No
- **Content rating:** Complete the questionnaire. This app will likely receive an **Everyone** rating.
- **Target audience:** 13+ (Garmin Connect's minimum age requirement)
- **Data safety:** Declare what data is collected and how it is used. This app stores credentials locally on-device and sends badge data to garminbadges.com.

**Data safety** (`Policy → Data safety`) — suggested declarations:
- Data is encrypted in transit (HTTPS)
- Users can request data deletion via garminbadges.com
- No data shared with third parties (garminbadges.com is the app's own backend)

---

## 8. Set up a release track

1. Go to **Release → Testing → Internal testing** (recommended for the first upload).
2. Click **Create new release**.
3. Upload `app-release.aab`.
4. Enter release notes, e.g. *"Initial release"*.
5. Click **Save** then **Review release** and **Start rollout**.

Once internal testing is verified, promote the release to **Closed testing**, then **Open testing**, and finally **Production** via the **Promote release** button.

---

## 9. Production release

Before promoting to Production, Play Console will run automated checks and may ask for additional information. Common requirements:

- **Permissions justification:** The app uses `INTERNET` only. If asked, state it is required to communicate with Garmin Connect and garminbadges.com.
- **Login credentials for review:** Provide a test Garmin account and garminbadges.com API key so Google's reviewers can test the app. Create a dedicated test account for this.

Production review typically takes 1–3 days for a new app.

---

## Updating the app

For every new release:

1. Increment `versionCode` (must be higher than the previous release) and update `versionName` in `app/build.gradle.kts`:
   ```kotlin
   defaultConfig {
       versionCode = 2
       versionName = "1.1"
   }
   ```
2. Run `./gradlew bundleRelease`.
3. Upload the new `.aab` in Play Console under **Release → Production → Create new release**.
