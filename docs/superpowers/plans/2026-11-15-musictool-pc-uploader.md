# musictool (PC uploader) Implementation Plan

> **Superseded.** This document describes an earlier design that synced music through a Backblaze B2 bucket. That was removed: songs now go from the PC to the phone over USB (`musictool` + adb). See `android/README.md` and `tools/musictool/README.md` for how it works today.


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Windows command-line tool that takes a folder of audio files, prepares them, uploads them to a private Backblaze B2 bucket and maintains `catalog.json`, which the Android app will later sync from.

**Architecture:** A small Python package. Pure logic (ids, catalog, encoding decisions) is separated from the two things that touch the outside world: ffmpeg/ffprobe (`media.py`) and B2 (`storage.py`). Ingest and removal are written against a `Storage` protocol so tests run against an in-memory fake.

**Tech Stack:** Python 3.15, ffmpeg and ffprobe 9 (already on PATH), `b2sdk`, `pytest`. Tags, duration and cover art all come from ffprobe/ffmpeg, so there is no tagging or imaging library.

**Spec:** `docs/superpowers/specs/2026-11-15-personal-music-app-design.md`

This is plan 1 of 2. The Android app gets its own plan once this tool has put real songs in the bucket.

## Global Constraints

- Bucket layout: `songs/<id>.<ext>`, `covers/<albumId>.jpg`, `catalog.json`.
- Song `id`: first 16 hex characters of the SHA-1 of the original file's bytes.
- Quality rule: lossless sources are converted to Ogg Opus at 256 kbps; already-lossy files are uploaded untouched; `--lossless` keeps lossless sources as FLAC.
- Covers: JPEG, 500 px wide.
- An `add` that would take the catalog's total past 9.5 GB (`9_500_000_000` bytes) is refused for that song.
- `catalog.json` is uploaded last on `add` and first on `remove`, so the phone never sees a song whose file is missing.
- `version` increases by exactly 1 per command that changes the catalog.
- B2 keys live in `~/.musictool/config.toml`, outside the repository. Nothing secret is committed.
- Commit messages carry no `Co-Authored-By` trailer.
- The folder is not yet a git repository; Task 1 runs `git init`.

## Review Focus

1. A folder containing non-audio or corrupt files (`.jpg`, `.cue`, `.txt`, a truncated `.flac`): each is skipped with one line naming it, and the rest are still added. (Task 4)
2. File names and tags in non-Latin script (for example Devanagari) on a Windows console: nothing crashes while printing, and the tags arrive intact in the catalog. (Tasks 2 and 5)
3. Network failure part-way through an `add`: songs already uploaded are recorded in the catalog, the command exits non-zero, and rerunning finishes the rest without re-uploading. (Task 4)
4. Missing config file, or ffmpeg not on PATH: a one-line message saying what to do, not a traceback. (Task 5)
5. Untidy tags such as track `3/12`, disc `1/2`, date `2019-05-01`, or no tags at all: parsed to `3`, `1`, `2019`, and the file-name fallback respectively. (Task 2)

## File Structure

```
.gitignore
tools/musictool/
  pyproject.toml            package metadata, `musictool` console script
  README.md                 setup and usage
  src/musictool/
    __init__.py
    ids.py                  song and album ids
    catalog.py              Song, Catalog, JSON read/write
    media.py                ffprobe/ffmpeg: probe, encoding decision, transcode, cover
    storage.py              Storage protocol, B2Storage
    ingest.py               add songs
    manage.py               remove, list, status
    config.py               load ~/.musictool/config.toml
    cli.py                  argument parsing and printing
  tests/
    conftest.py             ffmpeg-generated fixture audio, FakeStorage
    test_ids.py  test_catalog.py  test_media.py  test_storage.py
    test_ingest.py  test_manage.py  test_cli.py
```

---

### Task 1: Project skeleton, ids and catalog

**Files:**
- Create: `.gitignore`, `tools/musictool/pyproject.toml`, `tools/musictool/src/musictool/__init__.py`, `ids.py`, `catalog.py`
- Test: `tools/musictool/tests/test_ids.py`, `tests/test_catalog.py`

**Interfaces:**
- Produces:
  - `ids.song_id(path: Path) -> str`
  - `ids.album_id(album_artist: str, album: str) -> str`: first 16 hex of SHA-1 of `f"{album_artist.casefold()}\0{album.casefold()}"` encoded as UTF-8
  - `catalog.Song`: frozen dataclass with fields `id, title, artist, album, albumArtist, track, disc, year, durationMs, sizeBytes, format, file, cover`; `track`, `disc`, `year` are `int | None`; `cover` is `str | None`; `format` is one of `"opus" "flac" "mp3" "aac" "vorbis"`
  - `catalog.Catalog`: dataclass with `version: int = 0`, `updatedAt: str = ""`, `songs: list[Song]`
  - `Catalog.from_json(text: str) -> Catalog`, `Catalog.to_json() -> str`
  - `Catalog.has(song_id: str) -> bool`, `Catalog.add(song: Song) -> None`, `Catalog.remove(song_id: str) -> Song`, `Catalog.total_bytes() -> int`
  - `Catalog.bump() -> None`: `version += 1` and `updatedAt` set to the current UTC time as `YYYY-MM-DDTHH:MM:SSZ`
  - `catalog.CATALOG_NAME = "catalog.json"`, `catalog.LIMIT_BYTES = 9_500_000_000`

- [ ] **Step 1: Set up the repository and package**

Run `git init` in the project root. `.gitignore` covers `__pycache__/`, `.venv/`, `*.egg-info/`, `.pytest_cache/`, `config.toml`. `pyproject.toml` declares package `musictool` under `src/`, requires Python `>=3.12`, depends on `b2sdk`, has a `dev` extra with `pytest`, and defines the console script `musictool = "musictool.cli:main"`.

Run: `python -m venv tools/musictool/.venv` then `tools/musictool/.venv/Scripts/python -m pip install -e "tools/musictool[dev]"`
Expected: install succeeds. If `b2sdk` has no build for Python 3.15, record that and continue; Task 3 has the fallback.

- [ ] **Step 2: Write the failing tests**

```python
# test_ids.py
def test_song_id_is_first_16_hex_of_sha1(tmp_path):
    f = tmp_path / "a.bin"; f.write_bytes(b"hello")
    assert song_id(f) == "aaf4c61ddcc5e8a2"

def test_album_id_ignores_case_and_is_16_hex():
    assert album_id("Artist", "Album") == album_id("ARTIST", "album")
    assert len(album_id("Artist", "Album")) == 16
    assert album_id("Artist", "Album") != album_id("Artist", "Other")
```

```python
# test_catalog.py
def test_empty_catalog_round_trips():
    c = Catalog.from_json(Catalog().to_json())
    assert c.version == 0 and c.songs == []

def test_json_uses_spec_field_names():
    c = Catalog(); c.add(make_song(id="9f2c1a7b3d4e5f60", sizeBytes=7_000_000))
    data = json.loads(c.to_json())
    assert set(data) == {"version", "updatedAt", "songs"}
    assert set(data["songs"][0]) == {"id","title","artist","album","albumArtist","track",
        "disc","year","durationMs","sizeBytes","format","file","cover"}

def test_non_ascii_text_survives_round_trip():
    c = Catalog(); c.add(make_song(title="तुम ही हो"))
    assert Catalog.from_json(c.to_json()).songs[0].title == "तुम ही हो"

def test_bump_increments_version_by_one_and_stamps_utc():
    c = Catalog(version=41); c.bump()
    assert c.version == 42
    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", c.updatedAt)

def test_add_has_remove_and_total_bytes():
    c = Catalog(); c.add(make_song(id="a"*16, sizeBytes=5)); c.add(make_song(id="b"*16, sizeBytes=7))
    assert c.has("a"*16) and c.total_bytes() == 12
    assert c.remove("a"*16).id == "a"*16 and not c.has("a"*16)

def test_remove_unknown_id_raises_keyerror():
    with pytest.raises(KeyError): Catalog().remove("nope")
```

`make_song(**overrides)` is a helper in `conftest.py` returning a valid `Song`.

- [ ] **Step 3: Run tests to verify they fail**

Run (from `tools/musictool`): `.venv/Scripts/python -m pytest tests/test_ids.py tests/test_catalog.py -v`
Expected: FAIL on import.

- [ ] **Step 4: Implement `ids.py` and `catalog.py`** to the signatures above. `song_id` reads the file in chunks. `to_json` writes UTF-8 text with `ensure_ascii=False` and 2-space indent.

- [ ] **Step 5: Run tests to verify they pass**

Run: same command. Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add .gitignore tools/musictool docs
git commit -m "feat(musictool): package skeleton, ids and catalog"
```

---

### Task 2: Reading and preparing audio

**Files:**
- Create: `tools/musictool/src/musictool/media.py`
- Modify: `tools/musictool/tests/conftest.py` (fixture audio)
- Test: `tools/musictool/tests/test_media.py`

**Interfaces:**
- Produces:
  - `media.Probe`: frozen dataclass `codec: str, duration_ms: int, title: str, artist: str, album: str, album_artist: str, track: int | None, disc: int | None, year: int | None, has_cover: bool, tags_missing: bool`
  - `media.probe(path: Path) -> Probe`; raises `media.NotAudio` if ffprobe finds no audio stream or cannot read the file
  - `media.Plan`: frozen dataclass `action: Literal["copy", "opus", "flac"], format: str, ext: str`
  - `media.plan_encoding(codec: str, source_ext: str, keep_lossless: bool) -> Plan`
  - `media.prepare(path: Path, plan: Plan, workdir: Path) -> Path`: returns the file to upload (`path` itself for `copy`)
  - `media.extract_cover(path: Path, workdir: Path) -> Path | None`: 500 px wide JPEG, or `None`
  - `media.require_ffmpeg() -> None`; raises `media.FfmpegMissing` if `ffmpeg` or `ffprobe` is not on PATH
  - `media.content_type(ext: str) -> str`: `.opus` `audio/ogg`, `.flac` `audio/flac`, `.mp3` `audio/mpeg`, `.m4a` `audio/mp4`, `.ogg` `audio/ogg`, `.jpg` `image/jpeg`

Decision table for `plan_encoding`:

| Source codec | `keep_lossless=False` | `keep_lossless=True` |
|---|---|---|
| `flac` | `opus`, format `opus`, ext `.opus` | `copy`, format `flac`, ext `.flac` |
| `alac`, any `pcm_*`, `wavpack`, `ape` | `opus`, `opus`, `.opus` | `flac`, `flac`, `.flac` |
| `mp3` | `copy`, `mp3`, `.mp3` | same |
| `aac` | `copy`, `aac`, `.m4a` | same |
| `opus` | `copy`, `opus`, source ext | same |
| `vorbis` | `copy`, `vorbis`, `.ogg` | same |
| anything else | raises `media.NotAudio` | same |

Tag rules for `probe`: tag keys are matched case-insensitively and looked for in both format-level and stream-level tags (Ogg files keep them on the stream). `title` falls back to the file name without extension; `artist` and `album` fall back to `"Unknown"`; `album_artist` falls back to `artist`. `tags_missing` is true when title or artist was a fallback. `track` and `disc` take the number before any `/`. `year` is the first four digits of `date` or `year`. Unparseable numbers become `None`.

- [ ] **Step 1: Add fixtures to `conftest.py`**

A session-scoped `audio` fixture that uses ffmpeg to generate one-second files from `sine=frequency=440:duration=1` into a temp directory and returns a dict of paths:
`tagged_flac` (title `Song A`, artist `Artist`, album `Album`, album_artist `AA`, track `3/12`, disc `1/2`, date `2019-05-01`, with an embedded 800x800 cover made from `color=c=red:s=800x800`), `untagged_wav` (named `my track.wav`), `mp3`, `m4a`, `hindi_flac` (file name `तुम ही हो.flac`, title `तुम ही हो`), `truncated_flac` (first 200 bytes of `tagged_flac`), `not_audio` (`notes.txt`).

- [ ] **Step 2: Write the failing tests**

```python
def test_probe_reads_tags_and_tidies_numbers(audio):
    p = probe(audio["tagged_flac"])
    assert (p.codec, p.title, p.artist, p.album, p.album_artist) == ("flac","Song A","Artist","Album","AA")
    assert (p.track, p.disc, p.year) == (3, 1, 2019)
    assert 900 <= p.duration_ms <= 1100 and p.has_cover and not p.tags_missing

def test_probe_falls_back_when_untagged(audio):
    p = probe(audio["untagged_wav"])
    assert (p.title, p.artist, p.album, p.album_artist) == ("my track","Unknown","Unknown","Unknown")
    assert p.tags_missing and (p.track, p.disc, p.year) == (None, None, None)

def test_probe_keeps_non_latin_names(audio):
    assert probe(audio["hindi_flac"]).title == "तुम ही हो"

@pytest.mark.parametrize("key", ["not_audio", "truncated_flac"])
def test_probe_rejects_non_audio(audio, key):
    with pytest.raises(NotAudio): probe(audio[key])

@pytest.mark.parametrize("codec,ext,keep,expected", [
    ("flac", ".flac", False, ("opus","opus",".opus")),
    ("flac", ".flac", True,  ("copy","flac",".flac")),
    ("pcm_s16le", ".wav", False, ("opus","opus",".opus")),
    ("pcm_s16le", ".wav", True,  ("flac","flac",".flac")),
    ("alac", ".m4a", True, ("flac","flac",".flac")),
    ("mp3", ".mp3", False, ("copy","mp3",".mp3")),
    ("mp3", ".mp3", True,  ("copy","mp3",".mp3")),
    ("aac", ".m4a", False, ("copy","aac",".m4a")),
    ("opus", ".opus", False, ("copy","opus",".opus")),
    ("vorbis", ".ogg", False, ("copy","vorbis",".ogg")),
])
def test_plan_encoding(codec, ext, keep, expected):
    p = plan_encoding(codec, ext, keep)
    assert (p.action, p.format, p.ext) == expected

def test_plan_encoding_rejects_unknown_codec():
    with pytest.raises(NotAudio): plan_encoding("mjpeg", ".jpg", False)

def test_prepare_opus_makes_256k_opus_with_tags(audio, tmp_path):
    out = prepare(audio["tagged_flac"], Plan("opus","opus",".opus"), tmp_path)
    p = probe(out)
    assert out.suffix == ".opus" and p.codec == "opus" and p.title == "Song A"

def test_prepare_copy_returns_the_source_untouched(audio, tmp_path):
    assert prepare(audio["mp3"], Plan("copy","mp3",".mp3"), tmp_path) == audio["mp3"]

def test_prepare_flac_packs_wav_losslessly(audio, tmp_path):
    out = prepare(audio["untagged_wav"], Plan("flac","flac",".flac"), tmp_path)
    assert probe(out).codec == "flac"

def test_extract_cover_is_500px_jpeg(audio, tmp_path):
    cover = extract_cover(audio["tagged_flac"], tmp_path)
    assert cover.suffix == ".jpg" and image_width(cover) == 500

def test_extract_cover_none_when_absent(audio, tmp_path):
    assert extract_cover(audio["mp3"], tmp_path) is None
```

`image_width` is a test helper that asks ffprobe for the image's width.

- [ ] **Step 3: Run tests to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_media.py -v`. Expected: FAIL on import.

- [ ] **Step 4: Implement `media.py`**

All subprocess calls pass arguments as a list (no shell), capture output as bytes and decode as UTF-8, so non-Latin paths and tags work on Windows. `probe` uses `ffprobe -v error -show_format -show_streams -of json`; a stream with `disposition.attached_pic == 1` means `has_cover`. Opus: `-vn -c:a libopus -b:a 256k -map_metadata 0`. FLAC: `-vn -c:a flac -map_metadata 0`. Cover: `-an -frames:v 1 -vf scale=500:-2`. A non-zero exit from ffprobe or ffmpeg raises `NotAudio`.

- [ ] **Step 5: Run tests to verify they pass**

Run: same command. Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add tools/musictool
git commit -m "feat(musictool): probe, encoding rule, transcode and cover extraction"
```

---

### Task 3: Storage

**Files:**
- Create: `tools/musictool/src/musictool/storage.py`
- Modify: `tools/musictool/tests/conftest.py` (`FakeStorage`)
- Test: `tools/musictool/tests/test_storage.py`

**Interfaces:**
- Produces:
  - `storage.Storage` (a `Protocol`):
    - `read(name: str) -> bytes | None`: `None` when the file does not exist
    - `size(name: str) -> int | None`: `None` when the file does not exist
    - `put_bytes(name: str, data: bytes, content_type: str) -> None`
    - `put_file(name: str, path: Path, content_type: str) -> None`
    - `delete(name: str) -> None`: removes every stored version; no error if absent
  - `storage.StorageError(Exception)`: raised for any network or service failure
  - `storage.B2Storage(key_id: str, application_key: str, bucket: str)` implementing `Storage`
  - `conftest.FakeStorage`: in-memory `Storage` with `files: dict[str, bytes]`, `puts: list[str]` (names in upload order) and `fail_on: set[str]` (a `put_*` to a listed name raises `StorageError`)

- [ ] **Step 1: Write the failing tests**

Tests run one shared contract against both implementations, using a parametrized `store` fixture: `FakeStorage()` and a `B2Storage` built on b2sdk's in-memory simulator (`b2sdk.v2.RawSimulator` with `StubAccountInfo`) through an internal `B2Storage._from_api(api, bucket)` constructor.

```python
def test_read_and_size_of_missing_file_are_none(store):
    assert store.read("x") is None and store.size("x") is None

def test_put_bytes_then_read_and_size(store):
    store.put_bytes("catalog.json", b"{}", "application/json")
    assert store.read("catalog.json") == b"{}" and store.size("catalog.json") == 2

def test_put_file(store, tmp_path):
    f = tmp_path / "s.opus"; f.write_bytes(b"abc")
    store.put_file("songs/a.opus", f, "audio/ogg")
    assert store.size("songs/a.opus") == 3

def test_delete_removes_every_version(store):
    store.put_bytes("k", b"1", "text/plain"); store.put_bytes("k", b"22", "text/plain")
    store.delete("k")
    assert store.read("k") is None

def test_delete_missing_is_silent(store):
    store.delete("never-existed")
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_storage.py -v`. Expected: FAIL on import.

- [ ] **Step 3: Implement `FakeStorage` and `B2Storage`**

`B2Storage` wraps every b2sdk exception in `StorageError`. `delete` lists all versions of the exact name and deletes each. If `b2sdk` could not be installed in Task 1, implement `B2Storage` against B2's native HTTP API with `urllib` instead (`b2_authorize_account`, `b2_get_upload_url`, `b2_upload_file`, `b2_list_file_versions`, `b2_delete_file_version`, download by name), and run the contract only against `FakeStorage` plus the real-bucket check in Task 5.

- [ ] **Step 4: Run tests to verify they pass**

Run: same command. Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/musictool
git commit -m "feat(musictool): storage protocol with B2 and in-memory implementations"
```

---

### Task 4: Adding songs

**Files:**
- Create: `tools/musictool/src/musictool/ingest.py`
- Test: `tools/musictool/tests/test_ingest.py`

**Interfaces:**
- Consumes: everything in Tasks 1 to 3.
- Produces:
  - `ingest.AddReport`: dataclass `added: list[Song]`, `already_present: list[Path]`, `skipped: list[tuple[Path, str]]` (path and reason), `failed: list[tuple[Path, str]]`, `missing_tags: list[Path]`, `over_limit: list[Path]`
  - `ingest.add(paths: list[Path], storage: Storage, keep_lossless: bool = False, on_progress: Callable[[str], None] = lambda _: None) -> AddReport`

Behaviour of `add`, in order:
1. Expand each path: a file is taken as is; a folder is walked recursively. Files are processed in sorted order.
2. Load the catalog from storage (`read(CATALOG_NAME)`; absent means an empty catalog).
3. For each file: compute the id; if the catalog has it, record `already_present`. Otherwise probe (a `NotAudio` goes to `skipped` with the reason and the loop continues) and plan the encoding.
4. The target name is `songs/<id><ext>`. If `storage.size(target)` is not `None`, an earlier interrupted run already uploaded it: reuse that size and do not upload again. Otherwise prepare the file in a temporary directory and, if `catalog.total_bytes() + size > LIMIT_BYTES`, record `over_limit` and move on without uploading; else upload it.
5. If the source has a cover and `covers/<albumId>.jpg` is not in storage, extract and upload it. The song's `cover` is that name when the cover exists in storage, else `None`.
6. Add the `Song` to the catalog and to `added`. A `StorageError` on any upload puts the file in `failed` and stops the loop.
7. In a `finally` block, so it also runs after a failure or Ctrl+C: if any song was added, `bump()` the catalog once and upload it.

- [ ] **Step 1: Write the failing tests**

```python
def test_add_folder_uploads_songs_then_catalog_last(audio_dir, fake):
    report = add([audio_dir], fake)
    assert fake.puts[-1] == "catalog.json"
    cat = Catalog.from_json(fake.files["catalog.json"].decode())
    assert cat.version == 1 and len(cat.songs) == len(report.added)
    for s in cat.songs:
        assert s.file in fake.files and s.sizeBytes == len(fake.files[s.file])

def test_lossless_becomes_opus_and_lossy_is_untouched(audio, fake):
    add([audio["tagged_flac"], audio["mp3"]], fake)
    by_title = songs_by_format(fake)
    assert by_title["opus"].file.endswith(".opus")
    assert fake.files[by_title["mp3"].file] == audio["mp3"].read_bytes()

def test_keep_lossless_stores_flac(audio, fake):
    report = add([audio["tagged_flac"]], fake, keep_lossless=True)
    assert report.added[0].format == "flac" and report.added[0].file.endswith(".flac")

def test_cover_uploaded_once_per_album(audio, fake):
    add([audio["tagged_flac"]], fake)
    song = only_song(fake)
    assert song.cover == f"covers/{album_id('AA', 'Album')}.jpg" and song.cover in fake.files

def test_song_without_cover_has_null_cover(audio, fake):
    add([audio["mp3"]], fake); assert only_song(fake).cover is None

def test_adding_twice_changes_nothing(audio, fake):
    add([audio["tagged_flac"]], fake); puts = list(fake.puts)
    report = add([audio["tagged_flac"]], fake)
    assert report.added == [] and report.already_present == [audio["tagged_flac"]]
    assert fake.puts == puts                       # no catalog rewrite, version stays 1

def test_non_audio_and_corrupt_files_are_skipped_and_the_rest_added(audio, fake):
    report = add([audio["not_audio"], audio["truncated_flac"], audio["mp3"]], fake)
    assert {p for p, _ in report.skipped} == {audio["not_audio"], audio["truncated_flac"]}
    assert len(report.added) == 1

def test_untagged_file_is_added_and_reported(audio, fake):
    report = add([audio["untagged_wav"]], fake)
    assert report.missing_tags == [audio["untagged_wav"]] and report.added[0].title == "my track"

def test_non_latin_file_is_added(audio, fake):
    assert add([audio["hindi_flac"]], fake).added[0].title == "तुम ही हो"

def test_song_over_the_limit_is_refused_not_uploaded(audio, fake, monkeypatch):
    monkeypatch.setattr("musictool.ingest.LIMIT_BYTES", 10)
    report = add([audio["mp3"]], fake)
    assert report.over_limit == [audio["mp3"]] and fake.puts == []

def test_upload_failure_keeps_finished_songs_and_rerun_completes(audio, fake):
    first, second = sorted([audio["mp3"], audio["m4a"]])
    fake.fail_on = {f"songs/{song_id(second)}{second.suffix}"}
    report = add([first, second], fake)
    assert len(report.added) == 1 and report.failed[0][0] == second
    assert Catalog.from_json(fake.files["catalog.json"].decode()).version == 1
    fake.fail_on = set(); uploads_before = list(fake.puts)
    report = add([first, second], fake)
    assert len(report.added) == 1 and report.already_present == [first]
    assert f"songs/{song_id(first)}{first.suffix}" not in fake.puts[len(uploads_before):]

def test_orphan_upload_from_interrupted_run_is_reused(audio, fake):
    name = f"songs/{song_id(audio['mp3'])}.mp3"
    fake.files[name] = audio["mp3"].read_bytes()
    add([audio["mp3"]], fake)
    assert name not in fake.puts and only_song(fake).sizeBytes == len(fake.files[name])
```

`fake` is a fresh `FakeStorage`; `audio_dir` is a folder holding copies of the valid fixtures; `only_song` and `songs_by_format` are small test helpers that read the catalog back from the fake.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_ingest.py -v`. Expected: FAIL on import.

- [ ] **Step 3: Implement `ingest.add`** following the numbered behaviour above.

- [ ] **Step 4: Run tests to verify they pass**

Run: same command. Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/musictool
git commit -m "feat(musictool): add songs with resume, limit and skip handling"
```

---

### Task 5: Remove, list, status and the command line

**Files:**
- Create: `tools/musictool/src/musictool/manage.py`, `config.py`, `cli.py`, `tools/musictool/README.md`
- Test: `tools/musictool/tests/test_manage.py`, `tests/test_cli.py`

**Interfaces:**
- Consumes: Tasks 1 to 4.
- Produces:
  - `manage.find(catalog: Catalog, query: str) -> list[Song]`: a song whose `id` equals the query exactly, otherwise every song whose title, artist or album contains the query, ignoring case
  - `manage.remove(song_ids: list[str], storage: Storage) -> list[Song]`
  - `manage.Status`: dataclass `songs: int, total_bytes: int, limit_bytes: int, lossless_songs: int, lossless_bytes: int, version: int`
  - `manage.status(storage: Storage) -> Status`
  - `config.Config`: dataclass `key_id: str, application_key: str, bucket: str`
  - `config.load(path: Path | None = None) -> Config`: default path `~/.musictool/config.toml`, overridden by the `MUSICTOOL_CONFIG` environment variable; raises `config.ConfigError` with a message naming the path and the three required keys when the file or a key is missing
  - `cli.main(argv: list[str] | None = None, storage: Storage | None = None) -> int`

`remove` order: take the songs out of the catalog, `bump()` once, upload the catalog, then delete each song file, then delete a cover only if no remaining song uses it.

Command line:
- `musictool add <path>... [--lossless]`: prints one line per added song, then a summary: added, already present, skipped (each with its reason), songs with missing tags, songs refused for space, failures. Exit code 1 if anything failed, else 0.
- `musictool remove <query> [--all]`: one match is removed; several matches are listed and nothing is removed unless `--all` is given; no match prints "No song matches" and exits 1.
- `musictool list [query]`: `id  artist - title  (album)  [format]`, sorted by artist, album, disc, track.
- `musictool status`: song count, used space out of 9.5 GB as GB and percent, and how many songs and how much space are FLAC.
- `main` reconfigures stdout and stderr to UTF-8 with `errors="replace"`, calls `media.require_ffmpeg()` for `add`, and turns `ConfigError`, `FfmpegMissing` and `StorageError` into a one-line message on stderr and exit code 2.

- [ ] **Step 1: Write the failing tests**

```python
# test_manage.py
def test_find_by_exact_id_and_by_text(catalog3):
    assert [s.id for s in find(catalog3, catalog3.songs[0].id)] == [catalog3.songs[0].id]
    assert len(find(catalog3, "ARTIST")) == 3 and find(catalog3, "zzz") == []

def test_remove_writes_catalog_before_deleting_files(stocked):
    victim = catalog_of(stocked).songs[0]
    stocked.puts.clear(); remove([victim.id], stocked)
    assert stocked.puts == ["catalog.json"] and victim.file not in stocked.files
    assert catalog_of(stocked).version == 2 and not catalog_of(stocked).has(victim.id)

def test_cover_kept_while_another_song_uses_it(stocked_same_album):
    a, b = catalog_of(stocked_same_album).songs
    remove([a.id], stocked_same_album); assert a.cover in stocked_same_album.files
    remove([b.id], stocked_same_album); assert a.cover not in stocked_same_album.files

def test_status_counts_lossless_separately(fake, audio):
    add([audio["mp3"]], fake); add([audio["tagged_flac"]], fake, keep_lossless=True)
    s = status(fake)
    assert (s.songs, s.lossless_songs, s.limit_bytes, s.version) == (2, 1, 9_500_000_000, 2)
    assert 0 < s.lossless_bytes < s.total_bytes
```

```python
# test_cli.py
def test_add_then_list_and_status(audio, fake, capsys):
    assert main(["add", str(audio["tagged_flac"])], storage=fake) == 0
    main(["list"], storage=fake); out = capsys.readouterr().out
    assert "Artist - Song A" in out and "[opus]" in out
    main(["status"], storage=fake); assert "1 song" in capsys.readouterr().out

def test_add_lossless_flag(audio, fake):
    main(["add", "--lossless", str(audio["tagged_flac"])], storage=fake)
    assert only_song(fake).format == "flac"

def test_add_reports_skipped_and_missing_tags(audio, fake, capsys):
    main(["add", str(audio["not_audio"]), str(audio["untagged_wav"])], storage=fake)
    out = capsys.readouterr().out
    assert "notes.txt" in out and "my track.wav" in out

def test_add_exits_1_when_an_upload_fails(audio, fake):
    fake.fail_on = {f"songs/{song_id(audio['mp3'])}.mp3"}
    assert main(["add", str(audio["mp3"])], storage=fake) == 1

def test_remove_with_several_matches_needs_all(stocked, capsys):
    before = len(catalog_of(stocked).songs)
    assert main(["remove", "artist"], storage=stocked) == 1
    assert len(catalog_of(stocked).songs) == before
    assert main(["remove", "artist", "--all"], storage=stocked) == 0
    assert catalog_of(stocked).songs == []

def test_remove_no_match_exits_1(fake):
    assert main(["remove", "nothing"], storage=fake) == 1

def test_non_latin_title_prints_on_a_cp1252_console(audio, fake, monkeypatch):
    monkeypatch.setattr(sys, "stdout", io.TextIOWrapper(io.BytesIO(), encoding="cp1252"))
    assert main(["add", str(audio["hindi_flac"])], storage=fake) == 0

def test_missing_config_gives_a_message_not_a_traceback(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("MUSICTOOL_CONFIG", str(tmp_path / "none.toml"))
    assert main(["status"]) == 2
    err = capsys.readouterr().err
    assert "none.toml" in err and "key_id" in err and "Traceback" not in err

def test_missing_ffmpeg_gives_a_message(audio, fake, monkeypatch, capsys):
    monkeypatch.setattr("musictool.media.shutil.which", lambda _: None)
    assert main(["add", str(audio["mp3"])], storage=fake) == 2
    assert "ffmpeg" in capsys.readouterr().err
```

`stocked` is a `FakeStorage` with three songs by "Artist" already added; `stocked_same_album` has two songs sharing one album and cover; `catalog3` is the catalog of `stocked`; `catalog_of(store)` reads the catalog back.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_manage.py tests/test_cli.py -v`. Expected: FAIL on import.

- [ ] **Step 3: Implement `manage.py`, `config.py` and `cli.py`** to the interfaces above. `cli.main` builds a `B2Storage` from `config.load()` only when no `storage` is passed in.

- [ ] **Step 4: Run the whole suite**

Run: `.venv/Scripts/python -m pytest -v`. Expected: all PASS.

- [ ] **Step 5: Write `README.md`**

Covers: creating a private bucket and a read-write application key limited to it; the contents of `~/.musictool/config.toml` (`key_id`, `application_key`, `bucket`); installing with `pip install -e tools/musictool`; the four commands with one example each; the quality rule and what `--lossless` costs in space.

- [ ] **Step 6: Check against the real bucket** (needs Nitin's B2 keys in the config file)

Run: `musictool add <a folder with two or three real songs>`, then `musictool status` and `musictool list`.
Expected: the songs are listed, `status` shows their size, and the B2 web console shows `catalog.json`, `songs/` and `covers/` in the bucket. Then run `musictool remove <one id>` and confirm the file is gone from the console.

- [ ] **Step 7: Commit**

```bash
git add tools/musictool
git commit -m "feat(musictool): remove, list, status, CLI and setup guide"
```
