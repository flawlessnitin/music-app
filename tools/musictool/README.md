# musictool (PC to phone)

`musictool` puts music on your phone over a USB cable, using `adb`. There is no cloud and no account: the songs go straight into the app's own folder on the phone, and the app finds them there.

It reads tags, turns lossless files into Opus to save space, takes cover art, and sends the result. It also lists, searches and removes what is on the phone.

---

## 1. Setup

You need:
- **Python 3.12+**
- **ffmpeg** and **ffprobe** on your `PATH` (check with `ffmpeg -version`)
- **adb** (Android platform-tools). It is found on your `PATH`, or at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. To use another copy, set `MUSICTOOL_ADB`.
- The phone with **USB debugging** on, plugged in, and the app installed (see `android/README.md`). The first time, allow the "Allow USB debugging?" question on the phone.

Install the tool:

```bash
pip install -e tools/musictool
```

With the test tools:

```bash
pip install -e "tools/musictool[dev]"
```

If more than one phone is connected, set `MUSICTOOL_DEVICE` to the one to use (the id shown by `adb devices`).

---

## 2. Commands

### `add`
Send audio files, or whole folders (searched recursively), to the phone.

```bash
musictool add "D:\Music\Albums\Artist - Album"
```

To keep lossless files as FLAC instead of converting them to Opus:

```bash
musictool add --lossless "D:\Music\Purchases\favorite_track.flac"
```

Songs land on the phone as `Mood/Artist/Album/03 - Title.opus`, with `Folder.jpg` (the cover) next to them.

**Moods.** The folder you add is the mood: `musictool add D:\Music\phonk` puts those songs in the mood `phonk`. If you add a folder called `Music`, each folder inside it is a mood, and songs straight in `Music` have none. Files given one by one have no mood. Use `--mood sad` to choose the mood yourself. Adding a song again with a mood moves the copy already on the phone into that mood instead of sending it again. A `Folder.jpg` or `cover.jpg` beside your files is used when the songs have no art inside. Adding the same song twice sends it only once. When it finishes, the tool opens the app so it picks up the new songs.

The summary lists what was added, what was already there, files skipped (with the reason), songs with missing tags, songs that did not fit on the phone, and any failures.

### `list`
List the songs on the phone, optionally filtered by part of the artist, album or title.

```bash
musictool list
musictool list "Arijit"
```

### `remove`
Remove songs from the phone. A cover goes when the last song of its album does.

```bash
musictool remove "Test Tone"
musictool remove "Old Album" --all
```

If several songs match, nothing is removed unless you add `--all`.

### `status`
Shows how many songs are on the phone, the space they take, how many are FLAC, and how much space is free.

```bash
musictool status
```

---

## 3. Quality rule

- **Lossless sources (FLAC, WAV, ALAC, WavPack, APE)** become **Opus at 256 kbps**: about 7 MB a song, and in practice not distinguishable from lossless.
- **Already-lossy files (MP3, AAC/M4A, Ogg, Opus)** are sent untouched. Converting them again would only lose quality.
- **`--lossless`** keeps lossless files as FLAC (about 4 times bigger).

The tool always leaves 1 GB free on the phone and refuses songs that would not fit.

---

## 4. Tests

```bash
python -m pytest tools/musictool
```

The tests use a pretend phone, so nothing is sent anywhere. They need `ffmpeg` to make small test songs.
