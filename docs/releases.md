# Signed Android releases

The release workflow builds the APK with the repository's fixed Android signing key, verifies its certificate and package metadata, and packages those exact APK bytes inside the Magisk ZIP. It fails before building if the signing secrets or certificate pin are missing or do not match. CI never creates a replacement signing key.

## Configure signing once

The local setup requires Python 3.10+, JDK 17+ (`keytool`), and GitHub CLI (`gh`). Authenticate first, then use the setup helper:

```sh
gh auth login
python3 scripts/configure-release-signing.py --repo KiriKira/gsm2sip
```

The helper stores a private backup outside the checkout at `~/.local/share/gsm2sip-release-signing/KiriKira--gsm2sip`. Keep an offline copy of that directory, including its keystore and credentials. To import the existing release identity, pass its path and alias:

```sh
python3 scripts/configure-release-signing.py \
  --repo KiriKira/gsm2sip \
  --keystore /absolute/path/to/release.keystore \
  --alias YOUR_EXISTING_ALIAS
```

The setup helper uploads the key and public certificate fingerprint to this GitHub repository. Run it on a trusted workstation, never from Actions. It refuses to replace an already configured signer.

The repository configuration consists of these Actions secrets:

- `GSM_RELEASE_KEYSTORE_BASE64`
- `GSM_RELEASE_STORE_PASSWORD`
- `GSM_RELEASE_KEY_ALIAS`
- `GSM_RELEASE_KEY_PASSWORD`

And this Actions repository variable:

- `GSM_RELEASE_CERT_SHA256`: the SHA-256 fingerprint of the signing certificate in the keystore. Colons and uppercase letters are accepted.

The certificate fingerprint is public. Keep the keystore and passwords in the protected private backup and the repository's encrypted secrets. Do not commit them or include them in release assets.

## Build and publication triggers

- Every push to `main` runs tests, lint, Magisk checks, and a signed release build. It publishes a prerelease named `build-<run number>-<short commit>`.
- A tag matching `vMAJOR.MINOR.PATCH` publishes a stable release whose tag and version name are the tag itself.
- `workflow_dispatch` defaults to `publish=true`. From `main`, set it to `false` to build and verify without creating a GitHub Release. Other branches run a debug-only validation build without release signing or publication; manually dispatched tags can build a signed artifact but do not publish.

The Android `versionCode` is `10000 + github.run_number` and is limited to Android's supported range of 1 through 2,100,000,000. A workflow rerun keeps the same code and release tag; a new workflow run receives a higher code. Existing GitHub Release assets are never replaced.

## Release assets

Each release contains:

- `gateway.apk` — signed for direct Android installation.
- `gateway-magisk.zip` — Magisk module with the byte-identical signed APK and tools for arm64-v8a, armeabi-v7a, x86_64, and x86.
- `release-manifest.json` — source commit, Android version, signing certificate fingerprint, and SHA-256 plus byte size for each APK/ZIP.
- `SHA256SUMS.txt` — checksums for the APK, Magisk ZIP, and manifest.

Installing the first release over a debug build signed with a different certificate requires exporting any SMS archive, uninstalling the debug app, and installing the release build. Later releases signed with the configured key can upgrade in place.

Before upload, CI verifies the APK package ID, version code, version name, non-debuggable flag, pinned signer, 16 KiB zip alignment, the embedded APK hash, module version metadata, ZIP integrity, and all four Magisk tool ABIs. It also runs the existing unit tests, lint, and Magisk shell/runtime checks. The release workflow does not run the KVM-backed emulator suite.
