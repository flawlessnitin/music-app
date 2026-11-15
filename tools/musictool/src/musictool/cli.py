"""Command-line interface for musictool."""

from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

from musictool import ingest, manage, media
from musictool.media import FfmpegMissing
from musictool.phone import AdbPhone, Phone, PhoneError


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


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="musictool",
        description="Put music on your phone over USB (adb) and manage it.",
    )
    sub = parser.add_subparsers(dest="subcommand")

    p_add = sub.add_parser("add", help="Send songs to the phone")
    p_add.add_argument("paths", nargs="+", help="Audio files or folders to send")
    p_add.add_argument(
        "--mood",
        default=None,
        help="Put the songs in this mood (a folder on the phone). Without it, a folder's name is the mood.",
    )
    p_add.add_argument(
        "--lossless",
        action="store_true",
        help="Keep lossless files as FLAC instead of converting to Opus",
    )

    p_remove = sub.add_parser("remove", help="Remove songs from the phone")
    p_remove.add_argument("query", help="Part of the artist, album or title")
    p_remove.add_argument("--all", action="store_true", help="Remove all matching songs if several match")

    p_list = sub.add_parser("list", help="List songs on the phone")
    p_list.add_argument("query", nargs="?", default=None, help="Optional search filter")

    sub.add_parser("status", help="Show how many songs and how much space")
    return parser


def _open_app(phone: Phone) -> None:
    """Opening the app makes it look for new songs."""
    try:
        phone.open_app()
    except PhoneError as exc:
        print(f"Songs are on the phone, but the app could not be opened: {exc}", file=sys.stderr)


def _print_add_report(report: ingest.AddReport) -> None:
    print("\nSummary:")
    print(f"  Added: {len(report.added)}")
    if report.moved:
        print(f"  Moved: {len(report.moved)}")
        for old, new in report.moved:
            print(f"    {old} -> {new}")
    if report.already_present:
        print(f"  Already on the phone: {len(report.already_present)}")
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
    if report.no_space:
        print(f"  Not enough space on the phone: {len(report.no_space)}")
        for p in report.no_space:
            print(f"    {p}")
    if report.failed:
        print(f"  Failures: {len(report.failed)}")
        for p, reason in report.failed:
            print(f"    {p}: {reason}")


def main(argv: list[str] | None = None, phone: Phone | None = None) -> int:
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

        if phone is None:
            phone = AdbPhone()

        if args.subcommand == "add":
            report = ingest.add(
                [Path(p) for p in args.paths],
                phone=phone,
                keep_lossless=args.lossless,
                mood=args.mood,
                # Flush so each song shows as it finishes, even when output is piped.
                on_progress=lambda message: print(message, flush=True),
            )
            _print_add_report(report)
            if report.added or report.moved:
                _open_app(phone)
            return 1 if report.failed else 0

        if args.subcommand == "remove":
            matches = manage.find(phone.list_files(), args.query)
            if not matches:
                print("No song matches")
                return 1
            if len(matches) > 1 and not args.all:
                for f in matches:
                    print(f.rel)
                print("Several songs match. Use --all to remove all of them.")
                return 1
            removed = manage.remove([f.rel for f in matches], phone)
            for rel in removed:
                print(f"Removed {rel}")
            if removed:
                _open_app(phone)
            return 0

        if args.subcommand == "list":
            found = manage.songs(phone.list_files())
            if args.query:
                found = manage.find(found, args.query)
            for f in sorted(found, key=lambda f: f.rel.casefold()):
                print(f"{f.rel}  ({f.size / 1_000_000:.1f} MB)")
            return 0

        if args.subcommand == "status":
            st = manage.status(phone)
            print(f"{st.songs} song" if st.songs == 1 else f"{st.songs} songs")
            print(f"Space used: {st.total_bytes / 1_000_000_000:.2f} GB ({st.free_bytes / 1_000_000_000:.1f} GB free on the phone)")
            print(f"FLAC: {st.flac_songs} song{'s' if st.flac_songs != 1 else ''}, {st.flac_bytes / 1_000_000_000:.2f} GB")
            return 0

        return 0

    except (FfmpegMissing, PhoneError) as exc:
        sys.stderr.write(f"Error: {str(exc).replace(chr(10), ' ')}\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())
