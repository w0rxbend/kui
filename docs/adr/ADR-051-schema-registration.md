# ADR-051 — Registering a schema is an endpoint of `kui-schema`, and a rejection is the registry's own sentence

- Status: Accepted
- Date: 2026-09-06

## Context

`Register schema` has been drawn on the Schema Registry screen since M6 and has shipped
`aria-disabled="true"` with a sentence that was true: *"KUI cannot register a schema yet: the
gateway serves no endpoint that writes one."* The published `docs/api/openapi.json` carried six
schema paths — `GET subjects`, `GET/PUT compatibility`, `GET/PUT subject compatibility`,
`GET versions`, `GET version` and `POST version compatibility` — and the last of those is the
compatibility *check*, which stores nothing. Nothing registered. It is the one bullet of M6 with no
backend capability behind it, and definition-of-done rule 2 forbids a screen that needs a capability
that does not exist.

Three things were already in place and used by nothing.

- **The RBAC action.** `Action.SchemaCreate` is declared at
  `libs/security-core/src/kui/security/rbac/Vocabulary.scala:146` as
  `Action(Resource.Schema, "CREATE", true)`. Before this change its only other reference in the
  whole repository was its own implication row, `SchemaCreate | SchemaEdit | SchemaDelete =>
  Set(SchemaView)`. It is marked altering, so ADR-047's read-only rule applies to it with no second
  rule being written, and `RbacLawsSuite` already covers the implication.
- **The gateway's route.** `SchemaMutationEndpoints` is already an entry in
  `ServiceContracts.byService`, and `ContractRouting.derive` builds the gateway's public routes from
  that list. A write added to the list gets its `/api/v1` route with **no gateway change at all**.
  This was confirmed before it was assumed: `services/gateway` is untouched by this change.
- **The port's shape.** `SchemaRegistryPort` already had eight methods that never throw and answer
  `Left[KuiError]` for everything that went wrong, and `RegistryHttp` already spoke the Confluent
  API to four registry implementations.

## Decision

### 1. Registration is an endpoint of `kui-schema`, not a fold at the gateway

`POST /internal/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions`, published by the
gateway as `/api/v1/...`, taking `{schemaType, definition, references}` and answering
`{subject, id, version}`.

It is a POST onto the same path `schema.versions` is a GET on — the version *collection*, which is
what a registration appends to, and which is also the registry's own shape. The published document
therefore gains **no path**: it gains one operation on a path that already exists, the way a topic
collection is a GET and a POST.

The gateway was rejected as the home for it, and the reasoning is the mirror image of ADR-049's.
Search is a fold at the gateway because it owns no data and calls three services; registration is
one call to one upstream, and everything it needs is already in `kui-schema` — the registry client
with its circuit breaker, bulkhead, failover list and credentials; the `readOnly` flag on this
service's own cluster profile; and the size bound on operator text before it is forwarded. A
gateway fold would have to be handed all four, and the gateway deliberately does not decode a
service's request bodies at all (`NameSource.RequestBody` exists to say so) — it proxies them.

### 2. `Action.SchemaCreate` was already the right action, and nothing widens

The endpoint declares
`ResourceRequirement.named(Resource.Schema, "subject", Action.SchemaCreate)`, which is the same
declaration shape the two compatibility PUTs carry and is read by the same `EndpointDecision.decide`
at both enforcement points. No vocabulary entry is added, no implication row changes, and
`SchemaCreate` stops being a declared-and-unused action.

Because the action is `isAlter`, a read-only cluster refuses it through the existing gate. The
refusal is made **twice**, on purpose and at different distances: `EndpointDecision` refuses it at
the edge from `ClusterFlags`, and `RegisterSchemaUseCase` refuses it again from this service's own
`RegistryProfile` before the port is even resolved — so a registration against a read-only cluster
never appears in the registry's access log. That second one is ADR-047 §2's requirement and is
asserted by the absence of a recorded call, not by a status code.

### 3. A rejection is `KUI-VALIDATION` carrying the registry's own message in `details[0]`

A registry answering 409 or 422 to a registration is not an upstream fault. It has read the
operator's schema and refused it, and the sentence it sends —
*"Schema being registered is incompatible with an earlier schema for subject 'orders-value'"*,
followed by the field that broke the rule — is the only part of the answer anybody can act on.

So `RegistryHttp.errorFrom` maps those statuses to `ApplicationError.Invalid`, whose code is
`KUI-VALIDATION` and whose `details` become the envelope's `details` array. The registry's sentence
travels in `details[0].restrictions[0]`, with `details[0].field` set to `definition` — the browser's
own field name, because a form reads `details` to decide which input to mark. ADR-034 forbids
echoing an upstream body wholesale; this is one field the registry writes for humans, and nothing
else it sent. A registry that will not answer at all is `KUI-UPSTREAM-UNAVAILABLE`, unchanged.

No new `ErrorCode` is introduced. The thirty-one that exist cover this, and
`frontend/packages/api/src/constants.generated.ts` is compared byte for byte.

### 4. The version is `number | null`, because the registry answers registration with an id alone

`POST /subjects/{subject}/versions` returns `{"id": N}`. That is the entire documented Confluent
response, and Apicurio's and Karapace's compatibility layers copy it. The version number is a second
question, asked with `POST /subjects/{subject}` — the registry's "which version is *this* schema"
lookup, chosen over `GET .../versions/latest` because latest is a race: somebody else's registration
between the two calls would hand this operator a version that is not theirs.

A second call can fail after the first has succeeded. When it does, the schema **is** registered and
KUI does not know its number. All three of the obvious answers are wrong:

- returning a failure tells an operator to register it again, which is a second version;
- inventing "the previous latest plus one" prints a version number that may not exist;
- returning `0` is the never-a-zero rule broken in the one place a number is load bearing.

So the field is absent, and the screen says the schema registered and the number could not be read.
It is the uncommon branch — both calls go to the same registry over the same pool, one immediately
after the other — and it is a real one.

### 5. This mutation writes no audit record, and that is a gap rather than a decision

ADR-047 §3 requires one `MutationRecord` per mutation, successful or failed, and the record's `kind`
is a `kui.security.audit.MutationKind` — a sealed enum in `libs/security-core` with twelve cases and
none for a registration. Writing a thirteenth is a one-line change to a file this wave's partition
does not open, and inventing a second vocabulary in `services/schema` is exactly the drift
`SchemaEndpointClassificationSuite` exists to catch.

So `RegisterSchemaUseCase` logs the four facts a record would carry — principal, cluster, subject,
outcome — and this build ships **one unaudited mutation**. It is asserted rather than described:
`SchemaEndpointClassificationSuite` fails the day `MutationKind` grows the case, which is the day
the use case has to be given an `AuditSink`. The needed change is

```scala
case RegisterSchema extends MutationKind("schema.subject.version.register")
```

in `libs/security-core/src/kui/security/audit/AuditSink.scala`, after `SetSubjectCompatibility`.

## Consequences

- M6's last bullet has a capability behind it. `REGISTER_UNAVAILABLE_REASON` and its
  `aria-disabled` can go; the browser half is a separate packet's.
- `services/schema/api/openapi.json` gains one operation and two schemas —
  `RegisterSchemaRequest` and `RegisteredVersionDto` — and **no path**: 9 paths, 12 operations,
  20 component schemas, regenerated and committed with this change. The merged
  `docs/api/openapi.json` and `docs/api/openapi.browser.json` are somebody else's to regenerate, and
  `./mill services.gateway.api.openApiCheck` is red until they do, exactly as ADR-049 recorded for
  the search endpoint.
- `SchemaWiring.MaxRetries` still applies. Registering the same schema under the same subject twice
  is idempotent by the registry's own definition — it answers the existing id and version and adds
  no version — so a retried POST cannot create a second version.
- `services/gateway` is not edited, and `ServiceContracts.byService` still holds six services.

## Alternatives rejected

- **A gateway fold.** It would need the registry client, the read-only flag and the decoded body,
  none of which the gateway has or should have. See §1.
- **A new `MutationKind` invented locally, or the nearest existing case reused.** Two vocabularies
  for one audit trail, or a record that names an operation that did not happen. See §5.
- **A new `ErrorCode` for "the registry refused your schema".** `KUI-VALIDATION` with the registry's
  own message in `details` is what that code is for, and house rule 3 forbids widening the roster.
- **Passing the registry's whole error body through.** ADR-034 forbids it: an upstream body may
  carry another system's internals. One field, written for humans, is enough.
- **Making `version` required by re-reading the subject's version list until a number appears.** It
  turns a rare unknown into an unbounded wait and can still answer somebody else's version.
- **Reusing `CompatibilityCheckRequest` as the registration body.** The two requests have identical
  fields and opposite consequences, and one schema name on both operations in every generated client
  is how a caller sends the wrong one.

## Reversibility

High. The endpoint is one entry in `SchemaMutationEndpoints.all`, one method on
`SchemaRegistryPort`, one use case and one route. Removing it removes the gateway's public route
with no gateway change, for the same reason adding it needed none.
