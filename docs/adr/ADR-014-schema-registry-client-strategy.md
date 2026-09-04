# ADR-014 — Schema Registry: own REST client for management, Confluent serializers for wire format

- Status: Accepted
- Date: 2026-09-03

## Context

PLAN §12 posed two options: wrap the Confluent client entirely, or write an sttp client for
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
