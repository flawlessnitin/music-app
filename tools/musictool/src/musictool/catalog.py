"""Song dataclass, Catalog with JSON serialization, and mutation helpers."""

from __future__ import annotations

import json
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone


CATALOG_NAME = "catalog.json"
LIMIT_BYTES = 9_500_000_000


@dataclass(frozen=True)
class Song:
    id: str
    title: str
    artist: str
    album: str
    albumArtist: str
    track: int | None
    disc: int | None
    year: int | None
    durationMs: int
    sizeBytes: int
    format: str
    file: str
    cover: str | None


@dataclass
class Catalog:
    version: int = 0
    updatedAt: str = ""
    songs: list[Song] = field(default_factory=list)

    @classmethod
    def from_json(cls, text: str) -> Catalog:
        """Deserialize a Catalog from a JSON string."""
        data = json.loads(text)
        songs = [Song(**s) for s in data.get("songs", [])]
        return cls(
            version=data.get("version", 0),
            updatedAt=data.get("updatedAt", ""),
            songs=songs,
        )

    def to_json(self) -> str:
        """Serialize the Catalog to a JSON string with 2-space indent and non-ASCII preserved."""
        data = {
            "version": self.version,
            "updatedAt": self.updatedAt,
            "songs": [asdict(s) for s in self.songs],
        }
        return json.dumps(data, indent=2, ensure_ascii=False)

    def has(self, song_id: str) -> bool:
        """Return True if a song with the given id is in the catalog."""
        return any(s.id == song_id for s in self.songs)

    def add(self, song: Song) -> None:
        """Add a song to the catalog."""
        self.songs.append(song)

    def remove(self, song_id: str) -> Song:
        """Remove and return the song with the given id, or raise KeyError."""
        for i, s in enumerate(self.songs):
            if s.id == song_id:
                return self.songs.pop(i)
        raise KeyError(song_id)

    def total_bytes(self) -> int:
        """Return the sum of sizeBytes across all songs."""
        return sum(s.sizeBytes for s in self.songs)

    def bump(self) -> None:
        """Increment version by 1 and set updatedAt to the current UTC time."""
        self.version += 1
        self.updatedAt = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
