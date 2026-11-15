"""Configuration loading from TOML file."""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass
from pathlib import Path


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
        else:
            cfg_path = Path.home() / ".musictool" / "config.toml"

    required_keys = ("key_id", "application_key", "bucket")

    if not cfg_path.is_file():
        raise ConfigError(
            f"Config file not found at {cfg_path}. "
            f"It must define: {', '.join(required_keys)}"
        )

    try:
        with open(cfg_path, "rb") as f:
            data = tomllib.load(f)
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
