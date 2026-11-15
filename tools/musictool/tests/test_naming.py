from musictool.media import Probe
from musictool.naming import cover_path, sanitize, song_path


def probe(**kw) -> Probe:
    base = dict(
        codec="flac", duration_ms=1000, title="Song A", artist="Artist", album="Album",
        album_artist="AA", track=3, disc=1, year=2019, has_cover=False, tags_missing=False,
    )
    base.update(kw)
    return Probe(**base)


def test_song_path_uses_album_artist_album_and_track_number():
    assert song_path(probe(), ".opus") == "AA/Album/03 - Song A.opus"


def test_disc_number_is_added_only_for_later_discs():
    assert song_path(probe(disc=2, track=4), ".flac") == "AA/Album/2-04 - Song A.flac"
    assert song_path(probe(disc=1), ".flac") == "AA/Album/03 - Song A.flac"


def test_no_track_number_means_just_the_title():
    assert song_path(probe(track=None), ".mp3") == "AA/Album/Song A.mp3"


def test_cover_sits_next_to_the_songs():
    assert cover_path(probe()) == "AA/Album/Folder.jpg"


def test_characters_a_phone_path_cannot_hold_are_replaced():
    assert sanitize('AC/DC: "Back" in <Black>?') == "AC_DC_ _Back_ in _Black__"


def test_dots_and_spaces_at_the_ends_are_trimmed():
    assert sanitize("  Hello... ") == "Hello"


def test_empty_names_get_a_placeholder():
    assert sanitize("   ") == "Unknown"
    assert sanitize("...") == "Unknown"


def test_non_latin_names_are_kept():
    assert song_path(probe(title="तुम ही हो", track=None), ".opus") == "AA/Album/तुम ही हो.opus"


def test_very_long_titles_are_shortened_but_keep_the_extension():
    path = song_path(probe(title="x" * 400, track=None), ".opus")
    name = path.rsplit("/", 1)[1]
    assert len(name) <= 150
    assert name.endswith(".opus")
