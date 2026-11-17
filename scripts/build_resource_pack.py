#!/usr/bin/env python3
"""Build the independently downloadable Runify 1.21.11 resource pack.

QualityArmory textures/models are maintained by ZombieStriker and contributors.
Runify retains and credits those assets; it does not claim them as its own.
The upstream archive is converted to Minecraft 1.21.11's pack metadata format.
"""
import io
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

UPSTREAM = (
    "https://github.com/ZombieStriker/QualityArmory-Resourcepack/"
    "releases/download/latest/QualityArmory-21.zip"
)
FALLBACK = (
    "https://codeload.github.com/ZombieStriker/"
    "QualityArmory-Resourcepack/zip/refs/heads/master"
)
OUTPUT = Path("dist/Runify-ResourcePack-1.21.11.zip")
HEADERS = {"User-Agent": "RunifyResourcepackBuilder/1.0 (https://github.com/darkness22s/runify)"}


def download(url: str) -> bytes:
    request = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(request, timeout=45) as response:
        result = response.read(30_000_000)
    if len(result) < 100_000:
        raise ValueError("Downloaded resource pack is suspiciously small")
    return result


def main() -> None:
    if len(sys.argv) > 1:
        source = Path(sys.argv[1]).read_bytes()
    else:
        try:
            source = download(UPSTREAM)
        except Exception as exc:
            print(f"Upstream release unavailable ({exc}); trying upstream source", file=sys.stderr)
            source = download(FALLBACK)

    with zipfile.ZipFile(io.BytesIO(source)) as upstream:
        files = {}
        for entry in upstream.infolist():
            if entry.is_dir():
                continue
            name = entry.filename.replace("\\", "/")
            if name.startswith("/") or ".." in name.split("/"):
                raise ValueError(f"Unsafe resource pack path: {name}")
            root = name.split("/", 1)
            if len(root) > 1 and root[0].startswith("QualityArmory-Resourcepack-"):
                name = root[1]
            if not name.startswith("assets/") and name not in ("pack.png", "pack.mcmeta"):
                continue
            files[name] = upstream.read(entry)

    models = [key for key in files if key.startswith("assets/qualityarmory/models/gun/") and key.endswith(".json")]
    textures = [key for key in files if "/textures/" in key and key.endswith(".png")]
    if len(models) < 25 or len(textures) < 20:
        raise ValueError(f"Pack is missing firearm models/textures: {len(models)} models, {len(textures)} textures")
    item_model = "assets/minecraft/items/crossbow.json"
    if item_model not in files:
        raise ValueError("Pack has no 1.21.x item component model dispatch for guns")
    dispatch = json.loads(files[item_model])
    if not dispatch.get("model"):
        raise ValueError("Gun item dispatch JSON is empty")

    metadata = {
        "pack": {
            "min_format": [75, 0],
            "max_format": [75, 0],
            "description": "Runify 1.21.11 weapons - QualityArmory art by ZombieStriker and contributors",
        }
    }
    files["pack.mcmeta"] = (json.dumps(metadata, indent=2) + "\n").encode("utf-8")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUTPUT, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for name, data in sorted(files.items()):
            target.writestr(name, data)
    with zipfile.ZipFile(OUTPUT) as verify:
        if verify.testzip() is not None:
            raise ValueError("Bad ZIP CRC in built resource pack")
        config = json.loads(verify.read("pack.mcmeta"))
        assert config["pack"]["min_format"] == [75, 0]
        assert verify.read("assets/minecraft/items/crossbow.json")
    print(f"Resource pack OK: {OUTPUT}, {len(models)} gun models, {len(textures)} textures, {len(files)} files")


if __name__ == "__main__":
    main()
