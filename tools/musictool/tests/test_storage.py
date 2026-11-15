from pathlib import Path

import pytest
from b2sdk.v2 import B2Api, B2HttpApiConfig, RawSimulator, StubAccountInfo

from conftest import FakeStorage
from musictool.storage import B2Storage


@pytest.fixture(params=["fake", "b2"])
def store(request):
    if request.param == "fake":
        return FakeStorage()
    elif request.param == "b2":
        api_config = B2HttpApiConfig()
        api_config.raw_api_class = RawSimulator
        info = StubAccountInfo()
        api = B2Api(info, api_config=api_config)
        account_id, master_key = api.raw_api.create_account()
        api.authorize_account("production", account_id, master_key)
        api.create_bucket("test-bucket", "allPrivate")
        return B2Storage._from_api(api, "test-bucket")


def test_read_and_size_of_missing_file_are_none(store):
    assert store.read("x") is None and store.size("x") is None


def test_put_bytes_then_read_and_size(store):
    store.put_bytes("catalog.json", b"{}", "application/json")
    assert store.read("catalog.json") == b"{}" and store.size("catalog.json") == 2


def test_put_file(store, tmp_path):
    f = tmp_path / "s.opus"
    f.write_bytes(b"abc")
    store.put_file("songs/a.opus", f, "audio/ogg")
    assert store.size("songs/a.opus") == 3


def test_delete_removes_every_version(store):
    store.put_bytes("k", b"1", "text/plain")
    store.put_bytes("k", b"22", "text/plain")
    store.delete("k")
    assert store.read("k") is None


def test_delete_missing_is_silent(store):
    store.delete("never-existed")


def test_b2_keeps_only_the_newest_version_of_a_rewritten_file():
    api_config = B2HttpApiConfig()
    api_config.raw_api_class = RawSimulator
    api = B2Api(StubAccountInfo(), api_config=api_config)
    account_id, master_key = api.raw_api.create_account()
    api.authorize_account("production", account_id, master_key)
    api.create_bucket("test-bucket", "allPrivate")
    store = B2Storage._from_api(api, "test-bucket")
    for body in (b"1", b"22", b"333"):
        store.put_bytes("catalog.json", body, "application/json")
    assert store.read("catalog.json") == b"333"
    assert len(list(store._bucket.list_file_versions("catalog.json"))) == 1
