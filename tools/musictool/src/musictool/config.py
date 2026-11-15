"""Configuration loading from TOML file."""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass
from pathlib import Path


# tools/musictool/config.toml in the project (git-ignored), then the home folder.
PROJECT_CONFIG = Path(__file__).resolve().parents[2] / "config.toml"
HOME_CONFIG = Path.home() / ".musictool" / "config.toml"


class ConfigError(Exception):
    """Raised when configuration is missing or invalid."""
    pass


@dataclass
class Config:
    key_id: str
    application_key: str
    bucket: str


def load(path: Path | None = None) -> Config:
    """Load configuration from a TOML file.

    Default path is ~/.musictool/config.toml, overridden by MUSICTOOL_CONFIG env var.
    Raises ConfigError if the file does not exist or any required key is missing.
    """
    if path is not None:
        cfg_path = Path(path)
    else:
        env = os.environ.get("MUSICTOOL_CONFIG")
        if env:
            cfg_path = Path(env)
        elif PROJECT_CONFIG.is_file():
            cfg_path = PROJECT_CONFIG
        else:
            cfg_path = HOME_CONFIG

    required_keys = ("key_id", "application_key", "bucket")

    if not cfg_path.is_file():
        raise ConfigError(
            f"Config file not found at {cfg_path}. "
            f"It must define: {', '.join(required_keys)}"
        )

    try:
        # utf-8-sig: Notepad may save the file with a byte-order mark.
        data = tomllib.loads(cfg_path.read_text(encoding="utf-8-sig"))
    except Exception as exc:
        raise ConfigError(
            f"Failed to read config file at {cfg_path}: {exc}. "
            f"It must define: {', '.join(required_keys)}"
        )

    missing = [k for k in required_keys if not data.get(k)]
    if missing:
        raise ConfigError(
            f"Config file at {cfg_path} is missing required keys: {', '.join(missing)}. "
            f"Required keys: {', '.join(required_keys)}"
        )

    return Config(
        key_id=str(data["key_id"]),
        application_key=str(data["application_key"]),
        bucket=str(data["bucket"]),
    )
