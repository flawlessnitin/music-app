"""Send songs to the phone."""

from __future__ import annotations

import shutil
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from musictool import manage
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
    moved: list[tuple[str, str]] = field(default_factory=list)
    skipped: list[tuple[Path, str]] = field(default_factory=list)
    failed: list[tuple[Path, str]] = field(default_factory=list)
    missing_tags: list[Path] = field(default_factory=list)
    no_space: list[Path] = field(default_factory=list)


def _expand(paths: list[Path]) -> list[tuple[Path, str | None]]:
    """Files with the mood they belong to.

    Files given one by one have no mood. For a folder, the folder's name is the mood, except a folder
    called "Music": there each sub-folder is a mood, and files straight in it have none.
    Folders are walked recursively and only audio files are taken (pictures and notes are ignored).
    """
    files: list[tuple[Path, str | None]] = []
    for p in paths:
        p = Path(p)
        if p.is_dir():
            for f in p.rglob("*"):
                if not (f.is_file() and f.suffix.lower() in AUDIO_EXTENSIONS):
                    continue
                if p.name.casefold() == "music":
                    parts = f.relative_to(p).parts
                    files.append((f, parts[0] if len(parts) > 1 else None))
                else:
                    files.append((f, p.name))
        else:
            files.append((p, None))
    return sorted(files, key=lambda item: item[0])


def _sidecar_cover(song: Path) -> Path | None:
    for name in _SIDECAR_COVERS:
        candidate = song.parent / name
        if candidate.is_file():
            return candidate
    return None


def _send_cover(phone: Phone, p, path: Path, workdir: Path, on_phone: set[str], mood: str | None) -> None:
    cover_rel = cover_path(p, mood)
    if cover_rel in on_phone:
        return
    cover = extract_cover(path, workdir) if p.has_cover else _sidecar_cover(path)
    if cover is not None:
        phone.put_file(cover_rel, cover)
        on_phone.add(cover_rel)


def add(
    paths: list[Path],
    phone: Phone,
    keep_lossless: bool = False,
    on_progress: Callable[[str], None] = lambda _: None,
    mood: str | None = None,
) -> AddReport:
    """Put songs on the phone, converting lossless files to Opus unless [keep_lossless].

    [mood] files every song under that mood; without it a folder's name is the mood (see [_expand]).
    A song already on the phone without a mood is moved into its mood folder, not sent again.
    """
    report = AddReport()
    files = _expand(paths)

    on_phone = {f.rel for f in phone.list_files()}
    free = phone.free_bytes()

    with tempfile.TemporaryDirectory() as temp:
        for number, (path, derived_mood) in enumerate(files):
            song_mood = (mood or derived_mood or "").strip() or None
            workdir = Path(temp) / str(number)
            workdir.mkdir()
            try:
                try:
                    p = probe(path)
                    plan = plan_encoding(p.codec, path.suffix, keep_lossless)
                except NotAudio as exc:
                    report.skipped.append((path, str(exc)))
                    continue

                rel = song_path(p, plan.ext, song_mood)
                if rel in on_phone:
                    report.already_present.append(path)
                    continue
                if p.tags_missing:
                    report.missing_tags.append(path)

                plain = song_path(p, plan.ext)
                if song_mood and plain in on_phone:
                    try:
                        phone.move(plain, rel)
                        on_phone.discard(plain)
                        on_phone.add(rel)
                        _send_cover(phone, p, path, workdir, on_phone, song_mood)
                    except PhoneError as exc:
                        report.failed.append((path, str(exc)))
                        break
                    report.moved.append((plain, rel))
                    on_progress(f"Moved {p.artist} - {p.title} to {song_mood}")
                    continue

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

                    _send_cover(phone, p, path, workdir, on_phone, song_mood)
                except PhoneError as exc:
                    report.failed.append((path, str(exc)))
                    break

                report.added.append(Added(rel, path, size, plan.format))
                on_progress(f"Added {p.artist} - {p.title}")
            finally:
                # Converted files are large; do not let them pile up over a long run.
                shutil.rmtree(workdir, ignore_errors=True)

    if report.moved:
        manage.remove_orphan_covers(phone)
    return report

