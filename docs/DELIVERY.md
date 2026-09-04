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
