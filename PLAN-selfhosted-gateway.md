# PLAN — Self-hosted GSM/SIP Gateway

## Goal

Turn this fork into the **gateway component** of a private, self-hosted phone-number relay system:

```text
China Unicom SIM
      │
      │ VoLTE / SMS
      ▼
Rooted Android gateway
(this repository)
      │
      │ SIP over TLS + mandatory SRTP
      ▼
Dedicated public server
(Asterisk/PJSIP + control/SMS API)
      │
      ├── SIP/TLS + SRTP ──> primary phone call client
      └── HTTPS/WSS ───────> primary phone companion app
```

Primary use case:

- A dedicated rooted Android phone stays powered and connected at a fixed location with the China Unicom SIM inserted.
- A public server is the stable rendezvous/media relay point.
- The primary phone (for example a Galaxy Z Fold) does **not** need root.
- Incoming and outgoing calls use the original cellular number.
- SMS is received and sent remotely, including Chinese text and OTP messages.
- The system must be suitable for unattended 24/7 operation.

This repository should remain focused on the **rooted Android gateway**. Server and primary-phone client are separate projects; see [Repository split](#repository-split).

---

## Non-goals

- Reimplement carrier IMS/VoWiFi authentication.
- Move the physical SIM away from the gateway.
- Expose Android root or ADB directly to the public Internet.
- Depend on callagent.pro or any third-party relay service.
- Make the primary phone rooted.
- Support multiple simultaneous cellular calls in the first release.
- Preserve upstream behavior that weakens the whole Android permission/security model unless strictly required.

---

## Current upstream capabilities to preserve

The current code already implements substantial functionality that should be kept rather than rewritten:

### Calls

- GSM/VoLTE incoming call -> outbound SIP INVITE.
- SIP answer -> answer the GSM leg.
- SIP -> GSM outgoing calls.
- Bidirectional RTP audio bridge.
- Call-state mapping and SIP failure responses.
- RTP jitter buffer and media timeout.
- Device-specific Android Audio HAL / mixer routing.
- Root shell lifecycle and mixer control.
- G.722 / PCMA support.
- Optional SDES-SRTP.

### SMS

- Incoming SMS -> RFC 3428 SIP MESSAGE.
- SIP MESSAGE -> outgoing cellular SMS.
- Multipart SMS handling.
- Dual-SIM metadata for SMS.
- Durable incoming queue.
- Durable outgoing queue.
- Idempotency via `X-SMS-Id`.
- Submit and delivery reports.

### Runtime resilience

- Foreground service.
- Boot receiver.
- Wake lock / Wi-Fi lock.
- SIP re-registration.
- UDP NAT keepalive.
- TLS reconnect with backoff.
- Network-change reconnect.
- Root-shell recovery.
- Stale call-state recovery.

These are the high-value parts of upstream and should remain the foundation of the gateway.

---

# Repository split

## 1. This repository: `gsm2sip`

**Purpose:** rooted Android cellular gateway only.

Contains:

- Android Telephony / InCallService integration.
- GSM/VoLTE <-> SIP bridge.
- RTP/SRTP media.
- SMS <-> gateway protocol.
- Device/Audio HAL compatibility profiles.
- Root/Magisk support.
- Health/status reporting from the gateway.

Does **not** contain:

- Public server deployment.
- Asterisk configuration as production source of truth.
- User-facing remote Android application.

Keeping this repository gateway-only makes upstream synchronization much easier.

---

## 2. New repository: server

Recommended name:

```text
gsm2sip-server
```

Purpose:

- Dockerized public-server deployment.
- Asterisk using **PJSIP**, not legacy `chan_sip`.
- TLS certificates and SIP/TLS configuration.
- SRTP media anchoring.
- Call routing.
- SMS transport/API.
- Gateway status/heartbeat ingestion.
- Authentication and authorization.
- Rate limiting.
- Monitoring/metrics.
- Backups and deployment documentation.

Suggested stack:

```text
Docker Compose
├── Asterisk (PJSIP)
├── small control/API service
├── reverse proxy (Caddy/Nginx/Traefik)
└── optional Prometheus/Grafana later
```

The server should have a stable public IP/domain and should be the **only public endpoint** required by gateway and primary phone.

---

## 3. New repository: primary-phone app

Recommended name:

```text
gsm2sip-client-android
```

Purpose:

- Non-root Android companion app.
- Remote SMS inbox/outbox.
- OTP notification/copy UX.
- Call history.
- Gateway online/offline state.
- SIM/carrier/signal/battery/temperature state.
- Server-authenticated device pairing.
- Optional integrated SIP calling later.

### Phase 1 client strategy

Do **not** write a SIP phone immediately.

Use an existing SIP client such as Linphone for calls and build our app only for:

- SMS.
- Gateway state.
- History.
- Configuration/diagnostics.

This reduces risk while the GSM/audio bridge is still being validated.

### Phase 2 client strategy

After the gateway/server are stable, decide whether to:

1. keep an external SIP client and deep-link/integrate with it, or
2. embed a mature SIP library into the companion app.

The primary phone must never require root.

---

# Target architecture

```text
                     Cellular network
                           │
                           ▼
                ┌────────────────────┐
                │ Root Android       │
                │ Unicom SIM         │
                │                    │
                │ gsm2sip gateway    │
                └─────────┬──────────┘
                          │
                    SIP over TLS
                    mandatory SRTP
                          │
                          ▼
              ┌────────────────────────┐
              │ Public VPS             │
              │                        │
              │ Asterisk / PJSIP       │
              │ direct_media = no      │
              │ rtp_symmetric = yes    │
              │ force_rport = yes      │
              │ rewrite_contact = yes  │
              │                        │
              │ SMS / control API      │
              └─────────┬──────────────┘
                        │
              ┌─────────┴──────────┐
              │                    │
        SIP/TLS + SRTP        HTTPS / WSS
              │                    │
              ▼                    ▼
       SIP call client      Companion app
       on primary phone     on primary phone
       no root              no root
```

The VPS anchors media. Do not use direct media between the gateway and roaming primary phone.

---

# Phase 0 — Establish a safe fork baseline

Before adding features, remove assumptions specific to the upstream author's deployment.

## P0.1 Remove third-party defaults

- Remove `callagent.pro` as the default SIP server.
- Default server must be blank.
- Remove branding that implies dependency on Callagent.
- Keep generic SIP compatibility.
- Public STUN servers should be disabled by default or fully configurable.

Acceptance:

- Fresh install makes **no runtime connection to a third-party host** until the user configures a server.
- DNS/network capture confirms only configured destinations are contacted.

## P0.2 Replace release signing

Current release builds use the standard Android debug key.

Change to:

- Dedicated project release key.
- GitHub Actions secret or secure local signing workflow.
- Never commit the private keystore/password.
- Debug and release packages may no longer be freely interchangeable.

Acceptance:

- Release APK is not signed by `androiddebugkey`.
- Upgrade from one official release to the next works.

## P0.3 Eliminate unauditable bundled binaries

Current repository includes a prebuilt ARM64 `tinycap` without its source in this tree.

Change to:

- Build `tinycap` from a pinned, auditable tinyalsa/AOSP source revision, or
- use a ROM-provided compatible binary when explicitly detected.
- Produce hashes in CI.

Also ensure `tinymix` is reproducibly built from source.

Acceptance:

- Release artifacts contain no unexplained executable binary.
- CI records source revision and SHA-256 of native tools.

## P0.4 Licensing check

Upstream currently has no repository-level LICENSE file.

Before publishing derived release binaries or broadly distributing modified source:

- Ask upstream author to clarify/add a license.
- Keep server and primary-phone app as original, independently licensed projects.
- Do not copy gateway source into those projects.

---

# Phase 1 — Correctness fixes before Internet deployment

## P1.1 Fix TLS stream framing for UTF-8 bodies — critical

Current TLS transport converts each TCP read to a Kotlin `String` before applying SIP `Content-Length`.

That is incorrect because SIP `Content-Length` is measured in **bytes**, while Kotlin `String.length` measures characters/code units.

This can break Chinese SMS and any UTF-8 body, and UTF-8 code points may also be split across TCP reads.

Replace TLS receive buffering with a byte buffer:

```text
TLS bytes
   ↓
find CRLF CRLF in bytes
   ↓
decode headers only
   ↓
parse Content-Length = N bytes
   ↓
wait until N body bytes are present
   ↓
decode body as UTF-8
```

Tests required:

- ASCII MESSAGE in one TLS read.
- Chinese MESSAGE in one TLS read.
- Chinese UTF-8 code point split across reads.
- headers split across reads.
- body split across reads.
- multiple SIP messages in one read.
- SDP body.
- zero-length body.

This is a release blocker for public deployment.

## P1.2 Add SIP/parser unit tests

Add JVM tests for:

- request/response parsing.
- TLS stream framing.
- Content-Length.
- REGISTER challenge/response.
- INVITE state handling.
- MESSAGE idempotency.
- Unicode SMS.
- SRTP SDP parsing.
- malformed packets.

The current project has effectively no automated test suite; this must change before protocol refactoring.

## P1.3 Respect server-granted registration expiry

Current logic assumes a 3600-second registration and refreshes at 30 minutes.

Implement:

- parse `Expires` and Contact `expires=`.
- store granted expiry.
- refresh around 50–70% of granted lifetime.
- use sane min/max bounds.

Acceptance:

- Works correctly with 300 s, 600 s, 1800 s and 3600 s registrar expiry.

---

# Phase 2 — Secure public-server operation

## P2.1 TLS must be the production default

Production profile:

- SIP over TLS only.
- Certificate chain validation enabled.
- hostname verification enabled.
- SNI preserved.
- plaintext SIP allowed only behind an explicit developer option.

## P2.2 Make SRTP mandatory in production

Current behavior can continue a call as plain RTP if SRTP negotiation fails.

Add:

```text
require_srtp = true
```

When enabled:

- no valid compatible SRTP negotiation -> reject/fail the call.
- never silently downgrade to RTP.
- surface a clear error in logs/UI.

Initial supported profile can remain SDES:

```text
AES_CM_128_HMAC_SHA1_80
```

Later evaluate DTLS-SRTP only if needed.

## P2.3 Harden RTP endpoint validation

With SRTP enabled, only authenticated RTP may establish symmetric-RTP latching.

For non-production plaintext mode:

- restrict accepted RTP source to negotiated server IP/range where possible.
- never let arbitrary Internet packets permanently latch the stream.

## P2.4 Modernize SIP authentication

Current Digest support is minimal/legacy.

Implement at least:

- `qop=auth`.
- `cnonce`.
- `nc`.
- correct nonce lifecycle.
- proxy authentication.
- SHA-256 Digest if supported by server stack.

TLS remains required even with stronger Digest.

## P2.5 Remove global SMS rate-limit bypass

Do not set Android global SMS allowance to effectively unlimited.

Instead implement gateway-local policy:

Suggested initial defaults:

- maximum 5 outbound SMS/minute.
- maximum 30/hour.
- configurable destination allow/deny rules.
- temporary lockout after repeated unauthorized MESSAGE attempts.
- explicit audit log.

Limits must be adjustable but never default to unlimited.

---

# Phase 3 — Reduce invasive Android modifications

## P3.1 Stop hiding the whole PermissionController

Current Magisk module hides Android PermissionController globally.

That is too invasive for a long-lived appliance.

Investigate alternatives in this order:

1. Privileged-app permissions + correct foreground/microphone lifecycle.
2. Targeted AppOps handling for this UID only.
3. Audio-policy/vendor configuration specific to gateway package.
4. SELinux policy/module additions if required.
5. Only as a last resort consider platform-wide modification.

The target is:

- Android permission UI remains installed and functional.
- Only this gateway receives exceptional audio privileges.

## P3.2 Do not override a manual Magisk root denial

Current boot script can rewrite Magisk's DB to grant root automatically.

Replace with:

- health check detecting root denial.
- persistent visible error state.
- optional notification.
- no silent rewrite of user root policy.

For a headless appliance, installation documentation can require the user to grant permanent root once.

## P3.3 Do not mutate normal SMS-app state by default

Remove default behavior that:

- suppresses notifications from the user's SMS app.
- marks SMS as read/seen using direct DB modifications.

Provide explicit settings if these behaviors are desired on a dedicated appliance.

Default behavior must be non-destructive.

---

# Phase 4 — Gateway protocol for our server

Calls can remain standards-based SIP/RTP.

SMS/status should evolve into a clearly versioned contract.

## Calls

Gateway <-> Asterisk:

- SIP/TLS.
- mandatory SRTP.
- PJSIP-compatible routing.
- GSM caller -> SIP Caller-ID.
- SIM MSISDN -> destination/DID.
- outbound number -> Request-URI or explicit header.

Keep `X-GSM-Forward` for compatibility, but prefer standard Request-URI behavior where possible.

## SMS

Keep SIP MESSAGE for gateway <-> server initially because it is already implemented and durable.

Formalize headers:

```text
X-GSM-Gateway-Version
X-SMS-Id
X-SMS-From
X-SMS-To
X-SMS-Received
X-SMS-Parts
X-SMS-Sim-Sub
X-SMS-Sim-Slot
X-SMS-Sim-Carrier
X-SMS-Event
```

Define a versioned protocol document under:

```text
docs/protocol.md
```

Server must use `X-SMS-Id` for idempotency.

## Gateway health/status

Do not overload SIP MESSAGE with all future management data.

Add a small authenticated HTTPS status channel later, for example:

```json
{
  "gateway_id": "...",
  "version": "...",
  "online": true,
  "sip_registered": true,
  "sim": {
    "carrier": "China Unicom",
    "slot": 0,
    "service": "IN_SERVICE"
  },
  "battery": 78,
  "charging": true,
  "temperature_c": 31.2,
  "network": "wifi"
}
```

This is outbound from the gateway to the server; never expose a root-control HTTP server on the Android device.

---

# Phase 5 — Public server project

Implement in the separate `gsm2sip-server` repository.

## Asterisk

Use current Asterisk with `res_pjsip`, not `chan_sip`.

Gateway endpoint baseline:

```ini
direct_media=no
rtp_symmetric=yes
force_rport=yes
rewrite_contact=yes
```

Requirements:

- TLS-only production listener.
- SRTP required for gateway/client endpoints.
- strong random endpoint passwords.
- separate gateway and human-client accounts.
- ACL/fail2ban/firewall.
- sane registration expiry.
- media port range explicitly firewalled.
- no anonymous calls.

## Routing

Incoming GSM call:

```text
gateway INVITE
 -> identify gateway account
 -> read SIM DID / caller ID
 -> route to primary-phone SIP endpoint(s)
 -> optionally ring multiple authorized clients later
```

Outgoing:

```text
primary phone SIP INVITE
 -> authorize number and account
 -> choose gateway/SIM
 -> relay to gateway
 -> gateway places cellular call
```

Never permit arbitrary unauthenticated callers to use the gateway as a PSTN/GSM termination service.

## SMS API

Server receives SIP MESSAGE from gateway and stores normalized SMS.

Expose authenticated API to companion app:

```text
GET  /v1/messages
POST /v1/messages
GET  /v1/messages/{id}
GET  /v1/gateways
GET  /v1/calls
WS   /v1/events
```

Use push notifications only for event notification; sensitive SMS content should be fetched from our server after authentication rather than included in third-party push payloads.

## Rate limits

Enforce independently at both server and Android gateway.

Examples:

- outbound SMS.
- outbound calls/minute.
- maximum call duration if desired.
- authentication failures.
- SMS API writes.

---

# Phase 6 — Primary-phone client project

Implement in separate `gsm2sip-client-android`.

No root.

## V1

Features:

### SMS
- inbox.
- conversation grouping.
- sender/number.
- Chinese SMS.
- copy OTP action.
- reply/send.
- sent/submitted/delivered/failed states.
- search.
- optional notification redaction.

### Gateway
- online/offline.
- last heartbeat.
- SIP registration.
- carrier.
- signal/service state where available from gateway.
- battery/charging.
- app/gateway version.

### Calls
Initially:

- display synchronized call history.
- launch/configure external SIP client.
- do not implement our own SIP stack yet.

## V2

Evaluate integrated SIP calling only after V1 is stable.

Requirements if integrated:

- Android Telecom/ConnectionService integration.
- foreground call notification.
- Bluetooth/headset support.
- audio focus.
- lock-screen incoming-call UI.
- reliable push/wakeup for incoming calls.
- SIP/TLS + SRTP.
- network handover handling.

---

# Phase 7 — Unattended operation / reliability

The gateway is intended to run continuously.

## Gateway watchdog

Add health dimensions:

- foreground service alive.
- root available.
- SIP registered.
- cellular service available.
- SIM present.
- audio route/profile valid.
- last successful SMS relay.
- last successful call.
- device temperature.
- charger state.
- free storage.

Do not reboot aggressively on a single failure.

Suggested escalation:

```text
failure
 -> retry component
 -> rebuild SIP transport
 -> restart GatewayService
 -> only then optional app/process restart
 -> device reboot only after repeated confirmed unrecoverable failures
```

## Server-side offline alert

If gateway misses heartbeats for a configured interval:

- companion-app notification.
- optional email/other alert later.

## Network transitions

Test:

- Wi-Fi AP reboot.
- DHCP address change.
- loss/recovery of Internet.
- DNS failure.
- SIP server restart.
- TLS certificate renewal.
- gateway reboot.
- gateway process kill.
- Android screen-off for 24h+.

---

# Phase 8 — Device compatibility

Audio routing is the largest hardware-specific risk.

Before committing a gateway handset:

1. run the repository device-check tooling.
2. identify SoC/vendor Audio HAL.
3. verify digital caller capture.
4. verify agent -> modem uplink injection.
5. test with local mic/speaker physically muted where possible.
6. make at least several long calls.
7. reboot and repeat.

Prefer a Qualcomm device with a known-compatible route/profile.

Device support should be recorded in:

```text
docs/devices.md
```

with:

- device.
- SoC.
- ROM/version.
- kernel.
- working capture source.
- working injection path.
- known issues.

---

# Phase 9 — CI / release engineering

Gateway repository CI should:

1. compile Android app.
2. run JVM unit tests.
3. build native audio tools from pinned source.
4. verify no unexpected executables were added.
5. generate checksums.
6. build Magisk package.
7. sign APK with controlled release key on release workflow.
8. publish arm64-first artifact unless a tested 32-bit target is intentionally supported.

Do not commit signing secrets.

Add a dependency/reproducibility record for every release.

---

# Security acceptance criteria before real SIM use

Do not leave the project connected to the real phone number over the public Internet until all of these are true:

- [ ] No default/hidden connection to third-party SIP or STUN services.
- [ ] TLS UTF-8 stream framing fixed and tested.
- [ ] SIP TLS works with hostname/certificate verification.
- [ ] SRTP can be configured as mandatory and cannot silently downgrade.
- [ ] Server only allows authenticated gateway/client endpoints.
- [ ] Server has outbound-call authorization.
- [ ] Server and gateway both rate-limit outbound SMS.
- [ ] Release APK uses our own signing key.
- [ ] Bundled native executables are reproducible/auditable.
- [ ] SIP credentials are not logged.
- [ ] PermissionController is not globally removed/disabled.
- [ ] App does not silently re-enable root after a manual deny.
- [ ] Reboot/network/server-restart recovery has been tested.
- [ ] Chinese SMS over TLS passes automated and real-device tests.

---

# MVP milestones

## M0 — clean fork

- remove third-party defaults.
- introduce tests.
- own signing process.
- reproducible native tools.

## M1 — safe gateway

- TLS framing fix.
- registration-expiry fix.
- less-invasive permission handling.
- production TLS + mandatory SRTP mode.
- call/SMS rate limits.

## M2 — self-hosted server

- PJSIP Asterisk.
- public TLS endpoint.
- SRTP.
- authenticated routing.
- SMS service/API.
- basic gateway health.

## M3 — end-to-end test

From a primary Android phone outside the gateway network:

- incoming Unicom call rings remotely.
- answer with two-way audio.
- outgoing call uses Unicom number.
- incoming Chinese SMS appears remotely.
- outgoing Chinese SMS sends and reports status.
- gateway/server/client reboot independently and recover.

## M4 — companion app

- SMS UI.
- OTP UX.
- call history.
- gateway health.
- notifications.

## M5 — integrated calling (optional)

Only after the rest is stable:

- evaluate replacing external SIP client with integrated SIP/Android Telecom.

---

# Immediate next work in this repository

Recommended implementation order:

1. Add protocol/TLS tests.
2. Fix `TlsSipTransport` byte framing.
3. Remove `callagent.pro` and public STUN defaults.
4. Implement server-granted REGISTER expiry.
5. Add mandatory-SRTP production switch.
6. Replace debug release signing workflow.
7. Replace unauditable `tinycap`.
8. Remove global PermissionController hiding.
9. Remove automatic Magisk DB root re-grant.
10. Replace global SMS-limit override with app-level rate limits.
11. Stop mutating/silencing the normal SMS app by default.
12. Add gateway health/status protocol.
13. Build the separate server project.
14. Validate end-to-end with an external SIP client.
15. Build the companion Android app.

---

## Design principle

Keep the difficult, hardware-specific code in this gateway fork:

```text
Android Telephony + Audio HAL + modem audio + SMS
```

Keep Internet-facing logic on the server:

```text
authentication + routing + API + policy + monitoring
```

Keep user experience on the non-root primary-phone app:

```text
SMS + status + history + notifications + eventually calls
```

This separation lets the gateway remain small and appliance-like, reduces the attack surface of the rooted phone, and makes it possible to update server/client functionality without touching the fragile vendor audio path.
