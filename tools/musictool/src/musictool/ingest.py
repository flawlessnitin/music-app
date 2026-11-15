"""Add songs to storage and catalog."""

from __future__ import annotations

import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from musictool.catalog import CATALOG_NAME, LIMIT_BYTES, Catalog, Song
from musictool.ids import album_id, song_id
from musictool.media import (
    NotAudio,
    content_type,
    extract_cover,
    plan_encoding,
    prepare,
    probe,
)
from musictool.storage import Storage, StorageError


@dataclass
class AddReport:
    added: list[Song] = field(default_factory=list)
    already_present: list[Path] = field(default_factory=list)
    skipped: list[tuple[Path, str]] = field(default_factory=list)
    failed: list[tuple[Path, str]] = field(default_factory=list)
    missing_tags: list[Path] = field(default_factory=list)
    over_limit: list[Path] = field(default_factory=list)


def add(
    paths: list[Path],
    storage: Storage,
    keep_lossless: bool = False,
    on_progress: Callable[[str], None] = lambda _: None,
) -> AddReport:
    """Add songs from given paths to storage and update the catalog."""
    report = AddReport()

    # 1. Expand each path: file as is, folder walked recursively. Sorted order.
    files: list[Path] = []
    for p in paths:
        path_obj = Path(p)
        if path_obj.is_dir():
            for sub in path_obj.rglob("*"):
                if sub.is_file():
                    files.append(sub)
        else:
            files.append(path_obj)
    files.sort()

    # 2. Load the catalog from storage (absent means an empty catalog).
    raw_catalog = storage.read(CATALOG_NAME)
    if raw_catalog is not None:
        catalog = Catalog.from_json(raw_catalog.decode("utf-8"))
    else:
        catalog = Catalog()

    # In a finally block, bump and upload catalog if at least one song was added.
    try:
        with tempfile.TemporaryDirectory() as temp_dir_str:
            temp_dir = Path(temp_dir_str)
            for path in files:
                # 3. Compute id, check catalog, probe and plan encoding
                try:
                    sid = song_id(path)
                except OSError as exc:
                    report.skipped.append((path, str(exc)))
                    continue

                if catalog.has(sid):
                    report.already_present.append(path)
                    continue

                try:
                    p = probe(path)
                    plan = plan_encoding(p.codec, path.suffix, keep_lossless)
                except NotAudio as exc:
                    report.skipped.append((path, str(exc)))
                    continue

                if p.tags_missing:
                    report.missing_tags.append(path)

                target_name = f"songs/{sid}{plan.ext}"
                song_workdir = temp_dir / sid
                song_workdir.mkdir(parents=True, exist_ok=True)

                # 4. Target upload or reuse; check space limit
                try:
                    existing_size = storage.size(target_name)
                    if existing_size is not None:
                        file_size = existing_size
                        if catalog.total_bytes() + file_size > LIMIT_BYTES:
                            report.over_limit.append(path)
                            continue
                    else:
                        try:
                            prepared_path = prepare(path, plan, song_workdir)
                        except NotAudio as exc:
                            report.skipped.append((path, str(exc)))
                            continue

                        file_size = prepared_path.stat().st_size
                        if catalog.total_bytes() + file_size > LIMIT_BYTES:
                            report.over_limit.append(path)
                            continue

                        storage.put_file(target_name, prepared_path, content_type(plan.ext))

                    # 5. Cover art
                    cover_name = f"covers/{album_id(p.album_artist, p.album)}.jpg"
                    if p.has_cover and storage.size(cover_name) is None:
                        cover_path = extract_cover(path, song_workdir)
                        if cover_path is not None:
                            storage.put_file(cover_name, cover_path, content_type(".jpg"))

                    song_cover = cover_name if storage.size(cover_name) is not None else None

                    # 6. Add Song to catalog and to added
                    song = Song(
                        id=sid,
                        title=p.title,
                        artist=p.artist,
                        album=p.album,
                        albumArtist=p.album_artist,
                        track=p.track,
                        disc=p.disc,
                        year=p.year,
                        durationMs=p.duration_ms,
                        sizeBytes=file_size,
                        format=plan.format,
                        file=target_name,
                        cover=song_cover,
                    )
                    catalog.add(song)
                    report.added.append(song)
                    on_progress(f"Added {song.artist} - {song.title}")
                except StorageError as exc:
                    report.failed.append((path, str(exc)))
                    break
    finally:
        # 7. Bump and upload catalog if any song was added
        if report.added:
            catalog.bump()
            storage.put_bytes(
                CATALOG_NAME,
                catalog.to_json().encode("utf-8"),
                "application/json",
            )

    return report
