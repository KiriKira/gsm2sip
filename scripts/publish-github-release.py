#!/usr/bin/env python3
"""Publish a verified flat payload, staging assets in a recoverable draft first.

Published assets and tag targets are immutable. Requires gh and GH_TOKEN; no checkout.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote


def run(args, data=None):
    result = subprocess.run(args, input=data, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace").strip() or f"{args[0]} failed")
    return result.stdout


def api(repo, path, *, method="GET", body=None, optional=False):
    args = ["gh", "api", "--method", method, f"repos/{repo}/{path}"]
    if body is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=json.dumps(body).encode() if body is not None else None,
                            capture_output=True)
    if result.returncode:
        if optional and b"HTTP 404" in result.stderr:
            return None
        raise RuntimeError(result.stderr.decode(errors="replace").strip() or "GitHub API request failed")
    return json.loads(result.stdout) if result.stdout.strip() else None


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def validate_payload(directory, commit):
    if not directory.is_dir() or directory.is_symlink():
        raise RuntimeError("Payload directory is missing or is a symlink")
    files = {}
    for path in directory.iterdir():
        if path.is_symlink() or not path.is_file() or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", path.name):
            raise RuntimeError(f"Invalid release asset: {path.name}")
        files[path.name] = {"sha256": digest(path), "size": path.stat().st_size}
    required = {"release-manifest.json", "SHA256SUMS.txt"}
    if not required.issubset(files) or len(files) <= len(required):
        raise RuntimeError("Payload must include a manifest, checksums and release assets")
    manifest = json.loads((directory / "release-manifest.json").read_text())
    if manifest.get("source_commit") != commit or not manifest.get("version"):
        raise RuntimeError("Manifest source commit/version does not match this release")
    entries = manifest.get("artifacts")
    if not isinstance(entries, list) or not entries:
        raise RuntimeError("Manifest artifacts must be a nonempty list")
    declared = {}
    for entry in entries:
        name = entry["name"]
        if name in declared or name in required:
            raise RuntimeError("Duplicate or reserved manifest artifact")
        declared[name] = {"sha256": entry["sha256"], "size": entry["size"]}
    if declared != {name: value for name, value in files.items() if name not in required}:
        raise RuntimeError("Manifest artifact digests/sizes do not match the payload")
    checksums = {}
    for line in (directory / "SHA256SUMS.txt").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._+-]*)", line)
        if not match or match[2] in checksums:
            raise RuntimeError("Invalid or duplicate checksum entry")
        checksums[match[2]] = match[1]
    if checksums != {name: value["sha256"] for name, value in files.items() if name != "SHA256SUMS.txt"}:
        raise RuntimeError("Checksums do not match the complete payload")
    return files


def ensure_tag(repo, tag, commit):
    reference = api(repo, f"git/ref/tags/{quote(tag, safe='')}", optional=True)
    if reference is None:
        if tag.startswith("v"):
            raise RuntimeError("Version tag is missing; refusing to create an unsolicited stable tag")
        api(repo, "git/refs", method="POST", body={"ref": f"refs/tags/{tag}", "sha": commit})
        return
    target = reference["object"]
    for _ in range(8):
        if target["type"] == "commit":
            if target["sha"] != commit:
                raise RuntimeError("Existing release tag points to another commit; refusing to retarget it")
            return
        if target["type"] != "tag":
            break
        target = api(repo, f"git/tags/{target['sha']}")["object"]
    raise RuntimeError("Release tag does not resolve to the expected source commit")


def asset_matches(asset, expected):
    return (asset.get("state") == "uploaded" and asset.get("size") == expected["size"]
            and asset.get("digest") == f"sha256:{expected['sha256']}")


def list_assets(repo, release_id):
    assets = []
    page = 1
    while True:
        batch = api(repo, f"releases/{release_id}/assets?per_page=100&page={page}")
        assets.extend(batch)
        if len(batch) < 100:
            return assets
        page += 1


def publish(args):
    files = validate_payload(args.directory, args.commit)
    ensure_tag(args.repo, args.tag, args.commit)
    release = api(args.repo, f"releases/tags/{quote(args.tag, safe='')}", optional=True)
    if release is None:
        release = api(args.repo, "releases", method="POST", body={
            "tag_name": args.tag, "target_commitish": args.commit, "name": args.name,
            "draft": True, "prerelease": args.prerelease,
            "body": f"Source commit: `{args.commit}`\n\nSee release-manifest.json and SHA256SUMS.txt for artifact provenance and integrity.\n"})
    assets = list_assets(args.repo, release["id"])
    if not release["draft"]:
        matching = (release["prerelease"] == args.prerelease
                    and len(assets) == len(files)
                    and {asset["name"] for asset in assets} == set(files)
                    and all(asset["name"] in files and asset_matches(asset, files[asset["name"]]) for asset in assets))
        if not matching:
            raise RuntimeError("A different release is already published at this tag. Published assets are never overwritten; use a new tag/run.")
        print(f"Release already published with identical assets: {release['html_url']}")
        return
    existing = set()
    for asset in assets:
        name = asset["name"]
        if name in files and name not in existing and asset_matches(asset, files[name]):
            existing.add(name)
        else:
            # Only unpublished draft assets may be replaced during recovery.
            api(args.repo, f"releases/assets/{asset['id']}", method="DELETE")
    for name in sorted(files.keys() - existing):
        run(["gh", "release", "upload", args.tag, str(args.directory / name), "--repo", args.repo])
    assets = list_assets(args.repo, release["id"])
    if (len(assets) != len(files)
            or {asset["name"] for asset in assets} != set(files)
            or any(asset["name"] not in files or not asset_matches(asset, files[asset["name"]]) for asset in assets)):
        raise RuntimeError("Uploaded asset digests/sizes failed verification; release remains a draft")
    release = api(args.repo, f"releases/{release['id']}", method="PATCH", body={
        "draft": False, "prerelease": args.prerelease, "name": args.name,
        "make_latest": "false" if args.prerelease else "true"})
    print(f"Published verified release: {release['html_url']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--prerelease", action="store_true")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    args = parser.parse_args()
    if not args.repo or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        parser.error("Provide GITHUB_REPOSITORY or --repo OWNER/REPOSITORY")
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        parser.error("--commit must be a full lowercase Git commit SHA")
    if (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", args.tag)
            or ".." in args.tag or args.tag.endswith((".", ".lock"))):
        parser.error("Use a simple release tag without path components")
    publish(args)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print(f"Release publication failed: {error}", file=sys.stderr)
        sys.exit(1)
