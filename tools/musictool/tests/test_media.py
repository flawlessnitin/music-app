import json
import subprocess

import pytest

from musictool.media import (
    NotAudio,
    Plan,
    extract_cover,
    plan_encoding,
    prepare,
    probe,
)


def image_width(path):
    """Ask ffprobe for the width of an image file."""
    result = subprocess.run(
        [
            "ffprobe", "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width",
            "-of", "json",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    data = json.loads(result.stdout)
    return data["streams"][0]["width"]


def test_probe_reads_tags_and_tidies_numbers(audio):
    p = probe(audio["tagged_flac"])
    assert (p.codec, p.title, p.artist, p.album, p.album_artist) == ("flac","Song A","Artist","Album","AA")
    assert (p.track, p.disc, p.year) == (3, 1, 2019)
    assert 900 <= p.duration_ms <= 1100 and p.has_cover and not p.tags_missing


def test_probe_falls_back_when_untagged(audio):
    p = probe(audio["untagged_wav"])
    assert (p.title, p.artist, p.album, p.album_artist) == ("my track","Unknown","Unknown","Unknown")
    assert p.tags_missing and (p.track, p.disc, p.year) == (None, None, None)


def test_probe_keeps_non_latin_names(audio):
    assert probe(audio["hindi_flac"]).title == "तुम ही हो"


@pytest.mark.parametrize("key", ["not_audio", "truncated_flac"])
def test_probe_rejects_non_audio(audio, key):
    with pytest.raises(NotAudio): probe(audio[key])


@pytest.mark.parametrize("codec,ext,keep,expected", [
    ("flac", ".flac", False, ("opus","opus",".opus")),
    ("flac", ".flac", True,  ("copy","flac",".flac")),
    ("pcm_s16le", ".wav", False, ("opus","opus",".opus")),
    ("pcm_s16le", ".wav", True,  ("flac","flac",".flac")),
    ("alac", ".m4a", True, ("flac","flac",".flac")),
    ("mp3", ".mp3", False, ("copy","mp3",".mp3")),
    ("mp3", ".mp3", True,  ("copy","mp3",".mp3")),
    ("aac", ".m4a", False, ("copy","aac",".m4a")),
    ("opus", ".opus", False, ("copy","opus",".opus")),
    ("vorbis", ".ogg", False, ("copy","vorbis",".ogg")),
])
def test_plan_encoding(codec, ext, keep, expected):
    p = plan_encoding(codec, ext, keep)
    assert (p.action, p.format, p.ext) == expected


def test_plan_encoding_rejects_unknown_codec():
    with pytest.raises(NotAudio): plan_encoding("mjpeg", ".jpg", False)


def test_prepare_opus_makes_256k_opus_with_tags(audio, tmp_path):
    out = prepare(audio["tagged_flac"], Plan("opus","opus",".opus"), tmp_path)
    p = probe(out)
    assert out.suffix == ".opus" and p.codec == "opus" and p.title == "Song A"


def test_prepare_copy_returns_the_source_untouched(audio, tmp_path):
    assert prepare(audio["mp3"], Plan("copy","mp3",".mp3"), tmp_path) == audio["mp3"]


def test_prepare_flac_packs_wav_losslessly(audio, tmp_path):
    out = prepare(audio["untagged_wav"], Plan("flac","flac",".flac"), tmp_path)
    assert probe(out).codec == "flac"


def test_extract_cover_is_500px_jpeg(audio, tmp_path):
    cover = extract_cover(audio["tagged_flac"], tmp_path)
    assert cover.suffix == ".jpg" and image_width(cover) == 500


def test_extract_cover_none_when_absent(audio, tmp_path):
    assert extract_cover(audio["mp3"], tmp_path) is None
