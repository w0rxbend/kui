# ADR-045 — A destructive operation is confirmed against a server-computed plan, not against a form

- Status: Accepted
- Date: 2026-09-04

## Context

M4 ships KUI's first offset reset (`CG-004`). M3, which lands before it, already ships three
operations that change a Kafka cluster: produce (`MP-001`), resend (`MP-003`) and purge
(`MS-008`). M5 adds create, delete, clone, recreate, partition increase and replication-factor
change. That is a growing family of operations whose common problem is the same one:

**the operator confirms a description of an intent, and the system then performs something
else.** An offset reset by timestamp is the clearest case. The operator types "reset to
09:00". What actually happens depends on what offsets exist at 09:00 on each of twelve
partitions, on whether the log start has moved past that point, and on KIP-122's rule that a
timestamp with no matching record resolves to the *end* offset — which is the opposite of what
the operator expects and is how a reset intended to replay a morning's traffic instead skips
it entirely.

The reference products do not solve this. Kafbat's wizard submits the specification and shows
the operator nothing; Kouncil's does not clamp out-of-range offsets at all, which
`research/kafka/admin-capabilities.md` §3 calls "a foot-gun". Neither shows the numbers that
will be written before writing them.

The second problem is that a check performed at confirmation time is not a check performed at
write time. A group that was `Empty` when the operator opened the dialog may have a member by
the time they press the button.

## Decision

**Every destructive operation whose effect cannot be read off its own request is confirmed in
two phases, and the second phase accepts only a server-issued token naming the exact effect.**

### 1. Phase one — plan

`POST <resource>/plan` takes the operation's specification, resolves it against live cluster
state, and returns a **plan**: for each unit of work (a partition, a topic, a record range),
the current value, the proposed value, whether the proposal was clamped, and any warning. The
plan is a document the browser renders as a table. No cluster state is changed.

### 2. The plan token

A plan carries an opaque `planToken`: an HMAC over the tuple
`(clusterId, resourceId, the resolved values, expiry)` using the streaming cursor key already
configured for ADR-026. Validity is five minutes. Reusing the cursor key is deliberate: it
introduces no new secret, no new configuration key and no new rotation procedure, and it has
the property the token needs — any replica can verify a token any other replica minted.

### 3. Phase two — apply

`POST <resource>` accepts **only** a `planToken`. It never accepts a raw specification. It
re-verifies the operation's preconditions immediately before the write, and it writes exactly
the values the token names — not a recomputation, which could differ from what the operator saw.

An expired or tampered token is `KUI-VALIDATION`. A precondition that has become false between
plan and apply is that operation's own state error (for an offset reset, `KUI-INVALID-STATE`).

### 4. When this applies

An operation needs a plan phase when **its effect is not a function of its request alone** —
that is, when computing what will happen requires reading cluster state. Offset reset by
timestamp, duration, shift or "to earliest/latest" all qualify. So do M5's replication-factor
change and partition increase.

An operation whose request *is* its effect does not need one: producing a record the operator
typed, deleting a topic named in the URL, purging up to an offset the operator supplied. Those
still carry the `Mutation` marker and the audit record of ADR-047; they simply have nothing to
preview that the request does not already say.

## Consequences

- The browser's confirm button is disabled until a plan has been fetched and rendered. A UI
  that can submit without a plan cannot exist, because the apply endpoint has no other input.
- The plan is a contract type and appears in OpenAPI, so an API user gets the same protection
  as a browser user; `curl` cannot skip the preview either.
- Two round trips per destructive operation. That is the point.
- M5's mutations reuse the mechanism rather than inventing a per-operation confirmation.
- A plan is not a lock. It narrows a race; it does not close one. That is why the precondition
  is re-checked at apply time and why Kafka's own rejection remains the third line of defence.

## Alternatives rejected

- **A single-phase form with client-side preview.** The client cannot compute the offsets; only
  the broker knows them. A preview computed anywhere but the server is a guess rendered
  authoritatively.
- **A single-phase call that returns what it did.** Correct for reversible operations and
  useless here: an offset reset is not undoable once a consumer has committed past it.
- **A server-side session holding the plan.** Requires sticky sessions or shared state for a
  five-minute value. An HMAC'd token is stateless and works behind any load balancer, which is
  the same argument ADR-026 makes for cursors.
- **A confirmation typed by the operator ("type the group name to confirm").** Guards against
  clicking the wrong row; guards against nothing in the class of failure this ADR is about,
  where the operator is confident and the system's arithmetic is the surprise.

## Reversibility

High for the mechanism, low for the shape. Dropping the plan phase later would be a contract
break for every client that has learned to call it. Adding it to an operation that shipped
without it is easy.

## References

ADR-026 (the cursor key this reuses); ADR-034 (error envelope);
ADR-047 (the marker, refusal and audit record that accompany every mutation);
`research/kafka/admin-capabilities.md` §3 (KIP-122's rules and the reference products'
behaviour).
