# Ecosystem mapping: Kafbat dependencies → KUI Scala stack

**Title:** Validated and versioned ecosystem mapping with proposed `DEPENDENCY_MATRIX.md`
**Date:** 2026-09-03

## Question(s)

1. For every dependency in Kafbat's `gradle/libs.versions.toml` and `api/build.gradle`: what is it for,
   what does KUI do with it (Replace / Wrap / Drop / Defer), and which Scala/JVM library replaces it?
2. What is the exact latest version of every library in the KUI stack, verified
   against Maven Central and GitHub as of 2026-09-03? Is it Scala 3 and Cats Effect 3 compatible,
   maintained, and what license/transitive weight does it carry?
3. Resolve every open research item in the KUI stack's dependency list to a concrete recommendation.
4. Which Kafbat dependencies are not yet covered by that dependency list, and how should they map?

## Method and sources

- **Versions** were read directly from Maven Central `maven-metadata.xml`
  (`https://repo1.maven.org/maven2/<group>/<artifact>/maven-metadata.xml`) on 2026-09-03. The `search.maven.org`
  Solr index was found to be stale for Scala artifacts (it reported April/May 2025 versions for
  cats-effect, Tapir, etc.), so it was **not** used. Confluent artifacts were read from
  `https://packages.confluent.io/maven/` because they are not on Maven Central.
- **Maintenance, license, stars** were read via the GitHub REST API (`gh api repos/<owner>/<repo>`,
  `/releases`) on 2026-09-03.
- **Scala/JDK compatibility** was read from each project's `build.sbt` / `pom.xml` / README at the
  verified version tag.
- Kafbat input: `/tmp/kui-ref/kafbat/gradle/libs.versions.toml` and `/tmp/kui-ref/kafbat/api/build.gradle`
  (Spring Boot 3.5.16, Confluent 7.9.5, kafka-clients 7.9.5-ccs).
- "Latest stable" below excludes RC / milestone / nightly / snapshot versions unless explicitly noted.

Legend for the decision column: **Replace** = Scala/Typelevel counterpart; **Wrap** = keep the JVM
library behind a Scala port; **Drop** = not needed; **Defer** = later milestone.

---

## Findings

### F1. Platform: Scala 3, Mill, Scala.js

| Item | Verified value | Evidence |
| --- | --- | --- |
| Scala 3 latest LTS | **3.9.0** — artifacts on Maven Central dated 2026-08-26; the git tag `3.9.0` exists (sha `777528f1`). The scala-lang.org release post for 3.9.0 returned 404 on 2026-09-03 and the GitHub "release" object is still only `3.9.0-RC6` (2026-08-19), so the public announcement is imminent but not yet published. | https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/ ; https://github.com/scala/scala3/releases ; https://www.scala-lang.org/blog/next-scala-lts.html |
| Scala 3 previous LTS | **3.3.8** (2026-06-10; backports up to 3.8.4). Scala team keeps backporting to 3.3.x for one year after 3.9.0. | https://www.scala-lang.org/news/3.3.8/ |
| Scala 3 Next | 3.8.4 was the last Next before 3.9.0; **3.10.0-RC1** published 2026-08-27. | Maven metadata |
| JDK baseline forced by Scala 3.9 LTS | JDK 17+ (3.9 raised the minimum). | https://www.scala-lang.org/blog/next-scala-lts.html |
| Mill | **1.1.8** (2026-08-07). `1.2.0-RC1` exists (2026-06-17, pre-release). MIT. 2 785 stars, pushed 2026-09-01. | https://github.com/com-lihaoyi/mill/releases |
| Mill Scala.js support | Built in (`mill.scalajslib.ScalaJSModule`, artifact `com.lihaoyi:mill-libs-scalajslib_3:1.1.8`). Mill 1.1.8 builds with Scala 3.8.2 internally and bundles Scala.js worker **1.21.0**; user projects set `scalaJSVersion` freely (docs example uses 1.20.2; Scala.js 1.22.0 works with the same 1.x worker API). | https://raw.githubusercontent.com/com-lihaoyi/mill/1.1.8/mill-build/src/millbuild/Deps.scala ; https://mill-build.org/mill/scalalib/web-examples.html |
| Mill Docker packaging | **`mill-contrib-docker`** (`com.lihaoyi:mill-contrib-docker_3:$MILL_VERSION`, i.e. 1.1.8) is the first-party contrib module (Dockerfile-based, needs a docker daemon). Jib-based alternatives are dead or stale: `atty303/mill-jib` is **archived** (README: "reached the end of its useful life with the release of Mill 1.0; migrated to mill-contrib-docker"); `vic/mill-docker` last release 0.5.1 (2020); `GeorgOfenbeck/mill-docker` (Jib wrapper) last pushed 2025-07, no Mill 1.x artifact found on Central. | https://mill-build.org/mill/contrib/docker.html ; https://github.com/atty303/mill-jib |
| Scala.js | **1.22.0** (2026-06-20). Apache-2.0. `scalajs-library_2.13` is the runtime artifact; the Scala 3 compiler has Scala.js support built in (no separate `scalajs-compiler` for Scala 3). | https://github.com/scala-js/scala-js/releases |
| scalafmt | **3.11.5** (2026-07-27), Apache-2.0. | https://github.com/scalameta/scalafmt/releases |
| scalafix | **0.14.7** (2026-06-12), BSD-3-Clause. | https://github.com/scalacenter/scalafix/releases |

**Decision candidate:** Scala **3.9.0 (LTS)**, JDK **21** runtime baseline (see F9 for why not 17 or 25),
Mill **1.1.8**, Scala.js **1.22.0**. Fallback if 3.9.0 shows ecosystem breakage in the first two weeks:
3.3.8 LTS (every library below still publishes for 3.3.x; Tapir itself builds with 3.3.8).

### F2. Runtime: Cats, Cats Effect, FS2, fs2-kafka, kafka-clients

| Library | Latest stable | Scala 3 / CE3 | Maintenance | License | Notes |
| --- | --- | --- | --- | --- | --- |
| `org.typelevel:cats-core` | **2.13.0** (2025-01-20) | yes | Typelevel, active | Apache-2.0 | — |
| `org.typelevel:cats-effect` | **3.7.1** (2026-08-23) | yes | 2 235 stars, pushed 2026-09-03 | Apache-2.0 | — |
| `co.fs2:fs2-core` / `fs2-io` | **3.13.0** (2026-03-12) | yes | active | MIT (GitHub reports NOASSERTION; the LICENSE file is MIT) | — |
| `org.typelevel:fs2-kafka` | **4.0.0** (2026-05-04); `4.1.0-RC1` (2026-06-23) | yes | Moved to Typelevel org in 2026; 314 stars, pushed 2026-08-26 | Apache-2.0 | **Group id changed**: 4.x is `org.typelevel`, 3.x stays `com.github.fd4s` (last 3.9.1, 2025-10-24). 4.0.0 pom depends on `kafka-clients 4.2.0`, `fs2-core 3.13.0`, `cats-effect 3.7.0`. 4.x redesigns `CommittableOffset`/`CommittableOffsetBatch` (safe by construction) and folds transactional producers into `KafkaProducer`. |
| `org.apache.kafka:kafka-clients` | **4.3.1** (2026-06-23) | Java | ASF | Apache-2.0 | Clients require Java 11+; broker/tools 17+. Kafbat pins the Confluent `7.9.5-ccs` fork of 3.9.x; KUI should use vanilla 4.3.1 and let fs2-kafka's 4.2.0 transitive be overridden upward. |

The KUI stack's dependency notes say "fs2-kafka 4.x" — correct, but must also record the **`org.typelevel` group id** and that
fs2-kafka 4 pulls Kafka 4.x clients (Kafka 4 drops ZooKeeper-era APIs; Kafbat's 3.9-based code paths for
`AdminClient` differ in places, notably `describeCluster` and `listConsumerGroups`).

### F3. HTTP: Tapir, sttp 4, Netty

| Library | Latest stable | Notes |
| --- | --- | --- |
| `com.softwaremill.sttp.tapir:tapir-core` and all modules | **1.13.31** (2026-08-07) — confirmed latest; no 1.14 or 2.x published. | Builds with Scala 3.3.8 and 2.13.18; `Versions.scala` at v1.13.31: cats-effect 3.7.0, circe 0.14.16, sttp4 4.0.26, sttp-apispec 0.11.10, sttp-shared 1.5.2. 1 469 stars, pushed 2026-09-02, Apache-2.0. |
| `tapir-netty-server-cats` | 1.13.31 | Server for every service and the gateway. |
| `tapir-sttp-client4` | 1.13.31 | **Use this, not `tapir-sttp-client`** — the latter is the sttp 3 binding (also 1.13.31, kept for compatibility). |
| `tapir-openapi-docs`, `tapir-swagger-ui-bundle`, `tapir-json-circe`, `tapir-cats-effect`, `tapir-files` | 1.13.31 | — |
| `tapir-sttp-stub4-server` | 1.13.31 | HTTP fakes in tests (replaces WireMock / MockWebServer). |
| `tapir-otel4s-tracing`, `tapir-opentelemetry-metrics` | 1.13.31 | Server interceptors for otel4s traces / OTel metrics. |
| `tapir-iron` | 1.13.31 | Iron refined types in endpoint schemas. |
| `tapir-apispec-docs` | 1.13.31 | `TapirSchemaToJsonSchema` — Tapir `Schema` → JSON Schema (see F7). |
| `com.softwaremill.sttp.client4:core`, `fs2`, `circe` | **4.0.26** (2026-07-08) | 1 502 stars, Apache-2.0. `fs2` backend = HttpClient-based `HttpClientFs2Backend`. |
| `com.softwaremill.sttp.apispec:jsonschema-circe`, `openapi-circe-yaml` | **0.11.10** (2025-06-27) | Circe codecs for OpenAPI/JSON Schema ASTs. |
| `io.netty:netty-*` | 4.2.17.Final (2026-08) | Transitive via Tapir; Kafbat pins 4.1.137. Do not pin directly. |

### F4. JSON, logging, telemetry

| Library | Latest stable | Notes |
| --- | --- | --- |
| `io.circe:circe-core/generic/parser` | **0.14.16** (2026-06-24); `0.15.0-M1` exists (2026-06-24) | Stay on 0.14.16 (Tapir 1.13.31 is built against it). 2 542 stars, Apache-2.0, pushed 2026-07-23. |
| `io.circe:circe-yaml` | **0.16.1** (Jackson-free `circe-yaml-scalayaml` line is the future; `1.15.0` is the SnakeYAML-based artifact). Tapir uses `0.15.2`. | Used only for reading YAML config (see F6). |
| `org.typelevel:log4cats-core` / `log4cats-slf4j` | **2.8.0** (2026-03-11) | Scala 3, CE 3.7.0. `StructuredLogger[F]` carries `Map[String, String]` context → SLF4J MDC. |
| `ch.qos.logback:logback-classic` | **1.6.3** (2026-08-14) | EPL-1.0 / LGPL-2.1 dual. |
| `net.logstash.logback:logstash-logback-encoder` | **9.0** (2025-10-26) | JSON line logging for containers; Apache-2.0. |
| `org.slf4j:slf4j-api` | **2.0.18** | — |
| `org.typelevel:fabric-core` / `fabric-io` | **1.30.0** (2026-06-13) | See F5 — **not a logging library**. |
| `org.typelevel:otel4s-core`, `otel4s-oteljava`, `otel4s-oteljava-testkit` | **1.1.0** (2026-08-09); 1.0.0 shipped 2026-05-10 | Scala 3, CE3. 215 stars, pushed 2026-09-03, Apache-2.0. |
| `org.typelevel:otel4s-sdk`, `otel4s-sdk-exporter`, `otel4s-sdk-exporter-prometheus` | **0.19.2** (2026-08-27) | **Separate versioning**: the pure-Scala SDK line is still 0.x and lives in the `otel4s-sdk` project, cross-built for JS/Native. On the JVM use `otel4s-oteljava` with the Java SDK exporters instead. |
| `io.opentelemetry:opentelemetry-sdk`, `-exporter-otlp`, `-sdk-extension-autoconfigure` | **1.65.0** (2026-08-07) | OTLP exporter for traces/metrics/logs. |
| `io.opentelemetry:opentelemetry-exporter-prometheus` | **1.65.0-alpha** | Still `-alpha` after years; it is the only Java-SDK Prometheus scrape endpoint. Accept the alpha suffix (ADR-009 must record this) or use `otel4s-sdk-exporter-prometheus 0.19.2` (pure Scala, but that means running the 0.x SDK line). |

### F5. Fabric: what it is and whether it fits

`typelevel/fabric` (131 stars, MIT, last release 1.30.0 on 2026-06-13, author Matt Hicks / outr) is an
**immutable JSON-like AST library** — "Abstract Syntax Tree based on JSON concepts, but more abstract for
parsing and application" — with case-class conversions, deep merge, JSON DDL and a JSON parser
(`fabric-io`). It is the value model used by the same author's **scribe** logging library, which is
probably where the "structured-log value model" idea in the stack notes came from. It is *not* a logging
abstraction and it is a second JSON AST next to Circe, which violates KUI's "no two libraries for
the same responsibility" rule.

**Recommendation (ADR-008):** **Drop Fabric.** Use `log4cats` `StructuredLogger[F]` (context
`Map[String, String]` → SLF4J MDC) over Logback with `logstash-logback-encoder` producing JSON lines.
For structured values richer than strings, encode with Circe at the call site (`json.noSpaces`).
Correlation ids come from otel4s `Baggage`/span context, bridged into MDC by the
`opentelemetry-logback-mdc` appender or a small custom `Logger` wrapper. Single logging abstraction,
single JSON AST.

### F6. Configuration: Ciris vs PureConfig

| | Ciris | PureConfig |
| --- | --- | --- |
| Version | **3.15.0** (2026-06-04), `is.cir:ciris_3`, MIT, 408 stars, pushed 2026-08-26 | **0.17.10** (2026-01-26), `com.github.pureconfig:pureconfig-core_3` + `pureconfig-generic-scala3_3`, MPL-2.0, 1 540 stars, pushed 2026-08-30 |
| Model | Effectful `ConfigValue[F, A]`, composable, loads from env/props/files, `ciris-circe-yaml` module reads YAML via circe-yaml | HOCON/Typesafe Config, derivation-first; YAML via `pureconfig-yaml`; Scala 3 derivation is a separate module |
| CE3 | native (`ConfigValue[F, A].load[IO]`) | via `pureconfig-cats-effect` |
| Fits "YAML file + env override compatible with Kafbat keys"? | Yes: build `ConfigValue`s explicitly: `env("KAFKA_CLUSTERS_0_NAME").or(file("...").as[...])`. Kafbat keys like `kafka.clusters[0].bootstrapServers` / `KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS` are hand-mapped once, which is what an explicit model wants. | HOCON path syntax differs from Spring's relaxed binding; the `[0]` index and Spring's `_0_` env convention need custom `ConfigSource`s. |
| Secrets | `Secret[A]` type redacts on `toString` | no equivalent |

**Recommendation (ADR-013):** **Ciris 3.15.0** + `ciris-circe-yaml` for the YAML file, with an explicit
Scala config model validated by Iron (`iron-ciris` exists but is not required). Rationale: KUI already
commits to explicit codecs and Cats Effect; Ciris is effect-native, redacts secrets, and makes the
Kafbat-compatible env key convention an explicit mapping rather than a derivation fight. PureConfig
remains a valid fallback and is more popular, but its HOCON-first path model is a worse fit for
Spring-style relaxed env binding.

### F7. Schema registry, Avro, Protobuf, JSON Schema

| Library | Latest stable | Notes |
| --- | --- | --- |
| `io.confluent:kafka-schema-registry-client` and `kafka-avro-serializer`, `kafka-protobuf-serializer`, `kafka-json-schema-serializer` | **8.3.1** (2026-08-27) on `packages.confluent.io/maven` | Confluent Community License (not OSI-approved; redistribution inside a competing SaaS is restricted, embedding in a self-hosted UI is allowed). Heavy transitive graph: Jackson, Guava, protobuf-java, `wire-schema` (Kotlin), `everit`/`networknt` JSON Schema, Swagger annotations. Kafbat pins 7.9.5. |
| `com.github.fd4s:vulcan` | **1.13.0** (2026-04-06) | Avro codecs in Scala over `org.apache.avro:avro`. Group id is still `com.github.fd4s` (the repo moved to Typelevel but no `org.typelevel:vulcan` artifact exists yet). `org.typelevel:fs2-kafka-vulcan 4.0.0` bridges to fs2-kafka and *depends on the Confluent serializers*. |
| `org.apache.avro:avro` | **1.12.2** (2026-08-17) | Apache-2.0. |
| `com.thesamet.scalapb:scalapb-runtime` | **0.11.20** stable; `1.0.0-alpha.6` (2026-07-06) | Use 0.11.20 for now. Dynamic-schema use cases (KUI decodes messages against registry-fetched `.proto` text) need `protobuf-java` `DynamicMessage` + a `.proto` parser, which ScalaPB does not provide; the Confluent Protobuf serializer bundles `wire-schema` for exactly this. |
| `com.google.protobuf:protobuf-java` | **4.36.1** (2026-08-31) | — |
| JSON Schema generation | `io.circe:circe-json-schema` is **dead** (last 0.2.0 for 2.13 in 2021, no Scala 3 artifact). Alternatives: **`tapir-apispec-docs 1.13.31`** (`TapirSchemaToJsonSchema`) + `sttp.apispec:jsonschema-circe 0.11.10` for the AST → Circe. | Replaces `victools jsonschema-generator` (5.0.0, Java, reflection on Java classes — no use in Scala). |
| JSON Schema validation | `com.networknt:json-schema-validator` **3.0.7** (2026-08-20, Apache-2.0) | Replaces the abandoned `com.github.java-json-tools:json-schema-validator` 2.2.14 (2020) Kafbat uses in tests. Needed at runtime to validate produced messages against JSON Schema subjects. |

**Recommendation (ADR-014):** Option B for the **registry REST API** (own sttp-4 client generated from
Tapir endpoint definitions; the API is small: subjects, versions, compatibility, mode, schema
references) — this removes the Confluent client, Jackson and Guava from `kui-schema`. For **wire-format
(de)serialization** keep Option A but scoped: wrap `kafka-avro-serializer`, `kafka-protobuf-serializer`
and `kafka-json-schema-serializer` 8.3.1 in an isolated `kui-serde-confluent` module behind the `Serde`
port, because dynamic Protobuf/JSON-Schema decoding against registry-supplied schemas is the expensive
part and there is no Scala library that does it. Vulcan is used for KUI's *own* Avro records (e.g.
audit/export), not for decoding arbitrary user topics.

### F8. Validation, mapping, refined types, DI

| Library | Latest stable | Notes |
| --- | --- | --- |
| `io.github.iltotore:iron`, `iron-circe`, `iron-cats`, `iron-ciris` | **3.3.2** (2026-07-06) | Scala 3 only, Apache-2.0, 554 stars, pushed 2026-08-12. |
| `io.scalaland:chimney` | **1.11.0** (2026-07-14) is the last 1.x; **`2.0.0-RC1`** published 2026-09-02 | **Surprise:** Scala 3.7's given-priority change makes Chimney 1.x emit "ambiguous givens" errors/warnings on Scala ≥ 3.7 (needs a documented workaround). The 2.x line targets **Scala 3.9.0 LTS and JDK 17+** explicitly, drops 2.12, and moves macros onto `hearth`. Author states 1.x is parked. For a Scala 3.9 codebase use **2.0.0-RC1** now and bump to 2.0.0 final; ADR must record the RC status. |
| `com.softwaremill.macwire:macros`, `util` | **2.6.7** (2025-09-01) | Scala 3 supported (`macros_3` artifact). 1 314 stars, Apache-2.0. Last release a year ago but repo pushed 2026-09-01; low churn is expected for a wiring macro. |

### F9. Message filtering, search, analysis, caching, misc runtime JVM libraries

| Kafbat dep | Latest | JDK | Decision | Notes |
| --- | --- | --- | --- | --- |
| `dev.cel:cel` (cel-java) | **0.14.0** (2026-08-17), Apache-2.0, 285 stars | 11+ | Wrap | Pulls guava, protobuf-java, re2j, antlr4-runtime 4.13.2, auto-value. ~10 MB transitive. Keep behind `MessageFilterPort` in `kui-filter`. |
| ANTLR 4.13.2 | — | — | Drop | Only Kafbat's legacy Groovy-era filter grammar; CEL brings its own antlr runtime transitively, do not add a grammar. |
| Lucene 10.5.1 | 2026-08-12 | **21+** | Research → Replace first | Lucene 10 requires JDK 21. Kafbat uses it for fuzzy topic/consumer-group name search over in-memory data (hundreds to low tens of thousands of names). Recommendation: implement prefix/substring/trigram scoring in Scala (`kui-search` port); revisit Lucene only if a benchmark on ≥50k names shows >50 ms p95. |
| `org.apache.datasketches:datasketches-java` | **9.0.0** (2025-12-02) requires **JDK 25** (`<java.version>25</java.version>`); 8.0.0 requires 21; 7.0.1 requires 17 | see left | Wrap | **Surprise:** pick the version by JDK baseline. With JDK 21 use **8.0.0**. Behind `TopicAnalysisPort`. |
| `com.github.ben-manes.caffeine:caffeine` | **3.2.4** (2026-05-03), Apache-2.0 | 11+ | Wrap (large bounded caches only) | — |
| `com.github.blemale:scaffeine` | **5.3.0** (2024-07-10) | — | **Drop** | Two years without release; it is a thin Scala facade, and Cats Effect users should wrap Caffeine's `AsyncCache` in `IO` directly (≈40 lines). Small caches: CE `Ref` + TTL (`cats-effect-std` `MapRef`). |
| `org.msgpack:msgpack-core` | **0.9.12** (2026-05-03), Apache-2.0 | 8+ | Wrap | `jackson-dataformat-msgpack` is **Dropped** (Jackson); decode MessagePack → Circe `Json` via `MessageUnpacker` directly. |
| `com.unboundid:unboundid-ldapsdk` | **7.0.5** (2026-06-11) | 8+ | Wrap | Triple-licensed (GPLv2 / LGPLv2.1 / UnboundID LDAP SDK Free Use License); the Free Use License is the one to cite. Behind `IdentityProviderPort`. |
| `com.nimbusds:nimbus-jose-jwt` | **10.9.1** (2026-05-31), Apache-2.0 | 8+ | Wrap | JWT/JWKS verification for OIDC resource-server role. |
| `com.nimbusds:oauth2-oidc-sdk` | **11.38.2** (2026-07-21), Apache-2.0 | 8+ | Wrap (preferred over pac4j) | Discovery, auth-code + PKCE, token endpoint, ID token validation. ~1 MB with nimbus-jose-jwt. |
| `org.pac4j:pac4j-oidc` / `pac4j-core` | **6.5.7** (2026-09-02), Apache-2.0 | 17+ | **Reject** | pac4j's server-agnostic layer still assumes a servlet-shaped `WebContext`/`SessionStore` and pulls a large graph; there is no Tapir/http4s adapter maintained upstream. Hand-rolled OIDC over `oauth2-oidc-sdk` + Tapir security inputs is ~400 lines and fully controllable (ADR-015). |
| `software.amazon.msk:aws-msk-iam-auth` | **2.3.7** (2026-06-01) | 8+ | Wrap | Kafka SASL callback handler; isolated in `kui-kafka-auth`. Heavy (AWS SDK v2 core). |
| `com.azure:azure-identity` | **1.18.6** stable (1.19.0-beta.2 exists) | 8+ | Wrap | Same module; heavy (~15 MB with msal4j, netty-reactor). Make it an optional runtime classpath. |
| `com.google.cloud.hosted.kafka:managed-kafka-auth-login-handler` | **1.0.6** (2025-05-27) + `google-oauth-client` **1.39.0** | 8+ | Wrap | Same module. |
| `org.xerial.snappy:snappy-java` | **1.1.10.8** (2025-07-19) | — | Keep (runtime) | Needed by kafka-clients for snappy topics; runtime-only scope. |
| `at.yawk.lz4:lz4-java` | **1.11.2** (2026-08-06) | — | Keep (runtime) | Kafbat swapped `org.lz4:lz4-java 1.8.1` (2025-11, still maintained upstream) for this fork over CVE-2025-12183. Verify whether upstream 1.8.1 already contains the fix before adopting the fork; default to the fork to match Kafbat's CVE posture. |
| `org.apache.commons:commons-lang3/text/compress/pool2` | 3.20 / 1.15 / 1.28 / 2.13 | — | Drop | Scala stdlib + Cats; compression is inside kafka-clients. |
| `de.siegmar:fastcsv` | **4.4.0** (2026-07-28) | — | Replace | `org.gnieh:fs2-data-csv` **1.14.1** (2026-06-21), Apache-2.0, 166 stars, pushed 2026-09-03. |
| `org.projectlombok:lombok`, `org.mapstruct:mapstruct` | — | — | Drop / Replace | case classes; Chimney. |
| `org.openapitools:jackson-databind-nullable` | — | — | Drop | `Option`. |
| `io.micrometer:micrometer-registry-prometheus`, `io.prometheus:prometheus-metrics-*` 1.8.0 | — | — | Replace | otel4s + OTel exporters (F4). Pushgateway is a Kafbat feature for "export cluster metrics"; map to OTLP push (ADR-009). |
| `org.opendatadiscovery:oddrn-generator-java` 0.1.21, `ingestion-contract-client` 0.1.41 | last releases 2023/2024 | — | Defer (M9+) | Client is a generated OpenAPI client on Spring WebClient; when implemented, regenerate from ODD's OpenAPI spec with Tapir/sttp instead of wrapping. |
| `io.modelcontextprotocol.sdk:mcp-spring-webflux` 0.10.0 | Java SDK now **2.0.1** (2026-08-19), MIT | 17+ | Defer (M9+) → Scala candidates in F11 | — |
| `org.bouncycastle:bcpkix-jdk18on` | **1.85** (2026-07-12) | — | Keep (test) | Only for generating TLS test certs. |
| `com.squareup.okhttp3:*`, WireMock 3.9.1, MockWebServer | — | — | Replace | `tapir-sttp-stub4-server` for HTTP fakes; sttp backend stubs. |
| JUnit 5.12.2, Mockito 5.20.0, AssertJ 3.25.3, ByteBuddy, reactor-test | — | — | Replace | F10. |
| `org.testcontainers:*` 1.20.6 | Java **2.0.5** (2026-04-20) | — | Replace | testcontainers-scala 0.44.1 already targets Testcontainers Java 2.0.x; in v2 the Kafka module is `org.testcontainers:testcontainers-kafka` (the old `org.testcontainers:kafka` stopped at 1.21.4). |

**JDK baseline consequence:** Scala 3.9 needs 17+, pac4j (rejected) 17+, Lucene 10 (deferred) 21+,
datasketches 8.0.0 needs 21, 9.0.0 needs 25. **Recommend JDK 21 (LTS)** as the KUI runtime and CI
baseline; it keeps datasketches 8.0.0 and leaves Lucene 10 possible later. Do not choose JDK 25 yet:
Scala 3.9.0 and Mill 1.1.8 are validated on 21, and container base images for 25 are young.

### F10. Testing

| Library | Latest stable | Notes |
| --- | --- | --- |
| `org.scalameta:munit` | **1.3.6** (2026-09-03) | Apache-2.0; Scala 3; JVM/JS/Native. |
| `org.scalameta:munit-scalacheck` | **1.3.1** (2026-09-03) | — |
| `org.typelevel:munit-cats-effect` | **2.2.0** (2026-03-09) | CE 3.7. |
| `org.scalacheck:scalacheck` | **1.20.0** (2026-08-27) | BSD-3-Clause. |
| `org.typelevel:discipline-core` | **1.7.0** (2024-04-22) | Stable, feature-complete; low churn is expected. |
| `org.typelevel:discipline-munit` | **2.0.0** (2024-05-22) | — |
| `org.typelevel:weaver-cats` | **0.13.0** (2026-06-02) | **Group id moved** from `com.disneystreaming` (last 0.8.4, 2024) to `org.typelevel`. 0.13.0 has breaking changes with a scalafix migration. Apache-2.0 (LICENSE file), 85 stars on the new repo, pushed 2026-09-03. |
| `com.dimafeng:testcontainers-scala-munit`, `-kafka` | **0.44.1** (2025-12-23) | MIT; targets Testcontainers Java 2.0.3. Add `org.testcontainers:testcontainers-kafka:2.0.5` explicitly to get Kafka 4.x `KafkaContainer` (KRaft) fixes. |

**Recommendation (ADR-018):** **MUnit** is the single test framework for all modules (JVM and Scala.js,
one runner, one reporter, IDE support via Metals/BSP in Mill). `munit-cats-effect` covers `IO` suites;
Weaver's remaining advantages (parallel-by-default, `Resource` sharing across suites) are obtainable in
MUnit with `ResourceSuiteLocalFixture` and Mill's per-module test parallelism. Do **not** adopt Weaver
as a second framework (KUI's "no two libraries for the same responsibility" rule); revisit only if
integration suites become slow enough to need Weaver's global-resource model. ScalaCheck via
`munit-scalacheck`; Discipline via `discipline-munit` for type class instances in `kui-domain`.

### F11. MCP (Model Context Protocol) server — Scala candidates

| Candidate | Version | Stack | Signals |
| --- | --- | --- | --- |
| `net.andimiller.mcp:mcp-core`, `mcp-http4s`, `mcp-tapir`, `mcp-stdio` (andimiller/scala-mcp) | **0.13.0** (2026-06-12) | Cats Effect, cross JVM/JS/Native, automatic JSON Schema derivation, **has a Tapir module** | 1 star, MIT, pushed 2026-07-08, single author |
| `ch.linkyard.mcp:mcp-server`, `jsonrpc2` (linkyard/scala-effect-mcp) | **0.3.5** (2026-04-16) | fs2 + Cats Effect | 13 stars, MIT, pushed 2026-09-02 |
| TJC-LP/fast-mcp-scala | v0.4.0 (2026-05-27) | ZIO 2 (wrong effect system for KUI) | 23 stars, MIT |
| indoorvivants/mcp | v0.2.0 (2025-11-11) | protocol codegen from the MCP JSON schema, minimal runtime | 13 stars, Apache-2.0, pushed 2026-03 |
| `io.modelcontextprotocol.sdk:mcp` (official Java SDK) | **2.0.1** (2026-08-19) | Java, reactive (Reactor) transports; 3 682 stars, MIT | Wrapping it pulls Reactor into KUI — avoid. |

**Recommendation (Defer, M9+):** Prototype with **andimiller/scala-mcp 0.13.0** because of its Tapir
module and CE base; fall back to linkyard `scala-effect-mcp` if the former stalls. Do not wrap the Java
SDK (Reactor dependency). Record this as a Research row in ADR to be written at Milestone 9.

### F12. Frontend: Scala.js, Laminar, Airstream, Waypoint, url-dsl, scala-js-dom

| Library | Latest stable | Pre-release | Notes |
| --- | --- | --- | --- |
| `com.raquo:laminar` (`_sjs1_3`) | **17.2.1** | `18.0.0-M5` (2026-02-27) | MIT, 834 stars, pushed 2026-08-31. Requires Scala.js 1.16+. Laminar 18 has been in milestones since 2025 with no final; laminar.dev/blog lists 17.2.1 as the latest post. Use 17.2.1; plan a 18.0 migration spike once final ships. |
| `com.raquo:airstream` | **17.2.1** | `18.0.0-M5` | Version-locked to Laminar. |
| `com.raquo:waypoint` | **9.0.0** | `10.0.0-M7` | Waypoint 9 pairs with Laminar 17; 10 pairs with Laminar 18. Depends on `url-dsl` and uses uPickle only in tests. |
| `be.doeraene:url-dsl` | **0.7.0** (2025-01-03) | — | Transitive via Waypoint. |
| `org.scala-js:scalajs-dom` | **2.8.1** (2025-07-23) | — | — |
| `io.github.cquiroz:scala-java-time` | **2.7.0** (2026-06-14) | — | `java.time` on Scala.js; needed by shared contract module. |
| ScalaCSS (`com.github.japgolly.scalacss:core_sjs1_3`) | **1.0.0** (2021-11) | — | **Reject** — unmaintained for Scala.js 1/Scala 3 for four years. Use plain CSS modules per microfrontend (already the first-choice option for KUI). |
| Playwright Scala.js facade | none maintained found | — | Keep Playwright driven from Mill via a Node runner (this recommendation stands). |

**Note:** "Laminar 17.x" is still correct. Waypoint must be pinned to **9.0.0**, not the
`10.0.0-Mx` milestones that Scala Steward would propose.

---

## Rows in Kafbat's dependency list not yet covered (proposed mapping)

| Kafbat dependency | Responsibility | Proposed KUI decision |
| --- | --- | --- |
| `spring-boot-starter-actuator`, `spring-boot-actuator` | health/info/metrics endpoints | Replace: Tapir `/health`, `/ready`, `/info` endpoints in `kui-http` shared module; metrics via otel4s. |
| `spring-boot-starter-validation` (Jakarta Bean Validation) | DTO validation | Replace: Iron + Tapir validators. |
| `spring-boot-starter-logging` (Logback + SLF4J bridge) | logging backend | Replace: Logback 1.6.3 + logstash-logback-encoder 9.0 directly. |
| `spring-boot-configuration-processor`, `spring-boot-devtools` | IDE metadata, hot reload | Drop: Mill `-w` watch mode. |
| `swagger-integration-jakarta` 2.2.28 | Swagger annotations on generated contract | Drop: Tapir generates OpenAPI from endpoints. |
| `jakarta.annotation-api` | `@Generated`, `@Nullable` | Drop. |
| `okhttp3-logging-interceptor` (main scope) | HTTP client logging | Replace: sttp `LoggingBackend` wrapper. |
| `snappy-java`, `lz4-java` (yawk fork) | Kafka compression codecs | Keep, runtime scope (see F9). |
| `prometheus-metrics-exporter-pushgateway` | push metrics to Pushgateway | Replace: OTLP push via `opentelemetry-exporter-otlp`; Pushgateway parity is a documented feature gap. |
| `victools jsonschema-generator` | JSON Schema for produce dialog | Replace: `tapir-apispec-docs` + `sttp.apispec:jsonschema-circe` (F7). |
| `json-schema-validator` (java-json-tools, test) | validate messages in tests | Replace: `networknt json-schema-validator 3.0.7` (runtime too, for JSON Schema subjects). |
| `bytebuddy`, `mockito`, `assertj`, `reactor-test`, `junit` | tests | Replace: MUnit + fakes (F10). |
| `bouncycastle bcpkix-jdk18on` (test) | TLS test certificates | Keep in test scope, 1.85. |
| Gradle plugins `openapi-generator` 7.13.0, `openapi-validator`, `node-gradle`, `git-properties`, `docker-remote-api`, `sonarqube`, `checkstyle`, `jacoco`, `nexus-publish` | build | Replace: Tapir endpoints (no codegen), Mill `ScalaJSModule`, Mill `mill-contrib-docker`, Mill's built-in `scoverage` contrib (`mill-contrib-scoverage`), scalafmt/scalafix via Mill (`mill.scalalib.scalafmt.ScalafmtModule`, `mill-contrib-scalafix` is third-party `com.goyeau::mill-scalafix` — verify before adopting), git info via a tiny Mill task. |
| `kafka-clients` `test` classifier | broker test utilities | Replace: Testcontainers Kafka (KRaft) 2.0.5. |

---

## Decision candidates (Decision / Evidence / Tradeoff / Reversibility)

1. **Scala 3.9.0 LTS on JDK 21, Mill 1.1.8, Scala.js 1.22.0** — Evidence: F1, F9. Tradeoff: 3.9.0 is
   days old and the announcement post is not yet live; Chimney needs 2.0.0-RC1 on it. Reversibility:
   high (3.3.8 fallback; all libraries cross-publish).
2. **fs2-kafka `org.typelevel` 4.0.0 + kafka-clients 4.3.1** — Evidence: F2. Tradeoff: Kafka 4 client
   API differences vs Kafbat's 3.9 reference code. Reversibility: medium (4.x offset model is a
   deliberate redesign; do not start on 3.9.1).
3. **Tapir 1.13.31 + sttp 4.0.26 + `tapir-sttp-client4`** — Evidence: F3. Confirmed latest; no newer line.
4. **Drop Fabric; log4cats 2.8.0 + Logback + logstash encoder** — Evidence: F5. Reversibility: high.
5. **otel4s 1.1.0 `oteljava` + OTel Java SDK 1.65.0 (OTLP) + `exporter-prometheus 1.65.0-alpha`** —
   Evidence: F4. Tradeoff: alpha-suffixed Prometheus exporter. Reversibility: high (swap to
   `otel4s-sdk-exporter-prometheus 0.19.2` if the alpha proves unstable).
6. **Ciris 3.15.0 over PureConfig** — Evidence: F6. Reversibility: medium (config model is explicit
   either way; the loader is a single module).
7. **Schema registry: own Tapir/sttp REST client; Confluent serializers 8.3.1 wrapped for wire
   format** — Evidence: F7. Tradeoff: Confluent Community License and heavy transitive graph confined
   to one module. Reversibility: medium.
8. **Chimney 2.0.0-RC1** — Evidence: F8. Tradeoff: RC. Reversibility: high (mapping code is mechanical;
   hand-written mappers are the fallback).
9. **MUnit only (no Weaver)** — Evidence: F10.
10. **OIDC hand-rolled over `oauth2-oidc-sdk 11.38.2` + `nimbus-jose-jwt 10.9.1`; LDAP via UnboundID
    7.0.5; reject pac4j** — Evidence: F9.
11. **Lucene deferred; Scala in-memory search first** — Evidence: F9 (JDK 21 requirement, small data).
12. **datasketches 8.0.0 (not 9.0.0) because of JDK 25 requirement** — Evidence: F9.
13. **Laminar/Airstream 17.2.1, Waypoint 9.0.0** — Evidence: F12.
14. **MCP: andimiller/scala-mcp 0.13.0 prototype at M9** — Evidence: F11.
15. **Docker: `mill-contrib-docker` 1.1.8** — Evidence: F1 (Jib plugins dead).

## Open questions

- Scala 3.9.0 announcement: confirm the scala-lang.org release post and the GitHub release object are
  published before locking `DEPENDENCY_MATRIX.md`; otherwise start on 3.3.8 and bump.
- Chimney 2.0.0 final date; whether `hearth` macros compile cleanly under `-Werror -Wunused:all`.
- Whether `org.lz4:lz4-java 1.8.1` (2025-11-26) already fixes CVE-2025-12183 (Kafbat moved to the
  `at.yawk.lz4` fork in early 2026; check the CVE advisory before choosing).
- Confluent Community License review for `kui-serde-confluent` (self-hosted use is permitted; document it).
- `tapir-netty-server-cats` streaming of SSE/long-lived responses on Netty 4.2 for the message
  exploration path — needs a spike; Tapir's `serverSentEventsBody` and fs2 streams are supported.
- Mill 1.2.0 (RC1 since June 2026): stay on 1.1.8 until 1.2.0 final; re-check `mill-contrib-docker` API.

## Confidence

**High** for versions, licenses and activity: every number above comes from Maven Central metadata or
the GitHub API read on 2026-09-03, not from memory or the stale `search.maven.org` index.
**Medium** for the JDK-25 reading of datasketches 9.0.0 (read from the pom's `<java.version>` property
with `<release>${java.version}</release>`; not built locally) and for the Scala 3.9.0 "LTS" label
(inferred from the Scala team's published LTS plan, Chimney's release notes naming "Scala 3.9.0
(LTS)", and the artifact being on Central; the official announcement page was not yet reachable).
**Medium** for the recommendation rows (Ciris, MUnit-only, reject pac4j): these are judgment calls
with the evidence stated; the ADRs must carry the final decision.

---

## Proposed `DEPENDENCY_MATRIX.md`

Scopes: `main`, `runtime`, `test`, `build` (Mill plugin / tool), `js` (Scala.js only), `shared`
(cross-compiled JVM+JS). Modules follow KUI's naming convention. Versions verified 2026-09-03.

| Group | Artifact | Version | Scope | Modules | ADR |
| --- | --- | --- | --- | --- | --- |
| org.scala-lang | scala3-library_3 | 3.9.0 | main | all | ADR-001 |
| com.lihaoyi | mill-dist | 1.1.8 | build | root | ADR-001 |
| com.lihaoyi | mill-contrib-docker_3 | 1.1.8 | build | deployables | ADR-001 / ADR-005 |
| org.scalameta | scalafmt-core_2.13 | 3.11.5 | build | root | ADR-001 |
| ch.epfl.scala | scalafix-core_2.13 | 0.14.7 | build | root | ADR-001 |
| org.scala-js | scalajs-library_2.13 | 1.22.0 | js | kui-shell, kui-mfe-* | ADR-011 |
| org.typelevel | cats-core_3 | 2.13.0 | shared | all | ADR-002 |
| org.typelevel | cats-effect_3 | 3.7.1 | shared | all | ADR-002 |
| co.fs2 | fs2-core_3 | 3.13.0 | main | all JVM | ADR-002 |
| co.fs2 | fs2-io_3 | 3.13.0 | main | kui-http, kui-export | ADR-002 |
| org.typelevel | fs2-kafka_3 | 4.0.0 | main | kui-kafka, kui-messages | ADR-006 |
| org.apache.kafka | kafka-clients | 4.3.1 | main | kui-kafka | ADR-006 |
| org.xerial.snappy | snappy-java | 1.1.10.8 | runtime | kui-kafka | ADR-006 |
| at.yawk.lz4 | lz4-java | 1.11.2 | runtime | kui-kafka | ADR-006 |
| com.softwaremill.sttp.tapir | tapir-core_3 | 1.13.31 | shared | kui-contract-* | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-json-circe_3 | 1.13.31 | shared | kui-contract-* | ADR-003 / ADR-007 |
| com.softwaremill.sttp.tapir | tapir-iron_3 | 1.13.31 | shared | kui-contract-* | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-cats-effect_3 | 1.13.31 | main | services | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-netty-server-cats_3 | 1.13.31 | main | services, kui-gateway | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-files_3 | 1.13.31 | main | kui-gateway | ADR-003 / ADR-012 |
| com.softwaremill.sttp.tapir | tapir-sttp-client4_3 | 1.13.31 | shared | kui-gateway, kui-shell, kui-mfe-* | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-openapi-docs_3 | 1.13.31 | main | services, kui-gateway | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-swagger-ui-bundle_3 | 1.13.31 | main | kui-gateway | ADR-003 |
| com.softwaremill.sttp.tapir | tapir-apispec-docs_3 | 1.13.31 | main | kui-schema | ADR-014 |
| com.softwaremill.sttp.tapir | tapir-otel4s-tracing_3 | 1.13.31 | main | services, kui-gateway | ADR-009 |
| com.softwaremill.sttp.tapir | tapir-opentelemetry-metrics_3 | 1.13.31 | main | services, kui-gateway | ADR-009 |
| com.softwaremill.sttp.tapir | tapir-sttp-stub4-server_3 | 1.13.31 | test | all | ADR-018 |
| com.softwaremill.sttp.client4 | core_3 | 4.0.26 | shared | kui-http-client, js modules | ADR-003 |
| com.softwaremill.sttp.client4 | fs2_3 | 4.0.26 | main | kui-http-client | ADR-003 |
| com.softwaremill.sttp.client4 | circe_3 | 4.0.26 | shared | kui-http-client | ADR-007 |
| com.softwaremill.sttp.apispec | jsonschema-circe_3 | 0.11.10 | main | kui-schema | ADR-014 |
| io.circe | circe-core_3 | 0.14.16 | shared | all | ADR-007 |
| io.circe | circe-generic_3 | 0.14.16 | shared | contracts (semi-auto only) | ADR-007 |
| io.circe | circe-parser_3 | 0.14.16 | shared | all | ADR-007 |
| io.circe | circe-yaml_3 | 0.16.1 | main | kui-config | ADR-013 |
| is.cir | ciris_3 | 3.15.0 | main | kui-config | ADR-013 |
| is.cir | ciris-circe-yaml_3 | 3.15.0 | main | kui-config | ADR-013 |
| io.github.iltotore | iron_3 | 3.3.2 | shared | kui-domain, kui-contract-* | ADR-007 / ADR-013 |
| io.github.iltotore | iron-circe_3 | 3.3.2 | shared | kui-contract-* | ADR-007 |
| io.github.iltotore | iron-cats_3 | 3.3.2 | shared | kui-domain | ADR-007 |
| io.github.iltotore | iron-ciris_3 | 3.3.2 | main | kui-config | ADR-013 |
| io.scalaland | chimney_3 | 2.0.0-RC1 | main | application layers | ADR-007 |
| com.softwaremill.macwire | macros_3 | 2.6.7 | main (provided) | composition roots | ADR-010 |
| com.softwaremill.macwire | util_3 | 2.6.7 | main | composition roots | ADR-010 |
| org.typelevel | log4cats-core_3 | 2.8.0 | main | all JVM | ADR-008 |
| org.typelevel | log4cats-slf4j_3 | 2.8.0 | main | deployables | ADR-008 |
| org.slf4j | slf4j-api | 2.0.18 | main | deployables | ADR-008 |
| ch.qos.logback | logback-classic | 1.6.3 | runtime | deployables | ADR-008 |
| net.logstash.logback | logstash-logback-encoder | 9.0 | runtime | deployables | ADR-008 |
| org.typelevel | otel4s-core_3 | 1.1.0 | main | all JVM | ADR-009 |
| org.typelevel | otel4s-oteljava_3 | 1.1.0 | main | deployables | ADR-009 |
| org.typelevel | otel4s-oteljava-testkit_3 | 1.1.0 | test | services | ADR-009 |
| io.opentelemetry | opentelemetry-sdk | 1.65.0 | main | deployables | ADR-009 |
| io.opentelemetry | opentelemetry-sdk-extension-autoconfigure | 1.65.0 | main | deployables | ADR-009 |
| io.opentelemetry | opentelemetry-exporter-otlp | 1.65.0 | runtime | deployables | ADR-009 |
| io.opentelemetry | opentelemetry-exporter-prometheus | 1.65.0-alpha | runtime | deployables | ADR-009 |
| io.confluent | kafka-schema-registry-client | 8.3.1 | main | kui-serde-confluent | ADR-014 |
| io.confluent | kafka-avro-serializer | 8.3.1 | main | kui-serde-confluent | ADR-014 |
| io.confluent | kafka-protobuf-serializer | 8.3.1 | main | kui-serde-confluent | ADR-014 |
| io.confluent | kafka-json-schema-serializer | 8.3.1 | main | kui-serde-confluent | ADR-014 |
| org.apache.avro | avro | 1.12.2 | main | kui-serde-avro | ADR-014 |
| com.github.fd4s | vulcan_3 | 1.13.0 | main | kui-serde-avro | ADR-014 |
| com.thesamet.scalapb | scalapb-runtime_3 | 0.11.20 | main | kui-serde-protobuf | ADR-014 |
| com.google.protobuf | protobuf-java | 4.36.1 | main | kui-serde-protobuf | ADR-014 |
| com.networknt | json-schema-validator | 3.0.7 | main | kui-serde-jsonschema | ADR-014 |
| org.msgpack | msgpack-core | 0.9.12 | main | kui-serde-msgpack | ADR-014 |
| dev.cel | cel | 0.14.0 | main | kui-filter | ADR-017 |
| org.apache.datasketches | datasketches-java | 8.0.0 | main | kui-analysis | ADR-016 (or dedicated ADR) |
| com.github.ben-manes.caffeine | caffeine | 3.2.4 | main | kui-cache | ADR-016 |
| org.gnieh | fs2-data-csv_3 | 1.14.1 | main | kui-acl | — |
| com.nimbusds | oauth2-oidc-sdk | 11.38.2 | main | kui-identity | ADR-015 |
| com.nimbusds | nimbus-jose-jwt | 10.9.1 | main | kui-identity | ADR-015 |
| com.unboundid | unboundid-ldapsdk | 7.0.5 | main | kui-identity | ADR-015 |
| software.amazon.msk | aws-msk-iam-auth | 2.3.7 | runtime (optional) | kui-kafka-auth | ADR-006 |
| com.azure | azure-identity | 1.18.6 | runtime (optional) | kui-kafka-auth | ADR-006 |
| com.google.cloud.hosted.kafka | managed-kafka-auth-login-handler | 1.0.6 | runtime (optional) | kui-kafka-auth | ADR-006 |
| com.google.oauth-client | google-oauth-client | 1.39.0 | runtime (optional) | kui-kafka-auth | ADR-006 |
| com.raquo | laminar_sjs1_3 | 17.2.1 | js | kui-shell, kui-mfe-* | ADR-011 |
| com.raquo | airstream_sjs1_3 | 17.2.1 | js | kui-shell, kui-mfe-* | ADR-011 |
| com.raquo | waypoint_sjs1_3 | 9.0.0 | js | kui-shell | ADR-011 / ADR-012 |
| be.doeraene | url-dsl_sjs1_3 | 0.7.0 | js | kui-shell | ADR-011 |
| org.scala-js | scalajs-dom_sjs1_3 | 2.8.1 | js | kui-shell, kui-mfe-* | ADR-011 |
| io.github.cquiroz | scala-java-time_sjs1_3 | 2.7.0 | js | kui-contract-* (JS side) | ADR-011 |
| org.scalameta | munit_3 | 1.3.6 | test | all | ADR-018 |
| org.scalameta | munit-scalacheck_3 | 1.3.1 | test | all | ADR-018 |
| org.typelevel | munit-cats-effect_3 | 2.2.0 | test | all JVM | ADR-018 |
| org.scalacheck | scalacheck_3 | 1.20.0 | test | all | ADR-018 |
| org.typelevel | discipline-munit_3 | 2.0.0 | test | kui-domain | ADR-018 |
| com.dimafeng | testcontainers-scala-munit_3 | 0.44.1 | test | integration suites | ADR-018 |
| com.dimafeng | testcontainers-scala-kafka_3 | 0.44.1 | test | integration suites | ADR-018 |
| org.testcontainers | testcontainers-kafka | 2.0.5 | test | integration suites | ADR-018 |
| org.bouncycastle | bcpkix-jdk18on | 1.85 | test | kui-kafka (TLS tests) | ADR-018 |
| net.andimiller.mcp | mcp-core_3, mcp-tapir_3 | 0.13.0 | main (M9+, provisional) | kui-mcp | new ADR at M9 |

Explicitly **not** in the matrix: Fabric, Scaffeine, pac4j, Lucene, ScalaCSS, Confluent
`kafka-clients -ccs`, Jackson (any), Spring (any), ANTLR (direct), Weaver, WireMock, Mockito,
the official Java MCP SDK, datasketches 9.0.0, Chimney 1.x, `com.github.fd4s:fs2-kafka` 3.x,
`tapir-sttp-client` (sttp 3), `circe-json-schema`, `victools jsonschema-generator`.

### Sources (URLs)

- Maven Central metadata root: https://repo1.maven.org/maven2/ (per-artifact `maven-metadata.xml`, read 2026-09-03)
- Confluent Maven: https://packages.confluent.io/maven/io/confluent/kafka-schema-registry-client/maven-metadata.xml
- Scala LTS policy: https://www.scala-lang.org/blog/next-scala-lts.html ; 3.3.8: https://www.scala-lang.org/news/3.3.8/ ; scala3 releases: https://github.com/scala/scala3/releases
- Mill: https://github.com/com-lihaoyi/mill/releases ; https://mill-build.org/mill/contrib/docker.html ; https://mill-build.org/mill/scalalib/web-examples.html ; https://mill-build.org/mill/extending/thirdparty-plugins.html
- Cats Effect: https://github.com/typelevel/cats-effect/releases ; FS2: https://github.com/typelevel/fs2/releases
- fs2-kafka 4.0.0 (org move): https://github.com/typelevel/fs2-kafka/releases/tag/v4.0.0 ; vulcan: https://github.com/typelevel/vulcan/releases
- Kafka: https://github.com/apache/kafka (build.gradle `minClientJavaVersion = 11`, `minNonClientJavaVersion = 17` at 4.3.1)
- Tapir: https://github.com/softwaremill/tapir/releases/tag/v1.13.31 ; https://raw.githubusercontent.com/softwaremill/tapir/v1.13.31/project/Versions.scala
- sttp: https://github.com/softwaremill/sttp/releases/tag/v4.0.26
- Circe: https://github.com/circe/circe/releases/tag/v0.14.16
- log4cats: https://github.com/typelevel/log4cats/releases/tag/v2.8.0
- Fabric: https://github.com/typelevel/fabric (README)
- otel4s: https://github.com/typelevel/otel4s/releases/tag/v1.0.0 ; https://github.com/typelevel/otel4s/releases/tag/v1.1.0 ; https://typelevel.org/otel4s-sdk/sdk/overview.html
- MacWire: https://github.com/softwaremill/macwire/releases/tag/v2.6.7
- ScalaCheck: https://github.com/typelevel/scalacheck/releases/tag/v1.20.0 ; MUnit: https://github.com/scalameta/munit/releases/tag/v1.3.6 ; munit-cats-effect: https://github.com/typelevel/munit-cats-effect/releases/tag/v2.2.0
- Weaver (Typelevel): https://github.com/typelevel/weaver-test/releases/tag/v0.13.0
- testcontainers-scala: https://github.com/testcontainers/testcontainers-scala/releases/tag/v0.44.1 ; Testcontainers Java: https://github.com/testcontainers/testcontainers-java/releases
- Chimney: https://github.com/scalalandio/chimney/releases/tag/2.0.0-RC1 ; https://github.com/scalalandio/chimney/discussions/592
- Iron: https://github.com/Iltotore/iron/releases/tag/v3.3.2
- Ciris: https://github.com/vlovgr/ciris/releases/tag/v3.15.0 ; PureConfig: https://github.com/pureconfig/pureconfig
- ScalaPB: https://github.com/scalapb/ScalaPB/releases
- fs2-data: https://github.com/gnieh/fs2-data/releases/tag/v1.14.1
- cel-java: https://github.com/google/cel-java/releases/tag/v0.14.0
- datasketches-java poms: https://raw.githubusercontent.com/apache/datasketches-java/9.0.0/pom.xml (java 25), /8.0.0/pom.xml (21), /7.0.1/pom.xml (17)
- Lucene: https://github.com/apache/lucene (10.x requires JDK 21)
- UnboundID: https://github.com/pingidentity/ldapsdk ; Nimbus: https://connect2id.com/products/nimbus-jose-jwt ; pac4j: https://github.com/pac4j/pac4j
- Scala.js: https://github.com/scala-js/scala-js/releases/tag/v1.22.0
- Laminar: https://github.com/raquo/Laminar (tags) ; https://laminar.dev/blog/ ; Waypoint: https://github.com/raquo/Waypoint ; url-dsl: https://github.com/sherpal/url-dsl
- scalafmt: https://github.com/scalameta/scalafmt/releases ; scalafix: https://github.com/scalacenter/scalafix/releases
- Mill Docker plugins: https://github.com/atty303/mill-jib (archived) ; https://github.com/vic/mill-docker ; https://github.com/GeorgOfenbeck/mill-docker
- MCP: https://github.com/andimiller/scala-mcp ; https://github.com/linkyard/scala-effect-mcp ; https://github.com/TJC-LP/fast-mcp-scala ; https://github.com/indoorvivants/mcp ; https://github.com/modelcontextprotocol/java-sdk/releases
- Kafbat inputs: `/tmp/kui-ref/kafbat/gradle/libs.versions.toml`, `/tmp/kui-ref/kafbat/api/build.gradle`
