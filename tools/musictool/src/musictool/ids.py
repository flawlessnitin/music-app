"""Song and album ID generation based on SHA-1 hashes."""

from __future__ import annotations

import hashlib
from pathlib import Path


def song_id(path: Path) -> str:
    """Return the first 16 hex characters of the SHA-1 of the file's bytes."""
    h = hashlib.sha1()
    with open(path, "rb") as f:
        while chunk := f.read(8192):
            h.update(chunk)
    return h.hexdigest()[:16]


def album_id(album_artist: str, album: str) -> str:
    """Return the first 16 hex characters of the SHA-1 of '{artist_lower}\\0{album_lower}' (UTF-8)."""
    key = f"{album_artist.casefold()}\0{album.casefold()}"
    return hashlib.sha1(key.encode("utf-8")).hexdigest()[:16]
