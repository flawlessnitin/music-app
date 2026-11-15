import io

from musictool.cli import main
from musictool.phone import PhoneError


def test_add_then_list_and_status(audio, phone, capsys):
    assert main(["add", str(audio["tagged_flac"])], phone=phone) == 0
    capsys.readouterr()

    main(["list"], phone=phone)
    out = capsys.readouterr().out
    assert "AA/Album/03 - Song A.opus" in out

    main(["status"], phone=phone)
    assert "1 song" in capsys.readouterr().out


def test_add_opens_the_app_so_it_rescans(audio, phone):
    main(["add", str(audio["mp3"])], phone=phone)

    assert phone.opened == 1


def test_adding_nothing_new_does_not_open_the_app(audio, phone):
    main(["add", str(audio["mp3"])], phone=phone)
    phone.opened = 0

    main(["add", str(audio["mp3"])], phone=phone)

    assert phone.opened == 0


def test_add_lossless_flag(audio, phone):
    main(["add", "--lossless", str(audio["tagged_flac"])], phone=phone)

    assert any(r.endswith(".flac") for r in phone.files)


def test_add_reports_skipped_files(audio, phone, capsys):
    main(["add", str(audio["not_audio"]), str(audio["untagged_wav"])], phone=phone)

    out = capsys.readouterr().out
    assert "notes.txt" in out and "my track.wav" in out


def test_add_exits_1_when_a_push_fails(audio, phone):
    phone.fail_on = {"Unknown/Unknown/song.mp3"}

    assert main(["add", str(audio["mp3"])], phone=phone) == 1


def test_remove_with_several_matches_needs_all(audio, phone, capsys):
    main(["add", str(audio["mp3"]), str(audio["m4a"])], phone=phone)
    before = len(phone.files)
    capsys.readouterr()

    assert main(["remove", "unknown"], phone=phone) == 1
    assert len(phone.files) == before

    assert main(["remove", "unknown", "--all"], phone=phone) == 0
    assert not any(r.endswith((".mp3", ".m4a")) for r in phone.files)


def test_remove_with_one_match_works_and_opens_the_app(audio, phone):
    main(["add", str(audio["mp3"])], phone=phone)
    phone.opened = 0

    assert main(["remove", "song.mp3"], phone=phone) == 0
    assert phone.files == {}
    assert phone.opened == 1


def test_remove_no_match_exits_1(phone):
    assert main(["remove", "zzz"], phone=phone) == 1


def test_a_phone_problem_is_a_clear_error_not_a_crash(audio, capsys):
    class NoPhone:
        def list_files(self):
            raise PhoneError("No phone is connected.")

    assert main(["list"], phone=NoPhone()) == 2
    assert "No phone is connected." in capsys.readouterr().err


def test_unicode_output_does_not_crash_on_a_narrow_console(audio, phone, monkeypatch):
    main(["add", str(audio["hindi_flac"])], phone=phone)
    narrow = io.TextIOWrapper(io.BytesIO(), encoding="cp1252", errors="strict")
    monkeypatch.setattr("sys.stdout", narrow)

    assert main(["list"], phone=phone) == 0
