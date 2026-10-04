# SMS inbox recovery

The gateway has two separate inbound paths. The normal path journals each
`SMS_RECEIVED` broadcast before waking the service. Optional recovery scans
Android's retained SMS inbox so a later app or service start can import a
message that the gateway missed but the system did store.

Recovery is opt-in and uses Android's ordinary `READ_SMS` runtime permission.
The app does not request that permission at boot, become the default SMS app,
write to the system SMS provider, or use a root/Magisk permission broker. If
the user denies or revokes `READ_SMS`, recovery reports that it is unavailable;
the broadcast receiver path remains separate.

The selected recovery scope and gateway identity survive activity recreation.
If the user has not confirmed a full-inbox import yet, reopening the app previews
the retained inbox again and asks for confirmation; it never turns that choice
into an automatic import. An identity change cancels the pending confirmation.

The first choice protects messages from the time recovery is enabled onward.
The setup stores both the current provider row boundary and enable time, so it
does not import the existing inbox. A separate preview and confirmation can
select the full inbox currently retained by Android; those messages are then
uploaded to the currently paired gateway. Repeating setup for the same pair
keeps the original boundary. A different gateway identity gets a separate
scan scope and cannot take ownership of the old gateway's events.

Each scan reads at most 120 inbox rows. The row identity ledger, recovered
`sms.received` event, and page checkpoint are committed in one SQLite
transaction. Incremental scans retain their high-water row ID. A separately
checkpointed reconciliation sweep runs on first enable and at most once per
24 hours; it captures a maximum row ID when it starts, so rows arriving during
the sweep are left for incremental scanning. This also checks lower row IDs
that may appear after the provider database is rebuilt. Re-reading an already
seen provider row does not add another event.

The provider row ID is combined with stable row fields to form its local
identity, which helps distinguish an ID reused for a different message. A
broadcast can be linked to a provider row only when sender and body match,
the timestamps are within ten seconds, known subscription IDs do not conflict,
and exactly one unmatched broadcast is a candidate. When the evidence is
ambiguous, the importer keeps a recovered event as a possible duplicate rather
than collapsing two real, identical SMS messages. Missing provider
subscription metadata stays unknown; the gateway never picks a default SIM.

Android's inbox provider does not retain the original multipart segment count.
The current event protocol requires a positive `parts` value, so a recovered
row is stored with `source=recovered` locally and sent with `parts=1` as a
protocol placeholder. That value does not assert that the original SMS had one
network segment. The uploaded event otherwise uses the existing `sms.received`
wire format; the server currently rejects additional payload fields, so the
local `source` label is not sent to the server.

This is best-effort recovery, not an end-to-end delivery guarantee. It can
recover only messages that the carrier delivered to the phone and Android
retained in the inbox until a scan reads them. It cannot recover a message the
carrier never delivered, one Android never stored, or one deleted before the
scan. Once an event is journaled, the existing durable upload queue retries it
until the server acknowledges it.

Settings form values survive screen recreation, while the SIP password and
one-time pairing code are excluded from saved view state. The SIP password is
loaded from the encrypted Android Keystore-backed store after recreation.
