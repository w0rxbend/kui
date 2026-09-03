# CFGOP-002 — `kui.clusters[].admin`: timeout, batching and concurrency knobs

- **ID:** CFGOP-002
- **Title:** `kui.clusters[].admin`: timeout, batching and concurrency knobs
- **Milestone / Feature:** M1 / CL-002, OT-001
- **Owner role:** Infrastructure Lead
- **Context / service:** `libs/config`
- **Size:** S
- **Dependencies / blocked by:** CFGOP-001

## Goal (user value)

An operator whose cluster has ten thousand topics, or a broker on the other side of an ocean, can
raise the request timeout and lower the batch size for **that one cluster** without recompiling
anything and without changing how KUI talks to their healthy clusters.

## Scope

Five keys under `kui.clusters.<n>.admin`, decoded into the `AdminTuning` value object that
**KAFKA-001 defines in `libs/kernel`**. This task writes the decoder, the defaults and the
cross-field validation; it writes no type.

The defaults are not invented. They are the numbers `research/kafka/admin-capabilities.md` §0
records from the reference products, which are themselves the numbers those products arrived at
after hitting the failures the same document lists.

## Non-goals

- **No global default section.** There is no `kui.admin.*` that every cluster inherits. Five
  keys × the number of clusters is a small amount of duplication, and a two-level default chain
  is a support conversation every time somebody cannot work out which value is in effect.
  Revisit if a deployment ever configures more than about twenty clusters.
- **No consumer or producer tuning.** `AdminTuning` is the admin client's. The message service's
  consumer knobs arrive in M3 with their first caller.
- **No retry or backoff keys.** The Kafka client's own retry loop is bounded by
  `default.api.timeout.ms`, which `apiTimeout` sets. A second retry policy on top of it would be
  two policies for one failure (the same argument ADR-037 makes for HTTP).
- **No breaker keys.** ADR-037 owns the circuit breaker and it is per *upstream service*, not per
  Kafka cluster.

## Design references

`research/kafka/admin-capabilities.md` §0 rows "Single I/O thread", "Timeouts", "Batching",
"Partial failure", and DC-D1/DC-D2 (the chunking decision and its evidence);
ADR-006 (fs2-kafka and the admin ports; batching and bounded parallelism are the adapter's job,
configured from here); ADR-016 (the refresh cadence these timeouts must fit inside);
CFGOP-001 (the slice this extends, its key spellings and its error style); ADR-013.

## Files to change

```
libs/config/src/kui/config/ClusterConfig.scala          (the admin field stops being a default)
libs/config/src/kui/config/KuiConfigSource.scala        (decodeAdminTuning, UnknownKeys entries)
libs/config/test/src/kui/config/AdminTuningSuite.scala  (new)
libs/config/test/resources/config/clusters-admin.yaml   (new)
docs/operations/configuration.md                        (the five rows and the sizing paragraph)
```

## The keys

| Key | Environment name | Default | Kafka property it becomes | Meaning |
| --- | --- | --- | --- | --- |
| `kui.clusters.<n>.admin.requestTimeout` | …`_ADMIN_REQUESTTIMEOUT` | `30s` | `request.timeout.ms` | How long one request to a broker may take before the client gives up on it. |
| `kui.clusters.<n>.admin.apiTimeout` | …`_ADMIN_APITIMEOUT` | `60s` | `default.api.timeout.ms` | The whole-call budget including the client's internal retries. Must be at least `requestTimeout`. |
| `kui.clusters.<n>.admin.chunkSize` | …`_ADMIN_CHUNKSIZE` | `200` | *(none — KUI's own batching)* | How many topics, partitions or config resources go into one admin request. |
| `kui.clusters.<n>.admin.groupChunkSize` | …`_ADMIN_GROUPCHUNKSIZE` | `50` | *(none)* | The same, for consumer groups. Declared in M1, used from M4. |
| `kui.clusters.<n>.admin.parallelism` | …`_ADMIN_PARALLELISM` | `4` | *(none)* | How many chunks are in flight at once against one cluster. |

**Where the defaults come from.** `request.timeout.ms` 30 s and `default.api.timeout.ms` 60 s are
the Kafka client defaults, and Kafbat sets 30 000 explicitly rather than changing it
(`admin-capabilities.md` §0 "Timeouts"). 200 and 50 are Kafbat's chunk sizes for
topics/configs and for groups respectively, and 4 is its concurrency
(§0 "Batching", `ReactiveAdminClient.java:287-296,529-537`). Copying numbers that a widely
deployed product arrived at empirically is cheaper and more honest than picking round ones.

**Why `groupChunkSize` exists in M1 at all**, when no group code ships until M4: it is one line
of decoder and one row of documentation now, and adding a key later means an operator's
carefully tuned M1 configuration file grows a new section in M4 that they have to discover. It
is decoded, validated, logged and carried on `AdminTuning`; nothing reads it yet. This is the one
exception to the DEVPLAN's R-11 rule against building M2+ surface early, and it is justified by
the fact that a configuration key is a published interface, not code.

## Public Scala signatures to implement

```scala
package kui.config

// in KuiConfigSource, private:
private def decodeAdminTuning[F[_]: Async](layers: Layers, index: Int): F[Problems[AdminTuning]]
```

`AdminTuning` itself, its field names and `AdminTuning.Default`, are KAFKA-001's. If KAFKA-001's
field names differ from the key names above, the **key names in this table win** — they are the
operator-facing interface — and the decoder maps between them.

## Decisions taken here

**D-1 — bounds, and what is out of bounds.** Every value is validated, because an unbounded knob
is a way for an operator to make KUI worse without being told:

| Key | Accepted range | The message when it is outside |
| --- | --- | --- |
| `requestTimeout` | 1 s … 5 min | `expected a duration between 1s and 5m` |
| `apiTimeout` | 1 s … 15 min, and ≥ `requestTimeout` | `expected a duration between 1s and 15m, at least as long as kui.clusters.0.admin.requestTimeout (30s)` |
| `chunkSize` | 1 … 1000 | `expected a whole number between 1 and 1000` |
| `groupChunkSize` | 1 … 1000 | as above |
| `parallelism` | 1 … 32 | `expected a whole number between 1 and 32` |

The upper bounds are deliberately generous and the point of them is the *reason* rather than the
number: 1000 topics in one `describeTopics` is already past where the reference products found
brokers timing out, and 32 concurrent admin requests to one cluster is well past where the
client's single network thread stops being the bottleneck (`admin-capabilities.md` §0, "Single
I/O thread").

**D-2 — the `apiTimeout ≥ requestTimeout` check is a cross-field validation, reported against
`apiTimeout`, and it names the other key and its effective value.** An operator who lowered
`apiTimeout` to 10 s and left `requestTimeout` at 30 s has configured a client that gives up
before its own single request can finish, which looks exactly like a broken cluster. Naming the
other key and its value — including when that value is the default — is what makes the message
actionable.

**D-3 — the timeouts are durations, not milliseconds.** `30s`, not `30000`. The existing loader
already has `readDuration` and the existing `kui.gateway.services.<id>.timeout` is spelled the
same way. `kui.gateway.readinessIntervalMs` is the odd one out and stays as it is for
compatibility; nothing new copies it.

**D-4 — the admin section is optional in full.** A cluster with no `admin` key gets
`AdminTuning.Default`, and a cluster with `admin: { parallelism: 8 }` gets the default for the
other four. Per-key defaults, not per-section: configuring one knob must never silently reset
another.

## Library coordinates

None new.

## Acceptance criteria

```
$ ./mill libs.config.test
kui.config.AdminTuningSuite:
  + a cluster with no admin section gets every default
  + one configured knob leaves the other four at their defaults
  + apiTimeout below requestTimeout is rejected, naming both keys and both values
  + each of the five knobs is rejected outside its documented range
  + the environment can set one knob for one cluster only
```

Behavioural acceptance:

```
$ KUI_CLUSTERS_0_ADMIN_APITIMEOUT=5s ./mill services.cluster.app.run -- --config deployment/compose/kui.yaml
kui.clusters.0.admin.apiTimeout: expected a duration between 1s and 15m, at least as long as kui.clusters.0.admin.requestTimeout (30s); got '5s' (found '5s')   (environment)
# exit code 1
```

## Tests required

`AdminTuningSuite`, cases as listed in the acceptance output, plus:

- `defaultsMatchTheDocumentedTable` — a table-driven assertion that `AdminTuning.Default`'s five
  values are exactly 30 s, 60 s, 200, 50 and 4. This is the test that stops the documentation and
  the code drifting apart, and it is the reason CFGOP-008 can quote the table without re-deriving
  it.
- `boundsAreInclusive` — 1 s, 5 min, 1 and 1000 all load; 999 ms and 5 min 1 s do not.

## Observability

The per-cluster startup log line from CFGOP-001 gains the five effective values, including the
ones that came from defaults. An operator reading `docker logs` must be able to see what is
actually in force without knowing what the defaults are.

## Degraded behavior

None. These are startup-only values; there is no reload path in M1. A cluster whose tuning makes
it slow is a slow cluster, which surfaces as a stale snapshot with a visible `scrapedAt`
(ADR-016) rather than as a configuration error.

## Docs to update

`docs/operations/configuration.md`: the five rows in the cluster key table, the bounds table from
D-1, and a short "Tuning for a large cluster" paragraph saying which knob to reach for first
(`chunkSize` down before `requestTimeout` up, because a smaller request that succeeds is better
than a larger one that eventually does not).

## Deviations

Recorded during implementation.
