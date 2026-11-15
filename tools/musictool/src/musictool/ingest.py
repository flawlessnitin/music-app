"""Send songs to the phone."""

from __future__ import annotations

import shutil
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from musictool.media import NotAudio, extract_cover, plan_encoding, prepare, probe
from musictool.naming import AUDIO_EXTENSIONS, cover_path, song_path
from musictool.phone import Phone, PhoneError

# Always leave this much free so the phone keeps working.
RESERVE_BYTES = 1_000_000_000

_SIDECAR_COVERS = ("Folder.jpg", "folder.jpg", "cover.jpg", "Cover.jpg")


@dataclass
class Added:
    rel: str
    source: Path
    size: int
    format: str


@dataclass
class AddReport:
    added: list[Added] = field(default_factory=list)
    already_present: list[Path] = field(default_factory=list)
    skipped: list[tuple[Path, str]] = field(default_factory=list)
    failed: list[tuple[Path, str]] = field(default_factory=list)
    missing_tags: list[Path] = field(default_factory=list)
    no_space: list[Path] = field(default_factory=list)


def _expand(paths: list[Path]) -> list[Path]:
    """Files as given; folders walked, taking only audio files (pictures and notes are ignored)."""
    files: list[Path] = []
    for p in paths:
        p = Path(p)
        if p.is_dir():
            files.extend(f for f in p.rglob("*") if f.is_file() and f.suffix.lower() in AUDIO_EXTENSIONS)
        else:
            files.append(p)
    return sorted(files)


def _sidecar_cover(song: Path) -> Path | None:
    for name in _SIDECAR_COVERS:
        candidate = song.parent / name
        if candidate.is_file():
            return candidate
    return None


def add(
    paths: list[Path],
    phone: Phone,
    keep_lossless: bool = False,
    on_progress: Callable[[str], None] = lambda _: None,
) -> AddReport:
    """Put songs on the phone, converting lossless files to Opus unless [keep_lossless]."""
    report = AddReport()
    files = _expand(paths)

    on_phone = {f.rel for f in phone.list_files()}
    free = phone.free_bytes()

    with tempfile.TemporaryDirectory() as temp:
        for number, path in enumerate(files):
            workdir = Path(temp) / str(number)
            workdir.mkdir()
            try:
                try:
                    p = probe(path)
                    plan = plan_encoding(p.codec, path.suffix, keep_lossless)
                except NotAudio as exc:
                    report.skipped.append((path, str(exc)))
                    continue

                rel = song_path(p, plan.ext)
                if rel in on_phone:
                    report.already_present.append(path)
                    continue
                if p.tags_missing:
                    report.missing_tags.append(path)

                try:
                    ready = prepare(path, plan, workdir)
                except NotAudio as exc:
                    report.skipped.append((path, str(exc)))
                    continue

                size = ready.stat().st_size
                if size > free - RESERVE_BYTES:
                    report.no_space.append(path)
                    continue

                try:
                    phone.put_file(rel, ready)
                    on_phone.add(rel)
                    free -= size

                    cover_rel = cover_path(p)
                    if cover_rel not in on_phone:
                        cover = extract_cover(path, workdir) if p.has_cover else _sidecar_cover(path)
                        if cover is not None:
                            phone.put_file(cover_rel, cover)
                            on_phone.add(cover_rel)
                except PhoneError as exc:
                    report.failed.append((path, str(exc)))
                    break

                report.added.append(Added(rel, path, size, plan.format))
                on_progress(f"Added {p.artist} - {p.title}")
            finally:
                # Converted files are large; do not let them pile up over a long run.
                shutil.rmtree(workdir, ignore_errors=True)

    return report
