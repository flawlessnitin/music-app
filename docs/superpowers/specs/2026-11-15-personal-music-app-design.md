# Personal music app — design

> **Superseded.** This document describes an earlier design that synced music through a Backblaze B2 bucket. That was removed: songs now go from the PC to the phone over USB (`musictool` + adb). See `android/README.md` and `tools/musictool/README.md` for how it works today.


Date: 2026-11-15
Status: approved by Nitin on 2026-11-15 (Opus 256 kbps, plus a few FLAC)

## Intent

A private "Spotify for one": Nitin's own songs, playable on his Samsung Galaxy M11
(custom ROM), with no server to run and no recurring cost. The cloud holds the
master copy; the phone mirrors it and plays offline.

## Goals

- Play the whole library on the phone with no network at all.
- Add songs from the PC with one command; they show up on the phone by themselves.
- Feel like a normal music app: library, search, playlists, queue, background
  play, lock-screen and headset controls.
- Run smoothly on a Snapdragon 450 with 3–4 GB RAM.

## Non-goals (first version)

- Other users, sharing, or accounts.
- Recommendations, lyrics, podcasts, equaliser.
- Playing on other devices (web, desktop, second phone).
- Editing tags or uploading from the phone.
- Backing up playlists to the cloud (they live on the phone only).

## Anti-goals

- Any monthly bill.
- Anything that must stay running (a VM, a home server, a tunnel).
- Using mobile data without being asked.

## Constraints

- Phone: Galaxy M11, Snapdragon 450, 3–4 GB RAM, about 45 GB free internal
  storage, no microSD card. Custom ROM, so Google Play services may be absent:
  the app must not depend on them.
- Cloud: Backblaze B2 free tier, 10 GB of storage. Free download allowance is
  reported as about 3x stored data per month. Sources disagree on whether there
  are also daily download or API-call caps, so the app must tolerate being
  refused and carry on later.
- PC: Windows 11 with Python 3.15, ffmpeg 9, Node, Java 25 and git present.
  The Android SDK is not installed yet.
- The project folder is empty and is not yet a git repository.

## Approaches considered

1. **Serverless: files in object storage + catalog file + own Android app.**
   Chosen. Nothing to maintain, free, and the app is fully Nitin's own.
2. **Local-only with PC sync over USB/Wi-Fi.** Simplest, but no cloud copy: a
   wiped or lost phone loses the library, and adding songs needs the PC nearby.
3. **Navidrome on a free cloud VM + an existing client app.** Least code, but
   rests on Oracle's free tier (idle instances reclaimed, allowance reportedly
   cut in 2026) and is not "building my own".

Storage provider: Cloudflare R2 was recommended for its uncapped free egress.
Nitin chose **Backblaze B2** and has created the account. B2 works for this
design because the phone downloads each song once and then plays locally, so
download volume stays far below the free allowance.

## Decided by Nitin

- Build his own Android app, for himself only.
- No server.
- Backblaze B2 for online storage.
- Phone storage is the 45 GB of internal space.
- Audio must be top quality, for listening on wired earphones.

## Decided by Claude (correct any of these)

- Nitin has no audio files yet and will download them. The PC tool takes a
  folder of audio files, wherever they came from; it does not fetch music
  itself. Lossless purchases (Bandcamp, Qobuz and similar stores, or CD rips)
  are the sources that actually deliver top quality.
- Quality rule: lossless sources (FLAC, WAV, ALAC) are converted to Opus at
  256 kbps, which is beyond the point where the difference from lossless is
  audible. Files that are already lossy (MP3, AAC, Opus) are uploaded
  untouched, because re-encoding them would only lose quality. At about 7 MB
  a song, 10 GB holds roughly 1,400 songs. Confirmed by Nitin.
- A few chosen songs are kept as FLAC (Nitin's request). `add --lossless`
  uploads lossless sources as FLAC instead of converting them; WAV and ALAC
  are packed to FLAC, which loses nothing. Each costs about 28 MB, four times
  an Opus song, and `status` shows how much space the FLAC songs take. The
  catalog records each song's format so the app can show a "lossless" mark.
- The phone keeps the whole library offline by default.
- Native Kotlin app rather than Flutter or React Native, for low memory use on
  this phone and direct use of Android's media library.
- PC tool written in Python.

## Design

### Shape

```
PC: musictool add <folder>
      │  read tags, convert, upload
      ▼
B2 private bucket
  songs/<id>.<ext>      (.opus, .flac for the few lossless picks, or the
                        original .mp3/.m4a for lossy sources)
  covers/<albumId>.jpg
  catalog.json
      │  sync on Wi-Fi (read-only key)
      ▼
Phone app: local database + local song files, plays offline
```

### Bucket

- One private bucket.
- Two application keys, both limited to this bucket: a read-write key used only
  by the PC tool, and a read-only key used only by the phone.
- `catalog.json` is the single source of truth for what exists:

```json
{
  "version": 42,
  "updatedAt": "2026-11-15T10:00:00Z",
  "songs": [
    {
      "id": "9f2c1a7b3d4e5f60",
      "title": "…", "artist": "…", "album": "…", "albumArtist": "…",
      "track": 3, "disc": 1, "year": 2019,
      "durationMs": 215000, "sizeBytes": 7000000, "format": "opus",
      "file": "songs/9f2c1a7b3d4e5f60.opus",
      "cover": "covers/1b2c3d4e5f607182.jpg"
    }
  ]
}
```

- A song's `id` is the first 16 hex characters of the SHA-1 of the original
  file's bytes, so adding the same file twice does nothing.
- `version` goes up by one on every change; the phone compares it to decide
  whether to sync.

### PC tool (`musictool`)

Python command-line tool. Keys live in a local config file that is never
committed.

- `add <folder-or-file>`: find audio files, read tags, skip ones already in
  the catalog, apply the quality rule above (lossless to Ogg Opus 256 kbps
  with ffmpeg, lossy files kept as they are), extract cover art and
  shrink it to 500 px JPEG, upload, then upload the new catalog last.
- `remove <id-or-search>`: delete the song from the bucket and catalog.
- `list` / `status`: show songs and space used out of 10 GB.
- Refuses an `add` that would take the bucket past 9.5 GB.
- Files with missing tags fall back to the file name for the title and
  "Unknown" for artist and album; `add` prints these so they can be fixed.
- An interrupted `add` is safe to rerun: the catalog is written last, so the
  phone never sees a song whose file is missing.

### Android app

Kotlin, Jetpack Compose, Media3 (ExoPlayer with a media session service), Room
for the local database, WorkManager for sync, OkHttp for network. No Google
Play services. Minimum Android 10.

Screens:

```
┌──────────────────────────┐
│ Search…                  │
│ Songs  Albums  Artists   │
│ ──────────────────────── │
│ ▢ Song title        3:35 │
│   Artist · Album       ↓ │   ↓ = not on phone yet
│ ▢ Song title        4:02 │
│   Artist · Album         │
│ ──────────────────────── │
│ ▶ Now playing…      ⏯ ⏭ │   mini player, opens full player
│ Library Playlists Settings│
└──────────────────────────┘
```

- **Library:** songs, albums, artists, with search across all three.
- **Player:** full-screen player with cover, seek bar, previous/next, shuffle,
  repeat, and the queue (reorder, remove, play next, add to queue).
- **Playlists:** create, rename, delete, add and remove songs, reorder.
- **Settings:** B2 key entry, Wi-Fi-only switch (on by default), storage cap,
  space used, "sync now", last sync result.

Behaviour:

- **Setup:** the read-only key ID, key and bucket name are pasted once into
  Settings and stored encrypted on the phone. They are never in source code.
- **Sync:** runs when the app opens and periodically in the background, on
  Wi-Fi only unless the switch is turned off. It fetches the catalog; if the
  version changed it updates the local database, downloads new songs and
  covers, and deletes songs that were removed.
- **Playback:** a downloaded song plays from local storage. A song not yet
  downloaded streams from B2 if a network is allowed, and is saved as it plays.
- **Refusals from B2** (cap reached, rate limited, network lost): the download
  stops, keeps what it has, and resumes on the next sync. The app shows how
  many songs are still waiting. Songs already on the phone are unaffected.
- **Storage cap:** if the library would exceed the cap, the app stops
  downloading and says so; it does not delete songs on its own.
- **Background:** playback continues with the screen off, with notification,
  lock-screen, headset-button and Bluetooth controls, and pauses when
  headphones are unplugged.
- Playlists refer to songs by `id`. If a song is removed from the catalog it
  drops out of playlists.

### Testing

- PC tool: automated tests for tag reading, id generation, catalog updates and
  the rerun-after-interruption case, with B2 replaced by a fake.
- App: automated tests for catalog parsing, the sync decisions (what to
  download, what to delete, resume after refusal) and queue logic.
- Final check on the real phone: add songs on the PC, sync, turn on aeroplane
  mode, play with the screen off.

## Left to the builder

- Module and package layout, naming, and visual styling.
- Exact sync interval and retry timing.
- How the Android SDK gets installed on this PC and how the app is put on the
  phone (USB debugging is the expected route).
- Whether to use B2's native API or its S3-compatible one.

## Order of work

1. PC tool, so there is real music in the bucket to test against.
2. App: sync and local library.
3. App: playback and background controls.
4. App: playlists, queue, settings polish.
