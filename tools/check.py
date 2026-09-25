"""Local/CI verification. Does not launch Minecraft, install a mod, or contact game services."""

import hashlib
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
        if "${" in metadata["version"]:
            raise ValueError("Unexpanded mod version")
        expected = {"fabricloader", "minecraft", "java", "fabric-api", "fabric-language-kotlin"}
        if set(metadata["depends"]) != expected:
            raise ValueError("Unexpected required dependency; review this policy explicitly")
        if metadata.get("mixins") or metadata.get("jars"):
            raise ValueError("Foundation must not add mixins or embedded dependencies")
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
