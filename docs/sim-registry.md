# Local SIM registry contract

The gateway keeps server-assigned `sim_id` UUIDs separate from Android's
`subscriptionId`, SIM slot, and Telecom account. The server owns the wire
`mapping_revision`; Android identifiers are local lookup values and are never
used to infer an identity from a slot or account-handle string.

## Confirmation and revision rules

The UI fetches a server proposal, lets the user choose the corresponding local
subscriptions, then calls `SimRegistry.confirmMappings(context, bindings,
serverRevision)` once with the complete proposal. The registry validates the
whole set and persists it atomically. New or changed bindings require a server
revision greater than the last adopted revision. A changed physical card
cannot reuse a `sim_id` when the old and new salted fingerprints are both
available; request a new server SIM UUID instead. A one-item confirmation is
only for re-confirming an unchanged binding.

`mappingRevision` is authoritative and goes over the wire. The separate,
monotonic `localRevisionBarrier` is a device-side invalidation counter. It
advances when local observations invalidate identity, such as a subscription
disappearing, a card moving slots, or a fingerprint/account changing. A caller
can resolve a mapping once to obtain the barrier, then pass that value to
`resolve(..., expectedLocalRevisionBarrier)` immediately before dispatch. A
changed barrier rejects the operation even when the server revision has not
changed.

Heartbeat invalidations are applied atomically with
`applyHeartbeatInvalidation(context, serverRevision, invalidatedSimIds)`. The
server may advance its revision while invalidating one or more SIMs; those
bindings become `UNVERIFIED`, and the remaining bindings adopt the new server
revision. Replaying the same committed response is idempotent. A
`SIM_MAPPING_CHANGED` response followed by `GET /sims` uses
`invalidateAllAtServerRevision`: it adopts a non-regressing server revision
and marks every local binding unverified without activating or remapping any
SIM. A gateway owner change clears server SIM UUIDs and local revision state.

## Identity and call routing

ICCID is read transiently. The registry stores only a salted SHA-256 fingerprint
with a random device-local salt; neither raw ICCID nor fingerprint is included
in snapshots, logs, or uploads. If ICCID cannot be read, explicit local
confirmation is tied to Android's boot count and expires after a reboot (or
when boot count cannot be verified). If a fingerprint becomes available later,
the prior un-fingerprinted confirmation is invalidated and must be repeated.
Missing subscriptions and identity mismatches are marked unverified once and
advance the local barrier.

SMS availability is independent of voice-account availability. API 26–30 can
use a confirmed `subscriptionId` for SMS, but the registry reports voice
unavailable because those releases lack the supported public
subscription-to-`PhoneAccountHandle` association used here. On API 31+, the
registry obtains the handle from `TelephonyManager.createForSubscriptionId`,
checks the Telecom account and call capability, and uses exact handle equality
for incoming calls. Outgoing calls always use `TelecomManager.placeCall` with
`EXTRA_PHONE_ACCOUNT_HANDLE`; there is no default-account fallback.

The gateway's call dispatch ledger persists `(call_id, sim_id, mapping_revision,
direction)` before either SIP-to-GSM or GSM-to-SIP dispatch. Existing call IDs
are refused; interrupted `dispatching` entries recover to `unknown` and are not
automatically replayed.
