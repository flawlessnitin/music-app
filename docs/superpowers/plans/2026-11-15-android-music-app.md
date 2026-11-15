# Android Music App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Kotlin Android app for a Samsung Galaxy M11 that mirrors the music library held in a private Backblaze B2 bucket onto the phone and plays it offline, with library, search, playlists, queue and background playback.

**Architecture:** One Gradle module. `catalog.json` in the bucket is the source of truth; a background sync copies it into a local Room database and downloads song files into app storage. Playback goes through a Media3 `MediaSessionService` that plays the local file when present and streams from B2 otherwise. The UI is Jetpack Compose reading from the database and a `MediaController`.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Media3 (ExoPlayer + session), Room, WorkManager, OkHttp, kotlinx.serialization, Coil for cover images. Tests: JUnit, Robolectric, OkHttp MockWebServer, kotlinx-coroutines-test. No Google Play services.

**Spec:** `docs/superpowers/specs/2026-11-15-personal-music-app-design.md`

This is plan 2 of 2. Plan 1 (`2026-11-15-musictool-pc-uploader.md`) built the PC tool that fills the bucket; its `catalog.py` defines the catalog format this app reads.

## Global Constraints

- Minimum Android 10 (`minSdk 29`). Target and compile SDK: the latest stable at build time.
- Pin every library version in `gradle/libs.versions.toml`, using the latest stable releases at build time. Do not use alpha or beta artifacts.
- No dependency on Google Play services or Firebase.
- The app only ever reads from B2, with a read-only key. It never uploads or deletes there.
- The B2 key ID, application key and bucket name are entered in Settings, stored encrypted with an Android Keystore key, and never appear in source code, logs or backups (`android:allowBackup="false"`).
- Bucket layout: `catalog.json`, `songs/<id>.<ext>`, `covers/<albumId>.jpg`. Song formats: `opus`, `flac`, `mp3`, `aac`, `vorbis`.
- Wi-Fi-only is on by default. With it on, the app makes no network request on a metered connection, for sync or for streaming.
- The app never deletes a downloaded song except when the catalog no longer contains it.
- Default storage cap: 20 GB (`20_000_000_000` bytes).
- Song files live in app-specific storage (`context.filesDir/songs`, `context.filesDir/covers`), so no storage permission is needed.
- Package name `com.personal.music`; app label `Music`. Project lives in `android/`.
- Commit messages carry no `Co-Authored-By` trailer.

## Review Focus

1. The app is killed or the connection drops in the middle of a download: the half-written file is never played or counted as downloaded, and the next sync fetches it again. (Task 5)
2. A sync removes a song that is playing, queued or in a playlist: nothing crashes, the current song finishes or is skipped, and it disappears from playlists. (Tasks 3, 6 and 9)
3. The key is wrong, revoked or mistyped: Settings shows a plain message saying so, and everything already on the phone still plays. (Tasks 4 and 10)
4. The phone runs out of free space before the cap is reached: downloading stops with a message and no partial file is left behind. (Task 5)
5. The catalog has `null` for track, disc, year or cover, or carries fields this version does not know: it still loads. (Task 2)

## File Structure

```
android/
  settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml  gradlew(.bat)
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/personal/music/
    MusicApp.kt                 Application: builds the object graph (AppContainer)
    AppContainer.kt             manual dependency wiring, no DI framework
    catalog/CatalogModels.kt    RemoteCatalog, RemoteSong
    catalog/CatalogParser.kt
    db/Entities.kt  db/Daos.kt  db/MusicDatabase.kt
    library/LibraryRepository.kt
    settings/SettingsStore.kt   credentials, Wi-Fi-only, storage cap, last sync result
    b2/B2Client.kt              authorize, open a download, auth for streaming
    sync/SyncPlanner.kt         pure decision logic
    sync/Downloader.kt
    sync/SyncRunner.kt          one full sync pass
    sync/SyncWorker.kt          WorkManager entry point and scheduling
    playback/PlaybackService.kt MediaSessionService
    playback/MediaItems.kt      Song -> MediaItem
    playback/PlayerConnection.kt MediaController wrapper exposing state to the UI
    playlists/PlaylistRepository.kt
    ui/MainActivity.kt  ui/AppNav.kt  ui/theme/
    ui/library/  ui/player/  ui/playlists/  ui/settings/
  app/src/test/java/com/personal/music/...   JVM and Robolectric tests
```

---

### Task 1: Toolchain and empty app

**Files:**
- Create: the Gradle project under `android/`, `MusicApp.kt`, `ui/MainActivity.kt`, `ui/theme/`, `AndroidManifest.xml`
- Modify: root `.gitignore` (add `android/.gradle/`, `android/**/build/`, `android/local.properties`, `*.apk`)

**Interfaces:**
- Produces: a project where `./gradlew test` and `./gradlew assembleDebug` succeed, with every library in the Tech Stack already declared in `libs.versions.toml` and `app/build.gradle.kts`.

- [ ] **Step 1: Install the Android toolchain**

The PC has Java 25, git and no Android SDK. Install the Android command-line tools to `%LOCALAPPDATA%\Android\Sdk`, set `ANDROID_HOME`, then use `sdkmanager` to install `platform-tools`, the latest stable `platforms;android-NN` and matching `build-tools`, and accept the licences. If the current Android Gradle Plugin does not support running on Java 25, install JDK 21 (Temurin) alongside and point Gradle at it with `org.gradle.java.home` in `android/gradle.properties`.

Run: `adb version` and `sdkmanager --list_installed`
Expected: adb prints a version; the platform and build-tools are listed.

- [ ] **Step 2: Create the project**

Single `:app` module, Kotlin DSL, Compose enabled, `minSdk 29`, `applicationId "com.personal.music"`, label `Music`, `allowBackup="false"`. `MainActivity` shows a Compose screen with the text `Music`. Dark theme by default, following the system setting. Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`.

- [ ] **Step 3: Add one smoke test**

```kotlin
class SmokeTest { @Test fun `project builds and runs tests`() { assertEquals(4, 2 + 2) } }
```

- [ ] **Step 4: Verify**

Run (from `android/`): `./gradlew test assembleDebug`
Expected: `BUILD SUCCESSFUL`, and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Commit**

```bash
git add .gitignore android
git commit -m "feat(app): Android project skeleton"
```

---

### Task 2: Catalog parsing

**Files:**
- Create: `catalog/CatalogModels.kt`, `catalog/CatalogParser.kt`
- Test: `app/src/test/java/com/personal/music/catalog/CatalogParserTest.kt`

**Interfaces:**
- Produces:
  - `data class RemoteSong(val id: String, val title: String, val artist: String, val album: String, val albumArtist: String, val track: Int?, val disc: Int?, val year: Int?, val durationMs: Long, val sizeBytes: Long, val format: String, val file: String, val cover: String?)`
  - `data class RemoteCatalog(val version: Int, val updatedAt: String, val songs: List<RemoteSong>)`
  - `object CatalogParser { fun parse(json: String): RemoteCatalog }`; throws `CatalogFormatException` on text that is not a valid catalog

- [ ] **Step 1: Write the failing tests**

Use this fixture, which is exactly what the PC tool writes:

```json
{"version": 42, "updatedAt": "2026-11-15T10:00:00Z", "songs": [
 {"id":"9f2c1a7b3d4e5f60","title":"तुम ही हो","artist":"A","album":"B","albumArtist":"A",
  "track":3,"disc":1,"year":2019,"durationMs":215000,"sizeBytes":7000000,"format":"opus",
  "file":"songs/9f2c1a7b3d4e5f60.opus","cover":"covers/1b2c3d4e5f607182.jpg"},
 {"id":"aaaaaaaaaaaaaaaa","title":"T","artist":"Unknown","album":"Unknown","albumArtist":"Unknown",
  "track":null,"disc":null,"year":null,"durationMs":1000,"sizeBytes":28000000,"format":"flac",
  "file":"songs/aaaaaaaaaaaaaaaa.flac","cover":null,"somethingNew":"ignored"}]}
```

```kotlin
@Test fun `parses version and every field`()      // version 42, 2 songs, first title "तुम ही हो", sizeBytes 7_000_000
@Test fun `null track disc year and cover are kept as null`()
@Test fun `unknown fields are ignored`()          // "somethingNew" does not fail the parse
@Test fun `empty catalog parses`()                // {"version":0,"updatedAt":"","songs":[]}
@Test fun `garbage throws CatalogFormatException`()        // "<html>error</html>" and ""
@Test fun `missing required field throws CatalogFormatException`()  // a song with no "id"
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests "*CatalogParserTest"`. Expected: compilation fails, classes missing.

- [ ] **Step 3: Implement** with kotlinx.serialization, `ignoreUnknownKeys = true`, wrapping `SerializationException` in `CatalogFormatException`.

- [ ] **Step 4: Run to verify pass.** Same command; all PASS.

- [ ] **Step 5: Commit** `feat(app): catalog parsing`

---

### Task 3: Local library database

**Files:**
- Create: `db/Entities.kt`, `db/Daos.kt`, `db/MusicDatabase.kt`, `library/LibraryRepository.kt`
- Test: `library/LibraryRepositoryTest.kt` (Robolectric, in-memory Room)

**Interfaces:**
- Consumes: `RemoteCatalog`, `RemoteSong`.
- Produces:
  - `@Entity SongEntity`: every `RemoteSong` field plus `downloaded: Boolean` (default `false`); primary key `id`
  - `data class Album(val albumArtist: String, val album: String, val cover: String?, val year: Int?, val songCount: Int)`
  - `data class Artist(val name: String, val songCount: Int)`
  - `class LibraryRepository(db: MusicDatabase)`:
    - `suspend fun applyCatalog(catalog: RemoteCatalog): List<SongEntity>`: inserts new songs, updates changed metadata while keeping `downloaded`, deletes songs not in the catalog, stores `catalog.version`, and returns the deleted songs so the caller can remove their files
    - `suspend fun catalogVersion(): Int`: `0` before the first sync
    - `fun songs(): Flow<List<SongEntity>>`: ordered by title, case-insensitive
    - `fun albums(): Flow<List<Album>>`, `fun artists(): Flow<List<Artist>>` (artist = `albumArtist`)
    - `fun albumSongs(albumArtist: String, album: String): Flow<List<SongEntity>>`: ordered by disc then track, nulls first
    - `fun artistSongs(name: String): Flow<List<SongEntity>>`
    - `fun search(query: String): Flow<List<SongEntity>>`: case-insensitive match on title, artist or album; blank query returns everything
    - `suspend fun song(id: String): SongEntity?`
    - `suspend fun setDownloaded(id: String, downloaded: Boolean)`
    - `suspend fun pending(): List<SongEntity>`: songs with `downloaded = false`
    - `suspend fun downloadedBytes(): Long`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `applyCatalog inserts songs and stores the version`()
@Test fun `applyCatalog keeps the downloaded flag when metadata changes`()
@Test fun `applyCatalog deletes songs missing from the new catalog and returns them`()
@Test fun `catalogVersion is 0 before any sync`()
@Test fun `albums are grouped by albumArtist and album with a song count`()
@Test fun `albumSongs are ordered by disc then track with nulls first`()
@Test fun `search matches title artist and album ignoring case`()   // "तुम" finds the Hindi title
@Test fun `blank search returns every song`()
@Test fun `pending lists only songs not downloaded and downloadedBytes sums the rest`()
```

- [ ] **Step 2: Run to verify failure.** `./gradlew test --tests "*LibraryRepositoryTest"`

- [ ] **Step 3: Implement.** `applyCatalog` runs in one Room transaction. The catalog version is kept in a one-row `MetaEntity` table.

- [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit** `feat(app): local library database`

---

### Task 4: Settings and the B2 connection

**Files:**
- Create: `settings/SettingsStore.kt`, `b2/B2Client.kt`
- Test: `settings/SettingsStoreTest.kt` (Robolectric), `b2/B2ClientTest.kt` (MockWebServer)

**Interfaces:**
- Produces:
  - `data class Credentials(val keyId: String, val applicationKey: String, val bucket: String)`
  - `class SettingsStore(context: Context)`:
    - `suspend fun credentials(): Credentials?`, `suspend fun saveCredentials(c: Credentials)`, `suspend fun clearCredentials()`
    - `val wifiOnly: Flow<Boolean>` (default `true`), `suspend fun setWifiOnly(v: Boolean)`
    - `val capBytes: Flow<Long>` (default `20_000_000_000`), `suspend fun setCapBytes(v: Long)`
    - `val lastSync: Flow<SyncSummary?>`, `suspend fun setLastSync(s: SyncSummary)`
  - `data class SyncSummary(val finishedAtMillis: Long, val outcome: SyncOutcome, val message: String, val pendingSongs: Int)`
  - `enum class SyncOutcome { UP_TO_DATE, DOWNLOADED, WAITING, BAD_KEY, NO_NETWORK, STORAGE_FULL, CAP_REACHED, NOT_SET_UP, ERROR }`
  - `sealed class B2Exception : IOException`: `BadKey`, `Refused(val code: String)` (cap exceeded, rate limited, service unavailable), `NotFound`, `Network(cause)`
  - `class B2Client(http: OkHttpClient, credentials: suspend () -> Credentials?, authUrl: String = "https://api.backblazeb2.com/b2api/v3/b2_authorize_account")`:
    - `suspend fun open(name: String): Response`: an open HTTP response for `<downloadUrl>/file/<bucket>/<name>`; the caller closes it
    - `suspend fun readText(name: String): String`
    - `suspend fun streamUrl(name: String): String` and `suspend fun authHeader(): String`: for the player
    - `fun invalidate()`: drop the cached token

Verify the `b2_authorize_account` response shape (where the download URL and token sit) against Backblaze's current API reference before writing the parser; build the test fixture from that reference.

Behaviour: the token and download URL are cached in memory. A `401` on a download drops the cache, authorizes once more and retries once. `401` from authorize itself is `BadKey`. `403` with a cap code, `429` and `503` are `Refused`. `404` is `NotFound`. `IOException` is `Network`. Missing credentials throw `BadKey`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// SettingsStoreTest
@Test fun `credentials round trip and are not stored in plain text`()  // raw preference bytes do not contain the key
@Test fun `defaults are wifi only on and a 20 GB cap`()
@Test fun `clearCredentials removes them`()

// B2ClientTest
@Test fun `authorizes with basic auth then downloads with the token`()
@Test fun `reuses the token across downloads`()                       // 1 authorize request for 2 downloads
@Test fun `expired token re-authorizes once and retries`()
@Test fun `bad key gives BadKey`()
@Test fun `cap exceeded rate limit and 503 give Refused`()
@Test fun `missing file gives NotFound`()
@Test fun `connection failure gives Network`()
@Test fun `no saved credentials gives BadKey without any request`()
@Test fun `song names are url encoded in the download path`()
```

- [ ] **Step 2: Run to verify failure.** `./gradlew test --tests "*SettingsStoreTest" --tests "*B2ClientTest"`

- [ ] **Step 3: Implement.** Secrets are encrypted with an AES-GCM key held in the Android Keystore and stored through DataStore; do not use the deprecated `EncryptedSharedPreferences`. Under Robolectric, where the Keystore is absent, inject the cipher through a small `SecretCipher` interface with a fake in tests.

- [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit** `feat(app): settings store and B2 client`

---

### Task 5: Sync

**Files:**
- Create: `sync/SyncPlanner.kt`, `sync/Downloader.kt`, `sync/SyncRunner.kt`, `sync/SyncWorker.kt`
- Modify: `AppContainer.kt`, `MusicApp.kt` (schedule the periodic sync)
- Test: `sync/SyncPlannerTest.kt`, `sync/DownloaderTest.kt`, `sync/SyncRunnerTest.kt`

**Interfaces:**
- Consumes: `LibraryRepository`, `B2Client`, `SettingsStore`, `CatalogParser`.
- Produces:
  - `object SyncPlanner { fun plan(pending: List<SongEntity>, downloadedBytes: Long, capBytes: Long, freeBytes: Long): SyncPlan }`
  - `data class SyncPlan(val toDownload: List<SongEntity>, val blockedByCap: Int, val blockedByFreeSpace: Int)`
  - `class Downloader(b2: B2Client, songsDir: File, coversDir: File)`:
    - `suspend fun downloadSong(song: SongEntity)`: writes `<file name>.part`, then renames
    - `suspend fun downloadCover(name: String)`: skips if already present
    - `fun localFile(song: SongEntity): File`, `fun coverFile(name: String): File`
    - `fun delete(song: SongEntity)`, `fun deleteCoverIfUnused(name: String, stillUsed: Boolean)`
    - `fun clearPartials()`
  - `class SyncRunner(...) { suspend fun run(prioritySongId: String? = null): SyncSummary }`
  - `object SyncScheduler { fun schedulePeriodic(context: Context); fun syncNow(context: Context, prioritySongId: String? = null) }`

`SyncPlanner.plan`: takes pending songs in order; a song is downloaded while `downloadedBytes + sum so far + song.sizeBytes <= capBytes` and the same running total fits in `freeBytes` minus a 500 MB reserve; the rest are counted as blocked, by cap first.

`SyncRunner.run`, in order:
1. No credentials: `NOT_SET_UP`.
2. Wi-Fi-only is on and the connection is metered, or there is no connection: `NO_NETWORK`, no request made.
3. `clearPartials()`, fetch and parse `catalog.json`. If its version differs from the stored one, `applyCatalog`, then delete the files of removed songs and any cover no remaining song uses.
4. Reconcile: a song marked downloaded whose file is missing is marked not downloaded.
5. Plan, moving `prioritySongId` to the front, and download each song then its cover, marking each song downloaded as it completes.
6. `Refused` stops the pass with `WAITING`; `BadKey` with `BAD_KEY`; `Network` with `NO_NETWORK`; an out-of-space `IOException` with `STORAGE_FULL`. `NotFound` for one song skips it and continues. Songs blocked by the cap give `CAP_REACHED`. Otherwise `DOWNLOADED`, or `UP_TO_DATE` if nothing was pending.
7. The summary, with the count of songs still pending, is saved with `setLastSync` in every case.

`SyncWorker` is a `CoroutineWorker` running as a foreground `dataSync` service with a progress notification. `schedulePeriodic` enqueues a unique periodic work every 6 hours with a connected-network constraint; the Wi-Fi rule is enforced inside `SyncRunner`, since the user can turn it off. `syncNow` enqueues unique one-time work that replaces any queued one.

- [ ] **Step 1: Write the failing tests**

```kotlin
// SyncPlannerTest
@Test fun `everything fits`()
@Test fun `songs past the cap are blocked not planned`()
@Test fun `free space keeps a 500 MB reserve`()
@Test fun `nothing pending gives an empty plan`()

// DownloaderTest (MockWebServer, temp dirs)
@Test fun `a completed download is at its final name with the right bytes`()
@Test fun `a connection cut mid body leaves no final file`()            // Review Focus 1
@Test fun `clearPartials removes part files and keeps finished songs`()
@Test fun `a cover already on disk is not fetched again`()

// SyncRunnerTest (fake B2 via MockWebServer, in-memory db)
@Test fun `first sync downloads every song and reports DOWNLOADED`()
@Test fun `unchanged catalog with everything downloaded makes one request and reports UP_TO_DATE`()
@Test fun `a song removed from the catalog loses its row file and unused cover`()
@Test fun `a cover shared with a remaining song is kept`()
@Test fun `refusal part way keeps finished songs and reports WAITING with the pending count`()
@Test fun `the next run after a refusal downloads only what is left`()
@Test fun `bad key reports BAD_KEY and touches nothing on disk`()
@Test fun `metered connection with wifi only makes no request`()
@Test fun `out of space reports STORAGE_FULL and leaves no partial file`()   // Review Focus 4
@Test fun `a song whose file vanished is downloaded again`()
@Test fun `priority song is downloaded first`()
@Test fun `no credentials reports NOT_SET_UP`()
```

- [ ] **Step 2: Run to verify failure.** `./gradlew test --tests "*sync*"`

- [ ] **Step 3: Implement.** Network state and free space reach `SyncRunner` through two small interfaces (`NetworkState { val connected: Boolean; val metered: Boolean }`, `FreeSpace { fun bytes(): Long }`) so tests can fake them.

- [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit** `feat(app): library sync with resume and limits`

---

### Task 6: Playback

**Files:**
- Create: `playback/PlaybackService.kt`, `playback/MediaItems.kt`, `playback/PlayerConnection.kt`
- Modify: `AndroidManifest.xml` (service with `mediaPlayback` foreground type), `AppContainer.kt`
- Test: `playback/MediaItemsTest.kt`, `playback/PlayerConnectionTest.kt`

**Interfaces:**
- Consumes: `SongEntity`, `Downloader.localFile`, `Downloader.coverFile`, `B2Client.streamUrl`, `B2Client.authHeader`, `SettingsStore.wifiOnly`, `SyncScheduler.syncNow`.
- Produces:
  - `object MediaItems { fun from(song: SongEntity, localFile: File?, coverFile: File?): MediaItem }`: `mediaId = song.id`; URI is the local file when given, otherwise the custom URI `b2://<song.file>`; metadata carries title, artist, album and artwork URI
  - `class PlayerConnection(context: Context)`:
    - `val state: StateFlow<PlayerState>`
    - `fun play(songs: List<SongEntity>, startIndex: Int)`, `fun playNext(song: SongEntity)`, `fun addToQueue(song: SongEntity)`
    - `fun togglePlay()`, `fun next()`, `fun previous()`, `fun seekTo(positionMs: Long)`
    - `fun setShuffle(on: Boolean)`, `fun cycleRepeat()`
    - `fun moveQueueItem(from: Int, to: Int)`, `fun removeQueueItem(index: Int)`, `fun jumpTo(index: Int)`
  - `data class PlayerState(val current: QueueItem?, val queue: List<QueueItem>, val index: Int, val isPlaying: Boolean, val positionMs: Long, val durationMs: Long, val shuffle: Boolean, val repeat: RepeatMode, val error: String?)`
  - `data class QueueItem(val songId: String, val title: String, val artist: String, val artworkUri: String?)`
  - `enum class RepeatMode { OFF, ALL, ONE }`

`PlaybackService`: one ExoPlayer with audio attributes for music, `handleAudioFocus = true`, `setHandleAudioBecomingNoisy(true)` and a wake lock for network playback, wrapped in a `MediaSession`. Its data source resolves `b2://` URIs through `B2Client` at open time, adding the auth header, so a token that expires mid-queue is refreshed. When a `b2://` item starts and the network is allowed, it calls `SyncScheduler.syncNow(context, prioritySongId = id)` so the song is saved. If Wi-Fi-only blocks streaming, or the source fails, the player reports the error in `PlayerState.error` and moves to the next item instead of stopping. The queue is the player's own playlist; `playNext` inserts after the current index.

- [ ] **Step 1: Write the failing tests**

```kotlin
// MediaItemsTest (Robolectric)
@Test fun `downloaded song uses the local file uri`()
@Test fun `song not downloaded uses a b2 uri with the bucket path`()
@Test fun `metadata carries title artist album and artwork`()
@Test fun `missing cover gives no artwork uri`()

// PlayerConnectionTest (Media3 test-utils TestExoPlayerBuilder behind the same wrapper)
@Test fun `play sets the queue and starts at the chosen index`()
@Test fun `playNext inserts after the current item`()
@Test fun `addToQueue appends`()
@Test fun `moving and removing queue items updates state`()
@Test fun `removing the current item advances to the next`()     // Review Focus 2
@Test fun `cycleRepeat goes OFF ALL ONE OFF`()
@Test fun `a failing item is skipped and the error is reported`()
```

- [ ] **Step 2: Run to verify failure.** `./gradlew test --tests "*playback*"`

- [ ] **Step 3: Implement.** `PlayerConnection` takes a `Player` so the tests can hand it a test player; the app hands it a `MediaController` connected to `PlaybackService`.

- [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit** `feat(app): background playback service and queue`

---

### Task 7: Library screens and mini player

**Files:**
- Create: `ui/AppNav.kt`, `ui/library/LibraryScreen.kt`, `ui/library/LibraryViewModel.kt`, `ui/library/AlbumScreen.kt`, `ui/library/ArtistScreen.kt`, `ui/player/MiniPlayer.kt`
- Modify: `ui/MainActivity.kt`
- Test: `ui/library/LibraryViewModelTest.kt`, `ui/library/LibraryScreenTest.kt` (Compose UI test under Robolectric)

**Interfaces:**
- Consumes: `LibraryRepository`, `PlayerConnection`.
- Produces:
  - `class LibraryViewModel(repo, player)`: `val query: StateFlow<String>`, `fun setQuery(q: String)`, `val songs: StateFlow<List<SongEntity>>` (follows the query), `val albums`, `val artists`, `fun playSong(list: List<SongEntity>, index: Int)`, `fun playNext(song)`, `fun addToQueue(song)`
  - Navigation routes: `library`, `album/{albumArtist}/{album}`, `artist/{name}`, `player`, `playlists`, `playlist/{id}`, `settings`

Layout, as in the spec: a search field on top; tabs `Songs`, `Albums`, `Artists`; a list; a mini player above a bottom bar with `Library`, `Playlists`, `Settings`. A song row shows cover, title, `artist · album`, duration, and a download mark when the song is not on the phone yet; FLAC songs show a small `FLAC` label. Tapping a song plays the visible list from that song. Long-press opens a menu: `Play next`, `Add to queue`, `Add to playlist`. The mini player shows cover, title, artist, play/pause and next; tapping it opens `player`. Empty library shows `No songs yet. Add your B2 key in Settings, then sync.`

- [ ] **Step 1: Write the failing tests**

```kotlin
// LibraryViewModelTest
@Test fun `songs follow the search query`()
@Test fun `playSong sends the visible list and index to the player`()

// LibraryScreenTest
@Test fun `shows song rows with title and artist`()
@Test fun `a song not downloaded shows the download mark`()
@Test fun `flac song shows the FLAC label`()
@Test fun `empty library shows the setup hint`()
@Test fun `typing in search filters the list`()
@Test fun `tapping a song starts playback from it`()
@Test fun `mini player appears only when something is queued`()
```

- [ ] **Step 2: Run to verify failure.** `./gradlew test --tests "*ui.library*"`

- [ ] **Step 3: Implement.** Lists use `LazyColumn` with stable keys (`song.id`); covers load through Coil from `Downloader.coverFile`.

- [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit** `feat(app): library, search and mini player`

---

### Task 8: Player and queue screen

**Files:**
- Create: `ui/player/PlayerScreen.kt`, `ui/player/QueueSheet.kt`
- Test: `ui/player/PlayerScreenTest.kt`

**Interfaces:**
- Consumes: `PlayerConnection`, `PlayerState`.

Full-screen player: large cover, title, artist, seek bar with elapsed and total time, previous, play/pause, next, shuffle and repeat toggles, and a queue button. The queue sheet lists upcoming items with drag-to-reorder and swipe-to-remove; tapping an item jumps to it; the current item is highlighted. `PlayerState.error` is shown as a snackbar.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `shows the current title artist and times`()
@Test fun `play pause next previous call the player`()
@Test fun `dragging the seek bar seeks on release`()
@Test fun `repeat button shows OFF ALL ONE in turn`()
@Test fun `queue sheet lists items and highlights the current one`()
@Test fun `swiping a queue item removes it`()
@Test fun `tapping a queue item jumps to it`()
@Test fun `a player error is shown as a snackbar`()
```

- [ ] **Step 2: Run to verify failure.** `./gradlew test --tests "*PlayerScreenTest"`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit** `feat(app): full player and queue`

---

### Task 9: Playlists

**Files:**
- Create: `playlists/PlaylistRepository.kt`, `ui/playlists/PlaylistsScreen.kt`, `ui/playlists/PlaylistScreen.kt`, `ui/playlists/AddToPlaylistSheet.kt`
- Modify: `db/Entities.kt`, `db/Daos.kt`, `db/MusicDatabase.kt` (schema version 2 with a migration)
- Test: `playlists/PlaylistRepositoryTest.kt`, `ui/playlists/PlaylistScreenTest.kt`

**Interfaces:**
- Produces:
  - `@Entity PlaylistEntity(id: Long autoGenerate, name: String, createdAtMillis: Long)`
  - `@Entity PlaylistSongEntity(playlistId: Long, songId: String, position: Int)`: primary key `(playlistId, songId)`; foreign keys to both tables with `onDelete = CASCADE`
  - `class PlaylistRepository(db)`:
    - `fun playlists(): Flow<List<PlaylistSummary>>` where `PlaylistSummary(id, name, songCount)`
    - `fun songs(playlistId: Long): Flow<List<SongEntity>>`: in position order
    - `suspend fun create(name: String): Long`, `rename(id, name)`, `delete(id)`
    - `suspend fun addSong(playlistId: Long, songId: String)`: appended; adding a song already present does nothing
    - `suspend fun removeSong(playlistId: Long, songId: String)`, `suspend fun move(playlistId: Long, from: Int, to: Int)`

A blank name is rejected with `IllegalArgumentException`. Because of the cascade, a song removed by sync drops out of every playlist.

- [ ] **Step 1: Write the failing tests**

```kotlin
// PlaylistRepositoryTest
@Test fun `create rename delete`()
@Test fun `blank name is rejected`()
@Test fun `songs come back in the order added`()
@Test fun `adding the same song twice keeps one entry`()
@Test fun `move reorders and positions stay contiguous`()
@Test fun `removing a song closes the gap`()
@Test fun `a song deleted by sync disappears from playlists`()        // Review Focus 2
@Test fun `deleting a playlist keeps the songs in the library`()
@Test fun `migration from version 1 keeps existing songs`()

// PlaylistScreenTest
@Test fun `shows songs and plays from the tapped one`()
@Test fun `empty playlist shows a hint`()
@Test fun `add to playlist sheet lists playlists and can create one`()
```

- [ ] **Step 2: Run to verify failure.** `./gradlew test --tests "*playlists*"`
- [ ] **Step 3: Implement.** Wire `Add to playlist` from the song long-press menu in Task 7.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit** `feat(app): playlists`

---

### Task 10: Settings screen and check on the phone

**Files:**
- Create: `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `android/README.md`
- Test: `ui/settings/SettingsViewModelTest.kt`, `ui/settings/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `SettingsStore`, `SyncScheduler`, `LibraryRepository`, `B2Client`.
- Produces: `class SettingsViewModel`: `val ui: StateFlow<SettingsUi>`, `fun save(keyId, applicationKey, bucket)`, `fun setWifiOnly(v)`, `fun setCapGb(gb: Int)`, `fun syncNow()`, `fun clearKey()`

Settings shows: three fields for key ID, application key (hidden, with a show toggle) and bucket, and a `Save and test` button that tries to read `catalog.json` and reports the result; the Wi-Fi-only switch; a storage cap slider from 1 to 40 GB; `Used X GB of Y GB, N songs on phone, M waiting`; `Sync now`; and the last sync time with its outcome.

Messages for each outcome, shown under `Last sync`:

| Outcome | Message |
|---|---|
| `UP_TO_DATE` | `Up to date.` |
| `DOWNLOADED` | `Downloaded. Everything is on your phone.` |
| `WAITING` | `Backblaze paused downloads for now. N songs will download later.` |
| `BAD_KEY` | `Backblaze rejected the key. Check the key ID, key and bucket name.` |
| `NO_NETWORK` | `No Wi-Fi. Sync will run when Wi-Fi is back.` |
| `STORAGE_FULL` | `Your phone is out of space. N songs are waiting.` |
| `CAP_REACHED` | `Storage cap reached. Raise the cap to download N more songs.` |
| `NOT_SET_UP` | `Add your Backblaze key to start.` |
| `ERROR` | `Sync failed: <message>` |

- [ ] **Step 1: Write the failing tests**

```kotlin
// SettingsViewModelTest
@Test fun `save and test stores the key and reports success when the catalog is readable`()
@Test fun `save and test with a rejected key shows the BAD_KEY message and does not sync`()   // Review Focus 3
@Test fun `fields are trimmed before saving`()
@Test fun `each outcome maps to its message`()        // table above, with N filled in
@Test fun `cap slider stores gigabytes as bytes`()    // 20 -> 20_000_000_000

// SettingsScreenTest
@Test fun `application key is hidden until the show toggle`()
@Test fun `sync now is disabled until a key is saved`()
@Test fun `shows used space and waiting count`()
```

- [ ] **Step 2: Run to verify failure.** `./gradlew test --tests "*settings*"`
- [ ] **Step 3: Implement.** The first launch with no key opens Settings.
- [ ] **Step 4: Run the whole suite.** `./gradlew test assembleDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Write `android/README.md`**: how to build, how to enable USB debugging and install with `adb install`, and how to create the read-only B2 key.

- [ ] **Step 6: Check on the Galaxy M11** (needs the phone connected by USB with debugging on, and songs already uploaded with `musictool`)

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`; the app opens on Settings.
2. Enter the read-only key; `Save and test` succeeds; sync starts and songs appear.
3. When sync finishes, turn on aeroplane mode: every song plays, covers show, search works.
4. Play a song, turn the screen off, use the lock-screen and headset controls, unplug the earphones: playback pauses.
5. Play a FLAC song and an Opus song through wired earphones; both play without gaps or stutter.
6. On the PC run `musictool add` for a new song and `musictool remove` for an existing one; `Sync now` adds the first and removes the second, including from a playlist.
7. Enter a wrong key: the BAD_KEY message shows and the library still plays.
8. Scroll the full song list and switch tabs: no visible stutter on the M11.

- [ ] **Step 7: Commit** `feat(app): settings screen and setup guide`
