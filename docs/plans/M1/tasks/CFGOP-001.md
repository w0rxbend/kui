# CFGOP-001 — `kui.clusters[]`: typed security, the `properties` override layer, slug ids

- **ID:** CFGOP-001
- **Title:** `kui.clusters[]`: typed security, the `properties` override layer, slug ids
- **Milestone / Feature:** M1 / CL-001, CL-002, OT-001
- **Owner role:** Infrastructure Lead
- **Context / service:** `libs/config`
- **Size:** L
- **Dependencies / blocked by:** KAFKA-001

## Goal (user value)

An operator writes down how to reach their Kafka clusters — bootstrap addresses, TLS material,
a SASL mechanism and a password — and KUI either starts with all of it understood, or refuses to
start and prints **every** thing that is wrong with it in one message, with the key names and
without ever echoing the password.

## Scope

The `kui.clusters[]` slice of the static configuration model: the keys, their decoders, their
validation and their error messages. This is the M1 half of CFG-001's deliberately empty
placeholder (`List("kui", "clusters", "**")` in `UnknownKeys.Known`), which this task replaces
with the real key list.

1. A `ClusterConfig` per configured cluster, decoded by hand with the `Field` machinery already
   in `KuiConfigSource` (ADR-013: hand-written loaders, no derivation).
2. The typed security ADT of ADR-022, decoded from YAML into the `kui.kernel.cluster` types that
   **KAFKA-001 defines**. This task writes no ADT of its own; it writes the decoder.
3. The `properties` override layer: a free `Map[String, String]` of raw Kafka client properties,
   carried through untouched and redacted by key pattern in every rendering.
4. `ClusterId` derivation: an explicit `id`, or a slug of `name`. Two clusters that slug to the
   same id are a validation error naming both configured names.
5. Cluster-index discovery, so `KUI_CLUSTERS_0_SECURITY_PROTOCOL` is recognised as belonging to
   cluster `0` (the existing `Layers.childrenOf` cannot do this — see "Decisions", D-2).
6. Every problem accumulated with the rest of the configuration's problems, in one message.

## Non-goals

- **No `kui.store.*`.** STORE-004 owns that slice. This task does not add `kui.store` to
  `UnknownKeys.Known` and does not add a field to `KuiConfig` for it.
- **No `ConfigStore` overlay.** The store's records overlay this static list at runtime, and
  that merge is CLDOM-004's `ClusterRegistry`. This task produces the *static base* only.
- **No Kafka client.** `libs/config` gains a `libs.kafka` dependency in STORE-005 for the store
  adapter; nothing in this task needs it and this task must not add it.
- **No Kafbat legacy env mapping** (`KAFKA_CLUSTERS_0_*`). That is the M8 migration tool. The
  `properties` override layer is the compatibility surface ADR-022 promises, and it is enough.
- **No `admin` tuning keys.** CFGOP-002 adds them, immediately after this task.
- **No connection attempt at load time.** Configuration validation is syntactic. A cluster that
  is configured correctly and unreachable is a runtime `Section.Unavailable` (DEVPLAN §10, D4),
  never a startup failure — otherwise one dead broker stops KUI from starting at all.

## Design references

ADR-022 (the security ADT, the override layer, cloud handlers as optional runtime modules),
ADR-031 (`ClusterId` is a slug of the configured name; collisions rejected at config validation),
ADR-013 (Ciris, precedence, hand-written loaders, `Secret`), ADR-030 (no version assumptions in
configuration — nothing here asks the operator for a broker version),
`ARCHITECTURE.md` §10 and §14 (URL policy), `research/scala/security-research.md` §3 (the
mechanism table: exactly which `sasl.*` properties each mechanism needs, and Kouncil's
`String.format` JAAS injection this model exists to prevent), M1 DEVPLAN §10 D1 (the ADT lives in
`libs/kernel`), `docs/plans/M0/tasks/CFG-001.md` (the loader this extends, including its eight
recorded deviations — they are binding conventions, not history).

## Files to create or change

```
libs/config/src/kui/config/ClusterConfig.scala                        (new)
libs/config/src/kui/config/ClusterSecurityDecoder.scala               (new)
libs/config/src/kui/config/KuiConfig.scala                            (change: one field)
libs/config/src/kui/config/KuiConfigSource.scala                      (change: decodeClusters,
                                                                       Layers.indicesOf, the
                                                                       UnknownKeys entries)
libs/config/test/src/kui/config/ClusterConfigSuite.scala               (new)
libs/config/test/src/kui/config/ClusterSecuritySuite.scala             (new)
libs/config/test/src/kui/config/ClusterIdSlugSuite.scala               (new)
libs/config/test/resources/config/clusters-all-mechanisms.yaml         (new)
libs/config/test/resources/config/clusters-duplicate-slug.yaml         (new)
libs/config/test/resources/config/clusters-multiple-errors.yaml        (new)
docs/operations/configuration.md                                       (change: the cluster keys)
deployment/compose/kui.yaml, kui-cluster.yaml, kui-allinone.yaml       (no change here —
                                                                        CFGOP-006 fills them in)
```

**`KuiConfigSource.scala` and `KuiConfig.scala` are shared with STORE-004.** The rule, because
DEVPLAN §6.5 gives these two files no single owner: a task adds only its own `decode*` function,
its own field on `KuiConfig`, and its own entries in `UnknownKeys.Known`. Neither task
reformats, reorders or refactors anything the other wrote. The two edits are line-local and
merge cleanly.

## The keys

Repeated sections use a dotted index, never brackets (CFG-001 deviation 5), so one key has one
spelling in YAML, in the environment and on the command line.

| Key | Environment name | Default | Meaning |
| --- | --- | --- | --- |
| `kui.clusters.<n>.name` | `KUI_CLUSTERS_<N>_NAME` | *(required)* | The display name. 1–64 characters. |
| `kui.clusters.<n>.id` | `KUI_CLUSTERS_<N>_ID` | *(slug of `name`)* | The URL slug. Set it to keep bookmarks alive across a rename (ADR-031). |
| `kui.clusters.<n>.bootstrapServers` | `KUI_CLUSTERS_<N>_BOOTSTRAPSERVERS` | *(required)* | `host:port` entries; a YAML list, or comma-separated in the environment. |
| `kui.clusters.<n>.readOnly` | `KUI_CLUSTERS_<N>_READONLY` | `false` | Declared now, enforced in M5. Recorded on the profile and shown in the UI. |
| `kui.clusters.<n>.security.protocol` | …`_SECURITY_PROTOCOL` | `PLAINTEXT` | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT` or `SASL_SSL`. |
| `kui.clusters.<n>.security.mechanism` | …`_SECURITY_MECHANISM` | *(required for the two SASL protocols)* | See the mechanism table below. |
| `kui.clusters.<n>.security.username` | …`_SECURITY_USERNAME` | *(required for PLAIN and SCRAM)* | |
| `kui.clusters.<n>.security.password` | …`_SECURITY_PASSWORD` | *(required for PLAIN and SCRAM)* | A secret: literal, `env:NAME` or `file:/path`. |
| `kui.clusters.<n>.security.serviceName` | …`_SECURITY_SERVICENAME` | *(required for GSSAPI)* | `sasl.kerberos.service.name`. |
| `kui.clusters.<n>.security.principal` | …`_SECURITY_PRINCIPAL` | *(required for GSSAPI)* | |
| `kui.clusters.<n>.security.keytab` | …`_SECURITY_KEYTAB` | *(required for GSSAPI)* | A path inside the container. |
| `kui.clusters.<n>.security.tokenEndpoint` | …`_SECURITY_TOKENENDPOINT` | *(required for OAUTHBEARER)* | Checked against the URL policy. |
| `kui.clusters.<n>.security.clientId` | …`_SECURITY_CLIENTID` | *(required for OAUTHBEARER)* | |
| `kui.clusters.<n>.security.clientSecret` | …`_SECURITY_CLIENTSECRET` | *(required for OAUTHBEARER)* | A secret. |
| `kui.clusters.<n>.security.scope` | …`_SECURITY_SCOPE` | *(unset)* | |
| `kui.clusters.<n>.security.profile` | …`_SECURITY_PROFILE` | *(unset)* | AWS named profile, `AWS_MSK_IAM` only. |
| `kui.clusters.<n>.security.namespace` | …`_SECURITY_NAMESPACE` | *(required for AZURE_ENTRA)* | The Event Hubs namespace. |
| `kui.clusters.<n>.security.ssl.truststore.location` | …`_SECURITY_SSL_TRUSTSTORE_LOCATION` | *(unset)* | A path. Mutually exclusive with `inline`. |
| `kui.clusters.<n>.security.ssl.truststore.inline` | …`_SECURITY_SSL_TRUSTSTORE_INLINE` | *(unset)* | Base64 of the store's bytes. A secret. |
| `kui.clusters.<n>.security.ssl.truststore.password` | …`_SECURITY_SSL_TRUSTSTORE_PASSWORD` | *(unset)* | A secret. |
| `kui.clusters.<n>.security.ssl.truststore.type` | …`_SECURITY_SSL_TRUSTSTORE_TYPE` | `PKCS12` | `PKCS12` or `JKS`. |
| `kui.clusters.<n>.security.ssl.keystore.*` | …`_SECURITY_SSL_KEYSTORE_*` | *(unset)* | The same four keys, for mTLS. |
| `kui.clusters.<n>.security.ssl.keyPassword` | …`_SECURITY_SSL_KEYPASSWORD` | *(unset)* | A secret. |
| `kui.clusters.<n>.security.ssl.verifyHostname` | …`_SECURITY_SSL_VERIFYHOSTNAME` | `true` | `false` renders `ssl.endpoint.identification.algorithm=""`. |
| `kui.clusters.<n>.properties.<kafka.property>` | *(not settable from the environment — see D-4)* | *(empty)* | Raw Kafka client properties, applied last (ADR-022). |

**Mechanism table.** `security.mechanism` accepts exactly these spellings, upper-case, which are
the values Kafka itself uses so an operator can copy them from Kafka's own documentation:

| Value | ADT case | Required alongside | Integration-tested in M1? |
| --- | --- | --- | --- |
| `PLAIN` | `Mechanism.Plain` | `username`, `password` | **yes** (CFGOP-004) |
| `SCRAM-SHA-256` | `Mechanism.ScramSha256` | `username`, `password` | no (SHA-512 is) |
| `SCRAM-SHA-512` | `Mechanism.ScramSha512` | `username`, `password` | **yes** (CFGOP-004) |
| `GSSAPI` | `Mechanism.Gssapi` | `serviceName`, `principal`, `keytab` | no — string-level only (R-1) |
| `OAUTHBEARER` | `Mechanism.OAuthBearer` | `tokenEndpoint`, `clientId`, `clientSecret` | no — string-level only |
| `AWS_MSK_IAM` | `Mechanism.AwsMskIam` | *(optional `profile`)* | no — string-level only |
| `AZURE_ENTRA` | `Mechanism.AzureEntra` | `namespace` | no — string-level only |
| `GCP` | `Mechanism.GcpManagedKafka` | *(nothing)* | no — string-level only |

The last column is not decoration: R-1 in the DEVPLAN requires that
`docs/operations/configuration.md` carries this same column, so no mechanism is ever claimed as
supported without saying how far it was tested. CFGOP-008 checks that the two tables agree.

## Public Scala signatures to implement

```scala
package kui.config

import kui.kernel.{ClusterId, Secret}
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClusterSecurity}

/** One statically configured cluster: everything KUI needs to open a client, and nothing about
  * what it found when it did.
  *
  * `properties` is the escape hatch of ADR-022: raw Kafka client properties, applied after
  * everything the typed model rendered, so a mechanism KUI has not modelled yet is still usable
  * without waiting for a release.
  */
final case class ClusterConfig(
    id: ClusterId,
    name: String,
    bootstrapServers: BootstrapServers,
    security: ClusterSecurity,
    properties: Map[String, Secret[String]],
    readOnly: Boolean,
    admin: AdminTuning          // CFGOP-002 fills this in; until then, AdminTuning.Default
)

object ClusterConfig {

  /** ADR-031's derivation, as a total function with a named failure.
    *
    * Lower-cases, replaces every run of characters outside `[a-z0-9]` with a single `-`, and
    * trims leading and trailing dashes. A name that leaves nothing behind — `"***"`, or a name
    * written entirely in a non-Latin script — is a `Left` telling the operator to set `id`
    * explicitly, which is a better answer than inventing `cluster-1` and putting it in a URL
    * they never chose.
    */
  def slug(name: String): Either[String, ClusterId]

  /** Which raw property keys hold a secret, by pattern. Over-redaction is deliberate: a key
    * wrongly treated as a secret prints `***` in a diagnostic, and a key wrongly treated as
    * public prints a password into `docker logs`.
    */
  def isSecretProperty(key: String): Boolean
}
```

`KuiConfig` gains one field:

```scala
final case class KuiConfig(
    server: ServerConfig,
    gateway: GatewayConfig,
    telemetry: TelemetryConfig,
    clusters: List[ClusterConfig]      // added here; STORE-004 adds `store` beside it
)
```

`KuiConfig.Default.clusters` is `Nil`: a KUI with no clusters configured starts, serves the
shell, and shows an empty dashboard with the "no clusters configured" empty state. That is a
supported deployment — it is what an operator sees before they have registered anything through
the store — and it must not be a startup failure.

`KuiConfigSource` gains, as private members:

```scala
private def decodeClusters[F[_]: Async](layers: Layers, policy: UrlPolicy): F[Problems[List[ClusterConfig]]]

private object Layers:
  /** Numeric member names directly under `prefix`, in ascending order, across all three layers.
    *
    * `childrenOf` cannot be used for this. Its environment branch takes everything up to the
    * *last* underscore, which turns `KUI_CLUSTERS_0_SECURITY_PROTOCOL` into the member
    * `0-security`. That rule is right for the flat `services.<id>.<leaf>` map it was written for
    * and wrong for a nested list, so lists get their own discovery: the first segment after the
    * prefix, required to be a non-negative integer.
    */
  def indicesOf(prefix: String): List[Int]
```

`UnknownKeys.Known` loses `List("kui", "clusters", "**")` and gains one entry per key in the
table above with `*` in the index position, plus the one wildcard that stays:

```scala
List("kui", "clusters", "*", "properties", "**")   // a free map of Kafka properties
```

## Decisions taken here

**D-1 — `id` is settable, and the default is the slug.** ADR-031 says the id *is* the slug and
that renaming produces a new id. That is right as a default and wrong as an absolute: an
operator who fixes a typo in a display name would silently break every bookmark and every RBAC
entry. An explicit `id` is the escape hatch, it is validated by `ClusterId.from` like any other,
and `docs/operations/configuration.md` says what it is for. This does not contradict ADR-031 —
the derivation is unchanged — so it needs no amendment, only the documentation sentence.

**D-2 — list indices get their own discovery function.** See the comment on `indicesOf` above.
This is a real defect in the existing loader that only a nested list exposes: without it,
`KUI_CLUSTERS_0_SECURITY_PROTOCOL` names a cluster called `0-security` that no other key
mentions, and the cluster the operator meant to configure never appears. `PrecedenceSuite` gains
a case for it.

**D-3 — the index must be dense, starting at zero.** `kui.clusters.0` and `kui.clusters.2` with
no `1` is a validation error naming the gap. A sparse list almost always means a deleted entry
or a typo in an environment variable name, and silently renumbering would hide it.

**D-4 — `properties` is file-only.** A raw Kafka property key contains dots, and the environment
name mapping replaces dots with underscores, so `KUI_CLUSTERS_0_PROPERTIES_SSL_CIPHER_SUITES`
cannot be mapped back to `ssl.cipher.suites` without a second, inconsistent rule. The keys under
`properties` are therefore read from YAML files only, and an environment variable under that
prefix is an error that says so. A secret inside `properties` still uses `env:`/`file:` — the
*value* travels through the environment, only the *key* does not.

**D-5 — the secret-property patterns.** `isSecretProperty(key)` is true when the lower-cased key
contains any of `password`, `passphrase`, `secret`, `credential`, `token`, `jaas`, `keytab`. It
is deliberately a `contains` and not an exact list: Kafka adds properties faster than KUI
releases, and the cost of matching `key.deserializer` by accident is that a diagnostic prints
`***` for a class name.

**D-6 — every `properties` value is a `Secret[String]`, secret-patterned or not.** The renderer
calls `.value` at the edge (KAFKA-002) either way, so carrying the distinction in the type would
buy nothing and cost a second code path. `isSecretProperty` decides what a *diagnostic* prints,
not what the type is; a non-secret property renders its value in the redacted map, a secret one
renders `***`.

**D-7 — an unknown mechanism names the eight legal values.** Not "invalid mechanism": the
message is `expected one of PLAIN, SCRAM-SHA-256, …, got 'scram512'`. The operator who wrote
`scram512` needs the spelling, not the verdict.

## Library coordinates

No new dependency. Everything is already on `libs/config`'s classpath (DEPENDENCY_MATRIX.md):

```
is.cir::ciris::3.15.0
io.circe::circe-yaml::0.16.1
io.circe::circe-core::0.14.16
org.typelevel::cats-core::2.13.0
```

`libs.config`'s `moduleDeps` gains nothing: `kui.kernel.cluster` is inside `libs.kernel.jvm`,
which `libs.config` already depends on.

## Acceptance criteria

```
$ ./mill libs.config.test
+ every mechanism renders into the expected ADT case
+ a cluster with no security key is PLAINTEXT
+ two clusters whose names slug to the same id are rejected, naming both
+ a sparse cluster index is rejected, naming the gap
+ properties survive verbatim and are redacted by key pattern
```

Behavioural acceptance, reproducible by hand:

```
$ ./mill services.cluster.app.run -- --config libs/config/test/resources/config/clusters-multiple-errors.yaml
kui.clusters.0.bootstrapServers: expected one or more host:port entries; 'kafka-1' has no port (found 'kafka-1')   (file: clusters-multiple-errors.yaml)
kui.clusters.0.security.mechanism: expected one of PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, GSSAPI, OAUTHBEARER, AWS_MSK_IAM, AZURE_ENTRA, GCP; got 'scram512' (found 'scram512')   (file: clusters-multiple-errors.yaml)
kui.clusters.1.security.password: expected a secret; environment variable KUI_PROD_PASSWORD is not set (found ***)   (file: clusters-multiple-errors.yaml)
kui.clusters.2: expected clusters to be numbered from 0 with no gaps; 2 follows 0   (file: clusters-multiple-errors.yaml)
# exit code 1, four lines, no stack trace, no password anywhere in the output
```

That four-line output **is** the milestone exit criterion "Configuration with an unknown key, a
missing secret, or an invalid URL fails at startup with all errors accumulated in one message"
for the cluster half of the configuration; the unknown-key and invalid-URL halves already pass
from CFG-001 and are re-asserted by `ClusterConfigSuite` against a file that mixes all three.

## Tests required

- `ClusterConfigSuite` (unit):
  - `accumulatesEveryClusterProblem` — the `clusters-multiple-errors.yaml` fixture yields exactly
    the four problems above, in key order.
  - `unknownKeyUnderAClusterIsRejected` — `kui.clusters.0.bootstrapServer` (singular) names
    itself.
  - `propertiesAreNotSubjectToUnknownKeyChecking` — an arbitrary key under `properties` loads.
  - `propertiesFromTheEnvironmentAreRejectedWithTheReason` (D-4).
  - `emptyClusterListLoads` — `clusters: []` produces `Nil` and no problem.
  - `sparseIndexIsRejected` (D-3).
- `ClusterSecuritySuite` (unit, table-driven over the mechanism table): each of the eight
  mechanisms with its required keys decodes to the expected ADT case; each with a required key
  missing produces one problem naming that key and no others; `protocol: SASL_SSL` with no
  `mechanism` names `mechanism`; `protocol: PLAINTEXT` with a `mechanism` set is a problem saying
  the mechanism is only meaningful for a SASL protocol (a silently ignored mechanism is how an
  operator ends up with an unauthenticated connection they believe is authenticated).
- `ClusterSecuritySuite` (property, ScalaCheck): for any password drawn from a generator that
  includes quotes, backslashes, spaces, newlines, `=` and `;`, decoding round-trips the exact
  bytes. (Rendering that password into JAAS is KAFKA-002's property test; this one asserts the
  loader does not mangle it on the way in, which is where Kouncil's bug would have been invisible.)
- `ClusterIdSlugSuite` (unit + property): the documented derivation; `"Production EU"` →
  `production-eu`; `"prod  /  eu"` → `prod-eu`; `"***"` is a `Left` naming the cluster; a
  property asserting every `Right` satisfies `ClusterId.from`.
- `PrecedenceSuite` (change): one case per layer for `kui.clusters.0.name`, and the `indicesOf`
  case from D-2 with the value supplied only through the environment.
- `SecretRedactionSuite` (change): a cluster password and a `properties` entry matching D-5's
  patterns are added to the existing four-sink assertion, with the same negative control.

## Observability

The startup configuration log line (CFG-002) gains the cluster list: one entry per cluster with
`id`, `name`, `bootstrapServers`, `security.protocol`, `security.mechanism` and the *keys* of
`properties`. No `properties` value, no password, no keystore bytes. The count of configured
clusters is logged at INFO as its own field so an operator can see at a glance whether the file
they meant to mount was read.

## Degraded behavior

None at load time: an invalid cluster configuration is a startup failure like any other. The
degraded path belongs to runtime — a cluster that is configured correctly and unreachable
renders as `Section.Unavailable(reason)` inside a healthy dashboard response (DEVPLAN §10, D4),
and that is CLAPI-007's and CLUI-003's work, not this task's.

## Docs to update

`docs/operations/configuration.md`: a new "Clusters" section carrying the key table above
verbatim, the mechanism table **including its integration-test column** (R-1), a worked example
per security mode, and the D-1 sentence about `id`. The "What is not here yet" section's
forward reference to M1 is rewritten in the past tense.

## Deviations

Recorded during implementation.
