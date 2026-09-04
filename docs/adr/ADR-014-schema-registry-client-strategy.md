# ADR-014 — Schema Registry: own REST client for management, Confluent serializers for wire format

- Status: Accepted
- Date: 2026-09-03

## Context

The schema-registry research posed two options: wrap the Confluent client entirely, or write an sttp client for
the registry REST API and use Scala libraries for (de)serialization. Dynamic Protobuf and
JSON-Schema decoding against registry-supplied schemas has no Scala implementation.

## Decision

- **Management (`kui-schema-service`)**: an own Tapir/sttp-4 client for the Confluent-compatible
  REST API (subjects, versions, `referencedby`, compatibility, mode, `schemas/ids`, `types`,
  contexts), accepting both `application/vnd.schemaregistry.v1+json` and
  `application/vnd.schemaregistry+json`, basic auth or OAuth2 client credentials (never both),
  mTLS, failover list. Errors map through a sealed `SchemaRegistryError` covering the
  documented `error_code` table; unknown codes become `Upstream(code)`.
- **Wire format (`libs/serde-confluent`)**: wrap `kafka-avro-serializer`,
  `kafka-protobuf-serializer`, `kafka-json-schema-serializer` **8.3.1** behind the `Serde[F]`
  SPI in one isolated module with its own registry client caches (schema by id, size-bounded;
  subjects, TTL). This module is the only place with Jackson/Guava/Confluent on the classpath
  and is an optional runtime dependency of `kui-message-service` and `kui-topic-service`
  (analysis needs decoding too).
- Vulcan 1.13.0 and Avro 1.12.2 are used for KUI's own Avro records (audit export), not for
  user topics. ScalaPB 0.11.20 + protobuf-java 4.36.1 for `ProtobufFile`/`ProtobufRaw` serdes;
  `networknt json-schema-validator` 3.0.7 for validating produced JSON-Schema messages;
  `tapir-apispec-docs` + `sttp.apispec:jsonschema-circe` for JSON Schema generation in the
  produce dialog.
- `kui-message-service` reaches the registry through `libs/serde-confluent`, not through
  `kui-schema-service` (decoding needs per-record latency).

**Amendment 1 — the licence check came back mixed, and two of the three formats no longer need
Confluent code at all.**

The original decision said "wrap `kafka-avro-serializer`, `kafka-protobuf-serializer`,
`kafka-json-schema-serializer` 8.3.1" and recorded the Confluent Community License (CCL) as a
consequence for the whole module. Both halves of that turned out to be wrong in a way worth writing
down, because the difference decides what a KUI operator has to agree to.

*What the check found* (POM `<licenses>` blocks, `packages.confluent.io`, 2026-09-04):

| Artefact, 8.3.1 | Declared licence |
| --- | --- |
| `io.confluent:kafka-avro-serializer` | Apache-2.0 |
| `io.confluent:kafka-schema-registry-client` | Apache-2.0 |
| `io.confluent:kafka-schema-serializer` | Apache-2.0 |
| `io.confluent:kafka-protobuf-serializer`, `kafka-protobuf-provider` | **Confluent Community License**, then Apache-2.0 |
| `io.confluent:kafka-json-schema-serializer`, `kafka-json-schema-provider` | **Confluent Community License**, then Apache-2.0 |

None of them is published to Maven Central; all of them are on `packages.confluent.io`, so using any
of them also means adding a second resolver to the build.

*What follows.* What `kafka-avro-serializer` adds over `org.apache.avro:avro` is a registry client
and a schema cache — and KUI has to own both of those regardless. The client has to be
`libs/http`'s `UpstreamClient`, or the registry gets its own invisible retry loop instead of KUI's
circuit breaker, bulkhead and failover (ADR-037, CL-008); the cache has to be `libs/cache`'s
`BoundedCache`, or it reports none of the metrics ADR-016 requires of every other cache in the
product. Once those two are KUI's, the only Confluent code left doing work is
`GenericDatumReader` — which is Avro's own, Apache-2.0, and on Maven Central.

So `libs/serde-confluent` as built contains **no Confluent code**:

- **Avro** — `org.apache.avro:avro` 1.12.2, Apache-2.0, Maven Central.
- **JSON Schema** — the payload is JSON; `com.networknt:json-schema-validator` 3.0.7, Apache-2.0,
  Maven Central, validates what the produce form sends.
- **Protobuf** — *not implemented*. Decoding it dynamically needs a `.proto` parser and a descriptor
  builder, and the only maintained one is Confluent's `kafka-protobuf-provider`, which is CCL. A
  Protobuf payload therefore decodes to a named `DeserializeFailure` that says so, rather than to
  Base64 or to silence.

The module keeps its name. It is still the seam where registry-backed formats live, still the only
module allowed Jackson on its classpath (rule A12), and still an optional runtime dependency.

*What this changes for an operator.* Nothing they must agree to. CCL permits self-hosted use and
forbids offering the software as a competing managed service — a licence a KUI operator may well
accept, and not one KUI can accept on their behalf without saying so. Adding Protobuf later is
therefore two deliberate steps and not one commit: adding `packages.confluent.io` as a build
resolver, and recording in `docs/operations` that a KUI built with Protobuf support carries a
CCL component.

**Amendment 2 — Protobuf decodes after all, by parsing the schema ourselves.**

Amendment 1 concluded that Protobuf could not be decoded without Confluent's CCL-licensed
`kafka-protobuf-provider`, and left a Protobuf payload rendering as a named refusal. That conclusion
was about *libraries*, and it skipped the third option: writing the reader.

What a viewer needs from a `.proto` schema is a field table — number, name, label, type — and what it
needs from the payload is the published wire encoding. Both are small and both are stable: the
encoding has not changed since 2008, and the language subset a schema *held in a registry* uses is
narrow, because a registry schema has to be self-contained enough for a consumer to compile.
`libs/serde-confluent` therefore contains:

- `ProtoSchema` — a recursive-descent parser for that subset: `syntax`, `package`, `option`,
  `message` (nested), `enum`, `oneof`, `map<k, v>`, `reserved`, every scalar type, and the three
  field labels. `import` is parsed and *refused at use*: a field whose type comes from another file
  cannot be decoded, and the failure names the file rather than silently rendering the field as an
  unknown number.
- `ProtobufPayload` — the wire decoder, including Confluent's message-index prefix (the varint path
  that says which message of the schema a record is, which Avro and JSON Schema do not have). It
  follows Protobuf's canonical JSON mapping where that mapping has an opinion: 64-bit integers as
  strings, `bytes` as base64, enums by name. Fields the schema does not declare are *shown*, as
  `unknown_<n>`, because the usual cause is a record written with a newer schema and an operator
  cannot otherwise discover that.

**Decision.** KUI reads Protobuf and does not write it. Encoding needs the reverse of the same table
plus canonical-JSON parsing for every scalar type; a decode that is subtly wrong shows one bad row on
a screen, while an encode that is subtly wrong puts a malformed record in a topic that outlives the
mistake. The produce path refuses a Protobuf schema by name until an encoder exists and has been
tested against real producers.

**Tradeoff.** KUI now owns a parser for a language it does not control. The mitigation is scope —
schema text, not arbitrary `.proto` files — and tests: the decoder is checked against bytes produced
by `protoc` itself, not only against the encoder in its own suite.

**What this reverses.** The build change and the licence note Amendment 1 anticipated are no longer
needed. `libs/serde-confluent` still contains no Confluent code, and a KUI operator still has nothing
extra to agree to.

## Evidence

- `research/scala/ecosystem-mapping.md` F7 (versions, Confluent Community License, dead
  `circe-json-schema`, ScalaPB lacks dynamic schemas).
- `research/kafka/admin-capabilities.md` §6 (REST endpoints, error codes, non-Confluent registries).
- `research/kafbat/architecture.md` F6, F13, D7.

## Consequences

- Confluent Community License applies to `libs/serde-confluent` (self-hosted use permitted;
  documented in `docs/operations`). Deployments can run without it (String/JSON/Avro-embedded
  serdes still work).
- Two components talk to the registry; both get their config from `ClusterProfile`.

## Alternatives rejected

- Full Confluent client wrap for management: drags Jackson/Guava into the schema service and
  hides the REST surface KUI wants to expose (contexts, modes).
- Pure-Scala wire format: no dynamic Protobuf/JSON-Schema decoding library exists.

## Reversibility

Medium. Both sides sit behind ports; replacing the Confluent serializers later is one module.
