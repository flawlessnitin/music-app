"""Search, removal and status of the songs on the phone."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import PurePosixPath

from musictool.naming import AUDIO_EXTENSIONS, COVER_NAME
from musictool.phone import Phone, PhoneFile


def songs(files: list[PhoneFile]) -> list[PhoneFile]:
    return [f for f in files if PurePosixPath(f.rel).suffix.lower() in AUDIO_EXTENSIONS]


def find(files: list[PhoneFile], query: str) -> list[PhoneFile]:
    """Songs whose path (artist, album or title) contains the query, ignoring case."""
    q = query.casefold()
    return [f for f in songs(files) if q in f.rel.casefold()]


def remove(rels: list[str], phone: Phone) -> list[str]:
    """Delete songs from the phone, and an album's cover once its last song is gone."""
    on_phone = {f.rel for f in phone.list_files()}
    removed = sorted(set(rels) & on_phone)
    for rel in removed:
        phone.delete(rel)

    remaining = on_phone - set(removed)
    for folder in {str(PurePosixPath(r).parent) for r in removed}:
        cover = f"{folder}/{COVER_NAME}"
        has_songs = any(
            str(PurePosixPath(r).parent) == folder and PurePosixPath(r).suffix.lower() in AUDIO_EXTENSIONS
            for r in remaining
        )
        if cover in remaining and not has_songs:
            phone.delete(cover)
    return removed


def remove_orphan_covers(phone: Phone) -> list[str]:
    """Delete covers that no song sits beside any more (for example after songs moved to another folder)."""
    files = phone.list_files()
    song_folders = {str(PurePosixPath(f.rel).parent) for f in songs(files)}
    orphans = sorted(
        f.rel for f in files
        if PurePosixPath(f.rel).name == COVER_NAME and str(PurePosixPath(f.rel).parent) not in song_folders
    )
    for rel in orphans:
        phone.delete(rel)
    return orphans


@dataclass
class Status:
    songs: int
    total_bytes: int
    flac_songs: int
    flac_bytes: int
    free_bytes: int


def status(phone: Phone) -> Status:
    found = songs(phone.list_files())
    flac = [f for f in found if f.rel.lower().endswith(".flac")]
    return Status(
        songs=len(found),
        total_bytes=sum(f.size for f in found),
        flac_songs=len(flac),
        flac_bytes=sum(f.size for f in flac),
        free_bytes=phone.free_bytes(),
    )
