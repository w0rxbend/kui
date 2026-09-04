# KUI dependency matrix

Finalized 2026-09-03 from `research/scala/ecosystem-mapping.md` (versions verified against
Maven Central metadata and GitHub on that date). Every row cites the ADR that admits it.
Adding a dependency requires the review procedure in [CONTRIBUTING.md](CONTRIBUTING.md) and a row here.

Scopes: `main`, `runtime`, `test`, `build` (Mill plugin/tool), `js` (Scala.js only),
`shared` (cross-compiled JVM+JS). Modules use the ids in `ARCHITECTURE.md` §16
(`libs/<name>`, `services/<name>/<layer>`, `frontend/<name>`, `apps/allinone`).

## Platform and build

| Group | Artifact | Version | Scope | Modules | ADR |
| --- | --- | --- | --- | --- | --- |
| org.scala-lang | scala3-library_3 | 3.9.0 | main | all | ADR-001 |
| com.lihaoyi | mill-dist | 1.1.8 | build | root | ADR-001 |
| com.lihaoyi | mill-contrib-docker_3 | 1.1.8 | build | services/*/app, apps/allinone | ADR-001, ADR-005 |
| org.scalameta | scalafmt-core_2.13 | 3.11.5 | build | root | ADR-001 |
| ch.epfl.scala | scalafix-core_2.13 | 0.14.7 | build | root | ADR-001 |
| com.goyeau | mill-scalafix_mill1_3 | 0.6.2 | build | root | ADR-001 (BUILD-002: Mill 1.1.8 has no built-in scalafix support, so the scalafix gate needs this plugin) |
| org.scala-js | scalajs-library_2.13 | 1.22.0 | js | frontend/* | ADR-001, ADR-011 |

## Runtime core

| Group | Artifact | Version | Scope | Modules | ADR |
| --- | --- | --- | --- | --- | --- |
| org.typelevel | cats-core_3 | 2.13.0 | shared | all | ADR-002 |
| org.typelevel | cats-effect_3 | 3.7.1 | main | all JVM (application, infrastructure, api, app) | ADR-002 |
| co.fs2 | fs2-core_3 | 3.13.0 | main | all JVM | ADR-002 |
| co.fs2 | fs2-io_3 | 3.13.0 | main | libs/http, libs/config, services/security | ADR-002 |
| org.typelevel | fs2-kafka_3 | 4.0.0 | main | libs/kafka, libs/config | ADR-006, ADR-042 §5 |
| org.apache.kafka | kafka-clients | 4.3.1 | main | libs/kafka (transitive override), libs/testkit (the container fixture's own admin client) | ADR-006, ADR-041 A10 |
| org.xerial.snappy | snappy-java | 1.1.10.8 | runtime | libs/kafka | ADR-006 |
| at.yawk.lz4 | lz4-java | 1.11.2 | runtime | libs/kafka | ADR-006 |

## HTTP and contracts

| Group | Artifact | Version | Scope | Modules | ADR |
| --- | --- | --- | --- | --- | --- |
| com.softwaremill.sttp.tapir | tapir-core_3 | 1.13.31 | shared | libs/contracts-core, services/*/contract | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-json-circe_3 | 1.13.31 | shared | libs/contracts-core, services/*/contract | ADR-003, ADR-007 |
| com.softwaremill.sttp.tapir | tapir-iron_3 | 1.13.31 | shared | libs/contracts-core, services/*/contract | ADR-003, ADR-007 |
| com.softwaremill.sttp.tapir | tapir-cats-effect_3 | 1.13.31 | main | services/*/api, services/gateway | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-netty-server-cats_3 | 1.13.31 | main | libs/http, services/*/app, apps/allinone | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-files_3 | 1.13.31 | main | services/gateway | ADR-003, ADR-012 |
| com.softwaremill.sttp.tapir | tapir-sttp-client4_3 | 1.13.31 | shared | libs/http, services/gateway, frontend/ui-kernel | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-openapi-docs_3 | 1.13.31 | main | services/*/api, services/gateway | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-swagger-ui-bundle_3 | 1.13.31 | main | services/gateway | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-apispec-docs_3 | 1.13.31 | main | services/message/infrastructure | ADR-014 |
| com.softwaremill.sttp.tapir | tapir-otel4s-tracing_3 | 1.13.31 | main | libs/observability | ADR-009 |
| com.softwaremill.sttp.tapir | tapir-opentelemetry-metrics_3 | 1.13.31 | main | libs/observability | ADR-009 |
| com.softwaremill.sttp.tapir | tapir-sttp-stub4-server_3 | 1.13.31 | test | libs/testkit | ADR-018 |
| com.softwaremill.sttp.client4 | core_3 | 4.0.26 | shared | libs/http, frontend/ui-kernel | ADR-003 |
| com.softwaremill.sttp.client4 | fs2_3 | 4.0.26 | main | libs/http | ADR-003 |
| com.softwaremill.sttp.client4 | circe_3 | 4.0.26 | shared | libs/http | ADR-007 |
| com.softwaremill.sttp.apispec | jsonschema-circe_3 | 0.11.10 | main | services/message/infrastructure | ADR-014 |
| com.softwaremill.sttp.apispec | openapi-circe_3 | 0.11.10 | main | services/*/api | ADR-003 |

## JSON, config, validation, mapping, wiring

| Group | Artifact | Version | Scope | Modules | ADR |
| --- | --- | --- | --- | --- | --- |
| io.circe | circe-core_3 | 0.14.16 | shared | libs/contracts-core, libs/serde, libs/config, services/*/contract | ADR-007 |
| io.circe | circe-parser_3 | 0.14.16 | shared | same | ADR-007 |
| io.circe | circe-generic_3 | 0.14.16 | shared | services/*/contract (semi-auto only) | ADR-007 |
| io.circe | circe-yaml_3 | 0.16.1 | main | libs/config | ADR-013 |
| is.cir | ciris_3 | 3.15.0 | main | libs/config | ADR-013 |
| is.cir | ciris-circe-yaml_3 | 3.15.0 | main | libs/config | ADR-013 |
| io.github.iltotore | iron_3 | 3.3.2 | shared | libs/kernel, services/*/domain, services/*/contract | ADR-007 |
| io.github.iltotore | iron-circe_3 | 3.3.2 | shared | libs/contracts-core, services/*/contract | ADR-007 |
| io.github.iltotore | iron-cats_3 | 3.3.2 | shared | libs/kernel, services/*/domain | ADR-007 |
| io.github.iltotore | iron-ciris_3 | 3.3.2 | main | libs/config | ADR-013 |
| io.scalaland | chimney_3 | 2.0.0-RC1 | main | services/*/api, services/*/application | ADR-033 |
| com.softwaremill.macwire | macros_3 | 2.6.7 | main (provided) | services/*/app, apps/allinone | ADR-010 |
| com.softwaremill.macwire | util_3 | 2.6.7 | main | services/*/app, apps/allinone | ADR-010 |

## Logging and telemetry

| Group | Artifact | Version | Scope | Modules | ADR |
| --- | --- | --- | --- | --- | --- |
| org.typelevel | log4cats-core_3 | 2.8.0 | main | all JVM | ADR-008 |
| org.typelevel | log4cats-slf4j_3 | 2.8.0 | main | libs/observability, services/*/app | ADR-008 |
| org.slf4j | slf4j-api | 2.0.18 | main | libs/observability | ADR-008 |
| ch.qos.logback | logback-classic | 1.6.3 | runtime | services/*/app, apps/allinone | ADR-008 |
| net.logstash.logback | logstash-logback-encoder | 9.0 | runtime | services/*/app, apps/allinone | ADR-008 |
| org.typelevel | otel4s-core_3 | 1.1.0 | main | all JVM | ADR-009 |
| org.typelevel | otel4s-oteljava_3 | 1.1.0 | main | libs/observability, services/*/app | ADR-009 |
| org.typelevel | otel4s-oteljava-testkit_3 | 1.1.0 | test | libs/testkit | ADR-009 |
| io.opentelemetry | opentelemetry-sdk | 1.65.0 | main | libs/observability | ADR-009 |
| io.opentelemetry | opentelemetry-sdk-extension-autoconfigure | 1.65.0 | main | libs/observability | ADR-009 |
| io.opentelemetry | opentelemetry-exporter-otlp | 1.65.0 | runtime | services/*/app, apps/allinone | ADR-009 |
| io.opentelemetry | opentelemetry-exporter-prometheus | 1.65.0-alpha | runtime | services/*/app, apps/allinone | ADR-009 |

## Kafka ecosystem: serdes, registry, filters, analysis, caching, CSV

| Group | Artifact | Version | Scope | Modules | ADR |
| --- | --- | --- | --- | --- | --- |
| io.confluent | kafka-schema-registry-client | 8.3.1 | main | libs/serde-confluent | ADR-014 |
| io.confluent | kafka-avro-serializer | 8.3.1 | main | libs/serde-confluent | ADR-014 |
| io.confluent | kafka-protobuf-serializer | 8.3.1 | main | libs/serde-confluent | ADR-014 |
| io.confluent | kafka-json-schema-serializer | 8.3.1 | main | libs/serde-confluent | ADR-014 |
| org.apache.avro | avro | 1.12.2 | main | libs/serde | ADR-014 |
| com.github.fd4s | vulcan_3 | 1.13.0 | main | libs/serde (KUI-owned Avro records) | ADR-014 |
| com.thesamet.scalapb | scalapb-runtime_3 | 0.11.20 | main | libs/serde | ADR-014 |
| com.google.protobuf | protobuf-java | 4.36.1 | main | libs/serde | ADR-014 |
| com.networknt | json-schema-validator | 3.0.7 | main | libs/serde | ADR-014 |
| org.msgpack | msgpack-core | 0.9.12 | main | libs/serde | ADR-028 |
| io.kafbat.ui | serde-api | see open questions | main | libs/serde-kafbat-bridge (M6+) | ADR-028 |
| dev.cel | cel | 0.14.0 | main | libs/filter | ADR-017 |
| org.apache.datasketches | datasketches-java | 8.0.0 | main | services/topic/infrastructure | ADR-001 |
| com.github.ben-manes.caffeine | caffeine | 3.2.4 | main | libs/cache | ADR-016 |
| org.gnieh | fs2-data-csv_3 | 1.14.1 | main | services/security/infrastructure, libs/http (CSV responses) | ADR-023 (ACL CSV sync); no ADR admits it explicitly — raise one in M5 grooming |

## Identity and cluster authentication

| Group | Artifact | Version | Scope | Modules | ADR |
| --- | --- | --- | --- | --- | --- |
| com.nimbusds | oauth2-oidc-sdk | 11.38.2 | main | services/identity/infrastructure | ADR-015 |
| com.nimbusds | nimbus-jose-jwt | 10.9.1 | main | services/identity/infrastructure, libs/security-core (JVM adapter) | ADR-015, ADR-020 |
| com.unboundid | unboundid-ldapsdk | 7.0.5 | main | services/identity/infrastructure | ADR-015 |
| at.favre.lib | bcrypt | see open questions | main | services/identity/infrastructure | ADR-015 |
| software.amazon.msk | aws-msk-iam-auth | 2.3.7 | runtime (optional) | libs/kafka-auth | ADR-022 |
| com.azure | azure-identity | 1.18.6 | runtime (optional) | libs/kafka-auth | ADR-022 |
| com.google.cloud.hosted.kafka | managed-kafka-auth-login-handler | 1.0.6 | runtime (optional) | libs/kafka-auth | ADR-022 |
| com.google.oauth-client | google-oauth-client | 1.39.0 | runtime (optional) | libs/kafka-auth | ADR-022 |

## Frontend

| Group | Artifact | Version | Scope | Modules | ADR |
| --- | --- | --- | --- | --- | --- |
| com.raquo | laminar_sjs1_3 | 17.2.1 | js | frontend/* | ADR-011 |
| com.raquo | airstream_sjs1_3 | 17.2.1 | js | frontend/* | ADR-011 |
| com.raquo | waypoint_sjs1_3 | 9.0.0 | js | frontend/ui-shell, frontend/ui-kernel | ADR-011 |
| be.doeraene | url-dsl_sjs1_3 | 0.7.0 | js | frontend/ui-shell (transitive) | ADR-011 |
| org.scala-js | scalajs-dom_sjs1_3 | 2.8.1 | js | frontend/* | ADR-011 |
| io.github.cquiroz | scala-java-time_sjs1_3 | 2.7.0 | js | libs/contracts-core (JS side), services/*/contract (JS side), frontend/ui-kernel | ADR-011 |
| com.raquo | domtestutils_sjs1_3 | 19.0.0 | test | frontend/* | ADR-018 |
| npm: codemirror (@codemirror/state, view, lang-json, lang-sql, legacy-modes, lint, search) | — | 6.x, pinned in `frontend/package.json` | js (static ESM via import map) | frontend/ui-kernel | ADR-025 |
| npm: uplot | — | pinned in `frontend/package.json` | js (static ESM) | frontend/ui-kernel | ADR-025 |
| com.github.lolgab | mill-scalablytyped_mill1_3 | 0.4.1 | build (one-off, not routine) | facade generation only | ADR-025 (BUILD-006 spike 2 verified it generates and compiles under Mill 1.1.8 / Scala 3.9 / Scala.js 1.22; needs an npm `typescript` install, and generation runs through `compile`, not a named task) |

## Tests

| Group | Artifact | Version | Scope | Modules | ADR |
| --- | --- | --- | --- | --- | --- |
| org.scalameta | munit_3 | 1.3.6 | test | all (JVM and JS) | ADR-018 |
| org.scalameta | munit-scalacheck_3 | 1.3.1 | test | all | ADR-018 |
| org.scalacheck | scalacheck_3 | 1.20.0 | test | all | ADR-018 |
| org.typelevel | munit-cats-effect_3 | 2.2.0 | test | all JVM | ADR-018 |
| org.typelevel | discipline-munit_3 | 2.0.0 | test | libs/kernel | ADR-018 |
| com.dimafeng | testcontainers-scala-munit_3 | 0.44.1 | test | libs/testkit, integration suites, e2e | ADR-018 |
| com.dimafeng | testcontainers-scala-kafka_3 | 0.44.1 | test | libs/testkit | ADR-018 |
| org.testcontainers | testcontainers-kafka | 2.0.5 | test | libs/testkit | ADR-018 — **settled in CFGOP-004**: `org.testcontainers:kafka` does not resolve at any version; the module is published as `testcontainers-kafka`. Three M1 task specs write the wrong id and are wrong. |
| com.microsoft.playwright | playwright | 1.62.0 | test | e2e | ADR-018 (BUILD-006 spike 3; pinned together with the browser build it downloads, Chromium 1234 / Chrome for Testing 151.0.7922.34 — the library only speaks to its own revision) |
| org.bouncycastle | bcpkix-jdk18on | 1.85 | test | libs/testkit | ADR-018 |

## Explicitly excluded

Fabric, Scaffeine, pac4j, Lucene (deferred, ADR-038), ScalaCSS, Shoelace (deferred, ADR-024),
Confluent `kafka-clients -ccs`, Jackson (any direct use), Spring (any), ANTLR (direct), Weaver,
WireMock, Mockito, Groovy, the official Java MCP SDK, datasketches 9.0.0, Chimney 1.x,
`com.github.fd4s:fs2-kafka` 3.x, `tapir-sttp-client` (sttp 3), `circe-json-schema`,
`victools jsonschema-generator`, Monaco, Ace, any relational database driver (ADR-036).

## Open version questions

| Item | Question | Owner | Due |
| --- | --- | --- | --- |
| Scala 3.9.0 | Confirm the scala-lang.org announcement and GitHub release object are published before the first M0 commit; otherwise start on 3.3.8 and bump (ADR-001). | Principal Scala Engineer | M0 start |
| Chimney 2.0.0 | Final release date; verify `hearth` macros compile under `-Werror -Wunused:all` in an M0 spike (ADR-033). | Principal Scala Engineer | M0 |
| fs2-kafka 4.0.0 | Which of `describeMetadataQuorum`, `listGroups`, `describeProducers`, `describeShareGroups` need the raw `Admin` escape hatch on the pinned tag (ADR-006 lists the first three; `describeShareGroups` was added here and the ADR has not caught up). | Kafka Specialist | M1 |
| kafka-clients 4.3.1 | KIP-848 `GroupState`/`targetAssignment` exposure; defensive mapping for classic vs consumer groups (ADR-006, ADR-030). | Kafka Specialist | M1 |
| lz4-java | Whether upstream `org.lz4:lz4-java 1.8.1` already fixes CVE-2025-12183; the `at.yawk.lz4` fork stays until confirmed. | Security Engineer | M1 |
| Confluent 8.3.1 | Community License review documented in `docs/operations`; confirm optional-classpath packaging (ADR-014). | CTO | M3 |
| io.kafbat.ui:serde-api | Exact published version and Maven coordinates for the bridge module (ADR-028). | Kafka Specialist | M6 |
| at.favre.lib:bcrypt | Latest version and Java 21 compatibility (ADR-015). | Security Engineer | M6 |
| Mill 1.2.0 | Stay on 1.1.8 until 1.2.0 final; re-check `mill-contrib-docker` API then. | Infrastructure Lead | ongoing |
| Laminar 18 / Waypoint 10 | Upgrade task once both are final (ADR-011). | Frontend Architect | after release |
| opentelemetry-exporter-prometheus | Alpha status; fallback to `otel4s-sdk-exporter-prometheus 0.19.x` if unstable (ADR-009). | Infrastructure Lead | M1 |

### Closed by measurement

Each of these was an open question about a library that could not be answered from its
documentation, so it was answered by building the smallest thing that would prove or disprove it
against the exact versions this repository pins.

| Item | Answer |
| --- | --- |
| tapir-netty-server-cats | Netty keeps a `serverSentEventsBody` open past 10 minutes, flushes each event within ~2 ms, and cancels the fs2 stream within 8 ms of the client leaving. Netty stays; the http4s-ember fallback is not taken. |
| mill-scalablytyped | 0.4.1 generates and compiles a `@codemirror/state` facade under Mill 1.1.8, Scala 3.9 and Scala.js 1.22. Stays as ADR-025's one-off generator. |
| com.microsoft.playwright | 1.62.0, which downloads Chromium build 1234 (Chrome for Testing 151.0.7922.34). Both pinned in `build.mill`. |
