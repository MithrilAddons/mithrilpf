"""Single-source mod version validation; no Git writes or publication."""

import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)\.([1-9]\d*))?")


def read_version(text):
    values = re.findall(r"^mod_version=(.+)$", text, re.MULTILINE)
    if len(values) != 1 or not VERSION.fullmatch(values[0]):
        raise ValueError("mod_version must be X.Y.Z or X.Y.Z-alpha.N/beta.N/rc.N")
    return values[0]


def validate_tag(version, tag):
    if not VERSION.fullmatch(version) or tag != f"v{version}":
        raise ValueError("Release tag must exactly match mod_version")


def main():
    version = read_version((ROOT / "gradle.properties").read_text(encoding="utf-8"))
    if os.environ.get("GITHUB_REF_TYPE") == "tag":
        validate_tag(version, os.environ["GITHUB_REF_NAME"])
        # Publishing tags must name a reviewed commit already merged into main.
        subprocess.run(["git", "merge-base", "--is-ancestor", "HEAD", "origin/main"],
                       cwd=ROOT, check=True)
    print(f"Mod version: {version}")


if __name__ == "__main__":
    main()
