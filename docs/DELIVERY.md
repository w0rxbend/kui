# Delivery: what "a finished application" means

The roadmap has ten milestones and reaches feature parity with the reference products at the eighth.
That is the long road. This document defines the shorter one: the smallest set of work after which
somebody can run KUI against their own Kafka and use it for real work, and how far along that road
the project currently is.

It exists because "build the application" is not a task anyone can pick up. This turns it into a
sequence with an end.

## The bar

KUI is finished, for this purpose, when a person who has never seen the project can:

1. Clone it, run one command, and have KUI and a Kafka broker both running.
2. See their clusters, brokers and topics without configuring anything beyond a bootstrap address.
3. Read messages from a topic, including the JSON inside them, and publish one.
4. See consumer groups and how far behind they are.
5. Point it at their own cluster with a documented example configuration, including a secured one.
6. Have any part of it fail without the rest becoming unusable.

Point six is not a feature. It is the reason the architecture is shaped the way it is, and it is
tested rather than asserted.

## The sequence

| Stage | Delivers | State |
| --- | --- | --- |
| M0 Foundation | build, libraries, gateway, sample service, shell, single-process assembly, images, Compose | done |
| M1 Cluster connectivity | real Kafka connections with production security, clusters and brokers, the metadata store | done |
| M2 Topic explorer | topic list, search, detail, partitions, configuration | done |
| M3 Message explorer | browsing with every seek mode, streaming, serialization formats, publishing, filters | done except purge (`MS-008`) |
| M4 Consumer groups | groups, members, assignments, lag, offset reset | done, wizard included |
| Quickstart | one command that starts Kafka, seeds it with data, and opens KUI on it | done |
| Configuration examples | a plain example, a secured example, and the reference for every key | built |
| Demonstration environment | three unlike clusters, one KUI, and a switch to fail one of them | done, verified from a cold machine |

M3 and M4 were both recorded here earlier on 2026-09-04 as "part built, nothing reachable": modules
that compiled and were tested, with no path from a browser to any of them, and then as "reading
works, writing does not". Neither is the state now. Both services have an `api` module, a
composition root, routes served by the running process and a screen in the shell; M3 publishes as
well as reads, and M4's offset-reset wizard is on screen. What each still lacks is named in the
2026-09-04 integration section at the end of this document, which is the record to read in
preference to this paragraph.

The check is one command against a running quickstart:

```
$ curl -s localhost:8080/api/v1/openapi.json | jq -r '.paths | keys[]'
/api/v1/auth/logout
/api/v1/auth/me
/api/v1/capabilities
/api/v1/capabilities/stream
/api/v1/capabilities/{service}/probe
/api/v1/clusters
/api/v1/clusters/{clusterId}
/api/v1/clusters/{clusterId}/brokers
/api/v1/clusters/{clusterId}/brokers/{brokerId}/configs
/api/v1/clusters/{clusterId}/consumer-groups
/api/v1/clusters/{clusterId}/consumer-groups/lag
/api/v1/clusters/{clusterId}/consumer-groups/{groupId}
/api/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets
/api/v1/clusters/{clusterId}/consumer-groups/{groupId}/offsets/plan
/api/v1/clusters/{clusterId}/log-dirs
/api/v1/clusters/{clusterId}/refresh
/api/v1/clusters/{clusterId}/topics
/api/v1/clusters/{clusterId}/topics/refresh
/api/v1/clusters/{clusterId}/topics/{topicName}
/api/v1/clusters/{clusterId}/topics/{topicName}/config
/api/v1/clusters/{clusterId}/topics/{topicName}/messages/stream
/api/v1/clusters/{clusterId}/topics/{topicName}/overview
/api/v1/clusters/{clusterId}/topics/{topicName}/partitions
/api/v1/clusters/{clusterId}/topics/{topic}/consumer-groups
/api/v1/info
```

Twenty-five paths where there were eighteen. `messages/stream` and the five `consumer-groups` paths
are the new ones.

Stages M5 to M9 — cluster administration, authentication, the ecosystem plugins, metrics — are
beyond this bar. They are real work and they are planned, but nobody needs them to use the product.

## The quickstart, specifically

The thing a newcomer runs must not require them to understand the architecture:

- One command. It starts a Kafka broker, waits for it, creates a handful of topics with realistic
  names and shapes, publishes messages into them including JSON payloads, starts a consumer group so
  there is lag to look at, and starts KUI pointed at all of it.
- It must work on a machine with nothing installed but Docker.
- It must print the URL to open, and nothing else the reader has to act on.
- Tearing it down must leave nothing behind.

Anything that makes a newcomer read a second document before they see the product working is a
defect in the quickstart, not a gap in their understanding.

## Configuration examples

Three, each one a file a person can copy:

- The simplest thing that works: one cluster, no security.
- A production shape: several clusters, one with SASL and TLS, secrets read from the environment
  rather than written in the file.
- The full reference: every key, its default, and what happens when it is wrong.

## How progress is tracked

`STATUS.md` holds the current position and `docs/FEATURE_MATRIX.md` the per-capability state. This
document holds only the delivery bar and the sequence above. When a stage completes, its row changes
here and the detail lives in the milestone's own plan.

## Where this stands, 2026-09-04 (topic administration)

**KUI can now create a topic.** Until this pass it could read a Kafka cluster and publish records
into topics somebody else had made; the largest remaining functional gap was that the interface
could not make one. The topic service gained four operations — create, change configuration, add
partitions, delete — and the topic screens gained the controls for all four.

Everything here changes somebody's cluster, so what was built is mostly the guards.

**A read-only cluster refuses every one of them before a Kafka client is touched, and the refusal is
audited (ADR-047).** The check is `MutationGuard` in the application layer, ahead of the adapter:
it resolves the cluster's profile, refuses with `KUI-READ-ONLY`, and writes the audit record. Driven
against a cluster configured `readOnly: true`:

```
POST   …/clusters/readonly/topics                  KUI-READ-ONLY  "cluster Read only is configured
                                                                   read-only, so topic.create is
                                                                   not accepted"
POST   …/clusters/readonly/topics/seed.orders/deletion/plan
                                                   KUI-READ-ONLY  (…so topic.delete is not accepted)
kafka-topics.sh --list                             seed.orders    (nothing was created)
```

The audit line for the refusal is written whether or not the operation was allowed, which is the
half of ADR-047 that matters most: an attempt to change a cluster somebody has deliberately locked
is exactly the thing an audit trail exists to have noticed.

**The two operations that cannot be undone are confirmed against a server-computed plan (ADR-045).**
Deleting a topic and raising its partition count are two calls each, and the second accepts a signed
token and nothing else — there is no request shape in the contract that destroys something in one
hop, so `curl` gets the protection the browser gets. Which two operations need a plan is ADR-045's
own test rather than a judgement about how frightening each one feels: an operation needs one when
its effect is not a function of its request. Growing a topic depends on how many partitions it has
now and silently re-routes every future record; deleting depends on how many records are about to
be lost and on whether the cluster will recreate the topic by itself. Creating a topic and changing
a setting are single calls, because the request *is* the effect.

Driven against a single-node KRaft broker started for this pass, with a topic created, configured,
grown, published to and deleted:

```
create kui.m5.live (3 partitions, rf 1, retention.ms=604800000)
  broker:  PartitionCount: 3  ReplicationFactor: 1  Configs: retention.ms=604800000
PATCH config {"set":{"retention.ms":"86400000"}}
  broker:  retention.ms=86400000  DYNAMIC_TOPIC_CONFIG
PATCH config {"remove":["retention.ms"]}
  KUI:     retention.ms 604800000, source default        (the override is gone, not overwritten)
plan partitions 2                                        KUI-VALIDATION "…already has 3 partitions,
                                                          and Kafka cannot remove one"
plan partitions 6 → token, warning KEY_ROUTING_CHANGES
apply with the token + one character                     KUI-VALIDATION (the confirmation is refused)
apply with the token                                     3 → 6;  broker: PartitionCount: 6
apply the same token again                                KUI-VALIDATION "…already has 6 partitions"
publish 3 records
deletion plan     partitions 6, records 3, autoCreateEnabled true,
                  warnings RECORDS_LOST + AUTO_CREATE_ENABLED
DELETE with no token                                     KUI-VALIDATION "token is required"
DELETE with the token                                    200
  broker:  seed.orders            (the topic is gone)
  KUI:     the list no longer holds it
```

The replay case is worth reading twice. A spent token is not rejected by remembering it — nothing is
remembered — but because the apply step re-resolves the plan against the cluster as it is now, and a
partition count that has already moved cannot be increased to the same number twice. The same
mechanism refuses a token minted five minutes ago for a topic somebody else has grown since.

**Deleting a topic on a cluster with automatic topic creation is reported honestly.** The plan reads
`auto.create.topics.enable` off a broker and says, in the sentence the operator confirms, that the
first client to name the topic will recreate it with the broker's defaults and none of its
configuration. There are three answers, not two: on, off, and "KUI could not read it", and the third
is never rendered as the second. This is the behaviour the message browser was bitten by in an
earlier pass — a browse that created the topic it said was missing — so it is stated at the point of
decision rather than in a footnote.

**Driven in a browser as well as over the API.** Headless Chromium over the DevTools protocol,
against the same broker: "New topic" opens the form, a topic is created and the browser lands on its
page; the Settings tab's Edit button opens the setting prefilled and saving it changes the value on
the broker (`kafka-configs.sh --describe` reads back `retention.ms=120000`); the danger panel
previews a partition increase with its warning and previews the delete with its record count and its
auto-create warning; and confirming the delete leaves the receipt on screen while every control that
has stopped applying — the partition controls, the delete's own Preview, the link into the message
browser — is hidden. That last part is deliberate: the receipt is the operator's only record of an
irreversible action, and this project has already shipped a wizard whose receipt was destroyed by
the refresh the operation itself caused.

### Purge (`MS-008`), the last M3 row, is also built

It is the operation ADR-045 was written for, and it is now the third entry in the same panel. Two
calls: the first reads each partition's start and end offset and answers with how many records would
go and a token; the second takes only that token and deletes up to exactly the offsets the first one
resolved.

That last part is the whole feature, and it was driven to prove it:

```
publish 5 records
purge plan     partition 0: 0 → 3   partition 1: 0 → 2   records 5
               warnings RECORDS_LOST + CONSUMER_OFFSETS_UNCHANGED
publish 2 more records                                   (after the plan was read)
purge with a tampered token                              KUI-VALIDATION
purge with the token                                     purged 0 before 3, 1 before 2
broker: --time -2 (log start)   0:3   1:2                (the planned offsets)
broker: --time -1 (log end)     0:4   1:3                (the 2 later records are still there)
```

An operator lost exactly what they were shown and nothing that arrived while they were deciding. A
purge that re-read the end offsets at apply time — which is what a single-call endpoint would have
to do — would have deleted those two records as well.

Two things the plan says that operators are routinely surprised by, and that no reference product
says: committed consumer offsets are **not** moved by a purge, so a group below the new start of the
log follows its own `auto.offset.reset` and by default skips to the end; and Kafka refuses
`deleteRecords` on a topic that is only compacted, so a compacted topic is warned about before the
broker rejects the attempt. Both are on the screen the operator confirms.

It is `deleteRecords` and deliberately not delete-and-recreate, which is how the reference product
empties a topic: that throws away the topic's identity, leaves consumer groups pointed at a log that
no longer exists, and races automatic topic creation. The log is emptied and the topic, its
configuration and its partition count are untouched — visible on the screen afterwards, where the
partition table reads first offset 7, next offset 7 rather than starting again from zero.

The control is on the topic page beside "delete this topic", not on the message browser, because
that is where a person looks for it.

### What this pass did **not** do
- **A compacted topic was never purged.** The warning is computed from `cleanup.policy` and the
  broker's refusal is mapped, but no run has seen either on a screen.
- **Replication-factor change, clone and recreate** are M5 rows that remain unbuilt. They are not
  declared anywhere, which is deliberate: an endpoint that is declared is an endpoint somebody
  implements.
- **More than one broker.** Every run used a single node, so nothing about a replication factor
  larger than one, a partition reassignment in flight, or `createPartitions` with an explicit
  replica assignment was exercised. The adapter maps `InvalidReplicaAssignment` and
  `ReassignmentInProgress` to sentences of its own; neither sentence has been seen on a screen.
- **A cluster with `delete.topic.enable=false`.** The refusal is mapped and names the setting; the
  path has not been driven against a broker configured that way.
- **Scale.** A create on a cluster with ten thousand topics asks for a re-scrape of all of them, and
  what that costs has not been measured.

## Where this stands, 2026-09-04 (the secured cluster)

**Point 5 of the bar is met, and it was not before.** A KRaft broker speaking `SASL_SSL` with
`SCRAM-SHA-512` behind a private certificate authority, KUI pointed at it with
`deployment/examples/production.yaml` — three lines changed, being the two bootstrap addresses and
the insecure-cookie flag plain HTTP on localhost needs — and every screen this milestone claims
driven against it. The stack is kept, in `deployment/secured/`, so the next person changing anything
under `kui.clusters[].security` can run it in about a minute.

What answered, on the secured cluster only: the cluster row reporting `SASL_SSL` and
`SCRAM-SHA-512` and status `ok`; the broker list; the eight seeded topics; a topic's partitions and
its Kafka configuration; a message browse with the JSON values parsed and rendered as JSON; and
three consumer groups — `analytics-indexer` `STABLE` with one live member, `order-fulfilment`
`EMPTY` with a total lag of 9, `payments-ledger-sync` `EMPTY` — plus the lag-poll endpoint.

### The defect it found, which is the one the exercise was for

**The production example cannot work as written.** It puts the truststore password where every other
secret goes:

```yaml
password: "env:KUI_ANALYTICS_TRUSTSTORE_PASSWORD"
```

The loader resolved `env:` and `file:` for the SASL password and read the three TLS passwords
verbatim, so the password handed to Kafka was the literal name of the environment variable.
`Admin.create` threw while opening the PKCS12 store, and the cluster service reported
`KUI-UPSTREAM-UNAVAILABLE` — the same code a broker that is switched off produces. The secured
cluster sat on the dashboard reading "Unavailable" for ever with nothing anywhere naming the store,
the password or the exception.

Every gate was green through all of it. `ShippedConfigurationSuite` asserted that the example
*loads*, and it did. The M1 adapter suites cover plaintext, SCRAM over plain text and mutual TLS at
the port level, and `KafkaTopology` records in a comment that there is deliberately no `SaslSsl`
case — so the exact combination the example documents had never been exercised by anything.

Two fixes carry tests. The TLS passwords are now resolved like every other secret, so `env:` and
`file:` work under `ssl.truststore.password`, `ssl.keystore.password` and `ssl.keyPassword`, and one
naming an unset variable stops the process instead of becoming a wrong password. And
`ShippedConfigurationSuite` now asserts over the *resolved* values rather than over "it loaded", so
no secret in a shipped example can be its own reference again.

A third change is not a fix so much as the reason this took a day: a cluster failure now logs the
exception's class name beside the error code, so "the broker is off" and "the client could not be
built" stop being the same line.

### Two constants that a deployment could not change

Found while reading the same code paths, and both now configuration:
`kui.consumers.refreshInterval`, which was a constant in the consumer service's composition root,
and `kui.streaming.cursorKey`, which was a *secret* generated per process. The second is the
serious one: it signs both the browse cursor and the offset-reset plan token, so two replicas
rejected each other's, and a restart made a reset wizard an operator had left open impossible to
apply. Absent, it still falls back to a generated key — correct for one process — but the fallback
now says so in the startup log instead of being invisible.

### What was not done

The secured cluster was driven through KUI's own HTTP API, not through a browser: no browser was
reachable from this session. The shell and its bundle serve correctly from the secured stack
(`/ui/...` 200 `text/html`, `/ui/main.js` 200 `text/javascript`, 884 KB), and every endpoint the
screens call was exercised, but the rendering itself was last confirmed against the plaintext
quickstart in the integration pass below.

## Where this stands, 2026-09-04 (integration pass)

**Points 1, 2, 4 and 6 of the bar are met. Point 3 is met for reading and not for publishing. Point
5 is met on paper and has not been exercised against a secured cluster.** *Point 5 is now exercised;
see the section below, dated the same day.*

One command from a clean machine — no image on disk, `docker rmi` first — builds the all-in-one
image inside a container, starts a KRaft broker, seeds it, starts KUI and prints one URL. Everything
below was then driven in a headless Chromium against that running quickstart, not in a test.

- Seven topics list by default; `_schemas` and `__consumer_offsets` appear when "show internal
  topics" is ticked, and the total changes from 7 to 9 with it.
- A topic opens on its six partitions with first and next offsets per partition, and its Settings
  tab shows the Kafka configuration with its sources.
- A topic's page links to its records. `orders.v1` browses 16 records with the JSON readable in the
  table; `audit.log.raw`, whose values are deliberately not JSON, renders as text with no error.
- `?seekTo=offset::2` and `?seekTo=timestamp::<ms>` both return the expected records; per-partition
  seeks (`seekTo=3::1&seekTo=5::6`) read only the partitions they name.
- Three consumer groups list with their states — `analytics-indexer` Stable with one live member,
  `order-fulfilment` and `payments-ledger-sync` Empty — and `order-fulfilment` shows a real total lag
  of 9 broken down per partition.
- With the broker container stopped, every screen stays navigable and says why: the cluster row reads
  "Degraded: cluster too slow to answer", the topic list keeps its rows behind a "Stale:
  UPSTREAM_UNAVAILABLE" badge, and a browse ends `KUI-UPSTREAM-UNAVAILABLE`, retryable.

### Seven defects found by running it rather than by reading it

Every one of them was invisible to `./mill __.test`, which was green before and after.

**The message browser could not be opened.** The topic detail page's tab route
(`/clusters/c/topics/t/{tab}`) decoded *any* fifth segment to its default tab, and it is registered
before the message browser's `/clusters/c/topics/t/messages`. So it claimed that URL and drew the
topic's Overview. The screen existed, was tested, and could not be reached.

**And it had no link to it in any case.** A browse names a topic, the sidebar knows only a cluster,
and no screen offered a way in. The topic page now carries the link.

**Every cluster-scoped sidebar entry was a dead link.** Topics, Messages and Consumers each declared
a landing page holding a placeholder cluster id of `""`, on the documented understanding that the
shell would substitute the chosen cluster. It never did, and an empty path segment collapses —
`/ui/clusters//topics` is `/ui/clusters/topics`, which matches no route. Three screens reachable
only by typing an address, which is exactly how each had been checked.

**The message browser crashed on mount.** A Laminar `controlled` input paired with the `change`
event, which Laminar rejects at run time; the exception was thrown while the page was mounting, so
the whole screen was replaced by "Something went wrong". Nothing in the module's five suites ever
mounted the page.

**Browsing a topic that does not exist created it.** A Kafka consumer's
`allow.auto.create.topics` defaults to true and so does a broker's `auto.create.topics.enable`, so
asking for a missing topic's metadata creates it. A mistyped topic name answered
`KUI-TOPIC-NOT-FOUND` and left a new empty topic on the cluster. In a read-only product this is the
worst of the seven.

**`kui.topics.internalPrefix` did nothing.** Two functions implemented the rule, each with a
scaladoc claiming the two halves of "internal" were combined "exactly once", and neither was called
from production code. Only Kafka's own flag reached a screen.

**The Read button never came back.** `running` was cleared only by Stop and by unmount, so a bounded
browse that finished by itself left the button saying Stop for ever, beside a status line saying
"Finished".

Three of the seven were in code committed the same day; four had been in the repository longer.

Each fix carries the test that would have caught it: `ConsumerFactorySuite`, `MessagesPageSuite` (the
first suite that mounts the message browser at all), `BrowseSessionSuite`, and new cases in
`NavigationSuite`, `TopicsRoutesSuite` and `TopicDetailPageSuite`.

### What a newcomer still cannot do

Publish a message. Resend one. Reset a consumer group's offsets from the interface — the endpoints
are served and verified, the wizard is not built. Choose a serde. Load a second page of records past
one browse's limit. Everything under M5 and later: creating or configuring a topic, editing ACLs,
signing in.

## Where this stood, 2026-09-04 (M1)

**M1 is done, and the quickstart now shows a real cluster.** The packaging defect recorded below on
2026-09-03 is fixed: `/ui/main.js` is served as `text/javascript`, 966 KB, and the interface renders.

Run from a clean state, the quickstart brings up a Kafka broker in KRaft mode, seeds it, starts KUI
and prints one URL. Opening that URL now shows the seeded cluster on the dashboard — one row, online,
one broker, its controller and its disk usage — and the brokers page shows the broker itself.
`GET /api/v1/clusters`, `/clusters/{id}`, `/clusters/{id}/brokers`, `/clusters/{id}/brokers/{id}/configs`
(340 settings) and `/clusters/{id}/log-dirs` all answer against the real broker.

### Three defects found by running it rather than by reading it

None of these was visible in any single module's tests, and all three were found in the first hour of
running the assembled product.

**The all-in-one dropped the cluster list.** `AllInOneConfig` carried three configuration sections and
handed the cluster service `ClusterServiceConfig.Default`, so `kui.clusters[]` was parsed, validated
and then discarded. Every screen the milestone is about was empty and nothing reported an error,
because an empty registry is a legitimate configuration.

**The dashboard decoded a response nobody sends.** `GET /api/v1/clusters` is the one endpoint the
gateway aggregates rather than proxies, so it answers with the gateway's `ClusterOverviewDto`
(`{"clusters": …}`). The browser client declared the cluster service's `ClustersResponse`
(`{"items": …}`), whose decoder defaults a missing `items` to the empty list — so every response
decoded *successfully* into zero rows. The page drew "No clusters yet" under a "last updated just now"
timestamp. Both modules' own suites were green, because each tested itself against its own idea of the
payload.

**The end-to-end suite tested the previous milestone.** `./mill e2e.test` had a real dependency on the
all-in-one jar but none on the container images the Compose topology runs, so the two suites that
drive the distributed shape — including the fault-isolation suite that proves the milestone's headline
claim — ran against the M0 images for the whole of M1. Its own failure screenshot showed M0's deleted
"Ping" page.

### What a newcomer sees, and what they do not

They see the dashboard, the broker list, a broker's configuration and its log directories, all against
their own broker, with a cluster that is down rendered as a row that says why and stays clickable.

They do not see topics, messages or consumer groups: those are M2, M3 and M4, and the seeded data is
waiting for them. The quickstart's own seed creates eight topics, 111 messages and three consumer
groups that nothing in the interface can show yet. *All three are shown as of the integration pass
above.*

## Where this stood, 2026-09-03

The quickstart exists and runs. From a clean machine with only Docker, one command brings up a
Kafka broker in KRaft mode, waits until it can actually serve metadata rather than merely until the
container has started, seeds it with eight topics of deliberately varied shape, 111 messages, two
tombstones, non-JSON values and three consumer groups in three different states, then starts KUI and
prints one URL. A second run takes about 27 seconds. Teardown leaves no containers, volumes or
networks behind.

Two defects were found by running it rather than by reading it, which is the only way these are ever
found:

**The interface does not render from a clean clone.** The single-page fallback answers `/ui/main.js`
with the page itself, so the browser is handed HTML where it expects a module and refuses to run it.
The screen is blank while every health check passes and every container reports healthy — the worst
shape a failure can take. The cause is that the frontend bundle is not packaged into the deployable.
*Fixed; see 2026-09-04 above.*

**Two seeds were built where one was needed.** Two agents each wrote one without knowing about the
other, and the stack ran the weaker of them while the richer one sat unused and documented. Now
resolved to the richer one.

Neither was visible from the code. Both were visible within a minute of running it.

## Point 3's other half, 2026-09-04 (publishing)

**Point 3 is now met in full: a record can be read and one can be published.** Verified against the
quickstart's seeded broker — through KUI's own HTTP API and then through the screen in a headless
Chromium driven over the DevTools protocol, because the browser tool this session had was not
connected.

What works, and how it was checked:

- **Publish.** `POST /api/v1/clusters/quickstart/topics/orders.v1/messages` with a key, a JSON
  value, a header and `count: 2` answered
  `{"records":[{"partition":2,"offset":0,…},{"partition":2,"offset":1,…}]}`. Browsing that
  partition immediately afterwards returned both records with the value decoded as JSON, the key as
  a string and the `trace` header intact. The same thing done from the screen — Publish, fill the
  form, submit — answered "Published 1 record. partition 3, offset 3".
- **Resend.** `POST …/topics/orders.v1/messages/resend` with one range answered
  `{"toTopic":"orders.dlq","read":2,"written":2}`, and reading `orders.dlq` back showed the two
  records with the same key, the same value and the same header. From the screen: open a record,
  press *Copy to another topic*, and the drawer reports "Read 1 and wrote 1 into orders.dlq."
- **Republish from the browser.** Opening a record and pressing *Republish* fills the publish form
  with that record's key, value and headers, editable, with the partition left for Kafka to choose.
- **Read-only refusal (ADR-047).** A second cluster configured `readOnly: true` answered
  `KUI-READ-ONLY` — *"cluster Quickstart (read-only) is configured read-only, so produce is not
  accepted"* — and the service log carries `produce on orders.v1: refused`. The refusal happens
  before a producer is opened, which the unit suites assert by counting.
- **A partition the topic does not have** answers `KUI-VALIDATION` with
  *"topic orders.v1 has 6 partitions, numbered 0 to 5"* rather than an exception at send time.
- **Same-topic resend** is refused in the drawer before a request is sent, and again by the service.

### What is not built

Purge (`MS-008`). It is the destructive operation on this screen — it moves a log's low watermark
and the records below it are gone — and it is the one that needs ADR-045's plan token. No control is
rendered for it, not even a disabled one.

### One thing worth knowing

Asking a broker whether a topic exists *creates* it when that broker is configured with
`auto.create.topics.enable=true`, which the quickstart's is. Publishing to a name that does not
exist therefore succeeds on such a cluster. No Kafka client can avoid this — any metadata lookup has
the same effect — and KUI never creates a topic of its own accord; the remedy is the broker setting.
`KafkaRecordProducer` says so where the check is made, rather than claiming a guarantee it does not
have.

## The final integration pass, 2026-09-04

This section is the record the project owner asked for: what a newcomer can actually do, a verdict
on each of the six bar points, every defect and missing feature that is still there, and what was
not tested. It was written after running the quickstart from clean and driving the product in a
browser, not after reading the code.

Its value is entirely in its honesty, so the failures are given as much room as the successes.

### What one command gets you

```
deployment/quickstart/quickstart.sh
```

Docker is the only thing that has to be installed. On this machine, with the KUI image already
built, that took about ninety seconds from `down` to a working URL, most of it the broker's
readiness check. It starts a single-node Kafka 4.3.1 in KRaft mode, waits until the broker can serve
metadata rather than merely until the process is up, seeds it with seven topics, JSON records and
three consumer groups (one of them behind), starts KUI on it, and prints one line:

```
  KUI is running:  http://localhost:18080/ui/
```

Port 8080 was taken on this machine by an unrelated program. The script detected it, refused to
start, named the variable to override, and printed the exact command to run:

```
Something is already listening on:
  8080 (KUI, override with KUI_PORT)

Choose free ports and pass them in, for example:
  KUI_PORT=18080 KUI_QUICKSTART_KAFKA_PORT=19092 ./deployment/quickstart/quickstart.sh
```

That is the behaviour the quickstart section of this document asks for, and it is worth naming
because it is the first thing that can go wrong on somebody else's machine.

`quickstart.sh down` removed every container, the network and the volumes. Nothing was left behind.

### The six-point bar

| # | The bar | Verdict |
| --- | --- | --- |
| 1 | Clone it, run one command, have KUI and a Kafka broker both running | **Met** |
| 2 | See clusters, brokers and topics with no configuration beyond a bootstrap address | **Met** |
| 3 | Read messages including the JSON inside them, and publish one | **Met** |
| 4 | See consumer groups and how far behind they are | **Met** |
| 5 | Point it at your own cluster with a documented example, including a secured one | **Met** |
| 6 | Have any part of it fail without the rest becoming unusable | **Partly met** |

**Point 1 — met.** Evidence above. One caveat that belongs to developers rather than newcomers: the
script reuses whatever `kui-allinone` image is already on the machine and builds one only when none
is there. That is right for a newcomer and a trap for anyone who has just changed code — this pass
spent its first half hour testing a build that was seventeen minutes out of date. `README.md` now
says so.

**Point 2 — met.** The cluster list showed the one cluster online with its broker count and
controller; the broker list named node 1 as the controller; the topic list showed all seven seeded
topics with partitions, replication factor and message counts; the topic detail page showed the
per-partition first and next offsets and the cleanup policy. All of it from a browser, with nothing
configured beyond the bootstrap address the quickstart writes.

**Point 3 — met, end to end, in a browser.** From the topic page: *Browse messages*, *Read*, and the
records came back with their values rendered as JSON rather than as a wall of bytes:

```
Offset  Partition  Key         Value
0       3          ORD-10241   {"orderId":"ORD-10241","customerId":"CUST-8812", …
```

Then *Publish*, with the key field enabled, a JSON value and a header, which answered:

```
Published 1 record.
partition 3, offset 3
```

and reading the topic back with `Contains: ORD-FINAL-KEY` found it:

```
3  3  ORD-FINAL-KEY  {"orderId":"ORD-FINAL-KEY","note":"keyed publish from the browser"}
```

One thing to know about the publish form: **"No key" is checked by default and the key field starts
disabled.** That is a defensible default and it is not signposted; the first attempt in this pass
published a record with no key without noticing.

**Point 4 — met, including the wizard.** The group list showed three groups with their states and
lag. Opening `order-fulfilment` showed per-partition committed offsets, end offsets and lag. The
reset wizard was driven all the way through: choose *The beginning of each partition*, *Show me what
this would do*, which returned a plan naming what each partition would move from and to, and *Apply
this plan*. The total lag on screen went from 9 to 16 and the receipt named what had been written:

```
What was written
The offsets below were written. The group will read from them the next time it starts.
PARTITION  FROM  TO  CHANGE
0          1     0   -1
3          2     0   -2
```

The receipt did not appear on the first attempt. That was a real defect, found by using the product
and fixed in this pass — see below.

**Point 5 — met, and driven in a browser for the first time.** `deployment/secured/` was brought up
from scratch: certificates generated, a KRaft broker speaking `SASL_SSL` with `SCRAM-SHA-512` behind
a private certificate authority, and KUI pointed at it with the shipped
`deployment/examples/production.yaml`. KUI reported the cluster as

```
security: {'protocol': 'SASL_SSL', 'mechanism': 'SCRAM-SHA-512', 'truststoreConfigured': True}
summary: ok  kui-secured-000000001
```

and every screen was then driven against it in a headless browser: eight topics, `orders.v1`'s
detail and partitions, three consumer groups, and a message browse that finished with
`Finished · 16 records · 16 records read from Kafka` and rendered the JSON as JSON. The previous
pass had built this stack and exercised its API but explicitly recorded that no browser had been
driven against it; that gap is now closed. Teardown left nothing behind.

**Point 6 — partly met, and the qualification matters.** `docker stop kui-quickstart-kafka`, then:

- Every screen kept working. The shell, the sidebar, Settings, the topic list, the group list and
  the cluster list all rendered from last-known-good data. Nothing crashed and nothing went blank.
- Within a minute the cluster list changed to `Degraded: cluster not responding` and the topic list
  grew a marker reading `Stale: UPSTREAM_UNAVAILABLE`.
- Pressing *Read* on the message browser produced a clear failure in the status line:
  `reading records from Kafka` / `kafka answered with status 502`, and the server-sent stream
  carried a proper `KUI-UPSTREAM-UNAVAILABLE` error event with a correlation id.
- The gateway kept answering; `/api/v1/capabilities` reported the cluster, consumer and topic
  services as `degraded` for that cluster with a reason attached.

What stops this being a clean "met" is the consumer-group list. See the defects below: it goes on
showing lag figures from before the broker died, with no indication that they are old.

### Defects still present

**1. The consumer-group list shows stale lag with nothing saying so.** With the broker stopped, the
topic list says `Stale: UPSTREAM_UNAVAILABLE` and the cluster list says `Degraded`. The consumer
group list says nothing at all and keeps displaying the last lag it saw. The cause is visible in the
two responses: the topics endpoint wraps its payload in a freshness envelope —

```
{"topics":{"status":"stale","data":{"items":[…
```

— while the consumer-groups endpoint returns a bare `200` with the cached rows and no envelope, so
the browser has no way to know. `GroupListPage` already has the machinery to render the marker; it
is keyed on the last request having failed, and the request does not fail. Lag is the worst field in
the product to show stale without saying so: an operator reads it to decide whether consumers are
keeping up, and a dead broker makes it freeze rather than move, which looks like health. **Not
fixed** — it needs the freshness envelope carried through the consumer contract, its API module, the
gateway and the browser, which is more than could be landed and verified safely in this pass.

**2. Browsers that cached KUI before this pass will still see a blank page once.** The `immutable`
caching defect described below is fixed for every deployment from here on, but a cache entry already
granted a year cannot be retracted by a later response. A browser that had KUI open before this
change stays broken until its site data is cleared. Confirmed: after the fix was deployed, the
browser profile that had loaded the previous build still threw
`TypeError: …Bootstrap$(…).Vw is not a function` and rendered nothing, while a fresh profile worked.

**3. Error text leaks internals.** `Stale: UPSTREAM_UNAVAILABLE` puts a wire code in front of a
user, and `kafka answered with status 502` describes the gateway's view rather than the operator's
problem, which is that the broker is unreachable. Cosmetic, but it is on the screens people reach
when something is wrong.

**4. The topic page's Consumers tab appears only sometimes.** It is contributed by the consumers
microfrontend through a feature slot, and a guest's tab exists only once its feature has been
downloaded for some other reason. Open a topic page in a fresh tab and the strip is Overview and
Settings; visit Consumers first and come back and it is Overview, Settings and Consumers. This is
documented as intended in `GuestTabs`, and it is still a tab whose existence depends on the user's
browsing history.

**5. One flaky test.** `UpstreamClientSuite`'s "one log line per circuit transition" failed once in
five whole-repository runs and never on its own. It has no assertion difference, which is how MUnit
reports a timeout, and a full `./mill __.test` runs it beside Docker builds and Kafka containers.
Its `munitIOTimeout` was raised to three minutes; that is a mitigation, not a proven diagnosis.

### Defects found and fixed in this pass

Each was found by using the product, and none was caught by any test.

**`8fb23b7` — bundle chunks were cached as immutable under names that are not content hashes.** The
worst thing found. Upgrading a running KUI underneath an open browser left a permanently blank page:

```
TypeError: …$m_Lkui_ui_kernel_api_Bootstrap$(…).Vw is not a function
```

`StaticRoutes` served Scala.js's `internal-<40 hex>.js` chunks with `max-age=31536000, immutable`,
on the stated grounds that a hashed name never gets different bytes. It does. Two consecutive builds
both produced `internal-3ebfae0cba70adf981029a0da5b1e4b5ab5d02c6.js`, and the two files differed —
a method the first called `Vv` the second called `Vw`. That name identifies the *set of classes* in
the chunk, not the JavaScript emitted for them, and the linker assigns short member names across the
whole program at once. Combined with `main.js`, which is `no-cache` and so always current, that
gives a current `main.js` calling `Vw` against a year-old chunk that only has `Vv`. Every asset is
now `no-cache`. The cost is real and is stated in the code: with no validator on the response, each
page load refetches the bundle in full, and the largest chunk is about 6 MB. **The right end state
is `no-cache` plus a strong `ETag` with `If-None-Match` answered `304`, and that is outstanding
work, deliberately not half-built here.**

**`c100d30` — a missing static asset was answered with the shell and status `200`.** The related
half of the same failure. Every miss under `/ui/` fell through to `index.html`, so a browser asking
for a module file a new deployment no longer has received an HTML document where it expected
JavaScript — and nothing in its network log was marked as having failed. A miss whose name ends in
an extension the gateway knows how to serve is now a `404`. The test cannot be "contains a dot":
Kafka topic names contain dots, so `/ui/clusters/local/topics/orders.v1` is a screen.

**`7decef8` — the offset-reset receipt was destroyed by the refresh the reset itself caused.**
`GroupDetailPage` rebuilt its whole body from every new snapshot of the group, and the wizard was
built inside that expression. Applying a reset writes new committed offsets, and new committed
offsets are exactly what makes the next snapshot differ — so the element holding the receipt was
replaced in the same instant the receipt arrived. The offsets moved correctly and the operator was
shown nothing. The wizard is now built once and takes its topic list as a `Signal`. Nineteen tests
drove the wizard in isolation, where nothing ever replaces it, and there was no suite for the page
at all; `theReceiptSurvivesANewSnapshotOfTheGroup` now closes that gap.

**`b9dca64` — the dashboard told new users the product was not installed.** The first screen after
the quickstart said *"Cluster overviews appear here once the clusters feature is installed."* Written
in M0, true then, false since M1, and read by every newcomer as an instruction to go and find an
installation step that does not exist.

**`02372e1`, `57117fd`** — `./mill __.fix --check`, a gate CI runs, was red on `main` in four files;
and the flaky suite above.

### Missing features, stated plainly

- **Purge (`MS-008`)** — the only M3 row outstanding. No endpoint, no control, not even a disabled
  one. It is the destructive operation in this milestone and the one ADR-045's plan token exists for.
- **Serde selection on publish (`MP-004`)** — the browse screen can override how keys and values are
  *decoded*; the publish form cannot choose how they are *encoded*.
- **A real dashboard (`UI-012`)** — the home page is a signpost, not a summary.
- **Everything from M5 onwards** — topic and message deletion, cluster administration,
  authentication and authorisation, schema registry, Kafka Connect, ksqlDB, and metrics. These are
  beyond this bar and are not claimed anywhere.

### What was not tested

Stated so that nobody reads a silence as a pass.

- **Any browser other than headless Chromium 151.** No Firefox, no Safari, no mobile, no real
  Chrome with an extension attached. The Chrome extension was not connected in this session, so the
  browser was driven over the DevTools protocol.
- **Keyboard-only navigation, screen readers and contrast.** The markup has `role="status"` and skip
  links, and none of it was exercised with assistive technology.
- **More than one broker.** Every run in this pass used a single-node cluster, so nothing about
  replication, under-replicated partitions, leader election or rack awareness was observed — several
  columns on the cluster and topic screens were `—` for that reason and could not be checked.
- **Scale.** Seven topics and a few dozen records. Nothing here says how the topic list behaves at
  ten thousand topics or how a browse behaves against a partition holding millions of records.
- **The multi-container deployment under failure.** `deployment/compose` is covered by its own smoke
  script and by the e2e suites in `./mill __.test`; this pass drove the all-in-one process only, so
  point 6 was tested by stopping the *broker*, not by stopping a KUI service.
- **Authentication.** There is none to test; `authType` is `disabled`.
- **Upgrade from a previous release.** There has been no release. The upgrade case exercised here
  was one build of KUI replaced by another underneath an open browser, which is how defect 2 above
  was found.

### The gates

Every gate CI runs, on the commit this section was written against:

```
./mill __.checkFormat                     189/189, SUCCESS
./mill __.fix --check                     4164/4164, SUCCESS
./mill checkArchitecture                  129 modules, 10 rules, no layering violations
./mill frontend.uiShell.checkBundleShape  4 feature modules split out, main.js 887059 B of 1500000 B
./mill __.test                            8226/8226, SUCCESS
```

`./mill __.test` includes the Playwright end-to-end suites and the live Kafka adapter suites, which
start real brokers in containers.

### The honest summary

The bar this document sets is met at five points of six, and the sixth is met for everything except
one screen that shows stale numbers without admitting it. A person who has never seen this project
can run one command and, minutes later, read the records in their own Kafka, publish one, watch a
consumer group's lag and move it — and can do the same against a cluster secured with SASL and TLS
using the example configuration as shipped.

What should temper that is how the defects in this pass were found. Every one of them was invisible
to a green test suite of 8,226 cases and became obvious within minutes of using the product: a
caching header that white-screens every upgrade, a receipt destroyed by the refresh it caused, a
dashboard advertising that the product was not installed. The suite is good at what it was pointed
at. It was not pointed at the seams — deploying over a running browser, a screen outliving the data
under it — and that is where the remaining risk is.

## Point 6, proved rather than asserted, 2026-09-04 (the demonstration environment)

Point 6 of the bar — *"have any part of it fail without the rest becoming unusable"* — was until now
demonstrated only at the level of KUI's own services: `deployment/compose/` kills `kui-cluster` and
the gateway keeps answering. That is half the claim. The other half is a *cluster* failing, which a
single-broker quickstart physically cannot show. `deployment/demo/` is that other half, and this
section records what it actually did rather than what it was built to do.

### The newcomer path, timed from nothing

Starting from a machine with no `kui-allinone` image, no `deployment/secured/certs/`, and no
containers, networks or volumes — one command, no second attempt, no undocumented step:

```
$ deployment/demo/demo.sh
The KUI image kui-allinone:0.1.0-SNAPSHOT is not on this machine, so it has to be built.
  EXPECT SEVERAL MINUTES the first time. It happens once: the image is kept, and later runs
  start immediately.
...
#9 DONE 213.3s                  <- compiling KUI from source, in a container
#11 DONE 16.7s                  <- exporting the image
Generating the secured cluster's demonstration certificate authority (once, about ten seconds).
Certificate stored in file <ca.pem>
...
  KUI is running:  http://localhost:18080/ui/

./demo.sh up  0.75s user 0.43s system 0% cpu 4:41.15 total
```

**4 min 41 s cold**, of which 3 min 50 s is the one-time compile and about 10 s the certificate
authority. A second `up` after a full `down`: **44 s**, with all three clusters seeded and `ok`.

### Three clusters that are genuinely unalike

Read back through KUI's own API, not the seed's logs:

```
development  ok    brokers=1 PLAINTEXT              4 topics   2 groups
production   ok    brokers=3 PLAINTEXT             15 topics   7 groups
secured      ok    brokers=1 SASL_SSL SCRAM-SHA-512 4 topics   3 groups
```

Production carries the shapes a one-broker cluster cannot have: `analytics.pageviews` at 24
partitions / replication factor 3 / 8 000 messages, `clickstream.raw` at replication factor 2, and
lag that varies by three orders of magnitude across groups (`analytics-rollup` 8 000,
`order-fulfilment` 9, `payments-ledger-sync` 0). Each cluster has exactly one genuinely **live**
group — `order-notifier` (1 member), `search-indexer` (3 members), `secure-audit-stream` (1 member)
— reported `STABLE` next to the `EMPTY` ones, so the difference between "nobody is reading this" and
"somebody is reading this and is behind" is visible rather than described.

The secured cluster needs nothing from the reader. Its records come back deserialized over
`SASL_SSL`, JSON parsed and headers intact, against a certificate authority the script generated
during the same `up`:

```
event: message
data: {"partition":2,"offset":7,...,"value":{"text":"{\"orderId\":\"ORD-10249\",...}","kind":"json",
       "serde":"Json"},"headers":{"trace-id":"6ab4c2e70d15","event-type":"OrderPlaced",...}}
```

### The claim itself

**Stopping a whole cluster.** With Production stopped, Development and Secured answered in **10 ms
and 11 ms** — no slowdown, no shared timeout, no error borrowed from a neighbour. Production did not
vanish and did not lie:

```
summary: {"status": "stale", "fetchedAt": "2026-09-04T13:04:58Z", "reason": "UPSTREAM_UNAVAILABLE"}
topics:  {"status": "stale", "fetchedAt": "2026-09-04T13:04:28Z", "reason": "UPSTREAM_UNAVAILABLE"}
         topics still listed: 15
```

It stayed navigable, kept the 15 topics it had last read, and said when it read them. `start prod`
restored it with no intervention: by the time the command returned, both the summary and the topic
list were `ok` again.

**Stopping one broker of three** is the subtler case, and the one that separates "the cluster is
gone" from "the cluster is degraded". Production stayed `ok` and kept serving; `brokerCount` fell
from 3 to 2; and after Kafka's ISR shrink interval the topic table showed the cost:

```
analytics.pageviews    p=24  rf=3 outOfSync=24
orders.v1              p=12  rf=3 outOfSync=12
clickstream.raw        p=12  rf=2 outOfSync=8
...                                 TOTAL outOfSyncReplicas: 96
```

Kafka's own `--under-replicated-partitions` reported **147** for the same moment. The two agree
exactly: KUI's 96 covers the 15 topics it shows, and the missing 51 are `__consumer_offsets` (50)
and `_schemas` (1), both hidden behind the "show internal topics" switch by this deployment's
`kui.topics.internalPrefix: "_"`.

**Stopping the secured cluster** produced `stale` with reason `UPSTREAM_TIMEOUT` — a different
reason from the stopped Production cluster's `UPSTREAM_UNAVAILABLE`, which is the distinction that
stops an operator reading a TLS or credential problem as an unplugged broker.

**Teardown.** `demo.sh down` left zero containers, zero networks and zero volumes; the Compose file
declares no volumes at all, so broker data lives in the container layer and goes with it.

### What this pass found

One documentation defect, fixed: `deployment/demo/kui-demo.yaml` explained its
`internalPrefix: "_"` setting by claiming the topics screen would then "show the seven topics that
are yours". No cluster in the demonstration has seven topics — Production has 15, the other two have
4 each. The comment predates the per-cluster seed profiles.

One product defect, **found and not fixed**, because fixing it is a breaking contract change that
does not belong in a demonstration-environment pass:
`services/cluster/api/src/kui/cluster/api/ClusterMapping.scala:95` populates the broker DTO field
`inSyncReplicaCount` from `row.replicas` — the broker's *total* replica count, not its in-sync one.
The two are equal on a healthy cluster, which is why nothing caught it, and they diverge exactly
when an operator needs the number: with one broker of three stopped, both surviving brokers still
reported `inSyncReplicaCount: 147`, unchanged from before the failure. The consequence on screen is
currently muted rather than wrong — the brokers page renders this as a ratio against
`partitionCount`, which `ClusterMapping` hard-codes to `None`, so the "In-sync" figure shows `—`
instead of a wrong number — but the API field is untrue as it stands, and any consumer of
`/api/v1/clusters/{id}/brokers` reading it will be misled during precisely the incident it exists
for. The honest fix is to rename the field to `replicaCount`, which touches the contract, its golden
documents, the OpenAPI document and the frontend column.

### What was not checked

The browser was not driven in this pass: no Chrome extension was connected in this environment, so
every claim above is from KUI's own HTTP API and from Kafka's command-line tools, not from
screenshots. The cluster switcher, the topic and group tables, the message viewer and the stale-data
overlay were each verified through the endpoint that backs them and through the served
`/ui/` bundle returning 200 — but nobody looked at the rendered pixels. That is the one part of the
demonstration this pass asserts rather than proves.

## Point 6 closed, and the four open defects, 2026-09-04 (fault isolation and error surfaces)

The pass before this one recorded point 6 as *partly* met, with the reason stated precisely: the
topic list carried a freshness envelope so a stale list was marked stale, and the consumer-group
list did not. It also left four defects open. This pass closes the bar point and all four.

### The bar point: the consumer-group list now says when it is stale

`GET /api/v1/clusters/{c}/consumer-groups` used to answer a bare `200` carrying the rows of the last
successful scrape, whether or not the cluster was still answering. The browser had no way to tell
that from a live answer, so the screen went on showing lag figures from before the broker died with
nothing anywhere saying so.

That mattered more here than anywhere else in the product. Lag is the one number on this screen that
is supposed to move on its own, and an operator reads it to decide whether their consumers are
keeping up. A dead broker makes it **freeze** rather than climb — so a frozen lag column looks
exactly like a cluster that has caught up, and the screen quietly told the reader the opposite of the
truth.

The envelope the topic list already used now runs the whole way through:

| Layer | What changed |
| --- | --- |
| `services/consumer/contract` | `GroupsResponse(groups: Section[GroupPageDto], incompleteCoordinators: Int)`, mirroring `TopicsResponse`. `ConsumerEndpoints.list` returns it |
| `services/consumer/api` | `ConsumerSections` translates the application layer's existing `SnapshotFreshness` verdict into the wire vocabulary, in one place |
| gateway | nothing: its public routes are derived from the service contract, so the new document travels unchanged. `openapi.json` regenerated |
| `frontend/ui-consumers` | `ConsumersApi.list` decodes the envelope; `GroupListPage` dims the table, stamps it with the server's `fetchedAt` and shows the reason |

Two rules in the browser are worth writing down. The **server's section wins** over "the last request
failed": the server knows its scrape of the cluster failed, whereas a failed request only says that
this one call did not get through. And the badge's time is the server's `fetchedAt` and not the
moment the browser's request returned — a cached snapshot answered in a millisecond is not fresh
data, and stamping it with the request time would claim it was.

**The seam is tested, not the two sides.** That is deliberate: this defect survived because each half
was asserted against its own idea of the answer. Two golden documents — a fresh list and a stale one
whose rows are byte-identical to it — are encoded from the contract's own samples and committed.
`ConsumerRoutesSuite` asserts that the live routes produce that shape for a cluster that has stopped
answering; `GroupListFreshnessSuite` feeds the same committed text through the endpoint the *browser*
declares, into the real page, and asserts the table is dimmed, the badge appears, the rows survive,
and a section with no rows renders an explanation rather than an empty table.

### Defect 1 — error text leaked internals

`Stale: UPSTREAM_UNAVAILABLE` and `kafka answered with status 502` were being shown verbatim. The
second is worse than unhelpful: no Kafka broker speaks HTTP, so there is no 502 to go and look for,
and the thing that is actually wrong — the broker cannot be reached — was never stated.

An error code belongs in a log line and in a support conversation; a screen needs a sentence. Both
survive, in different places:

- `ReasonCode.sentence` gives each reason its operator-facing wording beside the wire spelling, in
  the one module the services and the browser share. `ReasonCode.of` moves the error-to-reason
  classification there too, so the topic list and the group list cannot describe one outage two ways.
- `StaleReason` carries the code separately from the message. The badge reads
  `Stale: the cluster is not answering` and puts the code in its tooltip beside the fetch time.
- `UserFacing.sentence` is the single rewriting rule, used by `ApiError.userMessage` and by the
  browse screen's status line. It rewrites **only** the three codes meaning "something KUI depends on
  is not working"; every other message stays the server's own words, which name the topic or cluster
  the request was about, and an unknown code is passed through rather than replaced with a guess.

### Defect 2 — the Consumers tab depended on browsing history

The tab strip on a topic page was derived from the features that happened to have been downloaded, so
a fresh page load offered Overview and Settings and the same page after a detour through Consumers
offered three tabs. A feature is already registered twice — a static half the shell draws the sidebar
from before anything is downloaded, and a dynamic half that is the code — and a tab's heading is one
word of data that belongs in the static half. `FeatureRoutes.guestTabs` now declares it, `GuestTabs`
builds the strip from the static registrations and takes no loaded-feature list at all, and opening a
guest's tab is what imports that guest's module. ADR-012's promise is intact: nothing is downloaded
in order to draw a tab.

### Defect 3 — the flaky test, diagnosed

`UpstreamClientSuite`'s "one log line per circuit transition" was mitigated with a three-minute
timeout and never diagnosed. The timeout was never the problem — the case runs in eighteen
milliseconds of *simulated* time, so no amount of machine load could push it past thirty seconds.

It was racing a subscription. `UpstreamClient` wrote circuit-transition log lines from a fiber
started with `.background`, and starting a fiber is not the same as that fiber having subscribed to
the breaker's `Topic`; a `Topic` delivers only to subscribers that already exist. The case papered
over that with a one-second sleep before reading the log, which is ordering by hope.

That race is a real defect and not only a test problem: an upstream that is already down when KUI
starts trips its circuit inside the same window, and the one INFO line telling an operator the
circuit opened is published to nobody. `CircuitBreaker.subscribed` is a `Resource` whose acquisition
registers the subscription, so the client cannot be handed out before something is listening. The
sleep and the raised timeout are both gone.

### Defect 4 — the caching replacement, landed

The `immutable` header was removed last pass and its replacement was left outstanding. Every static
asset now carries a strong `ETag` computed from its bytes — SHA-256 truncated to sixteen bytes — and
`If-None-Match` is answered `304` with no body, following RFC 9110 for lists, `W/` and `*`.
`index.html` is hashed after its bootstrap block is rendered in, so a configuration-only deployment
still invalidates it.

Correctness is unchanged: every asset is still revalidated, so no browser can assemble a page from
two builds. What changes is the price — a round trip instead of a six-megabyte download per load. The
validator comes from the content and never from the name, which is the whole lesson of the defect it
replaces.

### Every fix has a test, and every test was seen to fail first

| Fix | The failure that was observed first |
| --- | --- |
| Freshness envelope | `aListReadFromADeadClusterIsMarkedStaleOnTheWire`, `aListReadFromAHealthyClusterIsMarkedFreshOnTheWire`: `groups.status` was `None` — no envelope on the wire at all |
| Error text | `anUpstreamFailureIsRewrittenIntoASentenceAboutTheCluster`: obtained `kafka answered with status 502`. `theBadgeSaysWhatHappenedInWordsAndKeepsTheCodeForSupport`: obtained `Last updated 3 hours agoStale: UPSTREAM_UNAVAILABLE` |
| Consumers tab | `theTopicPageOffersAConsumersTabBeforeAnyFeatureHasBeenDownloaded`: `the topic page's guest tabs are List(), with nothing loaded` |
| Flaky test | `aCircuitThatOpensImmediatelyAfterStartUpIsStillLogged`: obtained `0` log lines, expected `1` |
| ETag | four cases in `StaticRoutesSuite` against the previous revision: `/ui/ was served with no ETag`, `no ETag on the first response`, and two more |

### One more defect, found by running the product

The Consumers tab exists now, and until this pass it had nothing behind it. With the tab open on a
topic that a group was actively consuming, and the consumer service reported `available` for that
cluster, the gateway answered:

```
GET /api/v1/clusters/verify/topics/verify.v1/overview
  "consumerGroups": { "status": "not_configured" }
```

`TopicOverviewUseCase` reports a section with no registered `SectionSource` as `NotConfigured`, which
is the right answer for a section nobody has built — and it was the answer a fully configured
deployment got, because `GatewayWiring` called `TopicOverviewUseCase.resource` and let the `sources`
map default to empty. The section was designed, contracted, tested against a fabricated source,
rendered by a real panel in the browser, and never wired to the service that fills it. It went
unnoticed for the same reason as defect 2: the tab in front of it only appeared when the consumers
feature happened to have been downloaded already, so hardly anyone reached the panel.

`ConsumerGroupsSource` is the missing map entry, and the gateway registers it whenever a consumer
service is configured. The rows still travel as `Json`, so `ui-topics` learns nothing about this
service and the gateway declares none of its types.

### What was not done in this pass

- The changes **were** driven in a browser in the end, once the tree linked again — against a
  single-node Kafka started for the purpose on port 49092, with KUI on 38181, in a Chromium profile
  created empty for each check. What was seen:
  - `/api/v1/clusters/verify/consumer-groups` answered `"status":"ok"` with the group's lag; the
    broker was then stopped, and within about a minute the same URL answered
    `"status":"stale"`, `"reason":"UPSTREAM_UNAVAILABLE"`, `"fetchedAt":"…13:48:37.688Z"` with the
    rows unchanged.
  - the group list at `/ui/clusters/verify/consumer-groups` then showed a badge reading
    `Last updated 2 minutes ago` / `Stale: the cluster is not answering`, with
    `title="2026-09-04 16:48:37 UTC+03:00 · Reason code: UPSTREAM_UNAVAILABLE"`, over a region
    marked `aria-busy="true"` that still held the rows.
  - `HEAD /ui/main.js` answered `Cache-Control: no-cache` and
    `ETag: "665a74d4a06eb5b929ff1e71377dabea"`; the same request with `If-None-Match` set to that
    value answered `304` with a zero-byte body.
  - a **fresh** browser profile whose first page was `/ui/clusters/verify/topics/verify.v1` showed
    the tab strip `Overview, Settings, Consumers`, and clicking Consumers loaded the guest module
    and drew the real table: `verify-group`, `Empty`, `3` partitions, lag `10`.
- **No screen was checked with more than one cluster, at scale, or in any browser other than
  headless Chromium 152.** Nothing about keyboard navigation, screen readers or contrast was
  exercised.
- `docs/api/openapi.json` was regenerated by a concurrent pass and carries this change along with
  theirs; only `services/consumer/api/openapi.json` was committed here.

## The dashboard, and choosing a serde when you publish — 2026-09-04

Two of the gaps this document listed under "Missing features" are closed: `UI-012`, a real dashboard
instead of a signpost, and `MP-004`, choosing how a record is serialized when publishing. Everything
below was run against the quickstart's broker and read on a screen.

### What the dashboard shows now

The first screen after the quickstart used to be a card saying a dashboard was not built yet. It now
shows the fleet in a strip — clusters online, brokers, topics, partitions, consumer groups — and one
card per cluster carrying that cluster's health, brokers and controller, its topic and partition
totals with the biggest topics drawn as bars, and its consumer groups counted by state with their
total lag. Quantities are drawn as bars beside the figure, which is the design's rule
(`research/design/REFERENCE.md`, "Notable interaction patterns"): comparing 50 against 12 costs a
reader two parses and comparing two bars costs none.

Where the numbers come from: the gateway's existing `/api/v1/clusters` aggregation gained two more
sections per cluster, filled by one call each to the topic service's `listTopics` and the consumer
service's `list` — both served from those services' own timed snapshots, neither touching a broker on
the request path, and all of them issued in parallel so the response is bounded by the slowest single
call rather than by their sum.

**Every figure was checked against what the broker reports.** With the quickstart running:

| KUI says | The broker says |
| --- | --- |
| 9 topics | `kafka-topics.sh --list \| wc -l` → 9 |
| 83 partitions | `kafka-topics.sh --describe \| grep -c "Partition:"` → 83 |
| Largest: `__consumer_offsets` 50, `analytics.pageviews` 12, `orders.v1` 6, `inventory.stock-levels` 4, `customers.profiles` 3 | the same five, same counts |
| 3 consumer groups, 1 Stable and 2 Empty | `kafka-consumer-groups.sh --describe --all-groups --state` → `analytics-indexer` Stable, `order-fulfilment` Empty, `payments-ledger-sync` Empty |
| Total lag 9 | `0 + 9 + 0` over the three groups |
| 1 broker, controller 1 | `describeCluster` |

Two figures are deliberately `—`: the cluster's Kafka version, which this broker does not report, and
— on the *first* reading after start-up — the total lag, because one group had not had its lag
computed yet. The lag tile said so in words: *"Lag is not totalled: 1 of 3 groups reported none."*
Thirty seconds later it read 9. That is the rule this screen is built on: **a figure nobody can
compute is absent and says why, never zero and never a partial sum.** A partial sum is the worse
failure, because it looks exact, and it grows when an outage ends — an operator would read a recovery
as a change in their fleet. The same rule makes the fleet-wide totals absent unless every cluster
contributed, and makes a cluster's partition total absent when it holds more topics than the gateway
summed in one page.

### Independent statuses, observed

`docker stop kui-quickstart-kafka`, then `GET /api/v1/clusters` every twenty seconds. The first
reading after the broker died:

```
outer ok | summary stale | topics ok  | groups stale
outer ok | summary stale | topics stale | groups stale
```

Three sections of one row in three different states in the same document, because three services
noticed at three different moments. That is the whole argument, and it is why the totals are not
folded into one status. On the screen each panel carried its own marker — *"Last read 10 minutes ago
— cluster not responding"* — over figures that stayed readable, and the consumer panel's lag went to
`—` with its reason underneath while the topic panel above it still showed 9 topics and 83
partitions. Nothing went blank and nothing crashed.

A section for a service this deployment does not run is `not_configured` and is hidden entirely, not
drawn as an error: four permanent red panels on every dashboard of every installation is how an
operator learns to stop reading red (ADR-032).

### Choosing a serde when you publish

The publish drawer has two new menus, *Write key as* and *Write value as*, offering the same eleven
serdes the browse bar offers for reading, from one list (`SerdeChoices`) that both read — two lists
maintained apart is how "publish with a serde nobody can read back" becomes possible. "Automatic" is
the default and sends no name at all, so the record is written with the serde it would have been read
with; and **Republish** starts on the serde the record it came from was decoded with, so republishing
round-trips by default and choosing anything else is a deliberate act.

Driven in a browser against the quickstart:

- *Write value as* `Json` with `{"orderId":"SERDE-PICKER","note":"published as Json"}` → *"Published
  1 record. partition 2, offset 0"*, and `kafka-console-consumer.sh` read exactly those bytes back.
- *Write value as* `Json` with `not json at all` → refused in the drawer, with the form's contents
  still in it: `expected null got 'not js...' (line 1, column 1)`.
- *Write value as* `Int64` with `17` → published, and the raw bytes on the partition are
  `00 00 00 00 00 00 00 11` — eight bytes, big-endian, seventeen. The picker is not decorative.

The serde override for *reading* was already built and is reachable: the browse bar's *Key as* and
*Value as* menus were verified to carry the same eleven names.

### What is rough

- **The `Json` serde's refusal is circe's own wording.** `expected null got 'not js...' (line 1,
  column 1)` is a parser's message, not an operator's. It is at least attached to the field and does
  not lose the composed record; it should be a sentence.
- **One cluster fills the whole card grid.** The cards are an `auto-fit` grid with a minimum column
  width, so a single-cluster deployment gets one very wide card. Correct, and not pretty.
- **Nothing here was tested with more than one cluster on screen.** The magnitude bars across
  clusters, and the "N clusters not counted" note on a partial total, are covered by unit tests and
  were not seen on a screen.
- **`docs/api/openapi.json` carries a concurrent pass's endpoints as well as these**, because it is
  regenerated whole and another agent was adding topic creation and deletion at the same time.
