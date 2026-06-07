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
  -alias garminbadges \
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
keyAlias=garminbadges
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

## 4. Create the app in Play Console

1. Go to [Google Play Console](https://play.google.com/console) and click **Create app**.
2. Fill in:
   - **App name:** Garmin Badge Database
   - **Default language:** English (or your preferred default)
   - **App or game:** App
   - **Free or paid:** Free
3. Accept the declarations and click **Create app**.

---

## 5. Complete the store listing

Navigate to **Store presence → Main store listing** and fill in:

| Field | Suggested content |
|---|---|
| Short description | Sync your Garmin badges and challenges to Garmin Badge Database |
| Full description | See below |
| App icon | 512×512 PNG (use the logo from `app/src/main/res/drawable/logo.png` on a solid background) |
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

## 6. Fill in required sections

Play Console will show a checklist of required items before you can publish. Work through each section:

**App content** (`Policy → App content`)
- **Privacy policy:** Required. Host a simple privacy policy page and link to it. At minimum it should state what data the app collects (Garmin credentials entered by the user, stored locally on-device) and that no data is shared with third parties beyond garminbadges.com.
- **Ads:** No
- **Content rating:** Complete the questionnaire. This app will likely receive an **Everyone** rating.
- **Target audience:** 18+ (the app requires a Garmin account)
- **Data safety:** Declare what data is collected and how it is used. This app stores credentials locally on-device and sends badge data to garminbadges.com.

**Data safety** (`Policy → Data safety`) — suggested declarations:
- Data is encrypted in transit (HTTPS)
- Users can request data deletion via garminbadges.com
- No data shared with third parties (garminbadges.com is the app's own backend)

---

## 7. Set up a release track

1. Go to **Release → Testing → Internal testing** (recommended for the first upload).
2. Click **Create new release**.
3. Upload `app-release.aab`.
4. Enter release notes, e.g. *"Initial release"*.
5. Click **Save** then **Review release** and **Start rollout**.

Once internal testing is verified, promote the release to **Closed testing**, then **Open testing**, and finally **Production** via the **Promote release** button.

---

## 8. Production release

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
