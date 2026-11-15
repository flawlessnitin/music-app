# musictool (PC Uploader)

`musictool` is a command-line tool for managing your personal music library on Backblaze B2. It probes and tags audio files, transcodes lossless files according to quality and storage rules, extracts cover art, uploads files to a private B2 bucket, and maintains a synchronized `catalog.json` for client apps to stream or download.

---

## 1. Backblaze B2 Setup

1. **Create a Private Bucket:**
   - Log in to your Backblaze account and navigate to **B2 Cloud Storage** > **Buckets**.
   - Click **Create a Bucket**.
   - Give the bucket a unique name (e.g. `my-personal-music-library`).
   - Ensure the bucket type is set to **Private**.
   - Keep Object Lock disabled and click **Create a Bucket**.

2. **Create an Application Key:**
   - Go to **Application Keys** in the Backblaze navigation menu.
   - Click **Add a New Application Key**.
   - Enter a name for the key (e.g. `musictool-uploader-key`).
   - Set **Allow access to Bucket(s)** to your private music bucket.
   - Set **Type of Access** to **Read and Write**.
   - Click **Create New Key**.
   - Copy the generated `keyID` and `applicationKey`. (The secret application key will only be shown once.)

---

## 2. Configuration

Create your local configuration file at `~/.musictool/config.toml` (e.g. `C:\Users\<Username>\.musictool\config.toml` on Windows):

```toml
key_id = "your-b2-key-id"
application_key = "your-b2-application-key"
bucket = "your-bucket-name"
```

> **Note:** The configuration file stays on your local machine outside the repository and must never be committed to source control.
> You can also specify a custom configuration file path by setting the `MUSICTOOL_CONFIG` environment variable:
>
> ```powershell
> $env:MUSICTOOL_CONFIG = "C:\path\to\custom-config.toml"
> ```

---

## 3. Installation

### Prerequisites
- **Python 3.12+** (tested with Python 3.15).
- **ffmpeg** and **ffprobe** installed and available on your system `PATH`. Verify with:
  ```bash
  ffmpeg -version
  ffprobe -version
  ```

### Install Package
Install `musictool` in editable mode into your virtual environment:

```bash
pip install -e tools/musictool
```

Or with development and test dependencies:

```bash
pip install -e "tools/musictool[dev]"
```

After installation, the `musictool` command is available in your shell.

---

## 4. Commands and Usage

### `add`
Add audio files or entire directories recursively. Probes tags, transcodes lossless audio to Opus (or preserves FLAC if requested), extracts cover art scaled to 500 px JPEG, uploads songs, and updates `catalog.json`.

```bash
musictool add "D:\Music\Albums\Artist - Album"
```

To preserve lossless sources as FLAC instead of converting to Opus:

```bash
musictool add --lossless "D:\Music\Purchases\favorite_track.flac"
```

*Summary output reports songs added, duplicates already present, skipped non-audio/corrupt files with reasons, songs with missing tags, space-refused songs, and any failed uploads.*

---

### `list`
List songs in the catalog formatted as `id  artist - title  (album)  [format]`, sorted by artist, album, disc, and track. An optional search query filters by exact ID, or matches artist, title, or album case-insensitively.

List all songs:
```bash
musictool list
```

Filter by query:
```bash
musictool list "Coldplay"
```

---

### `remove`
Remove songs from the catalog and delete their audio files from B2. Cover art is cleaned up if no remaining song in the catalog uses it.

- If one song matches the query, it is removed immediately.
- If multiple songs match, the tool lists the matching songs and does not delete anything unless `--all` is specified.
- If no songs match, exits with code 1.

Remove by unique song ID:
```bash
musictool remove 9f2c1a7b3d4e5f60
```

Remove all songs matching a search query:
```bash
musictool remove "Old Album" --all
```

---

### `status`
Displays library statistics including total song count, space used out of the 9.5 GB safety cap (in GB and percentage), count and space taken by lossless FLAC songs, and the catalog version.

```bash
musictool status
```

Example output:
```text
142 songs
Space: 1.15 GB / 9.5 GB (12.1%)
FLAC: 8 songs, 0.24 GB (241.5 MB)
Version: 15
```

---

## 5. Quality Rule and Space Economics

### The Quality Rule
- **Lossless sources (FLAC, WAV, ALAC, WavPack, APE):** Transcoded to **Ogg Opus at 256 kbps**. At 256 kbps, Opus is well past the bitrate at which listeners can reliably tell it from lossless, while averaging only ~7 MB per song.
- **Already-lossy files (MP3, AAC/M4A, Ogg Vorbis, Opus):** Uploaded **untouched**. Transcoding lossy audio results in generational degradation without saving significant space.
- **`--lossless` flag:** Preserves lossless files as FLAC (converting uncompressed WAV or ALAC to FLAC losslessly).

### Space Economics
Backblaze B2 provides a 10 GB free storage tier. `musictool` enforces a 9.5 GB catalog limit (`9_500_000_000` bytes) to prevent accidental overages.

- **Opus tracks (256 kbps):** Average ~7 MB per track. A 9.5 GB bucket can comfortably hold roughly **1,300 to 1,400 songs**.
- **FLAC tracks:** Average ~25 to 30 MB per track (about 4x the size of Opus). Storing pure FLAC would reduce library capacity to approximately **300 to 350 songs**.

Use `--lossless` selectively for priority tracks where exact bit-perfection is desired, and keep the rest as 256 kbps Opus to maximize library size within the free tier.
