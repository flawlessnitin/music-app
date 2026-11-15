import shutil

from musictool.ingest import add


def test_lossless_source_becomes_opus_with_its_cover(audio, phone):
    report = add([audio["tagged_flac"]], phone)

    assert [a.rel for a in report.added] == ["AA/Album/03 - Song A.opus"]
    assert report.added[0].format == "opus"
    assert "AA/Album/Folder.jpg" in phone.files
    assert phone.files["AA/Album/03 - Song A.opus"]


def test_keep_lossless_keeps_flac(audio, phone):
    report = add([audio["tagged_flac"]], phone, keep_lossless=True)

    assert [a.rel for a in report.added] == ["AA/Album/03 - Song A.flac"]
    assert report.added[0].format == "flac"


def test_a_lossy_file_is_copied_untouched(audio, phone):
    report = add([audio["mp3"]], phone)

    rel = report.added[0].rel
    assert rel.endswith(".mp3")
    assert phone.files[rel] == audio["mp3"].read_bytes()


def test_files_without_tags_are_added_and_reported(audio, phone):
    report = add([audio["untagged_wav"]], phone)

    assert len(report.added) == 1
    assert report.missing_tags == [audio["untagged_wav"]]
    assert report.added[0].rel.startswith("Unknown/Unknown/")


def test_adding_the_same_song_twice_sends_it_once(audio, phone):
    add([audio["mp3"]], phone)
    pushed = len(phone.pushed)

    report = add([audio["mp3"]], phone)

    assert report.added == []
    assert report.already_present == [audio["mp3"]]
    assert len(phone.pushed) == pushed


def test_non_audio_and_video_are_skipped_with_a_reason(audio, phone):
    report = add([audio["not_audio"], audio["video"], audio["truncated_flac"]], phone)

    assert report.added == []
    assert {p.name for p, _ in report.skipped} == {"notes.txt", "clip.mp4", "truncated.flac"}
    assert phone.files == {}


def test_a_folder_is_walked_and_pictures_and_notes_are_ignored(audio, phone, tmp_path):
    album = tmp_path / "mix"
    album.mkdir()
    shutil.copy(audio["mp3"], album / "one.mp3")
    shutil.copy(audio["m4a"], album / "two.m4a")
    (album / "notes.txt").write_text("x")
    (album / "Folder.jpg").write_bytes(b"jpg")

    report = add([album], phone)

    assert len(report.added) == 2
    assert report.skipped == []


def test_a_folder_jpg_next_to_songs_becomes_the_cover(audio, phone, tmp_path):
    album = tmp_path / "mix"
    album.mkdir()
    shutil.copy(audio["mp3"], album / "one.mp3")
    (album / "Folder.jpg").write_bytes(b"cover-bytes")

    add([album], phone)

    assert phone.files["Unknown/Unknown/Folder.jpg"] == b"cover-bytes"


def test_a_cover_already_on_the_phone_is_not_sent_again(audio, phone):
    add([audio["tagged_flac"]], phone)
    covers = [r for r in phone.pushed if r.endswith("Folder.jpg")]
    assert len(covers) == 1

    add([audio["tagged_flac"]], phone, keep_lossless=True)

    assert len([r for r in phone.pushed if r.endswith("Folder.jpg")]) == 1


def test_a_song_that_does_not_fit_is_refused(audio, phone):
    phone.free = 100

    report = add([audio["mp3"]], phone)

    assert report.added == []
    assert report.no_space == [audio["mp3"]]
    assert phone.files == {}


def test_a_failed_push_is_reported_and_stops_the_run(audio, phone):
    # Files go in name order, so song.m4a is tried first and song.mp3 is never reached.
    phone.fail_on = {"Unknown/Unknown/song.m4a"}

    report = add([audio["mp3"], audio["m4a"]], phone)

    assert [p.name for p, _ in report.failed] == ["song.m4a"]
    assert report.added == []
    assert phone.files == {}


def test_progress_is_reported_per_song(audio, phone):
    seen: list[str] = []

    add([audio["mp3"]], phone, on_progress=seen.append)

    assert len(seen) == 1 and "song" in seen[0]


def test_non_latin_names_work(audio, phone):
    report = add([audio["hindi_flac"]], phone)

    assert report.added[0].rel.endswith("तुम ही हो.opus")
