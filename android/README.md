# Personal Music App (Android)

A private music player for the Samsung Galaxy M11. Songs live on the phone only: you send them from the PC with `musictool` over a USB cable, and the app plays them. There is no cloud, no account and no internet (the app does not even have the permission).

It uses Jetpack Compose (Material 3), Media3 / ExoPlayer (`MediaSessionService`), Room and DataStore. No Google Play services or Firebase.

---

## 1. Building

### Requirements
- **JDK 21** (e.g. Eclipse Temurin 21)
- **Android SDK** with platform tools and `platforms;android-36` / `platforms;android-37` (compiled against API 37, targets API 36, minSdk 29)
- `ANDROID_HOME` pointing to the SDK (or set in `local.properties`)

### Build the debug APK
From the `android/` directory:

```powershell
.\gradlew.bat assembleDebug --console=plain
```

The APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

### Run the tests
```powershell
.\gradlew.bat test --console=plain
```

---

## 2. Installing on the phone

1. On the phone: **Settings > About phone > Software information**, tap **Build number** 7 times, then in **Developer options** turn on **USB debugging**.
2. Plug the phone into the PC, unlock it, and allow "Allow USB debugging?".
3. Check that it shows up, then install:
   ```powershell
   adb devices
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

Open the app once so it can make its folder.

---

## 3. Getting music onto the phone

From the PC, with the phone plugged in:

```bash
musictool add "D:\Music\Some Album"
```

See `tools/musictool/README.md`. The songs go to

```
/sdcard/Android/data/com.personal.music/files/Music/Artist/Album/03 - Title.opus
```

and the app scans that folder every time it comes to the front. You can also copy files into that folder by hand (for example with a file manager) and tap **Settings > Scan for new songs**. Songs you delete from the folder leave the library the next time the app scans.

The app reads title, artist, album, track and year from the file's tags. A `Folder.jpg` or `cover.jpg` next to the songs is used as the cover when the files have no art inside.

---

## 4. What the app does

- **Library:** Songs, Albums, Artists, Liked and Recent tabs, search, and a **Shuffle play** button.
- **Player:** play/pause, next/previous, seek, shuffle, repeat, a queue you can reorder, like (heart), playback speed, and a sleep timer. Lock screen, notification and headset controls come from the media session.
- **Playlists:** make them, add songs, reorder.
- **Resume:** the queue and position from last time come back when you open the app.
- **Sound:** Off, Bass boost, Treble boost or Vocal (uses the phone's equalizer, when it has one).

---

## 5. Things not checked on a real device

Gapless playback between songs, the equalizer sound, and the sleep timer pausing music with the screen off have only been tested in code, not by ear.
