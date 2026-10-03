# Device validation ledger

No target dual-SIM device has been connected to this development environment.
The existing README's historical single-device audio reports are not evidence
that the current three-component system works on either target SIM.

Implementation proceeds through Magisk's general interfaces without a model
allowlist. Run `tools/check-device.sh <adb-serial>` to inspect runtime capabilities.
The script only reads the module's probe output. It neither places calls
nor sends SMS, and passing it does not prove audio works.

| Requirement | Current evidence |
| --- | --- |
| Target model / SoC / ROM / Android / kernel | Awaiting target device |
| SIM A / SIM B operator, radio mode | Awaiting target device |
| Exact subscription ↔ PhoneAccountHandle association | Public API or Magisk system-UID broker; actual unique active mapping required, no API 31 gate |
| SIM A incoming / outgoing, both audio directions | Not tested |
| SIM B incoming / outgoing, both audio directions | Not tested |
| RFC4733 reaches a real cellular IVR on both SIMs | Not tested |
| Cross-SIM SMS / incoming call during active call | Not tested; modem and carrier determine DSDS behavior |
| Screen off 24h / uptime 72h / restart recovery | Not tested |

For each target, record the exact build, profile, per-SIM result and date. Keep
real phone numbers, ICCID/IMSI, SMS bodies and SIP/API secrets out of reports.
Audio capture and modem uplink injection are separate acceptance checks.
A device is voice-capable only after both directions pass on both SIMs.

## Signing and upgrades

Debug builds are development artifacts. Release builds use a persistent private
key provided through `GSM_RELEASE_STORE_FILE`, `GSM_RELEASE_STORE_PASSWORD`,
`GSM_RELEASE_KEY_ALIAS`, and `GSM_RELEASE_KEY_PASSWORD`; no key is checked in.
Without those values, Gradle may produce an unsigned artifact for inspection,
but `build.sh release` refuses to package it in a Magisk module. A release key
cannot update an older debug-signed install: back up local configuration and
journals using a deliberate migration procedure before reinstalling. Do not
discard an execution ledger containing dispatching/unknown tasks and then
replay those tasks on a fresh installation. Pair a new device identity instead.
