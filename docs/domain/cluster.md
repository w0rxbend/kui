# Cluster Registry and Topology

The bounded context served by `kui-cluster-service` (`docs/domain/context-map.md`). It owns which
clusters exist and how to reach them, the topology snapshot (`describeCluster`, the KRaft quorum,
brokers, broker configuration, log directories), cluster capability probing, and the cluster
configuration wizard and store. It is upstream of every other Kafka-facing context, which reads a
`ClusterProfile` from it rather than from its own configuration.

## Status in M1

**Modelled.** The domain and application layers hold the real model: a cluster is a configured
profile, a topology is a finding about that profile, and the two are deliberately different types
that cannot reach each other.

No M0 scaffolding remains: the sample `Ping` type, its use case, its wire shape and its endpoint
were deleted in one commit together with the routes that replaced them.

## The profile — what KUI knows about one configured cluster

`ClusterProfile` is the aggregate root. It is built only through `ClusterProfile.from`, which
returns `Either[DomainError, ClusterProfile]` and **accumulates every violation** rather than
stopping at the first: its callers are a startup validator that must report every bad field in one
message, and eventually a form that must light up every bad field at once.

| Field | Meaning |
| --- | --- |
| `id` | `ClusterId`, a slug of the configured name (ADR-031). Renaming a cluster produces a new id, and the Kafka-reported cluster id is a *finding* about the cluster rather than part of the profile. |
| `displayName` | 1 to 128 non-blank characters, no control characters. What an operator called it. |
| `bootstrap`, `security`, `properties`, `admin` | The typed connection, from `kui.kernel.cluster` (ADR-022 Amendment 1). The domain does not define its own copy: one definition, decoded by `libs/config`, rendered by `libs/kafka-auth`, and shared with the browser through `libs/contracts-core`. `profile.connection` assembles them into the single `ClusterConnection` a Kafka client takes. |
| `readOnly` | Whether this deployment may change anything on the cluster. |
| `colour` | An optional `ColourTag` from a closed palette, so that production and staging do not look alike in the switcher. A free CSS colour would be user-controlled text interpolated into a stylesheet. |
| `version` | `ProfileVersion`: the optimistic-concurrency version. `0` means "never written to the metadata store". |
| `origin` | `Static`, `Stored` or `StaticThenStored` — where these values came from. |

`ClusterRef(id, displayName)` is the cheap identity that goes on a log line, into a map key and
into a list row. Every value in this context that describes a *finding* holds a `ClusterRef` and
never a profile, which is what makes "no secret reaches a response body" a property of the types
rather than a rule someone has to remember.

**Five property keys are refused by name**: `bootstrap.servers`, `client.id`, `security.protocol`,
`sasl.mechanism` and `sasl.jaas.config`. The override map is an escape hatch for the properties KUI
does not model; an operator who sets `sasl.jaas.config` there silently replaces the JAAS string KUI
assembled, defeating the quoting and escaping that exists so a password containing a quote cannot
become an injection. The refusal names the key and says what to do instead.

## Topology — what a cluster is made of

`ClusterTopology` is the value the snapshot cell holds: a `ClusterDescription`, the detected
version, the KRaft quorum, the probed feature set, and one `BrokerLoad` per broker.

`libs/kafka` returns its own flat, invariant-free records of exactly what the AdminClient said, and
`services/cluster/infrastructure` maps them into these types, running the smart constructors as it
goes. That mapper is not duplication for its own sake: turning a foreign, nullable structure into a
validated domain value is what an adapter is for, and the alternative is either the domain
importing `org.apache.kafka.*` transitively or the domain having no invariants at all.

### Four things that look wrong and are not

`ClusterDescription.from` fails on exactly one thing — a duplicate broker id. Each of the following
is legal Kafka, each is a case a reference product got wrong at least once, and each has a test
whose name says so:

- `controller = None` — `describeCluster().controller()` is `null` during a failover.
- a controller that is **not** among the brokers — in KRaft the active controller can be a
  dedicated node with `process.roles=controller`, which never appears in `nodes()`.
- `kafkaClusterId = None` — some managed services do not report one.
- `authorizedOperations = None` — ACLs are disabled, or the broker predates 2.3.

A cluster that cannot be reached produces **no** topology at all; that is a snapshot state, not a
half-empty topology. An empty broker list is unrepresentable (`NonEmptyList`), because "reachable
with zero brokers" is not a state Kafka has and allowing it would put an empty table on the screen
where an unavailable panel belongs.

### Features are three-valued

`ClusterFeatures` holds `present`, `absent` **and** `unknown`, plus `probedAt`, and the three sets
always partition `ClusterFeature.All`. The third set is the whole point: a probe that timed out has
not established that a cluster *cannot* do something, and recording it as absent would hide a
working screen for an hour for a reason that was never true. The same shape exists in `libs/kafka`,
and the partition invariant is asserted on both sides — a shared invariant checked on one side only
is half a check.

| Feature | Probe | Minimum |
| --- | --- | --- |
| `authorized-operations` | `describeCluster(includeAuthorizedOperations)` | 2.3 |
| `config-documentation` | `DescribeConfigsOptions.includeDocumentation` | 2.6 |
| `broker-configs` | `describeConfigs(BROKER, id)` answers at all | — |
| `log-dirs` | `describeLogDirs` is neither unsupported nor refused | — |
| `kraft-quorum` | `describeMetadataQuorum` succeeds | 3.3 |
| `incremental-alter-configs` | `incrementalAlterConfigs` | 2.3 (first used in M5) |

### What M1 cannot fill

Three fields are declared and always `None`, so that the wire shape is final now and a later
milestone fills a value instead of changing a contract.

| Field | Why |
| --- | --- |
| `BrokerLoad.leaders` | Leadership comes from `describeTopics`, and the cluster service does not sweep topics. |
| `ClusterTopology.partitions` | Online, offline and under-replicated counts have no single API: they are aggregated from `describeTopics` + `describeLogDirs` + `listOffsets`. |
| `ClusterTopology.topics` | Needs `listTopics`. |

Per-broker **replica** counts and the skew percentage *are* derivable — `describeLogDirs` reports
one replica entry per replica per directory, which is a disk fact needing no topic sweep — and they
do ship. `BrokerLoad.withSkew` computes every broker's skew from the whole set at once, so two
brokers' numbers cannot be computed against different denominators and fail to add up on the page
they are shown on together.

### What the dashboard therefore draws

The four `None` fields above are not an implementation gap the screen papers over; they are visible
on it. The dashboard renders one row per configured cluster with:

| Cell | Source | In M1 |
| --- | --- | --- |
| cluster name, `read only` tag, the link | configuration, outside the section | always present, even when the cluster is unreachable |
| status chip | the row's section | `Online`, `Degraded: <reason>`, `Unavailable: <message>`, `Forbidden` |
| version, brokers, controller, disk | the section's payload | present once a scrape has succeeded |
| partitions, under-replicated, topics | — | always `—`, per the table above |
| throughput | — | **no column at all** |

The last two rows are the ones worth recording here, because they look like omissions and are
decisions:

- **The empty columns exist and read `—`.** A number the product will have one milestone from now
  gets its column now, so that filling it is a data change rather than a re-layout, and an em dash
  is an honest way to say "no value here".
- **Throughput gets no column.** There is no metrics service until M8, and unlike a partition
  count, a *zero* throughput is a meaningful reading. An empty column headed "Production" would be
  read as "this cluster has no traffic" — a claim, not an absence — which is worse than the column
  not being there.

### Skew, and its four edge cases

Skew is how far one broker's count sits above the average across brokers, as a percentage of that
average:

```
skew(broker) = 100 * (count(broker) - mean) / mean
```

It is not a metric and does not wait for the metrics service: it is arithmetic over the assignment
counts `describeLogDirs` already returns. A cluster where one machine holds forty percent more
replicas than its share is a cluster where one disk fills up first, and that is not visible in any
single command-line invocation.

Four rules, because a user who sees `—` where they expected a number needs to be able to find out
why:

1. **Only reported above the mean.** A broker carrying less than its share is not a problem, so it
   shows nothing rather than a negative number the reader has to work out is good news.
2. **A mean of zero reports nothing.** A cluster with no partitions is an ordinary state on a fresh
   install, and it must produce a dash rather than a division by zero, an `Infinity` or a `NaN`.
3. **A single broker is `0 %`, not nothing.** With one broker the mean *is* that broker, so zero is
   a real answer rather than an unknown one.
4. **A broker whose count is unknown is left out of the mean** and reports nothing itself. Counting
   it as zero would drag the mean down and inflate every other broker's figure — a wrong number that
   looks like a right one, which is worse than a dash.

The figure is computed server-side so that this table, a future export and any other client round
it the same way; the browser carries the same definition as a fallback for a deployment whose
service predates the field.

### Broker settings: where a value came from, and what is never sent

A broker setting carries the source it came from, and the vocabulary is a closed list with an
escape hatch, because Kafka has added sources between versions and will again:

| Source | Shown as | Order |
| --- | --- | --- |
| `DYNAMIC_BROKER_CONFIG` | Dynamic broker config | 1 |
| `DYNAMIC_DEFAULT_BROKER_CONFIG` | Dynamic default broker config | 2 |
| `DYNAMIC_BROKER_LOGGER_CONFIG` | Dynamic broker logger config | 3 |
| `STATIC_BROKER_CONFIG` | Static broker config | 4 |
| `DEFAULT_CONFIG` | Default config | 5 |
| anything else | Unknown, with the raw string kept | 6 |

The order is the feature. Somebody opening a broker's settings is nearly always asking "what did
someone change", so what someone changed at runtime is at the top and the defaults are at the
bottom. An unrecognised source renders as "Unknown" with its raw name available, never as a blank
cell and never as a failure to decode the response.

**Sensitive values are redacted by the service and never leave it.** A setting Kafka marks
sensitive arrives at the browser with no value at all — the DTO's `value` is absent and
`isSensitive` is true — so the mask on screen is not the browser hiding something it holds. That
distinction is stated on the screen itself, in the tooltip on the mask, because anyone who has used
an interface that merely hid a value it had in memory has reason to want to know which of the two
this is. It is asserted twice: once in the contract's own tests, and once in the browser, where a
suite feeds the screen a plaintext token and asserts it appears nowhere in the rendered DOM.

## Ports

Three traits, stated over an abstract `F[_]` with no bound at all, in domain types only.

| Port | The question it answers |
| --- | --- |
| `ClusterAdmin[F]` | What does this cluster look like? Five reads and a capability probe; no mutations — broker configuration is read-only in M1. |
| `ClusterConfigStore[F]` | Which clusters have I been told to remember, and one write. |
| `ConnectivityProbe[F]` | Can KUI open a connection and authenticate, right now? |

`ConnectivityProbe` is separate from `ClusterAdmin` because the two answer different questions for
different callers and fail differently: `describeCluster` runs on a thirty-second loop and may take
the full admin timeout, while a probe is on the decision path and needs a fast, bounded yes/no that
distinguishes *cannot reach* from *reached but refused*. Folding them together would make the probe
pay for a full describe, or make the refresh inherit the probe's short timeout.

`ClusterConfigStore` has **no stream**. It has `onChange(handler): F[F[Unit]]`, returning the
deregistration action, and `ClusterRegistry` in the application layer turns those callbacks into
the stream its subscribers want. A port over an abstract `F[_]` needs no runtime dependency;
`fs2.Stream` is a concrete type from a concrete runtime, and a domain that imports it can no longer
be read, tested or moved without it (ADR-041 Amendment 3).

`PartialResult[K, A]` carries `values` and `skipped`, and every requested key comes out in exactly
one of them. A silent drop is forbidden: returning an empty map on a per-key failure is why a
reference product's broker page cannot distinguish "this cluster has no dynamic configuration" from
"KUI is not allowed to read it" — and neither can its user.

### The degraded contract

| Situation | Port result |
| --- | --- |
| Cluster unreachable | `describeCluster` → `Left(InfrastructureError.Unreachable)`; `capabilities` → everything `unknown`; `probe` → `Unreachable` |
| Bad credentials | `Left(InfrastructureError.AuthFailed)`; `probe` → `AuthenticationFailed` |
| One broker of five down | `describeLogDirs` → `Right(PartialResult)` with that broker in `skipped` |
| Managed service hides broker configs | `brokerConfigs` → `Left(ApplicationError.Unsupported)` — never `Right(Nil)` |
| ZooKeeper cluster | `describeQuorum` → `Right(None)` |
| Store cluster stopped | `list`/`get` still answer from last known state; `put` → `Left(InfrastructureError.*)`; `health` → `Degraded` |
| File adapter configured | `put` → `Left(ApplicationError.Unsupported)`; `health` → `NotConfigured` |
| No cluster configured | `list` → `Right(Nil)`; not an error |

The `ApplicationError` / `InfrastructureError` split in that table is not stylistic: only the second
dims a capability. A worker who returns an infrastructure error for "this cluster does not support
log directories" makes the cluster service dim itself, for every user, on a cluster that is working
perfectly.

## Registry — two sources, one answer

`ClusterRegistry` resolves a `ClusterId` and is the only thing in the service allowed to: two
resolvers would be two precedence rules, and the second one is always the one nobody documented.

| Case | Result | `origin` |
| --- | --- | --- |
| In static configuration only | the static profile | `Static` |
| In the store only | the stored profile — **added**, never ignored | `Stored` |
| In both | the **stored** profile, in full | `StaticThenStored` |
| In the store, then the store becomes unreachable | the last replayed stored profile, unchanged | unchanged |
| In both, and the store record is deleted | the static profile again | `Static` |

**Whole-profile replacement, not a field-by-field merge.** A field-level merge would mean an
operator who removes `security` from a stored record silently inherits the configuration file's
credentials — a change that reads as "I removed the credentials" and behaves as "I kept them". It
would also make the version meaningless: a version identifies a record, and half a record has no
version.

`registryVersion` counts *observed changes to the resolved set*, not store record versions. It
increments only when the resolved profile map differs from the previous one, because an ETag that
changes when nothing did makes every downstream service rebuild its Kafka clients on a schedule,
and an ETag that does not change when something did is a correctness bug that shows up as a stale
credential. Equality is structural and includes the secrets, so a rotated password does bump it.

| Condition | Behaviour |
| --- | --- |
| Store unreachable at construction | the static list is served; `storeHealth = Degraded`; one WARN; the service starts |
| Store unreachable after replay | the last resolved set is served unchanged, and `resolve` keeps working |
| Store never configured | `storeHealth = NotConfigured`, which is never rendered as broken |
| Store returns an empty list | the static list, as `Static`. An empty store is a legitimate first start |
| Unknown cluster id | `ApplicationError.NotFound` with `KUI-CLUSTER-NOT-FOUND`, a 404 |

A cluster service that refused to start because its metadata store was down would take the whole UI
with it, so the first resolution never fails.

## Snapshot and staleness

One background loop per cluster, every thirty seconds. A read returns whatever the snapshot holds,
labelled with how old it is — **nothing on the request path talks to a broker**, which is what makes
the dashboard's response time a function of the gateway's fan-out rather than of the slowest
configured cluster.

The refresh, in order:

1. `describeCluster` — **required**. A failure fails the refresh.
2. the capability set, read from its own hourly cell (never probed inline).
3. `detectVersion` — optional; a failure means `version = None`.
4. `describeQuorum` — optional, and **skipped entirely** unless the `kraft-quorum` feature is present.
5. `describeLogDirs` — optional, and skipped unless `log-dirs` is present; a skipped broker gets no `BrokerLoad`.
6. `BrokerLoad.withSkew` over whatever step 5 produced.

Steps 3 to 5 run in parallel. Only step 1 is required because a cluster that answers it is
reachable and its page must render: a broker list with no disk figures is far more useful than an
unavailable panel, and it is exactly what a managed service looks like. Their failures are logged
at DEBUG and not WARN — on a managed service they fire every thirty seconds for ever, and a warning
that always fires teaches an operator to filter the log.

Capabilities refresh hourly, and again when a cluster transitions from offline to online: the usual
reason a cluster was away is that it was being upgraded, and its feature set is the thing most
likely to have changed.

| Cell state | Freshness | What the UI shows |
| --- | --- | --- |
| `Initializing`, no value | `Loading` | a skeleton row; the cluster is clickable |
| `Online`, value present | `Fresh(scrapedAt)` | the data, with "updated *n*s ago" |
| `Offline`, value present | `Stale(scrapedAt, reason, since)` | the data, greyed, timestamped, with the reason |
| `Offline`, no value | `Unavailable(reason, since)` | `Unavailable: <reason>`, and the row is still clickable |

`reason` is always a `KuiError.message` — display text, free of hosts, bodies and credentials by
construction — and never an exception's `getMessage`. There is no TTL and no eviction: a snapshot is
at most one interval old while the cluster is up and arbitrarily old while it is down, which is
precisely why `scrapedAt` is on every response instead of the value being discarded. `since` is
sticky across a changing reason, because the question about a greyed-out row is "how long has this
been broken", not "how long has it been broken in this particular way".

A forced refresh returns as soon as the refresh has been *started*. Awaiting it would block for the
full admin timeout against a dead cluster, and the button that triggered it would hang. Twenty
presses produce one admin call, because the cell deduplicates.

## Broker detail

| Read | Source | Why |
| --- | --- | --- |
| Broker list | the snapshot | it is a list screen, it must render for a dead cluster, and a page listing thirty brokers must not make thirty admin calls |
| Log directories | **live**, one call for that broker | a directory that went offline three seconds ago is the reason the operator opened the page |
| Broker configuration | **live** | it is never in the snapshot: scraping every broker's two hundred entries every thirty seconds is pointless traffic, and an operator who just changed a dynamic setting expects to see it |
| Per-partition sizes | derived from the same live call as the log directories | the same data, reshaped; a second call would double the cost of one page |

A live log-directory read that fails **falls back to the snapshot** and says so with `Stale`.
Configuration has no snapshot to fall back to, so its failure is an honest `Left`.

Broker existence is checked against the snapshot before any call is made, which has three
consequences worth stating so nobody removes the check as redundant: a bad id costs no network
call; the refusal is an `ApplicationError` and so cannot dim a capability; and a cluster whose
snapshot has never been filled reports the *cluster's* failure instead of "broker not found",
because the correct answer to "does broker 3 exist" on an unreachable cluster is "I cannot tell
you".

The broker list of a cluster that has never answered is an empty list **labelled unavailable** —
not a `Left`, and not a bare empty list. It is the one place where a successful answer carrying an
empty collection is right, and it is right *because* the freshness field carries the reason.

Broker configuration is read-only in M1. The mutation arrives in M5, behind read-only mode and
audit.

## Capabilities

`CapabilityReportUseCase` answers `GET /capabilities`, which the gateway polls and which decides
what the sidebar dims. It reads the registry and the cells, in memory, and never calls a broker: the
gateway polls it every ten seconds per replica.

| Observed | Reported |
| --- | --- |
| Snapshot is `Fresh` | `Available`, `reachable = true` |
| Snapshot is `Loading` | `Degraded("starting")` — never unavailable; a service that reported an outage for its first two seconds would make every rollout look broken |
| Snapshot is `Stale` or `Unavailable` | **`Available`**, `reachable = false` |
| Cluster is not in the registry | absent from the map entirely |
| The metadata store is `Degraded` | every entry becomes `Degraded("configuration store: …")` |
| The metadata store is `NotConfigured` | no effect; the file adapter is a supported way to run |

The third row is the decision most likely to be argued with, so it is stated as an invariant with a
property test: **no state of any managed Kafka cluster can move this service's reported capability
below `Available`.** Only two things can — a degraded store, and a process that has not finished
starting. Everything else about a managed cluster is data on a page, and reporting it otherwise
would dim the sidebar for every user because one operator typed a bad broker address.

`state` and `reachable` are two fields because one boolean cannot say both "the sidebar entry works"
and "the broker answered". This service never reports `Unavailable`: that is the gateway's verdict
when it gets no answer at all, and a self-reported `Unavailable` would be a service claiming it is
not there.
