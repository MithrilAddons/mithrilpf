"""Point the Modrinth project's Discord link, and every Discord invite in its description, at one invite."""

import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.modrinth.com/v2"
REPOSITORY = "MithrilAddons/mithrilpf"
INVITE = re.compile(r"https?://(?:www\.)?(?:discord\.gg|discord\.com/invite)/[A-Za-z0-9-]+")
VALID = re.compile(r"https://discord\.gg/[A-Za-z0-9-]{2,32}")


def call(method, path, token, data=None):
    headers = {"Authorization": token,
               "User-Agent": f"{REPOSITORY}/discord-link (https://github.com/{REPOSITORY})"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, method=method, headers=headers,
                                 data=json.dumps(data).encode() if data is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Modrinth returned HTTP {error.code}; check project access and token scopes") from None


def changes(project, invite):
    """The project fields that still point anywhere other than the invite."""
    result = {}
    body = project.get("body") or ""
    replaced = INVITE.sub(invite, body)
    if replaced != body:
        result["body"] = replaced
    if project.get("discord_url") != invite:
        result["discord_url"] = invite
    return result


def main():
    invite = os.environ.get("DISCORD_INVITE", "").strip()
    if not VALID.fullmatch(invite):
        raise ValueError("Give a Discord invite like https://discord.gg/abc123")
    token = os.environ.get("MODRINTH_TOKEN", "")
    if not token:
        raise ValueError("Set the MODRINTH_TOKEN Actions secret")
    project = urllib.parse.quote(os.environ.get("MODRINTH_PROJECT_ID", "mithrilpf"), safe="")
    current = call("GET", f"/project/{project}", token)
    update = changes(current, invite)
    if update:
        call("PATCH", f"/project/{current['id']}", token, update)
    after = call("GET", f"/project/{current['id']}", token)
    if changes(after, invite):
        raise RuntimeError("Modrinth still shows an old Discord link")
    print(f"Updated {', '.join(sorted(update)) or 'nothing'}; Modrinth now links only to {invite}")


if __name__ == "__main__":
    main()
