import subprocess
import tempfile
from pathlib import Path

import pytest

from musictool.catalog import Catalog, Song
from musictool.storage import Storage, StorageError


@pytest.fixture(scope="session")
def audio(tmp_path_factory):
    """Generate one-second test audio files via ffmpeg and return a dict of paths."""
    d = tmp_path_factory.mktemp("audio")

    def _run(args):
        subprocess.run(args, check=True, capture_output=True)

    # --- tagged_flac: full tags, embedded 800x800 red cover ---
    cover_raw = d / "cover_raw.png"
    _run([
        "ffmpeg", "-y", "-f", "lavfi", "-i", "color=c=red:s=800x800:d=1",
        "-frames:v", "1", str(cover_raw),
    ])
    cover_jpg = d / "cover.jpg"
    _run([
        "ffmpeg", "-y", "-i", str(cover_raw),
        str(cover_jpg),
    ])
    tagged_flac = d / "tagged.flac"
    _run([
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-i", str(cover_jpg),
        "-map", "0:a", "-map", "1:v",
        "-c:a", "flac",
        "-c:v", "mjpeg",
        "-disposition:v", "attached_pic",
        "-metadata", "title=Song A",
        "-metadata", "artist=Artist",
        "-metadata", "album=Album",
        "-metadata", "album_artist=AA",
        "-metadata", "track=3/12",
        "-metadata", "disc=1/2",
        "-metadata", "date=2019-05-01",
        str(tagged_flac),
    ])

    # --- untagged_wav: named "my track.wav", no tags ---
    untagged_wav = d / "my track.wav"
    _run([
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        str(untagged_wav),
    ])

    # --- mp3 ---
    mp3 = d / "song.mp3"
    _run([
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-c:a", "libmp3lame", "-b:a", "192k",
        str(mp3),
    ])

    # --- m4a (AAC) ---
    m4a = d / "song.m4a"
    _run([
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-c:a", "aac", "-b:a", "192k",
        str(m4a),
    ])

    # --- hindi_flac: non-Latin title ---
    hindi_flac = d / "तुम ही हो.flac"
    _run([
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-c:a", "flac",
        "-metadata", "title=तुम ही हो",
        str(hindi_flac),
    ])

    # --- truncated_flac: first 200 bytes of tagged_flac ---
    truncated_flac = d / "truncated.flac"
    truncated_flac.write_bytes(tagged_flac.read_bytes()[:200])

    # --- not_audio: a plain text file ---
    not_audio = d / "notes.txt"
    not_audio.write_text("just some notes", encoding="utf-8")

    # --- video: an mp4 with a real video track and AAC audio ---
    video = d / "clip.mp4"
    _run([
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "testsrc=duration=1:size=320x240:rate=10",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-c:a", "aac", "-shortest",
        str(video),
    ])

    # --- blank_tags_mp3: artist tag present but only spaces ---
    blank_tags_mp3 = d / "blank tags.mp3"
    _run([
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-c:a", "libmp3lame",
        "-metadata", "title=Has Title",
        "-metadata", "artist=   ",
        str(blank_tags_mp3),
    ])

    return {
        "video": video,
        "blank_tags_mp3": blank_tags_mp3,
        "tagged_flac": tagged_flac,
        "untagged_wav": untagged_wav,
        "mp3": mp3,
        "m4a": m4a,
        "hindi_flac": hindi_flac,
        "truncated_flac": truncated_flac,
        "not_audio": not_audio,
    }


def make_song(**overrides) -> Song:
    """Return a valid Song with sensible defaults, overridden by keyword args."""
    defaults = dict(
        id="0" * 16,
        title="Test Song",
        artist="Test Artist",
        album="Test Album",
        albumArtist="Test Artist",
        track=1,
        disc=1,
        year=2024,
        durationMs=210000,
        sizeBytes=5_000_000,
        format="opus",
        file="songs/0000000000000000.opus",
        cover=None,
    )
    defaults.update(overrides)
    return Song(**defaults)


class FakeStorage:
    def __init__(self) -> None:
        self.files: dict[str, bytes] = {}
        self.puts: list[str] = []
        self.fail_on: set[str] = set()

    def read(self, name: str) -> bytes | None:
        return self.files.get(name)

    def size(self, name: str) -> int | None:
        if name in self.files:
            return len(self.files[name])
        return None

    def put_bytes(self, name: str, data: bytes, content_type: str) -> None:
        if name in self.fail_on:
            raise StorageError(f"Simulated storage failure for {name}")
        self.files[name] = bytes(data)
        self.puts.append(name)

    def put_file(self, name: str, path: Path, content_type: str) -> None:
        if name in self.fail_on:
            raise StorageError(f"Simulated storage failure for {name}")
        self.files[name] = Path(path).read_bytes()
        self.puts.append(name)

    def delete(self, name: str) -> None:
        self.files.pop(name, None)


@pytest.fixture
def fake() -> FakeStorage:
    return FakeStorage()


def catalog_of(storage: Storage) -> Catalog:
    """Read and deserialize the catalog from storage, or return an empty Catalog."""
    raw = storage.read("catalog.json")
    if raw is None:
        return Catalog()
    return Catalog.from_json(raw.decode("utf-8"))


def only_song(storage: Storage) -> Song:
    """Read the catalog from storage and assert exactly one song is present."""
    raw = storage.read("catalog.json")
    assert raw is not None, "catalog.json not found in storage"
    cat = Catalog.from_json(raw.decode("utf-8"))
    assert len(cat.songs) == 1, f"Expected 1 song, found {len(cat.songs)}"
    return cat.songs[0]


@pytest.fixture
def stocked() -> FakeStorage:
    """FakeStorage pre-populated with three songs by 'Artist'."""
    s1 = make_song(
        id="1" * 16,
        title="Song 1",
        artist="Artist",
        album="Album 1",
        albumArtist="Artist",
        track=1,
        disc=1,
        year=2024,
        sizeBytes=5_000_000,
        format="opus",
        file=f"songs/{'1' * 16}.opus",
        cover="covers/cov1.jpg",
    )
    s2 = make_song(
        id="2" * 16,
        title="Song 2",
        artist="Artist",
        album="Album 2",
        albumArtist="Artist",
        track=2,
        disc=1,
        year=2024,
        sizeBytes=5_000_000,
        format="opus",
        file=f"songs/{'2' * 16}.opus",
        cover="covers/cov2.jpg",
    )
    s3 = make_song(
        id="3" * 16,
        title="Song 3",
        artist="Artist",
        album="Album 3",
        albumArtist="Artist",
        track=3,
        disc=1,
        year=2024,
        sizeBytes=5_000_000,
        format="opus",
        file=f"songs/{'3' * 16}.opus",
        cover=None,
    )
    cat = Catalog(version=1, updatedAt="2026-11-15T12:00:00Z", songs=[s1, s2, s3])
    store = FakeStorage()
    store.files["catalog.json"] = cat.to_json().encode("utf-8")
    store.files[s1.file] = b"audio1"
    store.files[s2.file] = b"audio2"
    store.files[s3.file] = b"audio3"
    store.files["covers/cov1.jpg"] = b"cover1"
    store.files["covers/cov2.jpg"] = b"cover2"
    return store


@pytest.fixture
def stocked_same_album() -> FakeStorage:
    """FakeStorage pre-populated with two songs sharing an album and cover."""
    s1 = make_song(
        id="a" * 16,
        title="Track A",
        artist="Artist",
        album="Shared Album",
        albumArtist="Artist",
        track=1,
        disc=1,
        year=2024,
        sizeBytes=5_000_000,
        format="opus",
        file=f"songs/{'a' * 16}.opus",
        cover="covers/shared.jpg",
    )
    s2 = make_song(
        id="b" * 16,
        title="Track B",
        artist="Artist",
        album="Shared Album",
        albumArtist="Artist",
        track=2,
        disc=1,
        year=2024,
        sizeBytes=5_000_000,
        format="opus",
        file=f"songs/{'b' * 16}.opus",
        cover="covers/shared.jpg",
    )
    cat = Catalog(version=1, updatedAt="2026-11-15T12:00:00Z", songs=[s1, s2])
    store = FakeStorage()
    store.files["catalog.json"] = cat.to_json().encode("utf-8")
    store.files[s1.file] = b"audio_a"
    store.files[s2.file] = b"audio_b"
    store.files["covers/shared.jpg"] = b"shared_cover"
    return store


@pytest.fixture
def catalog3(stocked) -> Catalog:
    """Catalog of the stocked fixture."""
    return catalog_of(stocked)


