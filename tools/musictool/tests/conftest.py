import subprocess
import tempfile
from pathlib import Path

import pytest

from musictool.catalog import Song


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

    return {
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
