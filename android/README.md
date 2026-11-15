# Personal Music App (Android)

Offline-first Android music player built for the Samsung Galaxy M11 that mirrors a personal music library held in a private Backblaze B2 bucket onto the phone.

The app uses Jetpack Compose (Material 3), ExoPlayer / Media3 (`MediaSessionService`), Room database, WorkManager, and OkHttp. It requires no Google Play services or Firebase.

---

## 1. Prerequisites & Building

### Requirements
- **JDK 21** (e.g. Eclipse Temurin 21)
- **Android SDK** with platform tools and `platforms;android-36` / `platforms;android-37` (compiled against API 37, targets API 36, minSdk 29)
- `ANDROID_HOME` pointing to your Android SDK installation (or configured via `local.properties`)

### Build the Debug APK
From the `android/` directory:

```powershell
.\gradlew.bat assembleDebug --console=plain
```

The APK will be generated at:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

### Run Tests
To run unit and Robolectric tests:

```powershell
.\gradlew.bat test --console=plain
```

---

## 2. Installing on the Samsung Galaxy M11

### Step 1: Enable USB Debugging on the Phone
1. Open the **Settings** app on the Galaxy M11.
2. Scroll to the bottom and tap **About phone**.
3. Tap **Software information**.
4. Find **Build number** and tap it **7 times** in rapid succession until you see a prompt: *"Developer mode has been enabled"*.
5. Go back to the main **Settings** menu and select **Developer options** (now at the bottom).
6. Enable the **USB debugging** toggle switch and tap **OK**.

### Step 2: Install via ADB
1. Connect your Galaxy M11 to the PC using a USB cable.
2. Unlock the phone screen. If prompted with *"Allow USB debugging?"*, check *"Always allow from this computer"* and tap **Allow**.
3. In PowerShell, verify the device is detected:
   ```powershell
   adb devices
   ```
   You should see your device ID with the state `device`.
4. Install or reinstall the APK:
   ```powershell
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 3. Creating a Read-Only Backblaze B2 Application Key

The Android app only ever **reads** and **streams** files from Backblaze B2. It never uploads, modifies, or deletes files in your bucket. For security, create a dedicated read-only application key limited strictly to your music bucket:

1. Log in to the [Backblaze B2 Console](https://www.backblaze.com/b2/cloud-storage.html).
2. Under the **Account** section in the left sidebar, click **Application Keys**.
3. Scroll down and click **Add a New Application Key**.
4. Configure the key:
   - **Name of Key**: A memorable name, e.g. `galaxy-m11-music-reader`.
   - **Allow access to Bucket(s)**: Select your private music bucket (do **not** select "All").
   - **Type of Access**: Select **Read Only**.
   - **File name prefix**: Leave blank (gives read access to `catalog.json`, `songs/`, and `covers/`).
   - **Duration (seconds)**: Leave blank (no expiration).
5. Click **Create New Key**.
6. **Important:** Copy the **keyID** and **applicationKey** immediately. Backblaze displays the `applicationKey` **only once**; if lost, a new key must be generated.

---

## 4. Settings & Configuration

When launched for the first time without a saved key, the app automatically opens the **Settings** screen.

### Backblaze B2 Storage
- **Key ID:** The B2 application key ID created above.
- **Application Key:** The secret application key string. This is masked (`••••••`) by default. Tap the eye icon to toggle visibility. The key is encrypted with an Android Keystore AES-GCM key and never stored in plain text.
- **Bucket name:** The exact name of your Backblaze B2 bucket.
- **Save and test:**
  - Trims leading/trailing whitespace and validates that all three fields are non-empty.
  - Encrypts and saves credentials to local storage.
  - Invalidates any cached token and tests connectivity by fetching `catalog.json`.
  - On success: reports `"Connected. N songs in your library."` and automatically kicks off an initial sync.
  - On bad credentials: reports `"Backblaze rejected the key. Check the key ID, key and bucket name."` (credentials remain saved for editing; sync is not started).
  - If catalog is missing: reports `"No catalog.json in the bucket yet. Add songs with musictool first."`.
  - On connection loss: reports a clear network error.
- **Clear key:** Deletes stored credentials and invalidates the B2 authorization cache.

### Sync & Download
- **Wi-Fi only:**
  - On by default.
  - When enabled, sync background jobs and streaming playback will not use cellular/metered data.
  - When disabled, downloads and streaming can proceed over mobile data.
- **Storage cap slider (1 to 40 GB):**
  - Configures the maximum storage allowance on the phone (default: 20 GB).
  - Changes are saved when the slider is released.
  - The background sync stops downloading once the storage cap or available phone disk space is reached.
- **Storage summary line:**
  - Displays `"Used X GB of Y GB, N songs on phone, M waiting"` (used GB formatted with one decimal place).
- **Sync now:**
  - Enqueues an immediate one-time sync with WorkManager.
  - Disabled until valid credentials are saved.
- **Last sync:**
  - Shows the date, time, and outcome of the last synchronization pass (e.g. `Last sync: 2026-11-15 18:30 - Up to date.`, `Downloaded. Everything is on your phone.`, or an error reason if failed).
