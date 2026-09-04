# ADR-047 — Every mutation ships with a marker, a per-cluster read-only refusal and an audit record, from the first one

- Status: Accepted
- Date: 2026-09-04

## Context

`docs/ROADMAP.md` §3 states the rule: "Mutations arrive in M5 together with read-only mode and
audit, so no destructive action ever ships without its safety net."

The milestone plans do not honour it, and neither noticed:

- **M3 ships three mutations.** Produce (`MP-001`), resend (`MP-003`) and purge (`MS-008`)
  change a Kafka cluster. `docs/plans/M3/DEVPLAN.md` §2 criterion 14 requires all three to be
  "refused on a read-only cluster with `KUI-READ-ONLY`" — while §3 of the same document says
  "no read-only mode" and "no audit records" until M5. The criterion names a mechanism the
  milestone declares out of scope. Purge in particular is irreversible: it moves a log's low
  watermark and the records below it are gone.
- **M4 ships three more** (offset reset, delete group, delete offsets) and *did* notice: its
  §10 D2 invents a three-part substitute — a `Mutation` marker, a per-cluster `readOnly`
  refusal, and an `AuditSink[F]` port with a log sink — and calls the roadmap's ordering a
  contradiction it has to resolve.
- **M5 will enumerate.** Its exit criterion is "every mutating operation in every service
  introduced so far returns `KUI-READ-ONLY`; the check is on the operation's `Mutation` marker,
  verified by a test that enumerates all endpoints and asserts each is classified." If M3's
  three endpoints ship without the marker, that test's first run is a retrofit across a service
  that has already shipped.

M4's answer is right and it is not M4's to own alone: the first mutation in the product is
M3's, not M4's.

## Decision

**A mutating endpoint may not ship without all three of the following. They are cheap, they are
ports, and M5 fills them in rather than introducing them.**

### 1. The `Mutation` marker

Every endpoint that changes cluster state carries a `Mutation` marker in its Tapir description
and a `MutationKind` value in the application layer. The marker is what M5's read-only policy
and M6's RBAC both key on, so both arrive as a policy over an existing classification rather
than as a hunt through endpoint lists.

A contract test in each service enumerates its own endpoint list and asserts that every
endpoint is classified — as a mutation or explicitly as a read. An unclassified endpoint fails
the build. This is M5's enumeration test, written once per service by the milestone that
creates the service.

### 2. The per-cluster `readOnly` refusal

A cluster profile carries a `readOnly` flag (it is already a cluster-level setting in both
reference products). Any `Mutation` against a cluster whose profile says `readOnly` is refused
with `KUI-READ-ONLY` **before any Kafka client is touched** — before serialisation, before a
producer is created, before an offset is read.

This is not M5's global read-only *mode*, which is a deployment-wide policy with its own
configuration and its own UI treatment. It is the per-cluster half, it is a property of data
the profile already carries, and it costs one guard in the application layer.

### 3. The `AuditSink[F]` port and the `MutationRecord`

Every mutation — successful or failed — writes exactly one `MutationRecord` through an
`AuditSink[F]` port: cluster, resource, operation, the before and after values where they are
known, the outcome, the timestamp, and no credential. A failed mutation writes one too, with
`outcome = Failed`.

The milestone that ships the first mutation ships the port and a structured-log sink. M5 adds
the `__kui_audit` Kafka topic sink behind the same port and the viewer that reads it. M6 adds
the principal, which is `system` until identity exists.

`AuditSink[F]` and `MutationRecord` are declared **once**, in `libs/security-core`, because
three services will write through them and ADR-023 already places the audit model there.

### 4. What this is not

It is not RBAC (M6), not a global read-only mode (M5), not an audit viewer (M6), and not a
substitute for ADR-045's plan-token confirmation, which answers a different question (*what
will this do?*) than this ADR does (*may this happen, and what happened?*). An operation that
needs a plan carries both.

## Consequences

- M3 gains the three parts. Its criterion 14 becomes satisfiable instead of self-contradictory,
  and `libs/security-core` gains `MutationKind`, `MutationRecord` and `AuditSink[F]` in the
  milestone that first needs them rather than in the milestone that first names them.
- M4 keeps its D2 substitutes unchanged; they are now an inherited rule rather than a local
  invention, and M4 stops being described as "the milestone where KUI first changes something".
- M5's read-only enumeration test is a policy over an existing marker, and its Kafka audit sink
  is a second implementation of an existing port. Both shrink.
- Three services carry a guard whose global policy does not exist yet. That guard is one
  `if` and one test, and the alternative — retrofitting classification across shipped
  endpoints — is the one the roadmap's own ordering rationale exists to avoid.
- The roadmap's sentence becomes true rather than aspirational.

## Alternatives rejected

- **Move M3's produce, resend and purge to M5.** Removes the reason to open the message screen,
  and M3's parity checkpoint (superset of Kafbat in message exploration) depends on them.
- **Let M3 ship them bare and retrofit in M5.** This is what the plans currently do by
  accident. It puts an irreversible operation on an unauthenticated default deployment with no
  record of who ran it and no way to switch it off per cluster.
- **Ship only the marker now and defer the refusal and the record.** The marker alone protects
  the *next* milestone's work, not this milestone's operators. The refusal is the part that
  makes a shared staging cluster safe today.

## Reversibility

High. All three parts are additive: a marker on an endpoint, a guard in a use case, a port with
one implementation. Removing them later is mechanical; adding them to endpoints that have
already shipped is the expensive direction, which is the argument for taking them now.

## References

`docs/ROADMAP.md` §3 (ordering rationale) and M5 exit criteria; ADR-021 (RBAC model),
ADR-023 (audit and masking), ADR-034 (`KUI-READ-ONLY`), ADR-036 (the profile that carries
`readOnly`), ADR-045 (plan-token confirmation);
`docs/plans/M3/DEVPLAN.md` §2 criterion 14 and §3;
`docs/plans/M4/DEVPLAN.md` §10 D2 (tasks GRP-018, GRP-019, GRP-023, GRP-026);
`docs/plans/M3` tasks MSG-022, MSG-024, MSG-028, MSG-029.
