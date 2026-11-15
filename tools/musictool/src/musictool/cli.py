"""Command-line interface for musictool."""

from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

from musictool import config, ingest, manage, media
from musictool.catalog import CATALOG_NAME, Catalog, Song
from musictool.config import ConfigError
from musictool.media import FfmpegMissing
from musictool.storage import B2Storage, Storage, StorageError


def _reconfigure_io() -> None:
    """Ensure stdout and stderr use UTF-8 encoding with errors='replace'."""
    for name in ("stdout", "stderr"):
        stream = getattr(sys, name, None)
        if stream is None:
            continue
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
                continue
            except Exception:
                pass
        if hasattr(stream, "buffer"):
            try:
                setattr(
                    sys,
                    name,
                    io.TextIOWrapper(stream.buffer, encoding="utf-8", errors="replace"),
                )
            except Exception:
                pass


def _song_sort_key(s: Song):
    return (
        (s.artist or "").casefold(),
        (s.album or "").casefold(),
        s.disc if s.disc is not None else 0,
        s.track if s.track is not None else 0,
        (s.title or "").casefold(),
    )


def _format_song(s: Song) -> str:
    return f"{s.id}  {s.artist} - {s.title}  ({s.album})  [{s.format}]"


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="musictool",
        description="Music uploader and library manager for personal music collection",
    )
    subparsers = parser.add_subparsers(dest="subcommand")

    # add
    p_add = subparsers.add_parser("add", help="Add songs to storage and catalog")
    p_add.add_argument("paths", nargs="+", help="Audio file or directory paths to add")
    p_add.add_argument(
        "--lossless",
        action="store_true",
        help="Keep lossless files as FLAC instead of converting to Opus",
    )

    # remove
    p_remove = subparsers.add_parser("remove", help="Remove songs from storage and catalog")
    p_remove.add_argument("query", help="Song ID, title, artist, or album to remove")
    p_remove.add_argument(
        "--all",
        action="store_true",
        help="Remove all matching songs if multiple match",
    )

    # list
    p_list = subparsers.add_parser("list", help="List songs in catalog")
    p_list.add_argument("query", nargs="?", default=None, help="Optional search filter")

    # status
    subparsers.add_parser("status", help="Show library status and space usage")

    return parser


def main(argv: list[str] | None = None, storage: Storage | None = None) -> int:
    """CLI entry point for musictool."""
    _reconfigure_io()

    if argv is None:
        argv = sys.argv[1:]

    parser = _build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        return exc.code if isinstance(exc.code, int) else 2

    if args.subcommand is None:
        parser.print_help()
        return 2

    try:
        if args.subcommand == "add":
            media.require_ffmpeg()

        if storage is None:
            cfg = config.load()
            storage = B2Storage(cfg.key_id, cfg.application_key, cfg.bucket)

        if args.subcommand == "add":
            paths = [Path(p) for p in args.paths]
            report = ingest.add(
                paths,
                storage=storage,
                keep_lossless=args.lossless,
                # Flush so each song shows as it finishes, even when output is piped.
                on_progress=lambda message: print(message, flush=True),
            )

            print("\nSummary:")
            print(f"  Added: {len(report.added)}")
            if report.already_present:
                print(f"  Already present: {len(report.already_present)}")
                for p in report.already_present:
                    print(f"    {p}")
            if report.skipped:
                print(f"  Skipped: {len(report.skipped)}")
                for p, reason in report.skipped:
                    print(f"    {p}: {reason}")
            if report.missing_tags:
                print(f"  Missing tags: {len(report.missing_tags)}")
                for p in report.missing_tags:
                    print(f"    {p}")
            if report.over_limit:
                print(f"  Refused (space limit): {len(report.over_limit)}")
                for p in report.over_limit:
                    print(f"    {p}")
            if report.failed:
                print(f"  Failures: {len(report.failed)}")
                for p, reason in report.failed:
                    print(f"    {p}: {reason}")

            return 1 if report.failed else 0

        elif args.subcommand == "remove":
            raw = storage.read(CATALOG_NAME)
            catalog = Catalog.from_json(raw.decode("utf-8")) if raw else Catalog()
            matches = manage.find(catalog, args.query)

            if not matches:
                print("No song matches")
                return 1

            if len(matches) > 1 and not args.all:
                for s in sorted(matches, key=_song_sort_key):
                    print(_format_song(s))
                return 1

            removed = manage.remove([s.id for s in matches], storage)
            for s in removed:
                print(f"Removed {s.artist} - {s.title}")
            return 0

        elif args.subcommand == "list":
            raw = storage.read(CATALOG_NAME)
            catalog = Catalog.from_json(raw.decode("utf-8")) if raw else Catalog()
            songs = manage.find(catalog, args.query) if args.query else list(catalog.songs)
            for s in sorted(songs, key=_song_sort_key):
                print(_format_song(s))
            return 0

        elif args.subcommand == "status":
            st = manage.status(storage)
            song_str = f"{st.songs} song" if st.songs == 1 else f"{st.songs} songs"
            used_gb = st.total_bytes / 1_000_000_000
            limit_gb = st.limit_bytes / 1_000_000_000
            pct = (st.total_bytes / st.limit_bytes * 100) if st.limit_bytes else 0.0
            lossless_str = (
                f"{st.lossless_songs} song" if st.lossless_songs == 1 else f"{st.lossless_songs} songs"
            )
            lossless_mb = st.lossless_bytes / 1_000_000
            lossless_gb = st.lossless_bytes / 1_000_000_000

            print(f"{song_str}")
            print(f"Space: {used_gb:.2f} GB / {limit_gb:.1f} GB ({pct:.1f}%)")
            print(f"FLAC: {lossless_str}, {lossless_gb:.2f} GB ({lossless_mb:.1f} MB)")
            print(f"Version: {st.version}")
            return 0

        return 0

    except (ConfigError, FfmpegMissing, StorageError) as exc:
        msg = str(exc).replace("\n", " ")
        sys.stderr.write(f"Error: {msg}\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())
