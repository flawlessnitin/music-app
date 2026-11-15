import io
from pathlib import Path
from typing import Protocol, runtime_checkable

from b2sdk.v2 import B2Api, Bucket, InMemoryAccountInfo
from b2sdk.v2.exception import B2Error, FileNotPresent


class StorageError(Exception):
    """Raised for any network or service failure."""
    pass


@runtime_checkable
class Storage(Protocol):
    def read(self, name: str) -> bytes | None:
        """Return file contents as bytes, or None if the file does not exist."""
        ...

    def size(self, name: str) -> int | None:
        """Return file size in bytes, or None if the file does not exist."""
        ...

    def put_bytes(self, name: str, data: bytes, content_type: str) -> None:
        """Upload in-memory bytes under name."""
        ...

    def put_file(self, name: str, path: Path, content_type: str) -> None:
        """Upload a file from disk under name."""
        ...

    def delete(self, name: str) -> None:
        """Remove every stored version of name; no error if absent."""
        ...


class B2Storage:
    def __init__(self, key_id: str, application_key: str, bucket: str) -> None:
        try:
            info = InMemoryAccountInfo()
            self._api = B2Api(info)
            self._api.authorize_account("production", key_id, application_key)
            self._bucket = self._api.get_bucket_by_name(bucket)
        except B2Error as e:
            raise StorageError(str(e)) from e

    @classmethod
    def _from_api(cls, api: B2Api, bucket: str | Bucket) -> "B2Storage":
        inst = cls.__new__(cls)
        inst._api = api
        try:
            if isinstance(bucket, str):
                inst._bucket = api.get_bucket_by_name(bucket)
            else:
                inst._bucket = bucket
        except B2Error as e:
            raise StorageError(str(e)) from e
        return inst

    def read(self, name: str) -> bytes | None:
        try:
            buf = io.BytesIO()
            self._bucket.download_file_by_name(name).save(buf)
            return buf.getvalue()
        except FileNotPresent:
            return None
        except B2Error as e:
            raise StorageError(str(e)) from e

    def size(self, name: str) -> int | None:
        try:
            info = self._bucket.get_file_info_by_name(name)
            return info.size
        except FileNotPresent:
            return None
        except B2Error as e:
            raise StorageError(str(e)) from e

    def put_bytes(self, name: str, data: bytes, content_type: str) -> None:
        try:
            self._bucket.upload_bytes(data, name, content_type=content_type)
        except B2Error as e:
            raise StorageError(str(e)) from e

    def put_file(self, name: str, path: Path, content_type: str) -> None:
        try:
            self._bucket.upload_local_file(
                local_file=str(path),
                file_name=name,
                content_type=content_type,
            )
        except B2Error as e:
            raise StorageError(str(e)) from e

    def delete(self, name: str) -> None:
        try:
            versions = list(self._bucket.list_file_versions(name))
            for version in versions:
                if version.file_name == name:
                    version.delete()
        except FileNotPresent:
            pass
        except B2Error as e:
            raise StorageError(str(e)) from e
