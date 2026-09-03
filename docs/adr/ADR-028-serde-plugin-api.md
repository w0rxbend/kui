# ADR-028 — Serde plugin API and compatibility with `io.kafbat.ui.serde.api`

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat's `serde-api` is a framework-free Java SPI (`Serde`, `DeserializeResult`,
`SchemaDescription`, `SerdeParameter`, `PropertyResolver`, `RecordHeaders`) loaded from jars
with a child-first classloader; community serdes (AWS Glue, Smile) target it. KUI's serdes
are Scala with explicit effects.

## Decision

- The KUI SPI is the Scala trait family in `ARCHITECTURE.md` §4.4 (`Serde[F]`,
  `Deserializer[F]`, `Serializer[F]`, `DeserializeResult(text, kind, properties)`), with the
  same lifecycle and resolution semantics as Kafbat: built-ins auto-configured without topic
  patterns, configured instances in order, pattern → explicit → default → String, mandatory
  `Fallback` String serde, per-record fallback on deserialization failure.
- Built-ins: String, Int32/64, UInt32/64, UUID, Base64, Hex, JSON, AvroEmbedded, ProtobufFile,
  ProtobufRaw, MessagePack (msgpack-core), Struct, MirrorMaker2 internal topics,
  `__consumer_offsets`; SchemaRegistry (Avro/Protobuf/JSON Schema) from `libs/serde-confluent`.
- **Binary compatibility with `io.kafbat.ui.serde.api` is a goal**, delivered by a bridge:
  `libs/serde-kafbat-bridge` depends on the published `io.kafbat.ui:serde-api` artifact and
  adapts a Java `Serde` instance to `Serde[F]` (`Sync[F].blocking` around calls,
  `PropertyResolver` backed by the typed cluster config). Jar loading uses one child-first
  classloader per configured serde path, cached, as Kafbat does. Scheduled M6+ (feature
  matrix SD-2). Provectus-era jars (`com.provectus...` package) are not supported.
- Serde config keys keep Kafbat's shape (`name`, `className`, `filePath`, `properties`,
  `topicKeysPattern`, `topicValuesPattern`, `defaultKeySerde`, `defaultValueSerde`).

## Evidence

- `research/kafbat/architecture.md` F6, D6, open question 1; `research/provectus/diff.md` F4, D5
  (API stable across the rename; Glue is an external plugin targeting this API).
- `research/scala/ecosystem-mapping.md` F7, F9 (msgpack-core, Confluent serializers).

## Consequences

- The Scala SPI stays the primary extension point; Java plugins get a stable adapter.
- The bridge is the only Java-facing module; its classloader isolation is tested with a
  sample jar in `libs/testkit`.
- The exact published version of `io.kafbat.ui:serde-api` is an open item in
  `DEPENDENCY_MATRIX.md`.

## Alternatives rejected

- Scala-only plugins: loses the existing community serdes for no gain.
- Making the Java SPI the primary API: forces Java types and blocking calls into every
  built-in serde.

## Reversibility

Medium. The bridge is additive; the Scala SPI is a public contract for KUI plugins.
