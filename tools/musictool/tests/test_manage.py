from musictool import manage
from musictool.phone import PhoneFile


def files(*rels: str) -> list[PhoneFile]:
    return [PhoneFile(r, 100) for r in rels]


LIBRARY = files(
    "AA/Album/01 - Tum Hi Ho.opus",
    "AA/Album/02 - Other.opus",
    "AA/Album/Folder.jpg",
    "Bee/Second/01 - Song.flac",
)


def test_songs_lists_only_audio_files():
    assert [f.rel for f in manage.songs(LIBRARY)] == [
        "AA/Album/01 - Tum Hi Ho.opus",
        "AA/Album/02 - Other.opus",
        "Bee/Second/01 - Song.flac",
    ]


def test_find_matches_any_part_of_the_path_ignoring_case():
    assert [f.rel for f in manage.find(LIBRARY, "tum hi")] == ["AA/Album/01 - Tum Hi Ho.opus"]
    assert len(manage.find(LIBRARY, "aa")) == 2
    assert manage.find(LIBRARY, "nothing") == []


def test_remove_deletes_the_songs(phone):
    phone.files = {f.rel: b"x" for f in LIBRARY}

    removed = manage.remove(["AA/Album/01 - Tum Hi Ho.opus"], phone)

    assert removed == ["AA/Album/01 - Tum Hi Ho.opus"]
    assert "AA/Album/01 - Tum Hi Ho.opus" not in phone.files
    assert "AA/Album/Folder.jpg" in phone.files


def test_the_cover_goes_when_the_last_song_of_the_album_does(phone):
    phone.files = {f.rel: b"x" for f in LIBRARY}

    manage.remove(["AA/Album/01 - Tum Hi Ho.opus", "AA/Album/02 - Other.opus"], phone)

    assert "AA/Album/Folder.jpg" not in phone.files
    assert "Bee/Second/01 - Song.flac" in phone.files


def test_removing_an_unknown_path_changes_nothing(phone):
    phone.files = {f.rel: b"x" for f in LIBRARY}

    assert manage.remove(["nope.opus"], phone) == []
    assert len(phone.files) == 4


def test_status_counts_songs_space_and_flac(phone):
    phone.files = {
        "AA/Album/01 - A.opus": b"x" * 10,
        "AA/Album/02 - B.flac": b"x" * 30,
        "AA/Album/Folder.jpg": b"x" * 5,
    }
    phone.free = 5_000

    st = manage.status(phone)

    assert st.songs == 2
    assert st.total_bytes == 40
    assert st.flac_songs == 1
    assert st.flac_bytes == 30
    assert st.free_bytes == 5_000
