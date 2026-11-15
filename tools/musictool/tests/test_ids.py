from musictool.ids import song_id, album_id


def test_song_id_is_first_16_hex_of_sha1(tmp_path):
    f = tmp_path / "a.bin"; f.write_bytes(b"hello")
    assert song_id(f) == "aaf4c61ddcc5e8a2"

def test_album_id_ignores_case_and_is_16_hex():
    assert album_id("Artist", "Album") == album_id("ARTIST", "album")
    assert len(album_id("Artist", "Album")) == 16
    assert album_id("Artist", "Album") != album_id("Artist", "Other")
