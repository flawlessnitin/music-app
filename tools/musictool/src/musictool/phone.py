"""The phone, reached over USB with adb. Songs live in the app's own Music folder."""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Protocol

APP_PACKAGE = "com.personal.music"
MUSIC_DIR = f"/sdcard/Android/data/{APP_PACKAGE}/files/Music"
PARTIAL_SUFFIX = ".part"


class PhoneError(Exception):
    """The phone could not be reached or refused something."""


@dataclass(frozen=True)
class PhoneFile:
    rel: str  # path inside the Music folder, with forward slashes
    size: int


class Phone(Protocol):
    def list_files(self) -> list[PhoneFile]: ...
    def put_file(self, rel: str, path: Path) -> None: ...
    def delete(self, rel: str) -> None: ...
    def free_bytes(self) -> int: ...
    def open_app(self) -> None: ...


def parse_find(text: str) -> list[PhoneFile]:
    """Read lines like `1234|/sdcard/.../Music/AA/Album/01 - A.opus`."""
    prefix = MUSIC_DIR + "/"
    files: list[PhoneFile] = []
    for line in text.splitlines():
        size, sep, path = line.partition("|")
        if not sep or not size.strip().isdigit() or not path.startswith(prefix):
            continue
        if path.endswith(PARTIAL_SUFFIX):
            continue  # Left over from a transfer that was cut off.
        files.append(PhoneFile(path[len(prefix):], int(size)))
    return files


def parse_df(text: str) -> int:
    """Free bytes from `df -k` output (last line: fs, blocks, used, available, ...)."""
    lines = [ln for ln in text.splitlines() if ln.strip()]
    fields = lines[-1].split() if lines else []
    if len(fields) < 4 or not fields[3].isdigit():
        raise PhoneError(f"Could not read the phone's free space: {text.strip()!r}")
    return int(fields[3]) * 1024


def _quote(text: str) -> str:
    return "'" + text.replace("'", "'\\''") + "'"


def find_adb() -> str:
    env = os.environ.get("MUSICTOOL_ADB")
    if env:
        return env
    on_path = shutil.which("adb")
    if on_path:
        return on_path
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidate = Path(local) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        if candidate.is_file():
            return str(candidate)
    raise PhoneError("adb was not found. Install Android platform-tools or set MUSICTOOL_ADB.")


def _default_run(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(args, capture_output=True)


class AdbPhone:
    def __init__(
        self,
        run: Callable[[list[str]], subprocess.CompletedProcess] = _default_run,
        adb: str | None = None,
        serial: str | None = None,
    ) -> None:
        self._run = run
        self._adb = adb
        self._serial = serial or os.environ.get("MUSICTOOL_DEVICE")

    # -- plumbing --

    def _adb_path(self) -> str:
        if self._adb is None:
            self._adb = find_adb()
        return self._adb

    def _pick_serial(self) -> str:
        if self._serial:
            return self._serial
        result = self._run([self._adb_path(), "devices"])
        devices = []
        for line in result.stdout.decode("utf-8", "replace").splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2:
                devices.append((parts[0], parts[1]))
        if not devices:
            raise PhoneError("No phone is connected. Plug it in with USB debugging on.")
        ready = [serial for serial, state in devices if state == "device"]
        if not ready:
            states = ", ".join(f"{s} is {st}" for s, st in devices)
            raise PhoneError(
                f"The phone is not ready ({states}). Unplug it, plug it in again and allow USB debugging."
            )
        if len(ready) > 1:
            raise PhoneError("More than one phone is connected. Set MUSICTOOL_DEVICE to the one to use.")
        self._serial = ready[0]
        return self._serial

    def _adb_call(self, *args: str) -> subprocess.CompletedProcess:
        serial = self._pick_serial()
        return self._run([self._adb_path(), "-s", serial, *args])

    def _shell(self, command: str) -> subprocess.CompletedProcess:
        return self._adb_call("shell", command)

    @staticmethod
    def _text(result: subprocess.CompletedProcess) -> str:
        return (result.stdout or b"").decode("utf-8", "replace") + (result.stderr or b"").decode("utf-8", "replace")

    # -- Phone --

    def list_files(self) -> list[PhoneFile]:
        result = self._shell(f"find {_quote(MUSIC_DIR)} -type f -exec stat -c '%s|%n' {{}} +")
        text = self._text(result)
        if result.returncode != 0 and "No such file" in text:
            return []  # The app has not made its folder yet.
        return parse_find(text)

    def put_file(self, rel: str, path: Path) -> None:
        target = f"{MUSIC_DIR}/{rel}"
        folder = target.rsplit("/", 1)[0]
        made = self._shell(f"mkdir -p {_quote(folder)}")
        if made.returncode != 0:
            raise PhoneError(f"Could not make {folder} on the phone: {self._text(made).strip()}")
        # Send under a temporary name and rename when it is all there, so a dropped cable never
        # leaves half a song under its real name (it would then look like it was already sent).
        partial = target + PARTIAL_SUFFIX
        pushed = self._adb_call("push", str(path), partial)
        if pushed.returncode != 0:
            self._shell(f"rm -f {_quote(partial)}")
            raise PhoneError(f"Could not send {rel}: {self._text(pushed).strip()}")
        renamed = self._shell(f"mv -f {_quote(partial)} {_quote(target)}")
        if renamed.returncode != 0:
            raise PhoneError(f"Could not finish {rel}: {self._text(renamed).strip()}")

    def delete(self, rel: str) -> None:
        result = self._shell(f"rm -f {_quote(f'{MUSIC_DIR}/{rel}')}")
        if result.returncode != 0:
            raise PhoneError(f"Could not delete {rel}: {self._text(result).strip()}")
        # Tidy up folders that are now empty.
        self._shell(f"find {_quote(MUSIC_DIR)} -mindepth 1 -type d -empty -delete")

    def free_bytes(self) -> int:
        result = self._shell("df -k /sdcard")
        if result.returncode != 0:
            raise PhoneError(f"Could not read the phone's free space: {self._text(result).strip()}")
        return parse_df(self._text(result))

    def open_app(self) -> None:
        result = self._shell(f"am start -n {APP_PACKAGE}/.ui.MainActivity")
        if result.returncode != 0:
            raise PhoneError(f"Could not open the app: {self._text(result).strip()}")
