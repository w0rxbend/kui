# CFGOP-004 — `libs/testkit`: the PLAINTEXT / SASL-SCRAM / SSL Testcontainers topology

- **ID:** CFGOP-004
- **Title:** `libs/testkit`: the PLAINTEXT / SASL-SCRAM / SSL Testcontainers topology
- **Milestone / Feature:** M1 / CL-002, OT-001, KU-033
- **Owner role:** QA Engineer
- **Context / service:** `libs/testkit`
- **Size:** L
- **Dependencies / blocked by:** KAFKA-002

## M1 gate review amendment — verify the Testcontainers coordinate first

**F-15, minor.** `DEPENDENCY_MATRIX.md` line 153 pins `org.testcontainers:testcontainers-kafka`
2.0.5; three M1 specs write `org.testcontainers:kafka:2.0.5`. Both cannot be the artifact that
resolves. This task is the first one that resolves it, so it settles it: resolve the coordinate,
use whichever one exists at 2.0.5, and **correct `DEPENDENCY_MATRIX.md` in this task's commit**
so the other specs and the matrix agree. Record the answer in the Implementation Report;
KAFKA-007 and CFGOP-005 read it from the matrix afterwards.

This task is also the single home of every Kafka container in the repository, PLAINTEXT
included. KAFKA-007's spec was corrected to depend on this one rather than declaring its own
(F-11).

## Goal (user value)

Every claim M1 makes about secured Kafka is checked against a real broker that really refuses
unauthenticated connections. "KUI works with SASL_SSL" stops being a rendering of property
strings and becomes a test that fails when it stops being true.

## Scope

One fixture that starts a single-node KRaft Kafka broker in one of three security
configurations, hands back a ready-to-use `ClusterConfig`, and tears down cleanly.

1. `KafkaTopology`, an enum of the three modes, and `KafkaFixture`, a `cats.effect.Resource`
   producing a `RunningBroker`.
2. **PLAINTEXT** — the baseline, and the mode every other `libs/kafka` suite uses.
3. **SASL_PLAINTEXT with SCRAM-SHA-512** — the mechanism most secured on-premise clusters use.
4. **SSL with mutual TLS** — a generated CA, broker certificate and client certificate, with
   hostname verification on.
5. Certificate and keystore generation with BouncyCastle, at fixture start, into a temporary
   directory — no committed binary fixtures (see D-4).
6. `ClusterConfigs.forBroker(RunningBroker)`, which produces the `kui.clusters[]` entry that
   reaches that broker, so a suite never assembles security settings by hand and the *fixture*
   is what exercises CFGOP-001's decoder.

This is one of the four tasks DEVPLAN §6.4 says to start before anything else, and it is the
first one whose answer is not knowable from a document. **Start it on day one, and if a mode
cannot be made to work, say so in `TECH_DEBT.md` within the first week rather than in week
three** — R-6's whole mitigation is the slack that starting early buys.

## Non-goals

- **No Schema Registry, Connect, ksqlDB or LDAP containers.** They arrive with the milestones
  that need them (DEVPLAN §7).
- **No multi-broker cluster.** One broker in combined KRaft mode. Replica placement, ISR
  shrinkage and rack awareness are interesting and none of them is an M1 exit criterion; a
  three-broker topology triples every suite's start-up cost for nothing this milestone asserts.
- **No SASL_SSL as a fourth mode.** It is the union of modes 3 and 4 and it renders no property
  the other two do not. The exit criterion names three modes; three is what ships. `KafkaTopology`
  is an enum, so adding it later is one case and one broker configuration.
- **No ZooKeeper.** ADR-030's minimum is 2.8, which supports KRaft, and the nightly 2.8 image is
  the only place a ZooKeeper-mode broker could be needed — see D-5.
- **No test that uses this fixture.** The suites live with the code they test: `libs/kafka`'s
  admin suites (KAFKA-007/008/009), the store's integration suite (STORE-009), and the parity
  suite (CFGOP-005). This task delivers the fixture and exactly one self-test proving it comes
  up in each mode.

## Design references

ADR-018 (MUnit and Testcontainers; no mocking library; fakes live in `libs/testkit`), ADR-022
(what a security mode has to look like from the client side), ADR-030 (2.8 minimum, the nightly
job), `research/kafka/admin-capabilities.md` §0 (what an admin client does against a broker that
authenticates but authorizes nothing — fault-injection scenario 4 uses this fixture), M1 DEVPLAN
§7 ("Testcontainers in M1") and risk R-6 with its documented fallback, `DEPENDENCY_MATRIX.md`
(every coordinate below is already pinned there).

## Files to create or change

```
libs/testkit/src/kui/testkit/kafka/KafkaTopology.scala
libs/testkit/src/kui/testkit/kafka/KafkaFixture.scala
libs/testkit/src/kui/testkit/kafka/RunningBroker.scala
libs/testkit/src/kui/testkit/kafka/CertificateAuthority.scala
libs/testkit/src/kui/testkit/kafka/ScramProvisioner.scala
libs/testkit/src/kui/testkit/kafka/ClusterConfigs.scala
libs/testkit/test/src/kui/testkit/kafka/KafkaFixtureSuite.scala
build.mill                       (libs.testkit.jvm gains four coordinates and libs.kafkaAuth is
                                  NOT added — see D-6; Versions gains three entries)
docs/testing.md                  (new section: running a suite against a secured broker)
```

`libs/testkit` is **cross-compiled**, and these sources are JVM-only. They go in the shared `src`
directory only if `libs.testkit.js` still compiles, which it will not — Testcontainers is a JVM
library. **Put every file above under `libs/testkit/src-jvm/kui/testkit/kafka/`**, the source set
only `libs.testkit.jvm` compiles (the same mechanism `libs.securityCore.jvm` uses for nimbus, and
the reason rule A6 exempts `.jvm` halves). Paths in the list above are written without the
`-jvm` suffix for readability; the actual directory is `src-jvm`.

## `libs/testkit` is a `KuiPureModule`

Three of its scalafix rules will bite immediately, and each has one right answer:

| Rule | Where it bites | What to do instead |
| --- | --- | --- |
| `noVars` | a container's lifecycle is mutable | build the container in a `val`, start it inside `Resource.make`; hold anything genuinely mutable in a `cats.effect.Ref` |
| `noNulls` | Testcontainers and Kafka Java APIs return `null` | wrap every Java read in `Option(...)` at the boundary |
| `noThrows` | a fixture wants to `throw` when a container will not start | return `Resource.eval(F.raiseError(...))` with a message naming the mode and the container's last log lines |
| `noAsInstanceOf` | Testcontainers' self-typed builders (`withEnv` returns `SELF`) sometimes need a cast when chained | do not chain: call the builder methods as statements on a `val`, which needs no cast |

This is not a reason to move the fixture out of `libs/testkit`. Every module's suite needs it,
`libs/testkit` is where ADR-018 puts shared test machinery, and the four workarounds above are
each one line.

## Public Scala signatures to implement

```scala
package kui.testkit.kafka

import cats.effect.{Async, Resource}
import kui.config.ClusterConfig            // NOTE: see D-6 — this is a *type reference only*
import kui.kernel.ClusterId

/** Which of the three security configurations a broker is started in. */
enum KafkaTopology {
  case Plaintext
  case SaslScram        // SASL_PLAINTEXT + SCRAM-SHA-512
  case MutualTls        // SSL, both directions, hostname verification on
}

/** A broker that is up, and everything a client needs to reach it. */
final case class RunningBroker(
    topology: KafkaTopology,
    bootstrapServers: String,               // "localhost:<mapped port>"
    /** Client properties that reach this broker. Already correct; a suite passes them straight
      * to an AdminClient when it wants to talk to the broker without going through KUI. */
    clientProperties: Map[String, String],
    /** Where the generated PKCS12 stores live, for the TLS mode. Empty otherwise. */
    materials: Option[TlsMaterials],
    /** The SCRAM user, for the SASL mode. Empty otherwise. */
    credentials: Option[ScramCredentials],
    /** The container's log so far, for a failure message. */
    logs: () => String
)

final case class TlsMaterials(
    truststore: java.nio.file.Path,         // PKCS12, holds the CA certificate
    truststorePassword: String,
    keystore: java.nio.file.Path,           // PKCS12, holds the client key and certificate
    keystorePassword: String,
    keyPassword: String
)

final case class ScramCredentials(username: String, password: String)

object KafkaFixture {

  /** Starts one broker in the given topology and stops it when the resource closes.
    *
    * Start-up is bounded: a broker that is not serving within `startTimeout` fails the resource
    * with a message naming the topology and the last 50 lines of the container log. A silent
    * hang here would be indistinguishable from a slow CI machine, and the difference is exactly
    * what a first-time reader needs to know.
    */
  def apply[F[_]: Async](
      topology: KafkaTopology,
      startTimeout: scala.concurrent.duration.FiniteDuration = 90.seconds
  ): Resource[F, RunningBroker]

  /** The image tag every mode uses, so a suite can print it in a failure message. */
  val Image: String
}

object ClusterConfigs {

  /** The `kui.clusters[]` entry that reaches this broker, with the typed security of ADR-022
    * filled in for the broker's topology.
    *
    * This is the seam that makes the parity suite meaningful: the fixture produces a *configured
    * cluster*, so CFGOP-005 exercises the real decoder, the real property renderer and the real
    * adapter rather than a hand-built profile that happens to work.
    */
  def forBroker(broker: RunningBroker, id: ClusterId): ClusterConfig

  /** The same, as YAML, for a suite that wants to drive the whole loader from a file. */
  def yamlFor(broker: RunningBroker, id: ClusterId): String
}

object CertificateAuthority {

  /** A throwaway CA, a server certificate whose SAN list contains `localhost` and `127.0.0.1`,
    * and a client certificate, written as two PKCS12 stores plus the broker's own pair.
    *
    * Generated per fixture rather than committed, so nothing in this repository has an expiry
    * date that will fail a build in 2027 (D-4).
    */
  def materialize[F[_]: Async](into: java.nio.file.Path): F[(TlsMaterials, BrokerTlsMaterials)]
}

final case class BrokerTlsMaterials(
    keystore: java.nio.file.Path,
    truststore: java.nio.file.Path,
    storePassword: String,
    keyPassword: String
)

object ScramProvisioner {

  /** Creates the SCRAM-SHA-512 user on a running broker, over the broker's bootstrap
    * PLAIN listener, using `Admin.alterUserScramCredentials` (Kafka 2.7+).
    *
    * See D-2 for why this is done from Java rather than by formatting the storage directory.
    */
  def create[F[_]: Async](bootstrap: String, admin: ScramCredentials, user: ScramCredentials): F[Unit]
}
```

## How each mode is configured, concretely

The image is `apache/kafka:4.1.0`, pinned as `Versions.kafkaBrokerImage`. It is the Apache
project's own image, runs KRaft in combined mode out of the box, and takes its whole
configuration from `KAFKA_*` environment variables.

**The advertised-listener problem, and the standard answer.** A broker must advertise the address
a client can reach, and under Testcontainers that address is `localhost:<mapped port>`, which is
not known until after the container starts. The fixture uses the same technique
`org.testcontainers.kafka.KafkaContainer` uses internally, and it is written out here rather than
inherited because two of the three modes cannot use that class:

1. The container's command is
   `sh -c 'while [ ! -f /tmp/kui-go ]; do sleep 0.1; done; /etc/kafka/docker/run'`.
2. Testcontainers starts it and maps port `9093`.
3. The fixture writes `KAFKA_ADVERTISED_LISTENERS` into `/etc/kafka/docker/env` inside the
   container with the now-known mapped port, using `copyFileToContainer`.
4. The fixture touches `/tmp/kui-go`; the broker starts.
5. The fixture waits for the log line `Kafka Server started`, bounded by `startTimeout`.

**Common to all three modes:**

```
KAFKA_NODE_ID=1
KAFKA_PROCESS_ROLES=broker,controller
KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9094
KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,<CLIENT listener>,BROKER:PLAINTEXT
KAFKA_INTER_BROKER_LISTENER_NAME=BROKER
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1
KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1
KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1
KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0
KAFKA_LOG_DIRS=/var/lib/kafka/data
CLUSTER_ID=<a fixed 22-character base64 id, the same for every fixture>
```

The controller and inter-broker listeners stay PLAINTEXT in every mode. That is deliberate and
it is worth stating so nobody "fixes" it: what is under test is how **KUI's client** reaches a
broker, and securing a single-node broker's conversation with itself would add three more
failure modes to the fixture and test nothing KUI does.

| Mode | Client listener | Extra environment |
| --- | --- | --- |
| `Plaintext` | `CLIENT:PLAINTEXT` on 9093 | — |
| `SaslScram` | `CLIENT:SASL_PLAINTEXT` on 9093 | `KAFKA_SASL_ENABLED_MECHANISMS=PLAIN,SCRAM-SHA-512`, `KAFKA_LISTENER_NAME_CLIENT_PLAIN_SASL_JAAS_CONFIG=<PlainLoginModule with the bootstrap admin user>`, `KAFKA_LISTENER_NAME_CLIENT_SCRAM___SHA___512_SASL_JAAS_CONFIG=org.apache.kafka.common.security.scram.ScramLoginModule required;`, `KAFKA_SASL_MECHANISM_INTER_BROKER_PROTOCOL` unset (inter-broker is PLAINTEXT) |
| `MutualTls` | `CLIENT:SSL` on 9093 | `KAFKA_SSL_KEYSTORE_FILENAME`, `KAFKA_SSL_KEYSTORE_CREDENTIALS`, `KAFKA_SSL_KEY_CREDENTIALS`, `KAFKA_SSL_TRUSTSTORE_FILENAME`, `KAFKA_SSL_TRUSTSTORE_CREDENTIALS`, `KAFKA_SSL_CLIENT_AUTH=required`, `KAFKA_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM=` (empty, for the broker's own inter-broker use) |

The TLS store files and their credential files are written to a host temporary directory by
`CertificateAuthority.materialize` and bind-mounted at `/etc/kafka/secrets`.

## Decisions taken here

**D-1 — the image is `apache/kafka:4.1.0`, not `confluentinc/cp-kafka`.** It is the Apache
project's own build, it is what `org.testcontainers:testcontainers-kafka` 2.0.5 defaults to, and
it carries no Confluent Community License question (which `DEPENDENCY_MATRIX.md` still has open
for the Confluent artifacts). The tag is pinned, never `latest`: a test suite whose behaviour
depends on when it ran is not a test suite.

**D-2 — the SCRAM user is created after start-up through `Admin.alterUserScramCredentials`, over
a PLAIN listener, and not by formatting the storage directory with `--add-scram`.** The
`--add-scram` route means overriding the image's entrypoint to run `kafka-storage.sh format`
ourselves, which couples the fixture to that image's internal layout and breaks on any image
change. `alterUserScramCredentials` has existed since Kafka 2.7, is below ADR-030's 2.8 floor, is
plain Java, and needs no container `exec`. The cost is that the client listener also offers
`PLAIN` with one bootstrap user — so the fixture states, and `KafkaFixtureSuite` asserts, that a
client presenting **no** credentials is still refused. That is the property the mode exists to
demonstrate; "only one mechanism is enabled" is not.

**D-3 — hostname verification stays on in the TLS mode.** Turning it off would make the fixture
easier and would delete the only test of the one setting operators most often get wrong. The
broker certificate's SAN list therefore contains `localhost` and `127.0.0.1`, and the client
connects to `localhost:<mapped port>`. `ClusterConfigs.forBroker` sets `verifyHostname = true`.

**D-4 — certificates are generated per fixture run, not committed.** A committed keystore has an
expiry date, and the build that fails on that date fails for a reason nobody will guess from the
error. BouncyCastle (`bcpkix-jdk18on` 1.85) is already a `libs/testkit` dependency for exactly
this. Generation costs roughly 200 ms — measure it and record the number in the implementation
report. R-6's documented fallback (a committed fixture and a static JAAS file) stands if
generation turns out to be the thing that does not work; taking it is a `TECH_DEBT.md` entry with
the expiry date written in the title.

**D-5 — the ADR-030 nightly 2.8 broker is a parameter of this fixture, not a second fixture.**
`KafkaFixture` reads the image from the `KUI_TEST_KAFKA_IMAGE` environment variable, defaulting
to `Versions.kafkaBrokerImage`. The nightly CI job sets it to `confluentinc/cp-kafka:6.2.15`
(Kafka 2.8, ZooKeeper-mode) and skips the KRaft-only environment variables when the image is not
`apache/kafka`. That branch is four lines and it is what makes ADR-030's promise checkable
instead of aspirational. **`KafkaFixtureSuite` runs against the default image only**; the nightly
job runs the whole `libs.kafka.test` and `libs.config.test` suites against the override.

**D-6 — `ClusterConfig` is `libs/config`'s, and `libs/testkit` may not depend on `libs/config`.**
`libs.config` depends on `libs.kernel`, and `libs.testkit` depends on `libs.kernel` too; an edge
from testkit to config is legal under A5 (both are libs) but it would make every module's test
classpath carry the configuration loader. The resolution is that `ClusterConfigs` lives in
`libs/testkit` and **does** take that edge — `libs.testkit.jvm.moduleDeps` gains `libs.config` —
because the alternative is every suite hand-building a `ClusterConfig`, which is precisely the
hand-built profile the parity suite must not use. It is one edge, between two libraries, in the
direction libraries already point. Record it in the implementation report as an edge added.

**D-7 — one broker per topology per JVM, shared across suites.** Starting a container per test
class would add roughly 20 seconds per suite. `KafkaFixture` is used through MUnit's
`ResourceSuiteLocalFixture` in the suites that need one topology, and through a
`ResourceGlobalFixture`-style shared holder in `libs/kafka`'s suites, which all use `Plaintext`.
Tests that mutate broker state (topics, SCRAM users) must namespace what they create by suite
name; the fixture provides `RunningBroker.uniqueName(prefix)` for that.

## Library coordinates

Already pinned in `DEPENDENCY_MATRIX.md`; this task adds them to `libs.testkit.jvm`:

```
com.dimafeng::testcontainers-scala-munit::0.44.1      (already present)
com.dimafeng::testcontainers-scala-kafka::0.44.1      (new on this module)
org.testcontainers:kafka:2.0.5                        (new on this module)
org.apache.kafka:kafka-clients:4.3.1                  (new on this module — A10 allows it here)
org.bouncycastle:bcpkix-jdk18on:1.85                  (already present)
```

`Versions` gains `kafkaClients = "4.3.1"`, `kafkaBrokerImage = "apache/kafka:4.1.0"` and
`kafkaBrokerImageLegacy = "confluentinc/cp-kafka:6.2.15"`. If KAFKA-004 has already added
`kafkaClients`, reuse it rather than declaring a second one.

## Acceptance criteria

```
$ ./mill libs.testkit.jvm.test
kui.testkit.kafka.KafkaFixtureSuite:
  + a PLAINTEXT broker starts and describeCluster returns one node
  + a SASL_PLAINTEXT/SCRAM-SHA-512 broker starts and the provisioned user can describeCluster
  + a SASL broker refuses a client that presents no credentials
  + an SSL broker starts and a client with the generated keystore can describeCluster
  + an SSL broker refuses a client that presents no certificate
  + an SSL client that trusts the CA but connects to 127.0.0.1 by IP still verifies the hostname
  + ClusterConfigs.forBroker round-trips through KuiConfigSource with no problems
```

Record in the implementation report: the wall-clock start-up time of each mode on CI, and the
certificate generation time (D-4). Budget: any single mode under 45 seconds; all three under two
minutes.

## Tests required

`KafkaFixtureSuite`, exactly the cases above. Three rules:

1. **Every negative case asserts the specific exception**, not "it failed":
   `SaslAuthenticationException` for the credential-less SASL client, `SslAuthenticationException`
   for the certificate-less TLS client. A test that accepts any failure passes when the broker is
   simply down.
2. **No `Thread.sleep`.** Container readiness is a Testcontainers wait strategy on the
   `Kafka Server started` log line; client readiness is a bounded retry of `describeCluster`.
3. **The suite skips loudly when Docker is unavailable** — the E2E-001 rule, applied here — with
   a message naming the mode that was skipped, never a silent pass.

## Observability

A fixture that fails to start attaches: the topology, the pinned image tag, the mapped port, the
last 50 lines of the container log and — for the TLS mode — the generated certificate's subject
and SAN list. Those five facts separate "the image changed", "the port was not mapped", "the
broker rejected its own configuration" and "the certificate did not match the host", which is
every way this fixture actually fails.

## Degraded behavior

No Docker means every suite that uses this fixture skips with a named message and CI marks the
corresponding exit criteria as unverified rather than green (E2E-001's rule, and DEVPLAN §9.1's
requirement that every criterion be demonstrated by a command). A skipped security mode must
never be reported as a pass.

## Docs to update

`docs/testing.md` (created by CFGOP-007 if it does not yet exist; if this task lands first,
create it): a section on running a suite against a secured broker, how to keep a container alive
for manual inspection, how to point the fixture at the 2.8 image (D-5), and the four
`KuiPureModule` workarounds above so the next person does not rediscover them.

## Deviations

Recorded during implementation, 2026-09-04.

**F-15 is settled: `org.testcontainers:kafka` does not exist at any version.** The module is
published as `org.testcontainers:testcontainers-kafka`, which is what `DEPENDENCY_MATRIX.md` line
153 already said. The three specs that write `org.testcontainers:kafka` (this one, KAFKA-007,
CFGOP-005) are wrong. The matrix's open-questions row is closed in the same commit and the row
records the answer.

**D-A — the image is `apache/kafka:4.3.1`, not `4.1.0`.** It matches the pinned `kafka-clients`
version, so a client-server incompatibility cannot be the explanation for a failure, and it is the
tag the repository already pulls.

**D-B — `org.testcontainers.kafka.KafkaContainer` is not used, for any mode.** Its listener map is
fixed, so neither the SASL nor the TLS mode can be expressed through it. Using it for one mode and
hand-rolling two would mean the three brokers differed in more than their security settings, which
is the one thing the parity suite needs them not to do. All three go through one `GenericContainer`
subclass that differs only in the environment it is given.

**D-C — the broker's TLS settings are `KAFKA_SSL_KEYSTORE_LOCATION` and friends, not the
`_FILENAME` / `_CREDENTIALS` pair the spec lists.** That indirection belongs to
`confluentinc/cp-kafka`. The `apache/kafka` image maps `KAFKA_<PROPERTY>` straight onto
`<property>` and ignores the Confluent spelling *silently*: the broker starts perfectly and then
fails every handshake with `handshake_failure`. This cost an hour to find and is written down in
`docs/testing.md` so it costs nobody else one.

**D-D — the credential files are not written.** They exist only to serve the Confluent indirection
above.

**D-E — "a SASL broker refuses a client that presents no credentials" asserts a
`TimeoutException`, and a second case covers `SaslAuthenticationException`.** A plaintext client
against a SASL listener never authenticates and so never gets an authentication error: the broker
waits for a SASL handshake, the client sends an API request, and nothing happens until the client's
own timeout fires. The new case, `a SASL broker refuses a client whose password is wrong`, is the
one that produces `SaslAuthenticationException`. Together they say the listener authenticates, and
that it does so against the provisioned user rather than against anybody at all. Both assert a
specific exception, which is the rule the spec sets.

**D-F — `KAFKA_LOG_DIRS` is not set.** The image's default is already `/var/lib/kafka/data` and
setting it added nothing.

**Measurements (this machine, images already pulled).** Eight cases, six containers, 41 s wall
clock in total. Certificate generation is inside the TLS fixture's start and is not separately
measurable at this granularity; a run with no TLS mode and a run with two differ by roughly the
container time alone. Every mode is well inside the spec's 45 s single-mode budget.

**Edge added (D-6, as the spec requires recording).** `libs.testkit.jvm.moduleDeps` gains
`libs.config`, and `libs.testkit.jvm.mvnDeps` gains `org.testcontainers:testcontainers-kafka` and
`org.apache.kafka:kafka-clients`. Rule A10 names `libs/testkit` on its allow-list;
`./mill checkArchitecture` passes.

**Still owed by this task's area.** `docs/testing.md` was extended rather than created (CFGOP-007's
E2E page already existed).
