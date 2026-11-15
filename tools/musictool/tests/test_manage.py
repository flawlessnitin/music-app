import pytest

from conftest import catalog_of
from musictool.ingest import add
from musictool.manage import find, remove, status


def test_find_by_exact_id_and_by_text(catalog3):
    assert [s.id for s in find(catalog3, catalog3.songs[0].id)] == [catalog3.songs[0].id]
    assert len(find(catalog3, "ARTIST")) == 3 and find(catalog3, "zzz") == []


def test_remove_writes_catalog_before_deleting_files(stocked):
    victim = catalog_of(stocked).songs[0]
    stocked.puts.clear(); remove([victim.id], stocked)
    assert stocked.puts == ["catalog.json"] and victim.file not in stocked.files
    assert catalog_of(stocked).version == 2 and not catalog_of(stocked).has(victim.id)


def test_cover_kept_while_another_song_uses_it(stocked_same_album):
    a, b = catalog_of(stocked_same_album).songs
    remove([a.id], stocked_same_album); assert a.cover in stocked_same_album.files
    remove([b.id], stocked_same_album); assert a.cover not in stocked_same_album.files


def test_status_counts_lossless_separately(fake, audio):
    add([audio["mp3"]], fake); add([audio["tagged_flac"]], fake, keep_lossless=True)
    s = status(fake)
    assert (s.songs, s.lossless_songs, s.limit_bytes, s.version) == (2, 1, 9_500_000_000, 2)
    assert 0 < s.lossless_bytes < s.total_bytes
