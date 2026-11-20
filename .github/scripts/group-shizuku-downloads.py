#!/usr/bin/env python3
"""Maintain the two grouped Shizuku APK downloads; preserve all versioned source releases."""
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

APP = "McpAdbAndroidLogger"
ROOT = "https://github.com/N2K02/McpAdbAndroidLogger/releases"
VERSION = re.compile(r"(?im)^Version code:[ \t]*(\d+)[ \t]*$", re.M)

def gh(*args):
    return subprocess.run(["gh", *args], check=True, text=True, capture_output=True).stdout

def candidate(release, channel):
    if release.get("draft") or bool(release.get("prerelease")) != (channel == "experimental"):
        return None
    tag = release.get("tag_name", "")
    if channel == "stable":
        match = re.fullmatch(r"shizuku-v(\d+\.\d+)", tag)
        pattern = rf"{APP}-SHIZUKU-{re.escape(match.group(1))}\.apk" if match else None
    else:
        match = re.fullmatch(r"experimental-shizuku-pr-\d+-run-\d+", tag)
        pattern = rf"{APP}-SHIZUKU-\d+\.\d+-experimental\.\d+\.apk" if match else None
    if not pattern:
        return None
    codes = VERSION.findall(release.get("body") or "")
    if len(codes) != 1:
        return None
    matching = [a for a in release.get("assets", []) if re.fullmatch(pattern, a.get("name", ""))
                and a.get("state") == "uploaded" and a.get("size", 0) > 0
                and re.fullmatch(r"sha256:[a-f0-9]{64}", a.get("digest") or "")
                and a.get("browser_download_url") == f"{ROOT}/download/{tag}/{a['name']}"]
    if len(matching) != 1:
        return None
    return (int(codes[0]), release.get("published_at") or "", tag, matching[0])

def select(releases, channel):
    options = [x for release in releases if (x := candidate(release, channel)) is not None]
    return max(options, key=lambda x: x[:2]) if options else None

def self_test():
    asset = {"name": f"{APP}-SHIZUKU-4.1.apk", "state": "uploaded", "size": 10,
             "digest": "sha256:" + "a" * 64,
             "browser_download_url": f"{ROOT}/download/shizuku-v4.1/{APP}-SHIZUKU-4.1.apk"}
    item = {"tag_name": "shizuku-v4.1", "draft": False, "prerelease": False,
            "body": "Version code: 100718", "assets": [asset]}
    assert select([item], "stable")[0] == 100718
    assert select([dict(item, tag_name="adb-v4.1")], "stable") is None
    assert select([dict(item, assets=[dict(asset, name=f"{APP}-ADB-4.1.apk")])], "stable") is None
    assert select([dict(item, assets=[dict(asset, digest="bad")])], "stable") is None
    assert select([item], "experimental") is None

def publish(repo, channel, releases):
    picked = select(releases, channel)
    if not picked:
        raise RuntimeError(f"No verified Shizuku {channel} APK; refusing to alter grouped release")
    code, _, tag, asset = picked
    group_tag = f"downloads-{channel}"
    old = next((r for r in releases if r.get("tag_name") == group_tag), None)
    marker = f"<!-- n2k02-download-group:{channel} -->"
    if old and (old.get("draft") or bool(old.get("prerelease")) != (channel == "experimental")
                or not (old.get("body") or "").startswith(marker)):
        raise RuntimeError("Unmanaged or incompatible grouped release; refusing overwrite")
    title = f"{channel.title()} downloads · Shizuku"
    body = (f"{marker}\n# {title}\n\n"
            f"Official Shizuku APK: [{asset['name']}]({asset['browser_download_url']})\n\n"
            f"Version code: {code}\nInstall only the Shizuku build.\n")
    old_assets = {a["name"]: a for a in (old.get("assets") or [])} if old else {}
    current = old_assets.get(asset["name"])
    if current and current.get("digest") != asset.get("digest"):
        raise RuntimeError("Grouped APK with the same name has an unexpected checksum")
    with tempfile.TemporaryDirectory() as folder:
        if not current:
            gh("release", "download", tag, "--repo", repo, "--pattern", asset["name"], "--dir", folder)
            path = Path(folder) / asset["name"]
            actual = "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()
            if actual != asset["digest"] or path.stat().st_size != asset["size"]:
                raise RuntimeError("Canonical Shizuku APK checksum/size mismatch")
        if old:
            if not current:
                gh("release", "upload", group_tag, str(path), "--repo", repo)
            gh("release", "edit", group_tag, "--repo", repo, "--title", title,
               "--notes", body, "--latest=false")
            # Only grouped copies are removed; canonical versioned APKs are untouched.
            for name in old_assets:
                if name != asset["name"] and name.endswith(".apk"):
                    gh("release", "delete-asset", group_tag, name, "--repo", repo, "--yes")
        else:
            cmd = ["release", "create", group_tag, str(path), "--repo", repo,
                   "--title", title, "--notes", body, "--latest=false"]
            if channel == "experimental":
                cmd.append("--prerelease")
            gh(*cmd)
    print(f"Updated {group_tag}: {asset['name']}")

def main():
    self_test()
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        print("Shizuku channel selection tests passed")
        return
    if len(sys.argv) != 1:
        raise ValueError("Usage: group-shizuku-downloads.py [--self-test]")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if repo != "N2K02/McpAdbAndroidLogger" or not os.environ.get("GH_TOKEN"):
        raise RuntimeError("Correct repository and GH_TOKEN required")
    pages = json.loads(gh("api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"))
    releases = [item for page in pages for item in page]
    for channel in ("stable", "experimental"):
        publish(repo, channel, releases)

if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
