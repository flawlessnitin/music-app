"""Song search, removal and status reporting."""

from __future__ import annotations

from dataclasses import dataclass

from musictool.catalog import CATALOG_NAME, LIMIT_BYTES, Catalog, Song
from musictool.storage import Storage


def find(catalog: Catalog, query: str) -> list[Song]:
    """Find songs in catalog.

    Matches exact song id first; otherwise searches case-insensitively across
    title, artist, and album.
    """
    exact = [s for s in catalog.songs if s.id == query]
    if exact:
        return exact

    q = query.casefold()
    return [
        s for s in catalog.songs
        if q in s.title.casefold() or q in s.artist.casefold() or q in s.album.casefold()
    ]


def remove(song_ids: list[str], storage: Storage) -> list[Song]:
    """Remove songs from catalog and storage.

    Order: remove from catalog, bump catalog, upload catalog, delete song files,
    and delete cover art only if no remaining song uses it.
    """
    raw = storage.read(CATALOG_NAME)
    if raw is None:
        return []
    catalog = Catalog.from_json(raw.decode("utf-8"))

    removed: list[Song] = []
    for sid in set(song_ids):
        if catalog.has(sid):
            removed.append(catalog.remove(sid))

    if not removed:
        return []

    # 1. bump() once
    catalog.bump()

    # 2. Upload catalog before deleting files
    storage.put_bytes(
        CATALOG_NAME,
        catalog.to_json().encode("utf-8"),
        "application/json",
    )

    # 3. Delete each song file
    for s in removed:
        storage.delete(s.file)

    # 4. Delete cover only if no remaining song uses it
    deleted_covers: set[str] = set()
    for s in removed:
        if s.cover and s.cover not in deleted_covers:
            still_used = any(rem.cover == s.cover for rem in catalog.songs)
            if not still_used:
                storage.delete(s.cover)
                deleted_covers.add(s.cover)

    return removed


@dataclass
class Status:
    songs: int
    total_bytes: int
    limit_bytes: int
    lossless_songs: int
    lossless_bytes: int
    version: int


def status(storage: Storage) -> Status:
    """Return library status and storage usage."""
    raw = storage.read(CATALOG_NAME)
    if raw is not None:
        catalog = Catalog.from_json(raw.decode("utf-8"))
    else:
        catalog = Catalog()

    lossless = [s for s in catalog.songs if s.format == "flac"]
    return Status(
        songs=len(catalog.songs),
        total_bytes=catalog.total_bytes(),
        limit_bytes=LIMIT_BYTES,
        lossless_songs=len(lossless),
        lossless_bytes=sum(s.sizeBytes for s in lossless),
        version=catalog.version,
    )
