import json
import re

import pytest

from musictool.catalog import Catalog
from conftest import make_song


def test_empty_catalog_round_trips():
    c = Catalog.from_json(Catalog().to_json())
    assert c.version == 0 and c.songs == []

def test_json_uses_spec_field_names():
    c = Catalog(); c.add(make_song(id="9f2c1a7b3d4e5f60", sizeBytes=7_000_000))
    data = json.loads(c.to_json())
    assert set(data) == {"version", "updatedAt", "songs"}
    assert set(data["songs"][0]) == {"id","title","artist","album","albumArtist","track",
        "disc","year","durationMs","sizeBytes","format","file","cover"}

def test_non_ascii_text_survives_round_trip():
    c = Catalog(); c.add(make_song(title="तुम ही हो"))
    assert Catalog.from_json(c.to_json()).songs[0].title == "तुम ही हो"

def test_bump_increments_version_by_one_and_stamps_utc():
    c = Catalog(version=41); c.bump()
    assert c.version == 42
    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", c.updatedAt)

def test_add_has_remove_and_total_bytes():
    c = Catalog(); c.add(make_song(id="a"*16, sizeBytes=5)); c.add(make_song(id="b"*16, sizeBytes=7))
    assert c.has("a"*16) and c.total_bytes() == 12
    assert c.remove("a"*16).id == "a"*16 and not c.has("a"*16)

def test_remove_unknown_id_raises_keyerror():
    with pytest.raises(KeyError): Catalog().remove("nope")
