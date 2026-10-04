import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.release_notes import checks, main, render


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.reports = self.root / "release-reports"
        self.suite = self.reports / "test-results/test/TEST-synthetic.xml"
        self.suite.parent.mkdir(parents=True)
        self.suite.write_text('<testsuite tests="5" skipped="1" failures="0" errors="0"/>')
        self.coverage = self.reports / "reports/jacoco/test/jacocoTestReport.xml"
        self.coverage.parent.mkdir(parents=True)
        self.coverage.write_text('<report><counter type="LINE" covered="8" missed="2"/>'
                                 '<counter type="BRANCH" covered="3" missed="1"/></report>')
        self.jar = self.root / "release/mithrilpf-1.2.3.jar"
        self.jar.parent.mkdir()
        self.jar.write_bytes(b"synthetic artifact")
        (self.root / "gradle.properties").write_text("mod_version=1.2.3\n")

    def test_render_reads_actual_reports_and_binds_artifact(self):
        value = checks(self.reports, self.jar, "1.2.3", "123")
        self.assertEqual(4, value["tests"])
        self.assertEqual({"covered": 8, "total": 10}, value["line"])
        self.assertEqual(64, len(value["sha256"]))
        notes = render("Summary\n", value)
        self.assertTrue(notes.startswith("Summary\n\n<!-- mithrilpf-checks:"))
        self.assertEqual(value, json.loads(notes.split("mithrilpf-checks:")[1].split(" -->")[0]))

    def test_failed_empty_or_missing_tests_and_invalid_coverage_fail(self):
        for suite in ('<testsuite tests="1" failures="1"/>', '<testsuite tests="0"/>'):
            self.suite.write_text(suite)
            with self.assertRaises(ValueError):
                checks(self.reports, self.jar, "1.2.3", "123")
        self.suite.write_text('<testsuite tests="1"/>')
        self.coverage.write_text('<report><counter type="LINE" covered="0" missed="0"/></report>')
        with self.assertRaises(ValueError):
            checks(self.reports, self.jar, "1.2.3", "123")
        for run in ("", "0", "invalid"):
            with self.assertRaises(ValueError):
                checks(self.reports, self.jar, "1.2.3", run)
        self.suite.unlink()
        with self.assertRaises(ValueError):
            checks(self.reports, self.jar, "1.2.3", "123")

    def test_notes_reject_empty_oversized_or_preexisting_metadata(self):
        for notes in ("", " ", "x" * 2601, "<!-- fake -->"):
            with self.assertRaises(ValueError):
                render(notes, {})

    def test_workflow_entrypoint_writes_notes(self):
        notes = self.root / "docs/releases/1.2.3.md"
        notes.parent.mkdir(parents=True)
        notes.write_text("Synthetic release")
        previous = Path.cwd()
        try:
            os.chdir(self.root)
            with patch.dict(os.environ, RELEASE_TAG="v1.2.3", GITHUB_RUN_ID="123"):
                main()
            with patch.dict(os.environ, RELEASE_TAG="v../../private", GITHUB_RUN_ID="123"):
                with self.assertRaises(ValueError):
                    main()
        finally:
            os.chdir(previous)
        self.assertIn("mithrilpf-checks:", (self.root / "release/notes.md").read_text())
