# CFGOP-005 — The three-security-mode parity suite through the contract client

- **ID:** CFGOP-005
- **Title:** The three-security-mode parity suite through the contract client
- **Milestone / Feature:** M1 / CL-002, BR-001, BR-002, BR-005, PA-003
- **Owner role:** QA Engineer
- **Context / service:** `services/cluster/api` (test only), driven by `libs/testkit`
- **Size:** L
- **Dependencies / blocked by:** CFGOP-004, CLAPI-004

## Goal (user value)

The milestone's headline claim — "KUI shows you the same cluster whether it is open, SASL-secured
or behind mutual TLS" — is one test. Security changes how KUI *connects* and nothing about what
it *reports*, and the suite fails if that ever stops being true.

## Scope

One suite that, for each of the three topologies `KafkaTopology` provides:

1. starts a broker (CFGOP-004's fixture),
2. loads a real `KuiConfig` from the YAML `ClusterConfigs.yamlFor` produces — so the actual
   configuration decoder of CFGOP-001 and the actual property renderer of KAFKA-002 are in the
   path, not a hand-built profile,
3. brings up the cluster service's endpoints through the Tapir stub interpreter over the real
   server logic (CLAPI-004),
4. calls the four read endpoints through the **contract client**, and
5. records the responses.

Then it asserts that all three recordings are equal after the normalisation in "What parity
means" below, and that the normalised fields are non-trivial — a suite that compared three empty
responses would also be green.

## Non-goals

- **No HTTP socket, no gateway, no browser.** This suite is about the Kafka side of the service.
  The gateway's aggregation is CLAPI-007's `TestControl` test and the browser is CFGOP-007's.
- **No SASL_SSL.** Three modes, as the exit criterion states (CFGOP-004 D-1's reasoning).
- **No write path.** M1's one write endpoint is CLAPI-009's, and it is store-backed rather than
  cluster-backed, so security-mode parity does not apply to it.
- **No topics.** The four endpoints are the cluster read endpoints, and nothing creates a topic
  beyond what the broker creates for itself.
- **No performance assertion.** Modes differ in latency by design; asserting they do not would be
  asserting something false.

## Design references

`docs/ROADMAP.md` M1, exit criterion 1 (quoted verbatim below), M1 DEVPLAN §7 row
"Security-mode parity", ADR-018 (MUnit, Testcontainers, the Tapir stub interpreter), ADR-022
(what the three modes render), ADR-034 (the error envelope, for the negative cases),
`research/kafka/admin-capabilities.md` §1 (which fields `describeCluster`, `describeConfigs` and
`describeLogDirs` actually produce, and which of them legitimately differ between runs).

The exit criterion, verbatim:

> Testcontainers suite: PLAINTEXT, SASL_PLAINTEXT/SCRAM and SSL clusters; each yields the same
> broker list, configs and log dirs through the contract client.

## An explicit exception to the area boundaries

DEVPLAN §6.5 gives `services/cluster/{contract,api,app}/**` to the CLAPI area and tells CFGOP to
keep out of every service's own modules. This suite has to live there anyway, because §7 places
it in `services.cluster.api.test` and because the alternative — putting it in `libs/testkit` —
is forbidden by rule A5: a library may not depend on a service.

**The exception, decided here:** CFGOP-005 owns exactly two paths, and CLAPI must not create a
file at either:

```
services/cluster/api/test/src/kui/cluster/api/SecurityModeParitySuite.scala
services/cluster/api/test/src/kui/cluster/api/ParityRecording.scala
```

plus the one `moduleDeps` line that puts `libs.testkit.jvm` on
`services.cluster.api.test` — which DEVPLAN §6.5's shared-`build.mill` rule already permits ("a
task edits only the `object` it creates plus the `moduleDeps` line of the module it is wiring").
Nothing else under `services/cluster/` is touched.

## Files to create or change

```
services/cluster/api/test/src/kui/cluster/api/SecurityModeParitySuite.scala
services/cluster/api/test/src/kui/cluster/api/ParityRecording.scala
build.mill                          (services.cluster.api.test gains libs.testkit.jvm)
docs/testing.md                     (the parity section)
```

## Public Scala signatures to implement

```scala
package kui.cluster.api

import io.circe.Json
import kui.testkit.kafka.KafkaTopology

/** What one security mode produced, reduced to the JSON a client would have received.
  *
  * JSON rather than the DTOs on purpose: the exit criterion says "through the contract client",
  * and comparing decoded case classes would pass even if the encoder dropped a field on one path
  * and the decoder defaulted it back. Comparing the wire form is comparing what a browser sees.
  */
final case class ParityRecording(
    topology: KafkaTopology,
    clusters: Json,
    brokers: Json,
    brokerConfigs: Json,
    logDirs: Json
)

object ParityRecording {

  /** Everything that legitimately differs between two runs against two different containers,
    * replaced by a constant. The list is exhaustive and each entry has a reason; see the table
    * in the task spec. A field that is normalised but not in that table is a bug in this suite,
    * because it is hiding a real difference.
    */
  def normalise(recording: ParityRecording): ParityRecording

  /** A field-by-field diff, rendered for a failure message: the JSON pointer, the value under
    * the first topology and the value under the second. `assertEquals` on two large JSON blobs
    * produces an unreadable failure, and an unreadable failure is a test people delete.
    */
  def diff(left: ParityRecording, right: ParityRecording): List[String]
}
```

## What parity means, exactly

The four responses are compared after replacing these and only these:

| Field | Why it legitimately differs | Replaced with |
| --- | --- | --- |
| `clusterId` (the Kafka-reported one, ADR-031) | each container forms its own cluster | `"<kafka-cluster-id>"` |
| `brokers[].host`, `brokers[].port`, `bootstrapServers` | Testcontainers maps a different port per container | `"<host>"`, `0` |
| `scrapedAt`, and every other timestamp | wall-clock | `"1970-01-01T00:00:00Z"` |
| `logDirs[].totalBytes`, `usableBytes`, `partitions[].size` | the host filesystem, and one broker has written more than another | `-1` |
| `configs[]` entries whose name is in `ModeSpecificConfigs` | the security settings themselves — see below | removed |
| `id`, `name` of the configured cluster | the suite names each one after its topology | `"parity"` |

`ModeSpecificConfigs` is the exact set, and it is a `val` in the suite so it is reviewable:
`listeners`, `advertised.listeners`, `listener.security.protocol.map`,
`inter.broker.listener.name`, `sasl.enabled.mechanisms`,
`sasl.mechanism.inter.broker.protocol`, `ssl.client.auth`, `ssl.keystore.location`,
`ssl.keystore.password`, `ssl.key.password`, `ssl.truststore.location`,
`ssl.truststore.password`, `ssl.endpoint.identification.algorithm`, and every key beginning
`listener.name.`. Removing a broker config because it names the security configuration is
removing the thing that is *supposed* to differ. Removing one for any other reason is hiding a
defect, and the review question for any addition to this list is "is this a security setting?"

**The non-triviality assertions**, which are what stop the suite passing vacuously:

- `brokers` has exactly one entry, with a non-empty `nodeId`.
- `brokerConfigs` has at least 50 entries after `ModeSpecificConfigs` is removed. (A real broker
  reports several hundred; 50 is a floor that only an empty or failed response can fall below.)
- `logDirs` has at least one directory with at least one partition.
- `clusters` reports `status: Online` and a `controller`.

## Decisions taken here

**D-1 — parity is asserted on normalised JSON, not on DTOs.** See the comment on
`ParityRecording`. The exit criterion says "through the contract client", and the contract's
promise is a wire shape.

**D-2 — the suite drives the real configuration loader.** `ClusterConfigs.yamlFor` writes a
temporary YAML file and the suite calls `KuiConfigSource.load` on it with `UrlPolicy.Dev`. Any
other route would let a defect in CFGOP-001's decoder or KAFKA-002's renderer pass unnoticed,
which is the exact class of defect this milestone's headline criterion is about. A configuration
that fails to load fails the test with the loader's own accumulated message.

**D-3 — three brokers run one at a time, not concurrently.** Three JVM containers plus three
JVM-side clients is enough memory pressure to make CI flaky for a reason unrelated to what is
being tested, and the suite is not slow enough to need the parallelism (budget below). MUnit's
`testParallelism` is set to false for this suite.

**D-4 — each mode's recording is written to `out/parity/<topology>.json` on failure.** Three
large JSON documents in a terminal is not a diagnosis. `ParityRecording.diff` prints the first
twenty differing pointers in the failure message and the files hold the rest, and CI uploads the
directory as an artifact.

**D-5 — a mode that cannot start fails this suite; it does not skip it.** CFGOP-004's fixture
skips loudly when *Docker* is unavailable, which is an environment fact. A broker that Docker can
start and KUI cannot reach is the thing under test, and skipping it would turn a red milestone
criterion green.

**D-6 — the negative case is part of parity.** A fourth assertion per mode: with the credentials
deliberately wrong (a bad SCRAM password; a client keystore signed by a different CA), the same
endpoint returns the same `ErrorEnvelope` shape with the same error code across the two secured
modes — **`KUI-UPSTREAM-AUTH`** (502), which is what `KafkaErrorMapper` (KAFKA-005) maps
`SaslAuthenticationException` and `SslAuthenticationException` to, statused by
`ErrorEnvelope.statusOf`. Corrected at the M1 gate review: this spec previously named
`KUI-CLUSTER-AUTHENTICATION-FAILED`, a code no task creates and which is not in `ErrorCode`. Parity of the failure path matters at least as much as
parity of the success path: it is what tells an operator their password is wrong rather than
their cluster being down.

## Library coordinates

None new. The suite uses what `services.cluster.api.test` and `libs.testkit.jvm` already have:

```
org.scalameta::munit::1.3.6
org.typelevel::munit-cats-effect::2.2.0
com.softwaremill.sttp.tapir::tapir-sttp-stub4-server::1.13.31
com.dimafeng::testcontainers-scala-munit::0.44.1
```

## Acceptance criteria

```
$ ./mill services.cluster.api.test.testOnly kui.cluster.api.SecurityModeParitySuite
kui.cluster.api.SecurityModeParitySuite:
  + PLAINTEXT: the four endpoints answer and the response is non-trivial
  + SASL_PLAINTEXT/SCRAM-SHA-512: the four endpoints answer and the response is non-trivial
  + SSL with mutual TLS: the four endpoints answer and the response is non-trivial
  + the three normalised recordings are identical
  + wrong SCRAM credentials and a wrong client certificate produce the same error envelope
```

Runtime budget: under three minutes on CI including all three container starts. Record the
actual time in the implementation report.

This output **is** M1 exit criterion 1. Attach the run log to `STATUS.md` (CFGOP-008).

## Tests required

The single suite above, with the five cases named. Two structural rules:

1. **The three modes are one parameterised body, not three copies.** A copied assertion that
   somebody updates in two places out of three is how a parity suite stops testing parity.
2. **`normalise` is itself tested**, in `ParityRecordingSuite` beside it: given two recordings
   that differ only in mapped port, `normalise` makes them equal; given two that differ in
   `brokers[].rack`, it does not. A normalisation function with no tests can be made to pass
   anything.

## Observability

On failure: the topology that differed, the first twenty JSON pointers from
`ParityRecording.diff`, the path to the three recording files, and the broker container's last
50 log lines for the failing mode. A parity failure is nearly always either an adapter that
behaves differently under authentication or a broker configured differently than intended, and
those four artifacts tell the two apart without a re-run.

## Degraded behavior

The suite has none of its own. Its subject is degraded behaviour of the *system*: D-6's negative
case asserts that an authentication failure is a named, mapped, statused error and not a 500 or a
hang.

## Docs to update

`docs/testing.md`: a "Security-mode parity" section explaining what the normalisation list is,
why each entry is on it, and the review question for adding to it (`ModeSpecificConfigs` above).
That paragraph is the guard rail — the suite is only as strong as the shortness of that list.

## Deviations

Recorded during implementation.
