import subprocess
from pathlib import Path

import pytest

from musictool.phone import (
    APP_PACKAGE,
    MUSIC_DIR,
    AdbPhone,
    PhoneError,
    parse_df,
    parse_find,
)


class Recorder:
    """Stands in for running adb: records commands and replies from a script."""

    def __init__(self, replies=None):
        self.calls: list[list[str]] = []
        self.replies = replies or {}

    def __call__(self, args: list[str]) -> subprocess.CompletedProcess:
        self.calls.append(args)
        for needle, (code, out) in self.replies.items():
            if needle in " ".join(args):
                return subprocess.CompletedProcess(args, code, out.encode("utf-8"), b"")
        return subprocess.CompletedProcess(args, 0, b"", b"")


def phone(replies=None, serial="ABC123") -> tuple[AdbPhone, Recorder]:
    rec = Recorder(replies)
    return AdbPhone(run=rec, adb="adb", serial=serial), rec


def test_parse_find_reads_size_and_relative_path():
    text = f"123|{MUSIC_DIR}/AA/Album/01 - A.opus\n45|{MUSIC_DIR}/AA/Album/Folder.jpg\n"
    files = parse_find(text)
    assert [(f.rel, f.size) for f in files] == [("AA/Album/01 - A.opus", 123), ("AA/Album/Folder.jpg", 45)]


def test_parse_find_ignores_blank_and_foreign_lines():
    assert parse_find("\nfind: nothing here\n") == []


def test_parse_df_reads_available_kilobytes():
    text = "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/fuse 100 40 60 40% /storage/emulated\n"
    assert parse_df(text) == 60 * 1024


def test_parse_df_garbage_is_an_error():
    with pytest.raises(PhoneError):
        parse_df("nothing useful")


def test_put_file_makes_the_folder_then_pushes(tmp_path: Path):
    f = tmp_path / "a.opus"
    f.write_bytes(b"x")
    p, rec = phone()

    p.put_file("AA/Album/01 - A.opus", f)

    joined = [" ".join(c) for c in rec.calls]
    assert any("mkdir -p" in c and "AA/Album" in c for c in joined)
    push = next(c for c in rec.calls if "push" in c)
    # It goes under a temporary name first, and is renamed once it has all arrived.
    assert push[-1] == f"{MUSIC_DIR}/AA/Album/01 - A.opus.part"
    assert push[:3] == ["adb", "-s", "ABC123"]
    rename = next(c for c in joined if " mv " in c or c.endswith("mv"))
    assert ".opus.part" in rename and rename.rstrip().endswith("01 - A.opus'")


def test_a_failing_push_is_reported(tmp_path: Path):
    f = tmp_path / "a.opus"
    f.write_bytes(b"x")
    p, _ = phone({"push": (1, "adb: error: no space")})

    with pytest.raises(PhoneError):
        p.put_file("a.opus", f)


def test_delete_quotes_odd_names():
    p, rec = phone()

    p.delete("AA/It's \"Mine\"/01 - A.opus")

    cmd = rec.calls[0][-1]
    assert cmd.startswith("rm -f ")
    assert "'\\''" in cmd


def test_open_app_starts_the_main_screen():
    p, rec = phone()

    p.open_app()

    assert f"{APP_PACKAGE}/.ui.MainActivity" in " ".join(rec.calls[0])


def test_no_device_is_a_clear_error():
    rec = Recorder({"devices": (0, "List of devices attached\n\n")})
    p = AdbPhone(run=rec, adb="adb")
    with pytest.raises(PhoneError, match="No phone"):
        p.free_bytes()


def test_offline_device_is_a_clear_error():
    rec = Recorder({"devices": (0, "List of devices attached\nXYZ\toffline\n")})
    p = AdbPhone(run=rec, adb="adb")
    with pytest.raises(PhoneError, match="offline"):
        p.free_bytes()


def test_two_phones_need_a_choice():
    rec = Recorder({"devices": (0, "List of devices attached\nA\tdevice\nB\tdevice\n")})
    p = AdbPhone(run=rec, adb="adb")
    with pytest.raises(PhoneError, match="(?i)more than one"):
        p.free_bytes()


def test_the_single_connected_phone_is_picked_automatically():
    rec = Recorder({
        "devices": (0, "List of devices attached\nONLY\tdevice\n"),
        "df": (0, "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/fuse 100 40 60 40% /s\n"),
    })
    p = AdbPhone(run=rec, adb="adb")

    assert p.free_bytes() == 60 * 1024
    assert any(c[:3] == ["adb", "-s", "ONLY"] for c in rec.calls if "df" in " ".join(c))


def test_a_failed_push_cleans_up_the_partial_file(tmp_path: Path):
    f = tmp_path / "a.opus"
    f.write_bytes(b"x")
    p, rec = phone({"push": (1, "adb: error: connection reset")})

    with pytest.raises(PhoneError):
        p.put_file("a.opus", f)

    assert any("rm -f" in " ".join(c) and "a.opus.part" in " ".join(c) for c in rec.calls)


def test_half_sent_files_are_not_listed_as_songs():
    text = "\n".join([f"10|{MUSIC_DIR}/AA/Album/01 - A.opus", f"5|{MUSIC_DIR}/AA/Album/02 - B.opus.part"])

    assert [f.rel for f in parse_find(text)] == ["AA/Album/01 - A.opus"]


def test_move_makes_the_folder_then_renames_on_the_phone():
    p, rec = phone()

    p.move("AA/Album/01 - A.opus", "phonk/AA/Album/01 - A.opus")

    joined = [" ".join(c) for c in rec.calls]
    assert any("mkdir -p" in c and "phonk/AA/Album" in c for c in joined)
    assert any(" mv -f " in c and "01 - A.opus" in c and "phonk/AA/Album" in c for c in joined)
    assert any("-empty -delete" in c for c in joined)


def test_a_failed_move_is_reported():
    p, _ = phone({"mv -f": (1, "mv: no such file")})

    with pytest.raises(PhoneError):
        p.move("a.opus", "b/a.opus")
