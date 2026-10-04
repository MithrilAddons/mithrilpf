"""Attach release-build test results to reviewed notes as hidden metadata."""

import hashlib
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def checks(reports, jar, version, run_id):
    suites = list((reports / "test-results/test").glob("TEST-*.xml"))
    if not suites or not run_id.isdecimal() or int(run_id) <= 0:
        raise ValueError("Missing test reports or workflow run")
    tests = 0
    for path in suites:
        suite = ET.parse(path).getroot()
        if any(int(suite.get(key, "0")) for key in ("failures", "errors")):
            raise ValueError("Release tests failed")
        tests += int(suite.attrib["tests"]) - int(suite.get("skipped", "0"))
    if tests <= 0:
        raise ValueError("No passing tests")
    report = ET.parse(reports / "reports/jacoco/test/jacocoTestReport.xml").getroot()
    value = dict(version=1, mod_version=version, run_id=int(run_id), tests=tests,
                 sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
    for kind in ("LINE", "BRANCH"):
        counter = report.find(f"counter[@type='{kind}']")
        covered, missed = int(counter.attrib["covered"]), int(counter.attrib["missed"])
        if covered < 0 or missed < 0 or covered + missed == 0:
            raise ValueError("Invalid coverage counters")
        value[kind.lower()] = dict(covered=covered, total=covered + missed)
    return value


def render(notes, results):
    if not notes.strip() or len(notes) > 2600 or "<!--" in notes:
        raise ValueError("Release notes must be reviewed text under 2600 characters")
    return notes.rstrip() + "\n\n<!-- mithrilpf-checks:" + json.dumps(results, separators=(",", ":")) + " -->\n"


def main():
    version = os.environ["RELEASE_TAG"].removeprefix("v")
    results = checks(Path("release-reports"), Path(f"release/mithrilpf-{version}.jar"),
                     version, os.environ["GITHUB_RUN_ID"])
    notes = Path(f"docs/releases/{version}.md").read_text(encoding="utf-8")
    Path("release/notes.md").write_text(render(notes, results), encoding="utf-8")


if __name__ == "__main__":
    main()
