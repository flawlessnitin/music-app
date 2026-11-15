import io
import sys

from conftest import catalog_of, only_song
from musictool.cli import main
from musictool.ids import song_id


def test_add_then_list_and_status(audio, fake, capsys):
    assert main(["add", str(audio["tagged_flac"])], storage=fake) == 0
    main(["list"], storage=fake); out = capsys.readouterr().out
    assert "Artist - Song A" in out and "[opus]" in out
    main(["status"], storage=fake); assert "1 song" in capsys.readouterr().out


def test_add_lossless_flag(audio, fake):
    main(["add", "--lossless", str(audio["tagged_flac"])], storage=fake)
    assert only_song(fake).format == "flac"


def test_add_reports_skipped_and_missing_tags(audio, fake, capsys):
    main(["add", str(audio["not_audio"]), str(audio["untagged_wav"])], storage=fake)
    out = capsys.readouterr().out
    assert "notes.txt" in out and "my track.wav" in out


def test_add_exits_1_when_an_upload_fails(audio, fake):
    fake.fail_on = {f"songs/{song_id(audio['mp3'])}.mp3"}
    assert main(["add", str(audio["mp3"])], storage=fake) == 1


def test_remove_with_several_matches_needs_all(stocked, capsys):
    before = len(catalog_of(stocked).songs)
    assert main(["remove", "artist"], storage=stocked) == 1
    assert len(catalog_of(stocked).songs) == before
    assert main(["remove", "artist", "--all"], storage=stocked) == 0
    assert catalog_of(stocked).songs == []


def test_remove_no_match_exits_1(fake):
    assert main(["remove", "nothing"], storage=fake) == 1


def test_non_latin_title_prints_on_a_cp1252_console(audio, fake, monkeypatch):
    monkeypatch.setattr(sys, "stdout", io.TextIOWrapper(io.BytesIO(), encoding="cp1252"))
    assert main(["add", str(audio["hindi_flac"])], storage=fake) == 0


def test_missing_config_gives_a_message_not_a_traceback(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("MUSICTOOL_CONFIG", str(tmp_path / "none.toml"))
    assert main(["status"]) == 2
    err = capsys.readouterr().err
    assert "none.toml" in err and "key_id" in err and "Traceback" not in err


def test_missing_ffmpeg_gives_a_message(audio, fake, monkeypatch, capsys):
    monkeypatch.setattr("musictool.media.shutil.which", lambda _: None)
    assert main(["add", str(audio["mp3"])], storage=fake) == 2
    assert "ffmpeg" in capsys.readouterr().err


def test_config_saved_with_a_byte_order_mark_still_loads(tmp_path):
    from musictool import config
    f = tmp_path / "config.toml"
    f.write_text('key_id = "a"\napplication_key = "b"\nbucket = "c"\n', encoding="utf-8-sig")
    assert config.load(f).bucket == "c"


def test_config_in_the_project_folder_is_used_before_the_home_one(tmp_path, monkeypatch):
    from musictool import config
    monkeypatch.delenv("MUSICTOOL_CONFIG", raising=False)
    project, home = tmp_path / "project.toml", tmp_path / "home.toml"
    home.write_text('key_id = "h"\napplication_key = "h"\nbucket = "home"\n', encoding="utf-8")
    monkeypatch.setattr(config, "PROJECT_CONFIG", project)
    monkeypatch.setattr(config, "HOME_CONFIG", home)
    assert config.load().bucket == "home"
    project.write_text('key_id = "p"\napplication_key = "p"\nbucket = "project"\n', encoding="utf-8")
    assert config.load().bucket == "project"
