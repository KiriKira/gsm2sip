#!/usr/bin/env python3
"""Configure stable Android signing using the user's gh credentials and JDK.

Private material stays outside the checkout; passwords never appear in arguments.
Run again after a partial upload to reuse the same signing identity.
"""
from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys


SECRET_NAMES = (
    "GSM_RELEASE_KEYSTORE_BASE64", "GSM_RELEASE_STORE_PASSWORD",
    "GSM_RELEASE_KEY_ALIAS", "GSM_RELEASE_KEY_PASSWORD",
)
PIN_NAME = "GSM_RELEASE_CERT_SHA256"


def run(args, *, data=None, env=None):
    result = subprocess.run(args, input=data, env=env, capture_output=True)
    if result.returncode:
        # Do not echo a subprocess error that might contain private input.
        hint = ("Check gh authentication and repository Secrets/Variables write permissions; "
                "rerun using the same private backup." if args[0] == "gh" else
                "Check the JDK installation, keystore alias and passwords.")
        raise RuntimeError(f"{args[0]} {args[1]} failed (exit {result.returncode}). {hint}")
    return result.stdout


def api_json(path, *, optional=False):
    result = subprocess.run(["gh", "api", path], capture_output=True)
    if result.returncode:
        if optional and (b"HTTP 404" in result.stderr or b'"status":"404"' in result.stdout):
            return None
        raise RuntimeError("Cannot read repository Actions configuration. Authenticate gh with "
                           "Secrets and Variables write access to the requested repository.")
    return json.loads(result.stdout)


def private_write(path: Path, data: bytes):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(data)


def prepare(args, *, already_has_secrets: bool, existing_pin: str | None):
    directory = args.output_dir.expanduser().resolve()
    # Backups must never accidentally become repository contents.
    checkout = Path(__file__).resolve().parent.parent
    if directory == checkout or checkout in directory.parents:
        raise RuntimeError("Choose a private output directory outside this checkout.")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    record = directory / "credentials.json"
    if record.exists():
        if args.keystore:
            raise RuntimeError("This backup already has a signer. Use its existing credentials "
                               "or choose a different --output-dir to import a key.")
        config = json.loads(record.read_text())
        if config.get("repository") != args.repo:
            raise RuntimeError("The backup belongs to a different repository.")
        key = directory / "release.keystore"
        if not key.is_file():
            raise RuntimeError("Backup keystore is missing; restore it from your private backup.")
    elif args.keystore:
        if not args.alias:
            raise RuntimeError("--alias is required when importing an existing keystore.")
        source = args.keystore.expanduser().resolve()
        if not source.is_file():
            raise RuntimeError("The input keystore does not exist.")
        store_password = os.environ.get("GSM_RELEASE_STORE_PASSWORD") or getpass.getpass("Keystore password: ")
        key_password = os.environ.get("GSM_RELEASE_KEY_PASSWORD") or getpass.getpass("Key password (Enter = keystore password): ") or store_password
        config = {"repository": args.repo, "alias": args.alias,
                  "store_password": store_password, "key_password": key_password}
        key = directory / "release.keystore"
        private_write(key, source.read_bytes())
        private_write(record, (json.dumps(config, indent=2) + "\n").encode())
    else:
        if already_has_secrets or existing_pin:
            raise RuntimeError("Repository already has a signing configuration. Import its "
                               "original keystore with --keystore and --alias, or restore the "
                               "local backup. Refusing to generate a replacement signer.")
        key = directory / "release.keystore"
        if key.exists():
            raise RuntimeError("An unpaired keystore already exists; recover its credentials "
                               "rather than replacing it.")
        password = secrets.token_urlsafe(36)
        config = {"repository": args.repo, "alias": args.alias or "gsm-release",
                  "store_password": password, "key_password": password}
        # Write credentials first, so a keytool interruption is recoverable.
        private_write(record, (json.dumps(config, indent=2) + "\n").encode())
        env = dict(os.environ, GSM_SIGNING_STORE_PASSWORD=password,
                   GSM_SIGNING_KEY_PASSWORD=password)
        try:
            run(["keytool", "-genkeypair", "-noprompt", "-storetype", "PKCS12",
                 "-keystore", str(key), "-alias", config["alias"],
                 "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                 "-dname", f"CN={args.repo},O=GSM Relay",
                 "-storepass:env", "GSM_SIGNING_STORE_PASSWORD",
                 "-keypass:env", "GSM_SIGNING_KEY_PASSWORD"], env=env)
        except Exception:
            if not key.exists():
                record.unlink()
            raise
    os.chmod(record, 0o600)
    os.chmod(key, 0o600)
    env = dict(os.environ, GSM_SIGNING_STORE_PASSWORD=config["store_password"],
               GSM_SIGNING_KEY_PASSWORD=config["key_password"])
    certificate = run(["keytool", "-exportcert", "-keystore", str(key),
                       "-alias", config["alias"], "-storepass:env", "GSM_SIGNING_STORE_PASSWORD"], env=env)
    # Exporting a certificate only validates the store password. A CSR also proves
    # that this alias has an accessible private key with the configured password.
    run(["keytool", "-certreq", "-keystore", str(key), "-alias", config["alias"],
         "-storepass:env", "GSM_SIGNING_STORE_PASSWORD",
         "-keypass:env", "GSM_SIGNING_KEY_PASSWORD"], env=env)
    fingerprint = hashlib.sha256(certificate).hexdigest()
    if existing_pin and existing_pin.replace(":", "").strip().lower() != fingerprint:
        raise RuntimeError("Keystore certificate differs from the repository's pinned signer. "
                           "Refusing to change the signing identity.")
    return directory, key, config, fingerprint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="OWNER/REPOSITORY")
    parser.add_argument("--keystore", type=Path, help="Import an existing keystore instead of generating one")
    parser.add_argument("--alias", help="Alias of the imported key")
    parser.add_argument("--output-dir", type=Path, help="Private backup directory outside the checkout")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        parser.error("--repo must be OWNER/REPOSITORY")
    if not args.output_dir:
        args.output_dir = Path.home() / ".local/share/gsm2sip-release-signing" / args.repo.replace("/", "--")
    for command in ("gh", "keytool"):
        if not shutil.which(command):
            raise RuntimeError(f"Install {command} and add it to PATH before running this script.")
    configuration = api_json(f"repos/{args.repo}/actions/secrets?per_page=100")
    existing_names = {entry["name"] for entry in configuration["secrets"]}
    pin = api_json(f"repos/{args.repo}/actions/variables/{PIN_NAME}", optional=True)
    directory, key, config, fingerprint = prepare(
        args, already_has_secrets=bool(existing_names.intersection(SECRET_NAMES)),
        existing_pin=pin["value"] if pin else None)
    # Pin first: a partial upload can be resumed, but never rotates the signer.
    run(["gh", "variable", "set", PIN_NAME, "--repo", args.repo, "--body", fingerprint])
    values = (base64.b64encode(key.read_bytes()), config["store_password"].encode(),
              config["alias"].encode(), config["key_password"].encode())
    for name, value in zip(SECRET_NAMES, values):
        run(["gh", "secret", "set", name, "--repo", args.repo], data=value)
    print(f"Configured four signing Secrets and {PIN_NAME} for {args.repo}.")
    print(f"Public certificate SHA-256: {fingerprint}")
    print(f"Private backup (keystore AND passwords; keep an offline copy): {directory}")
    print("Run the Release workflow on main to publish the first signed build.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print(f"Signing configuration failed: {error}", file=sys.stderr)
        sys.exit(1)
