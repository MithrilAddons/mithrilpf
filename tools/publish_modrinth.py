"""Mirror a published GitHub gameplay JAR to Modrinth without rebuilding it."""

import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

from tools.versioning import VERSION

REPOSITORY = "MithrilAddons/mithrilpf"
API = "https://api.modrinth.com/v2"
DEPENDENCIES = [
    {"project_id": "P7dR8mSH", "dependency_type": "required"},  # Fabric API
    {"project_id": "Ha28R6CL", "dependency_type": "required"},  # Fabric Language Kotlin
    {"project_id": "mOgUt4GM", "dependency_type": "optional"},  # Mod Menu
]


def release_version(tag):
    version = tag.removeprefix("v")
    match = VERSION.fullmatch(version)
    if not tag.startswith("v") or not match:
        raise ValueError("Expected a release tag vX.Y.Z[-alpha.N/beta.N/rc.N]")
    channel = match.group(4)
    return version, "alpha" if channel == "alpha" else "beta" if channel else "release"


def prepare(release, tag, artifact, checksum):
    version, channel = release_version(tag)
    filename = f"mithrilpf-{version}.jar"
    if release["isDraft"] or release["tagName"] != tag:
        raise ValueError("Only published, matching GitHub releases can be uploaded")
    if release["isPrerelease"] != (channel != "release"):
        raise ValueError("GitHub prerelease flag disagrees with the version tag")
    digest = hashlib.sha256(artifact).hexdigest()
    if checksum.strip() != f"{digest}  {filename}":
        raise ValueError("Release JAR does not match SHA256SUMS")
    with zipfile.ZipFile(io.BytesIO(artifact)) as jar:
        metadata = json.loads(jar.read("fabric.mod.json"))
        marker = dict(line.split("=", 1) for line in
                      jar.read("assets/mithrilpf/build.properties").decode().splitlines()
                      if line and not line.startswith("#"))
    if (metadata["id"] != "mithrilpf" or metadata["version"] != version
            or metadata["environment"] != "client"
            or marker != {"version": version, "officialRelease": "true"}):
        raise ValueError("Expected the matching official client release JAR")
    minecraft = metadata["depends"]["minecraft"]
    if not isinstance(minecraft, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", minecraft):
        raise ValueError("An exact Minecraft version is required for Modrinth")
    return {
        "name": release["name"] or f"MithrilPF {version}",
        "version_number": version,
        "version_type": channel,
        "changelog": release["body"] or "",
        "game_versions": [minecraft],
        "loaders": ["fabric"],
        "dependencies": DEPENDENCIES,
        "featured": channel == "release",
        "status": "listed",
        "file_parts": ["file"],
        "primary_file": "file",
    }


def request(path, token, data=None, content_type=None):
    headers = {"Authorization": token,
               "User-Agent": f"{REPOSITORY}/release-publisher (https://github.com/{REPOSITORY})"}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(API + path, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Modrinth returned HTTP {error.code}; check project access and token scopes") from None


def multipart(payload, filename, artifact):
    boundary = uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\n'
            'Content-Type: application/json\r\n\r\n').encode()
    body += json.dumps(payload).encode() + b"\r\n"
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
             'Content-Type: application/java-archive\r\n\r\n').encode()
    body += artifact + f"\r\n--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def already_uploaded(versions, payload, artifact):
    matches = [v for v in versions if v["version_number"] == payload["version_number"]]
    if not matches:
        return False
    expected_hash = hashlib.sha512(artifact).hexdigest()
    if (len(matches) != 1 or matches[0]["version_type"] != payload["version_type"]
            or matches[0]["game_versions"] != payload["game_versions"]
            or matches[0]["loaders"] != payload["loaders"]
            or not any(f["hashes"].get("sha512") == expected_hash for f in matches[0]["files"])):
        raise ValueError("A conflicting Modrinth version already exists; nothing was overwritten")
    return True


def main():
    tag = os.environ["RELEASE_TAG"]
    version, _ = release_version(tag)
    token = os.environ.get("MODRINTH_TOKEN", "")
    if not token:
        raise ValueError("Set the MODRINTH_TOKEN repository Actions secret")
    project = urllib.parse.quote(os.environ.get("MODRINTH_PROJECT_ID", "mithrilpf"), safe="")
    gh = ["gh", "release"]
    release = json.loads(subprocess.check_output(
        [*gh, "view", tag, "--repo", REPOSITORY,
         "--json", "tagName,isDraft,isPrerelease,name,body"], encoding="utf-8"))
    filename = f"mithrilpf-{version}.jar"
    with tempfile.TemporaryDirectory() as directory:
        subprocess.run([*gh, "download", tag, "--repo", REPOSITORY,
                        "--pattern", filename, "--pattern", "SHA256SUMS", "--dir", directory], check=True)
        artifact = (Path(directory) / filename).read_bytes()
        payload = prepare(release, tag, artifact, (Path(directory) / "SHA256SUMS").read_text())
    payload["project_id"] = request(f"/project/{project}", token)["id"]
    versions = request(f"/project/{project}/version", token)
    if already_uploaded(versions, payload, artifact):
        print(f"Modrinth already contains the matching {version}; no upload needed")
        return
    body, content_type = multipart(payload, filename, artifact)
    result = request("/version", token, body, content_type)
    print(f"Published {version} ({payload['version_type']}): https://modrinth.com/mod/{project}/version/{result['id']}")


if __name__ == "__main__":
    main()
