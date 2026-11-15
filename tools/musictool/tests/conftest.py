from musictool.catalog import Song


def make_song(**overrides) -> Song:
    """Return a valid Song with sensible defaults, overridden by keyword args."""
    defaults = dict(
        id="0" * 16,
        title="Test Song",
        artist="Test Artist",
        album="Test Album",
        albumArtist="Test Artist",
        track=1,
        disc=1,
        year=2024,
        durationMs=210000,
        sizeBytes=5_000_000,
        format="opus",
        file="songs/0000000000000000.opus",
        cover=None,
    )
    defaults.update(overrides)
    return Song(**defaults)
