"""Where a song goes on the phone: Artist/Album/NN - Title.ext, with the cover next to it."""

from __future__ import annotations

import re

from musictool.media import Probe

AUDIO_EXTENSIONS = {
    ".mp3", ".flac", ".opus", ".ogg", ".m4a", ".aac", ".wav", ".wma", ".alac", ".ape", ".wv",
}
COVER_NAME = "Folder.jpg"

_BAD_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_MAX_FILE_NAME = 150


def sanitize(part: str) -> str:
    """Make a tag value safe to use as a file or folder name."""
    cleaned = _BAD_CHARS.sub("_", part).strip(" .")
    return cleaned[:100].strip(" .") or "Unknown"


def _folder(probe: Probe) -> str:
    return f"{sanitize(probe.album_artist or probe.artist)}/{sanitize(probe.album)}"


def _mood_prefix(mood: str | None) -> str:
    cleaned = (mood or "").strip()
    return f"{sanitize(cleaned)}/" if cleaned else ""


def song_path(probe: Probe, ext: str, mood: str | None = None) -> str:
    """Path of the song on the phone, relative to its Music folder. A mood adds a folder on top."""
    if probe.track is None:
        prefix = ""
    elif probe.disc is not None and probe.disc > 1:
        prefix = f"{probe.disc}-{probe.track:02d} - "
    else:
        prefix = f"{probe.track:02d} - "

    title = sanitize(probe.title)
    room = _MAX_FILE_NAME - len(prefix) - len(ext)
    return f"{_mood_prefix(mood)}{_folder(probe)}/{prefix}{title[:room].rstrip(' .')}{ext}"


def cover_path(probe: Probe, mood: str | None = None) -> str:
    return f"{_mood_prefix(mood)}{_folder(probe)}/{COVER_NAME}"
