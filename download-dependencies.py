"""Fetch the upstream Android runtime dependencies used by Foldable RDP."""
import hashlib
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent
URL = "https://github.com/iiordanov/remote-desktop-clients/releases/download/dependencies/remote-desktop-clients-libs-17.tar.gz"
SHA256 = "ec916db7e3d89a112ef4b9167e6a3ebf82d52f918de2e80dc9a6c8e56e51010e"
PREFIXES = (
    "remoteClientLib/src/main/jniLibs/",
    "remoteClientLib/jni/libs/deps/FreeRDP/client/Android/Studio/freeRDPCore/src/main/",
)


def main():
    with tempfile.TemporaryDirectory() as directory:
        archive = Path(directory) / "dependencies.tar.gz"
        digest = hashlib.sha256()
        with urllib.request.urlopen(URL, timeout=60) as response, archive.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != SHA256:
            raise ValueError("The dependency archive does not match the expected SHA-256 digest")

        with tarfile.open(archive, "r:gz") as bundle:
            for member in bundle:
                if not member.isfile() or not member.name.startswith(PREFIXES):
                    continue
                path = (ROOT / member.name).resolve()
                if not any(path.is_relative_to(ROOT / prefix) for prefix in PREFIXES):
                    raise ValueError("Invalid path in the dependency archive")
                path.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as source, path.open("wb") as output:
                    shutil.copyfileobj(source, output)


if __name__ == "__main__":
    main()
