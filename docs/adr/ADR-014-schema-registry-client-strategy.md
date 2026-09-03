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
