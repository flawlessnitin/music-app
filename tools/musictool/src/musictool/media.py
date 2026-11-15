"""ffprobe/ffmpeg: probe, encoding decision, transcode, cover extraction."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Literal


class NotAudio(Exception):
    """Raised when a file is not a recognised audio format or cannot be read."""


class FfmpegMissing(Exception):
    """Raised when ffmpeg or ffprobe is not on PATH."""


@dataclass(frozen=True)
class Probe:
    codec: str
    duration_ms: int
    title: str
    artist: str
    album: str
    album_artist: str
    track: int | None
    disc: int | None
    year: int | None
    has_cover: bool
    tags_missing: bool


@dataclass(frozen=True)
class Plan:
    action: Literal["copy", "opus", "flac"]
    format: str
    ext: str


# --- Codec classification ---

_LOSSLESS_CODECS = {"flac", "alac", "wavpack", "ape"}
_LOSSY_MAP = {
    "mp3":    ("mp3",    ".mp3"),
    "aac":    ("aac",    ".m4a"),
    "opus":   ("opus",   None),    # ext comes from source
    "vorbis": ("vorbis", ".ogg"),
}


def _is_lossless(codec: str) -> bool:
    return codec == "flac" or codec in _LOSSLESS_CODECS or codec.startswith("pcm_")


# --- Tag helpers ---

def _parse_int_before_slash(value: str | None) -> int | None:
    """Parse an integer before an optional '/' separator, e.g. '3/12' -> 3."""
    if value is None:
        return None
    value = value.strip()
    if "/" in value:
        value = value.split("/")[0].strip()
    try:
        return int(value)
    except (ValueError, TypeError):
        return None


def _parse_year(value: str | None) -> int | None:
    """Extract the first four-digit year from a date string."""
    if value is None:
        return None
    m = re.search(r"\d{4}", value)
    if m:
        return int(m.group())
    return None


def _get_tag(tags: dict[str, str], *keys: str) -> str | None:
    """Look up a tag case-insensitively, trying each key in order."""
    lower_tags = {k.lower(): v for k, v in tags.items()}
    for key in keys:
        val = lower_tags.get(key.lower())
        if val is not None and val.strip():
            return val.strip()
    return None


# --- Public functions ---

def probe(path: Path) -> Probe:
    """Probe an audio file with ffprobe and return structured metadata.

    Raises NotAudio if the file has no audio stream or cannot be read.
    """
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-show_format", "-show_streams",
                "-of", "json",
                str(path),
            ],
            capture_output=True,
            check=True,
        )
    except subprocess.CalledProcessError as exc:
        raise NotAudio(f"ffprobe failed on {path}") from exc

    try:
        data = json.loads(result.stdout.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise NotAudio(f"ffprobe returned invalid output for {path}") from exc

    # Find the audio stream
    audio_stream = None
    has_cover = False
    for stream in data.get("streams", []):
        if stream.get("codec_type") == "audio":
            if audio_stream is None:
                audio_stream = stream
        if stream.get("codec_type") == "video":
            disp = stream.get("disposition", {})
            if disp.get("attached_pic", 0) == 1:
                has_cover = True
            else:
                # A real video track: a music video, not a song file.
                raise NotAudio(f"{path} is a video file")

    if audio_stream is None:
        raise NotAudio(f"No audio stream found in {path}")

    codec = audio_stream.get("codec_name", "")

    # Duration: try stream first, then format
    duration_s = audio_stream.get("duration")
    if duration_s is None:
        fmt = data.get("format", {})
        duration_s = fmt.get("duration")
    try:
        duration_ms = int(float(duration_s) * 1000)
    except (TypeError, ValueError):
        duration_ms = 0

    # Merge tags from format-level and stream-level (stream tags take priority for Ogg)
    fmt_tags = data.get("format", {}).get("tags", {})
    stream_tags = audio_stream.get("tags", {})
    # Merge: format-level first, then stream-level overrides
    tags: dict[str, str] = {}
    tags.update(fmt_tags)
    tags.update(stream_tags)

    raw_title = _get_tag(tags, "title")
    raw_artist = _get_tag(tags, "artist")
    raw_album = _get_tag(tags, "album")
    raw_album_artist = _get_tag(tags, "album_artist")

    tags_missing = raw_title is None or raw_artist is None

    title = raw_title if raw_title is not None else path.stem
    artist = raw_artist if raw_artist is not None else "Unknown"
    album = raw_album if raw_album is not None else "Unknown"
    album_artist = raw_album_artist if raw_album_artist is not None else artist

    track = _parse_int_before_slash(_get_tag(tags, "track"))
    disc = _parse_int_before_slash(_get_tag(tags, "disc"))
    year = _parse_year(_get_tag(tags, "date", "year"))

    return Probe(
        codec=codec,
        duration_ms=duration_ms,
        title=title,
        artist=artist,
        album=album,
        album_artist=album_artist,
        track=track,
        disc=disc,
        year=year,
        has_cover=has_cover,
        tags_missing=tags_missing,
    )


def plan_encoding(codec: str, source_ext: str, keep_lossless: bool) -> Plan:
    """Decide how to encode a file based on its codec and the keep_lossless flag.

    Raises NotAudio for unrecognised codecs.
    """
    if codec == "flac":
        if keep_lossless:
            return Plan("copy", "flac", ".flac")
        return Plan("opus", "opus", ".opus")

    if _is_lossless(codec):
        # alac, pcm_*, wavpack, ape
        if keep_lossless:
            return Plan("flac", "flac", ".flac")
        return Plan("opus", "opus", ".opus")

    if codec in _LOSSY_MAP:
        fmt, ext = _LOSSY_MAP[codec]
        if ext is None:
            ext = source_ext  # opus keeps the source extension
        return Plan("copy", fmt, ext)

    raise NotAudio(f"Unrecognised audio codec: {codec}")


def prepare(path: Path, plan: Plan, workdir: Path) -> Path:
    """Prepare an audio file according to the plan.

    Returns the file to upload (the original for 'copy', a new file for transcoding).
    """
    if plan.action == "copy":
        return path

    out = workdir / (path.stem + plan.ext)

    if plan.action == "opus":
        cmd = [
            "ffmpeg", "-y",
            "-i", str(path),
            "-vn",
            "-c:a", "libopus",
            "-b:a", "256k",
            "-map_metadata", "0",
            str(out),
        ]
    elif plan.action == "flac":
        cmd = [
            "ffmpeg", "-y",
            "-i", str(path),
            "-vn",
            "-c:a", "flac",
            "-map_metadata", "0",
            str(out),
        ]
    else:
        raise ValueError(f"Unknown plan action: {plan.action}")

    try:
        subprocess.run(cmd, capture_output=True, check=True)
    except subprocess.CalledProcessError as exc:
        raise NotAudio(f"ffmpeg transcode failed for {path}") from exc

    return out


def extract_cover(path: Path, workdir: Path) -> Path | None:
    """Extract cover art from an audio file as a 500px-wide JPEG.

    Returns the path to the cover, or None if no cover is embedded.
    """
    # First check if there is a cover
    try:
        p = probe(path)
    except NotAudio:
        return None

    if not p.has_cover:
        return None

    cover = workdir / "cover.jpg"
    cmd = [
        "ffmpeg", "-y",
        "-i", str(path),
        "-an",
        "-frames:v", "1",
        "-vf", "scale=500:-2",
        str(cover),
    ]
    try:
        subprocess.run(cmd, capture_output=True, check=True)
    except subprocess.CalledProcessError:
        return None

    if cover.exists():
        return cover
    return None


def require_ffmpeg() -> None:
    """Raise FfmpegMissing if ffmpeg or ffprobe is not on PATH."""
    if shutil.which("ffmpeg") is None:
        raise FfmpegMissing("ffmpeg is not on PATH. Install it from https://ffmpeg.org/")
    if shutil.which("ffprobe") is None:
        raise FfmpegMissing("ffprobe is not on PATH. Install it from https://ffmpeg.org/")


def content_type(ext: str) -> str:
    """Return the MIME content type for a file extension."""
    types = {
        ".opus": "audio/ogg",
        ".flac": "audio/flac",
        ".mp3": "audio/mpeg",
        ".m4a": "audio/mp4",
        ".ogg": "audio/ogg",
        ".jpg": "image/jpeg",
    }
    return types.get(ext, "application/octet-stream")
