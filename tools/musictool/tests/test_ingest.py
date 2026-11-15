import shutil
from pathlib import Path

import pytest

from musictool.catalog import Catalog, Song
from musictool.ids import album_id, song_id
from musictool.ingest import add
from musictool.storage import Storage


@pytest.fixture
def audio_dir(tmp_path, audio):
    d = tmp_path / "valid_audio"
    d.mkdir()
    valid_keys = ["tagged_flac", "untagged_wav", "mp3", "m4a", "hindi_flac"]
    for key in valid_keys:
        src = audio[key]
        shutil.copy2(src, d / src.name)
    return d


def only_song(storage: Storage) -> Song:
    raw = storage.read("catalog.json")
    assert raw is not None, "catalog.json not found in storage"
    cat = Catalog.from_json(raw.decode("utf-8"))
    assert len(cat.songs) == 1, f"Expected 1 song, found {len(cat.songs)}"
    return cat.songs[0]


def songs_by_format(storage: Storage) -> dict[str, Song]:
    raw = storage.read("catalog.json")
    assert raw is not None, "catalog.json not found in storage"
    cat = Catalog.from_json(raw.decode("utf-8"))
    return {s.format: s for s in cat.songs}


def test_add_folder_uploads_songs_then_catalog_last(audio_dir, fake):
    report = add([audio_dir], fake)
    assert fake.puts[-1] == "catalog.json"
    cat = Catalog.from_json(fake.files["catalog.json"].decode())
    assert cat.version == 1 and len(cat.songs) == len(report.added)
    for s in cat.songs:
        assert s.file in fake.files and s.sizeBytes == len(fake.files[s.file])


def test_lossless_becomes_opus_and_lossy_is_untouched(audio, fake):
    add([audio["tagged_flac"], audio["mp3"]], fake)
    by_title = songs_by_format(fake)
    assert by_title["opus"].file.endswith(".opus")
    assert fake.files[by_title["mp3"].file] == audio["mp3"].read_bytes()


def test_keep_lossless_stores_flac(audio, fake):
    report = add([audio["tagged_flac"]], fake, keep_lossless=True)
    assert report.added[0].format == "flac" and report.added[0].file.endswith(".flac")


def test_cover_uploaded_once_per_album(audio, fake):
    add([audio["tagged_flac"]], fake)
    song = only_song(fake)
    assert song.cover == f"covers/{album_id('AA', 'Album')}.jpg" and song.cover in fake.files


def test_song_without_cover_has_null_cover(audio, fake):
    add([audio["mp3"]], fake); assert only_song(fake).cover is None


def test_adding_twice_changes_nothing(audio, fake):
    add([audio["tagged_flac"]], fake); puts = list(fake.puts)
    report = add([audio["tagged_flac"]], fake)
    assert report.added == [] and report.already_present == [audio["tagged_flac"]]
    assert fake.puts == puts                       # no catalog rewrite, version stays 1


def test_non_audio_and_corrupt_files_are_skipped_and_the_rest_added(audio, fake):
    report = add([audio["not_audio"], audio["truncated_flac"], audio["mp3"]], fake)
    assert {p for p, _ in report.skipped} == {audio["not_audio"], audio["truncated_flac"]}
    assert len(report.added) == 1


def test_untagged_file_is_added_and_reported(audio, fake):
    report = add([audio["untagged_wav"]], fake)
    assert report.missing_tags == [audio["untagged_wav"]] and report.added[0].title == "my track"


def test_non_latin_file_is_added(audio, fake):
    assert add([audio["hindi_flac"]], fake).added[0].title == "तुम ही हो"


def test_song_over_the_limit_is_refused_not_uploaded(audio, fake, monkeypatch):
    monkeypatch.setattr("musictool.ingest.LIMIT_BYTES", 10)
    report = add([audio["mp3"]], fake)
    assert report.over_limit == [audio["mp3"]] and fake.puts == []


def test_upload_failure_keeps_finished_songs_and_rerun_completes(audio, fake):
    first, second = sorted([audio["mp3"], audio["m4a"]])
    fake.fail_on = {f"songs/{song_id(second)}{second.suffix}"}
    report = add([first, second], fake)
    assert len(report.added) == 1 and report.failed[0][0] == second
    assert Catalog.from_json(fake.files["catalog.json"].decode()).version == 1
    fake.fail_on = set(); uploads_before = list(fake.puts)
    report = add([first, second], fake)
    assert len(report.added) == 1 and report.already_present == [first]
    assert f"songs/{song_id(first)}{first.suffix}" not in fake.puts[len(uploads_before):]


def test_orphan_upload_from_interrupted_run_is_reused(audio, fake):
    name = f"songs/{song_id(audio['mp3'])}.mp3"
    fake.files[name] = audio["mp3"].read_bytes()
    add([audio["mp3"]], fake)
    assert name not in fake.puts and only_song(fake).sizeBytes == len(fake.files[name])


def test_two_songs_of_one_album_share_one_cover_upload(audio, fake, tmp_path):
    import subprocess
    second = tmp_path / "second.flac"
    subprocess.run(["ffmpeg", "-y", "-i", str(audio["tagged_flac"]), "-map", "0", "-c", "copy",
                    "-metadata", "title=Song B", str(second)], check=True, capture_output=True)
    report = add([audio["tagged_flac"], second], fake)
    assert len(report.added) == 2 and report.added[0].cover == report.added[1].cover
    assert [n for n in fake.puts if n.startswith("covers/")] == [report.added[0].cover]


def test_converted_files_are_cleaned_up_after_each_song(audio, fake, tmp_path, monkeypatch):
    monkeypatch.setattr("tempfile.tempdir", str(tmp_path))
    seen = []
    add([audio["tagged_flac"], audio["hindi_flac"]], fake,
        on_progress=lambda _: seen.append(len(list(tmp_path.rglob("*.opus")))))
    assert seen == [1, 1]
