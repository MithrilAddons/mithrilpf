"""Local/CI verification. Does not launch Minecraft, install a mod, or contact game services."""

import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WRAPPER_SHA256 = "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def verify_jar(path):
    with zipfile.ZipFile(path) as jar:
        if jar.testzip() is not None:
            raise ValueError("Corrupt JAR entry")
        metadata = json.loads(jar.read("fabric.mod.json"), object_pairs_hook=unique_object)
        if metadata["id"] != "mithrilpf" or metadata["environment"] != "client":
            raise ValueError("Incorrect mod identity")
        if metadata.get("license") != "MIT":
            raise ValueError("Incorrect project license")
        if metadata.get("icon") != "assets/mithrilpf/icon.png":
            raise ValueError("Missing mod icon reference")
        if jar.read(metadata["icon"]) != (ROOT / "src/main/resources" / metadata["icon"]).read_bytes():
            raise ValueError("Packaged mod icon differs from the source asset")
        if jar.read("META-INF/licenses/LICENSE_mithrilpf") != (ROOT / "LICENSE").read_bytes():
            raise ValueError("Packaged project license is missing or differs from LICENSE")
        if "${" in metadata["version"]:
            raise ValueError("Unexpanded mod version")
        expected_version = next(line.split("=", 1)[1] for line in
                                (ROOT / "gradle.properties").read_text().splitlines()
                                if line.startswith("mod_version="))
        if metadata["version"] != expected_version or path.name != f"mithrilpf-{expected_version}.jar":
            raise ValueError("Packaged version or filename does not match gradle.properties")
        distribution = dict(line.split("=", 1) for line in
                            jar.read("assets/mithrilpf/build.properties").decode().splitlines()
                            if line and not line.startswith("#"))
        release_build = (os.environ.get("GITHUB_ACTIONS") == "true"
                         and os.environ.get("GITHUB_REPOSITORY") == "MithrilAddons/mithrilpf"
                         and os.environ.get("GITHUB_EVENT_NAME") == "push"
                         and os.environ.get("GITHUB_REF_TYPE") == "tag"
                         and os.environ.get("GITHUB_REF_NAME") == f"v{expected_version}"
                         and os.environ.get("GITHUB_WORKFLOW") == "Release")
        if distribution != {"version": expected_version,
                            "officialRelease": str(release_build).lower()}:
            raise ValueError("Incorrect updater distribution marker")
        expected = {"fabricloader", "minecraft", "java", "fabric-api", "fabric-language-kotlin"}
        if set(metadata["depends"]) != expected:
            raise ValueError("Unexpected required dependency; review this policy explicitly")
        if metadata.get("mixins") != ["mithrilpf.mixins.json"]:
            raise ValueError("Unexpected mixin configuration or embedded dependency")
        if metadata.get("jars") != [{"file": "META-INF/jars/qrcodegen-1.8.0.jar"}]:
            raise ValueError("Only the reviewed local QR generator may be embedded")
        jar.read("META-INF/jars/qrcodegen-1.8.0.jar")
        jar.read("META-INF/licenses/LICENSE_qrcodegen")
        with zipfile.ZipFile(io.BytesIO(jar.read("assets/mithrilpf/updater.jar"))) as helper:
            helper.read("dev/mithril/mithrilpf/update/UpdateInstaller.class")
            if helper.read("META-INF/licenses/LICENSE_mithrilpf") != (ROOT / "LICENSE").read_bytes():
                raise ValueError("Updater license mismatch")
        mixins = json.loads(jar.read("mithrilpf.mixins.json"))
        if mixins["client"] != ["DungeonConnectionMixin"]:
            raise ValueError("Unexpected packet hooks; explicit review required")
        jar.read("dev/mithril/mithrilpf/mixin/DungeonConnectionMixin.class")
        jar.read("META-INF/licenses/LICENSE_noamm")
        for entries in metadata["entrypoints"].values():
            for entry in entries:
                if entry["value"].replace(".", "/") + ".class" not in jar.namelist():
                    raise ValueError(f"Missing entrypoint: {entry['value']}")
        for name in jar.namelist():
            if name.endswith((".pem", ".key", ".log")) or name.startswith(("config/", "logs/")):
                raise ValueError(f"Unexpected private/runtime artifact: {name}")
        jar.read("assets/mithrilpf/lang/en_us.json")
    print(f"Verified packaged mod: {path.name}")


def main():
    subprocess.run([sys.executable, "tools/versioning.py"], cwd=ROOT, check=True)
    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    if hashlib.sha256(wrapper.read_bytes()).hexdigest() != WRAPPER_SHA256:
        raise ValueError("Gradle wrapper checksum mismatch")
    for path in (ROOT / "src/main/resources").rglob("*.json"):
        json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tools/tests"],
                   cwd=ROOT, check=True)
    command = [str(ROOT / "gradlew.bat")] if os.name == "nt" else ["sh", str(ROOT / "gradlew")]
    subprocess.run([*command, "build", "--console=plain"], cwd=ROOT, check=True)
    jars = [path for path in (ROOT / "build/libs").glob("*.jar")
            if not path.name.endswith("-sources.jar")]
    if len(jars) != 1:
        raise ValueError("Expected one gameplay JAR; use Gradle clean to remove stale outputs")
    verify_jar(jars[0])


if __name__ == "__main__":
    main()
