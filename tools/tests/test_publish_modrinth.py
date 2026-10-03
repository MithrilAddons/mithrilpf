import hashlib
import io
import json
import unittest
from email.parser import BytesParser
from email.policy import default
from unittest.mock import patch
import urllib.error
import zipfile

from tools.publish_modrinth import already_uploaded, multipart, prepare, release_version, request


class ModrinthPublishingTest(unittest.TestCase):
    def artifact(self, version="0.2.0-rc.7", official="true", minecraft="26.1.2"):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as jar:
            jar.writestr("fabric.mod.json", json.dumps({
                "id": "mithrilpf", "version": version, "environment": "client",
                "depends": {"minecraft": minecraft},
            }))
            jar.writestr("assets/mithrilpf/build.properties",
                         f"version={version}\nofficialRelease={official}\n")
        return buffer.getvalue()

    def prepare(self, artifact=None, **changes):
        artifact = artifact if artifact is not None else self.artifact()
        release = {"tagName": "v0.2.0-rc.7", "isDraft": False, "isPrerelease": True,
                   "name": "MithrilPF 0.2.0-rc.7", "body": "Release notes"} | changes
        checksum = f"{hashlib.sha256(artifact).hexdigest()}  mithrilpf-0.2.0-rc.7.jar\n"
        return prepare(release, "v0.2.0-rc.7", artifact, checksum)

    def test_channels_and_invalid_tags(self):
        for suffix, channel in (("", "release"), ("-alpha.1", "alpha"),
                                ("-beta.2", "beta"), ("-rc.7", "beta")):
            self.assertEqual((f"1.2.3{suffix}", channel), release_version(f"v1.2.3{suffix}"))
        for tag in ("1.2.3", "v1.2.3-SNAPSHOT", "v1.2.3-rc.0", "v1.2.3;echo bad"):
            with self.assertRaises(ValueError):
                release_version(tag)

    def test_metadata_comes_from_published_artifact(self):
        payload = self.prepare()
        self.assertEqual(["26.1.2"], payload["game_versions"])
        self.assertEqual("Release notes", payload["changelog"])
        self.assertEqual("beta", payload["version_type"])
        self.assertEqual(["fabric"], payload["loaders"])
        self.assertEqual(["required", "required", "optional"],
                         [d["dependency_type"] for d in payload["dependencies"]])

    def test_rejects_drafts_mismatched_tags_and_channel_flags(self):
        for change in ({"isDraft": True}, {"tagName": "v0.2.0"}, {"isPrerelease": False}):
            with self.assertRaises(ValueError):
                self.prepare(**change)

    def test_rejects_local_wrong_version_and_range_artifacts(self):
        for artifact in (self.artifact(official="false"), self.artifact(version="0.1.0"),
                         self.artifact(minecraft=">=26.1.2")):
            with self.assertRaises(ValueError):
                self.prepare(artifact)

    def test_rejects_corrupt_checksum(self):
        with self.assertRaisesRegex(ValueError, "SHA256SUMS"):
            prepare({"isDraft": False, "tagName": "v0.2.0-rc.7", "isPrerelease": True},
                    "v0.2.0-rc.7", self.artifact(), "wrong checksum")

    def test_retry_skips_same_artifact_and_rejects_conflicts(self):
        artifact = self.artifact()
        payload = self.prepare(artifact)
        existing = payload | {"files": [{"hashes": {"sha512": hashlib.sha512(artifact).hexdigest()}}]}
        self.assertFalse(already_uploaded([], payload, artifact))
        self.assertTrue(already_uploaded([existing], payload, artifact))
        for changes in ({"version_type": "release"}, {"files": []}, {"loaders": ["forge"]},
                        {"game_versions": ["1.21"]}):
            with self.assertRaises(ValueError):
                already_uploaded([existing | changes], payload, artifact)

    def test_multipart_preserves_notes_and_binary_jar(self):
        artifact = self.artifact()
        payload = self.prepare(artifact) | {"changelog": "Notes: \u2603\n**Fixes**"}
        body, content_type = multipart(payload, "mithrilpf-0.2.0-rc.7.jar", artifact)
        message = BytesParser(policy=default).parsebytes(
            f"Content-Type: {content_type}\r\n\r\n".encode() + body)
        metadata, file = list(message.iter_parts())
        self.assertEqual(payload, json.loads(metadata.get_payload(decode=True)))
        self.assertEqual(artifact, file.get_payload(decode=True))
        self.assertEqual("mithrilpf-0.2.0-rc.7.jar", file.get_filename())

    @patch("urllib.request.urlopen")
    def test_http_failures_do_not_expose_token(self, urlopen):
        urlopen.side_effect = urllib.error.HTTPError("https://api.modrinth.com", 401,
                                                    "private details", {}, None)
        with self.assertRaisesRegex(RuntimeError, "HTTP 401") as caught:
            request("/version", "secret-token")
        self.assertNotIn("secret-token", str(caught.exception))
        self.assertNotIn("private details", str(caught.exception))
