import io
import json
import os
import unittest
from unittest.mock import patch
import urllib.error

from tools.modrinth_discord import call, changes, main

NEW = "https://discord.gg/7uRvj6yc4J"


class ModrinthDiscordTest(unittest.TestCase):
    def test_every_invite_in_the_description_is_replaced(self):
        project = {"body": "Join https://discord.gg/old1 or https://discord.com/invite/old2!\n"
                           "Docs: https://example.com", "discord_url": "https://discord.gg/old1"}
        self.assertEqual({"body": f"Join {NEW} or {NEW}!\nDocs: https://example.com",
                          "discord_url": NEW}, changes(project, NEW))
        self.assertEqual({}, changes({"body": f"Join {NEW}", "discord_url": NEW}, NEW))
        self.assertEqual({"discord_url": NEW}, changes({"body": None, "discord_url": None}, NEW))

    @patch("tools.modrinth_discord.call")
    def test_main_patches_then_verifies(self, call_mock):
        before = {"id": "abc", "body": "https://discord.gg/old1", "discord_url": None}
        after = {"id": "abc", "body": NEW, "discord_url": NEW}
        call_mock.side_effect = [before, None, after]
        with patch.dict(os.environ, {"DISCORD_INVITE": NEW, "MODRINTH_TOKEN": "secret"}):
            main()
        self.assertEqual(("GET", "/project/mithrilpf", "secret"), call_mock.call_args_list[0].args)
        self.assertEqual(("PATCH", "/project/abc", "secret", {"body": NEW, "discord_url": NEW}),
                         call_mock.call_args_list[1].args)
        call_mock.side_effect = [after, before]
        with patch.dict(os.environ, {"DISCORD_INVITE": NEW, "MODRINTH_TOKEN": "secret"}):
            with self.assertRaises(RuntimeError):
                main()

    def test_invalid_input_and_missing_token_are_rejected(self):
        for invite in ("", "discord.gg/abc", "https://evil.example/abc", "https://discord.gg/a b"):
            with patch.dict(os.environ, {"DISCORD_INVITE": invite, "MODRINTH_TOKEN": "x"}):
                with self.assertRaises(ValueError):
                    main()
        with patch.dict(os.environ, {"DISCORD_INVITE": NEW, "MODRINTH_TOKEN": ""}):
            with self.assertRaises(ValueError):
                main()

    @patch("tools.modrinth_discord.urllib.request.urlopen")
    def test_requests_send_the_token_and_hide_error_bodies(self, urlopen):
        urlopen.return_value.__enter__.return_value = io.BytesIO(b'{"id": "abc"}')
        self.assertEqual({"id": "abc"}, call("PATCH", "/project/abc", "secret", {"discord_url": NEW}))
        request = urlopen.call_args.args[0]
        self.assertEqual("PATCH", request.get_method())
        self.assertEqual("secret", request.get_header("Authorization"))
        self.assertEqual({"discord_url": NEW}, json.loads(request.data))
        urlopen.return_value.__enter__.return_value = io.BytesIO(b"")
        self.assertIsNone(call("GET", "/project/abc", "secret"))
        urlopen.side_effect = urllib.error.HTTPError("u", 401, "no", {}, io.BytesIO(b"secret detail"))
        with self.assertRaisesRegex(RuntimeError, "HTTP 401"):
            call("GET", "/project/abc", "secret")


if __name__ == "__main__":
    unittest.main()
