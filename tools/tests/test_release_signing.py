"""Exercise the JDK release signer with temporary keys and synthetic release JARs."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class ReleaseSigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.classes.cleanup)
        subprocess.run(["javac", "-d", cls.classes.name, str(ROOT / "tools/ReleaseSigning.java")],
                       check=True, capture_output=True, timeout=30)

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.private = self.root / "test.key"
        self.public = self.root / "test.pub"
        self.jar = self.root / "mithrilpf-1.0.0.jar"
        self.signature = self.root / "release.sig"
        self.run_tool("generate", self.private, self.public)
        self.write_jar()

    def run_tool(self, *args, secret=None, success=True):
        environment = os.environ.copy()
        environment.pop("RELEASE_SIGNING_KEY", None)
        if secret is not None:
            environment["RELEASE_SIGNING_KEY"] = secret
        result = subprocess.run(["java", "-cp", self.classes.name, "ReleaseSigning", *map(str, args)],
                                env=environment, capture_output=True, text=True, timeout=20)
        self.assertEqual(success, result.returncode == 0, result.stdout + result.stderr)
        if secret:
            self.assertNotIn(secret, result.stdout + result.stderr)
        return result

    def write_jar(self, version="1.0.0", official="true", public=None):
        with zipfile.ZipFile(self.jar, "w") as jar:
            jar.writestr("assets/mithrilpf/release-signing.pub",
                         self.public.read_text() if public is None else public)
            jar.writestr("assets/mithrilpf/build.properties",
                         f"version={version}\nofficialRelease={official}\n")
            jar.writestr("synthetic.txt", "test artifact")

    def sign(self, **kwargs):
        self.run_tool("sign", "1.0.0", self.jar, self.signature, self.public,
                      secret=self.private.read_text().strip(), **kwargs)

    def test_sign_verify_and_preserve_artifact(self):
        before = self.jar.read_bytes()
        self.sign()
        self.assertEqual(64, self.signature.stat().st_size)
        self.assertEqual(before, self.jar.read_bytes())
        self.run_tool("verify", "1.0.0", self.jar, self.signature, self.public)
        self.sign(success=False)

    def test_key_generation_never_overwrites(self):
        before = self.private.read_bytes(), self.public.read_bytes()
        self.run_tool("generate", self.private, self.public, success=False)
        self.assertEqual(before, (self.private.read_bytes(), self.public.read_bytes()))

    def test_requires_matching_secret_and_never_prints_it(self):
        other_private, other_public = self.root / "other.key", self.root / "other.pub"
        self.run_tool("generate", other_private, other_public)
        for secret in (None, "not-a-key", "x" * 257, other_private.read_text().strip()):
            self.run_tool("sign", "1.0.0", self.jar, self.signature, self.public,
                          secret=secret, success=False)
            self.assertFalse(self.signature.exists())

    def test_rejects_nonrelease_and_wrong_packaged_key(self):
        for kwargs in ({"official": "false"}, {"version": "1.0.1"}, {"public": "wrong"},
                       {"public": "x" * 129}):
            self.write_jar(**kwargs)
            self.sign(success=False)
            self.assertFalse(self.signature.exists())

    def test_rejects_tampered_artifact_and_signature(self):
        self.sign()
        original = self.signature.read_bytes()
        for bad in (b"", original[:-1], original + b"x", bytes(64)):
            self.signature.write_bytes(bad)
            self.run_tool("verify", "1.0.0", self.jar, self.signature, self.public, success=False)
        self.signature.write_bytes(original)
        with zipfile.ZipFile(self.jar, "a") as jar:
            jar.writestr("tampered.txt", "tampered")
        self.run_tool("verify", "1.0.0", self.jar, self.signature, self.public, success=False)

    def test_rejects_invalid_or_mismatched_version_and_filename(self):
        for version in ("01.0.0", "1.0.0-rc.0", "1.0.0\n", "1.0.1", "2147483648.0.0"):
            self.run_tool("sign", version, self.jar, self.signature, self.public,
                          secret=self.private.read_text().strip(), success=False)
        wrong_name = self.jar.rename(self.root / "different.jar")
        self.run_tool("sign", "1.0.0", wrong_name, self.signature, self.public,
                      secret=self.private.read_text().strip(), success=False)

    def test_rejects_invalid_inputs(self):
        self.run_tool("unknown", success=False)
        self.jar.write_bytes(b"")
        self.sign(success=False)
        self.public.write_text("x" * 129)
        self.sign(success=False)


if __name__ == "__main__":
    unittest.main()
