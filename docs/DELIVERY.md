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
| M3 Message explorer | browsing with every seek mode, streaming, serialization formats, publishing, filters | reading is done; publishing is not built |
| M4 Consumer groups | groups, members, assignments, lag, offset reset | reading is done; offset reset is served but has no screen |
| Quickstart | one command that starts Kafka, seeds it with data, and opens KUI on it | done |
| Configuration examples | a plain example, a secured example, and the reference for every key | built |

M3 and M4 were both recorded here on 2026-09-04 as "part built, nothing reachable": modules that
compiled and were tested, with no path from a browser to any of them. That is no longer the state.
Both services now have an `api` module, a composition root, routes served by the running process and
a screen in the shell, and both have been used against the quickstart's own broker in a browser.

What each still lacks is named rather than implied. **M3** reads and does not write: there is no
publish endpoint, no resend, no serde picker and no "load more" past one browse's limit, so bar
point 3's "and publish one" is not met. **M4** reads, and its offset-reset endpoints are served and
verified against a real broker, but no screen drives them — the reset wizard is not built. Bar
point 4, which asks only to see groups and their lag, is met.

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
