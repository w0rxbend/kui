# The KUI demonstration environment

Three Kafka clusters, one KUI, one command, on your own machine. Nothing to configure, and nothing
to install except Docker.

```
deployment/demo/demo.sh
```

It prints one line you have to act on:

```
  KUI is running:  http://localhost:18080/ui/
```

And when you have finished:

```
deployment/demo/demo.sh down
```

Everything it created is removed — containers, network and volumes. Nothing is left running and
nothing is left in a directory for you to find months later. The downloaded images stay, because
fetching them again next time would only waste your time; the script tells you how to remove those
too.

The first run takes a few minutes if KUI's image has to be built (it is compiled inside a
container, so a machine with only Docker is enough) and about a minute afterwards. Five Kafka
brokers plus KUI want roughly 4 GB of memory available to Docker; the script checks and says so
before it starts, because a broker killed part-way through looks exactly like a cluster failing for
no reason.

If you have never seen KUI at all, [`../quickstart/`](../quickstart/README.md) is a one-cluster
version of this that is up in thirty seconds. Come here when you want to see what the product is
actually for.

## Why three clusters and not one

This is worth understanding before you click anything.

A Kafka console that manages one cluster is a viewer. What makes KUI a management tool is that a
team's clusters are never one cluster: there is the one developers break, the one that must not be
touched, and the one behind a firewall that needs a password and a certificate. They differ in how
you reach them, in what is on them, and in how much it costs when one of them is unwell.

So the demonstration runs three, and each is there to show something a single cluster physically
cannot:

| Cluster | What it is | Why it is here |
| --- | --- | --- |
| **Development** | one broker, no security at all | The small, scruffy one. A handful of topics, a partition or two each, short retention, a leftover test topic nobody cleaned up. Its job is to be the cluster you switch *away* from. |
| **Production** | three brokers, still plain text, sized like the real thing | Replication factor 3, `min.insync.replicas=2`, partition leadership spread across brokers, and enough messages that paging, filtering and lag are real work. None of this exists on a one-broker cluster: replication has nothing to say and every leader is the same broker. |
| **Secured** | one broker behind SASL and TLS, with a private certificate authority | That a cluster needing a password and a certificate looks and behaves exactly like the others once it is configured — the security is a configuration concern, not a different product. |

Put together they give you the cluster switcher with three genuinely different clusters in it,
three capability states side by side, and — the reason the architecture is shaped the way it is —
the ability to switch one cluster off and watch the other two carry on. There is a walkthrough of
that below, and it is the part to read if you read only one.

## What is in the clusters

Each cluster is seeded before KUI starts, so no screen is empty and the numbers are the same on
every machine and on every run. The seed writes topics with real configurations, messages that were
written by a person to be read by a person, and consumer groups in deliberately different states.

Three things are worth knowing about the data.

**The same application, deployed twice.** Several topic names appear on both Development and
Production — `orders.v1`, `customers.profiles`, `audit.log.raw` — with different partition counts,
different retention and vastly different volumes. Switching clusters therefore shows you the same
topic at two sizes rather than two unrelated lists, which is what switching clusters is like in
real life.

**Consumer groups in the three states you have to be able to tell apart.** A group that is
**stopped and behind**, with lag spread unevenly across its partitions; one that is **stopped and
caught up**, so that zero lag is visibly not the same thing as no group; and one that is **live**,
held open by a real consumer process in its own container, so it has a member and a heartbeat.

That last one has to be a separate long-running container. A consumer group has members only while
some process is holding a session open with the broker; a seed job exits, and the group it created
goes empty the moment it does. The stopped groups' offsets are written directly with
`kafka-consumer-groups.sh --reset-offsets` rather than by running a consumer for a few seconds,
because a consumer racing a timer produces different lag on a fast laptop than on a loaded one, and
a demonstration whose numbers change every run is one nobody trusts.

**Deliberately awkward data.** `audit.log.raw` is not JSON, on purpose — the message browser has to
show you bytes it cannot parse and say so, rather than pretending it failed. `customers.profiles`
is compacted and contains tombstones, which a browser has to draw as absence rather than as an
empty string. There is an empty topic, because a topic list where every row has messages never
shows you what zero looks like.

[`../quickstart/seed/README.md`](../quickstart/seed/README.md) documents the seed data and the
scripts that write it.


### Avro, against a real Schema Registry

The Development cluster runs a Confluent Schema Registry, and one of its topics — `orders.avro.v1`
— holds Avro records rather than text. That distinction matters more than it sounds. An Avro record
on the wire is five bytes of header (one zero byte, then the four-byte id of a schema) followed by
Avro's binary encoding; without asking the registry for that schema, nothing can turn those bytes
back into fields, and a message browser can only show mangled text or Base64. A very large share of
production Kafka is written this way, so a KUI that could not read it would be a KUI most teams
could not use.

Open `orders.avro.v1` in the message browser and the records read as JSON, with the serde reported
as `SchemaRegistry` and the subject named. The schema screen lists the subject and its versions.

Production and Secured have no registry on purpose. "This cluster has one and that one does not" is
a real difference between clusters, and KUI has to render it as *not configured* rather than as a
failure — which is what those two clusters show.

The registry is also published on your own machine, at `http://localhost:18081` (override with
`KUI_DEMO_REGISTRY_PORT`), so `curl http://localhost:18081/subjects` works from your shell.

## What to look at first

Roughly ten minutes, in this order. Each step shows something the previous one could not.

1. **The dashboard.** Three clusters, three rows, each with its own state, its own broker count and
   the age of its last successful refresh. That per-row state is what everything below depends on.
2. **Switch to Production and open its brokers.** Three brokers, each leading a share of the
   partitions. This is the screen Development cannot produce.
3. **Open `orders.v1` on Production, then `orders.v1` on Development.** Same name, twelve
   partitions against three, a week of retention against a day, replication 3 against 1. Two
   deployments of one application, which is exactly what they are.
4. **Browse `audit.log.raw`.** Not JSON, on purpose. KUI shows the bytes as text and tells you it
   could not parse them. Being honest about what it does not understand is a property worth
   checking for in any tool that reads other people's data.
5. **Open the consumer groups on Production.** Several groups, different stories: lag spread
   unevenly across partitions on one, zero across the board on another, a live member on a third.
6. **Start the offset-reset wizard on a group that is behind, and stop before applying it.** The
   wizard shows what *would* change, partition by partition, and hands you a token standing for
   that exact plan; the apply step takes the token rather than a fresh specification, so what is
   applied is what you were shown and not what the cluster happens to look like a minute later.
7. **Switch to Secured.** Everything works the same way. That is the point of the screen.
8. **Then break something on purpose** — the next section.

## The one to actually watch: fault isolation

This is the product's central claim, and the reason KUI is built the way it is.

**The claim.** One cluster failing must not damage the others. Not "recovers quickly" and not
"shows an error page": the other clusters keep working normally, at full speed, while the failed
one explains itself on its own row and keeps its last known data visible and clearly marked as old.

**Why that is not automatic.** The obvious way to build this console is to fetch every cluster's
state before rendering a page. Do that and the slowest cluster sets the speed of the whole
application — and a cluster that has stopped answering does not return an error, it *hangs*, until
something times out, on every request, for everybody. That is how most internal tools fail: not by
crashing, but by becoming uniformly unusable because one dependency went quiet. KUI gives each
cluster its own connection, its own background refresh and its own capability state, so a failure
has a boundary.

### Watch it happen

**Step 1 — the baseline.** With everything running, open the dashboard. Three clusters, all
healthy. Leave the browser on this page; you will not need to reload it.

**Step 2 — stop one cluster.** In another terminal:

```
deployment/demo/demo.sh stop prod
```

That stops the three production brokers and nothing else. KUI is untouched, and so are the other
two clusters. The containers are stopped rather than removed, so their data survives and `start`
brings back the same cluster rather than an empty one.

**Step 3 — watch what happens, and what does not.** Within one refresh interval — about half a
minute, because KUI refreshes in the background instead of blocking your page on a network call:

- the **Production** row becomes unavailable, with the reason and the age of its last good
  snapshot;
- **Development** and **Secured** are unchanged and stay fully responsive. Click into them: topics
  list, messages browse, groups show lag. Nothing is slower;
- KUI has not restarted, no page has gone blank, and nothing is spinning.

**Step 4 — open the cluster that is down.** This is the part most tools get wrong. Its pages are
still reachable. You see the last data KUI had, marked stale with its age, next to a plain
statement of what is wrong. You are not bounced back to the dashboard and you are not shown a
skeleton loader that never resolves. *"I cannot reach this cluster; here is what I last knew; it
was four minutes ago"* is a useful answer. A spinner is not.

**Step 5 — bring it back.**

```
deployment/demo/demo.sh start prod
```

Within a refresh interval the row returns to healthy on its own — or immediately if you press
refresh on the cluster. Nothing needs restarting, no cache needs clearing, and you do not reload
the browser. Recovery is as undramatic as the failure was, which is the point: the failure was
contained, so there is nothing to recover *from* except the cluster itself.

**Step 6 — the smaller, more interesting failure.** A whole cluster vanishing is the easy case.
Try one broker of three instead:

```
deployment/demo/demo.sh stop prod-broker
```

The cluster stays up. Its two survivors elect new leaders for the partitions the stopped broker
led, and KUI shows what that costs: topics with three replicas now have two in sync and are
reported as **under-replicated**, and the ones configured with `min.insync.replicas=2` are sitting
exactly at their limit — one more broker gone and they would start refusing writes. This is the
state an operator is actually paged about, and it is a state, not an outage. `start prod-broker`
puts it back.

**Step 7 — and secured.** Try `stop secured` too. A cluster that fails while authenticating fails
differently from one whose brokers are simply gone, and the message says which. An expired
certificate and an unplugged broker must never look identical in a console: they are not the same
problem and they do not have the same fix.

### What this is not

It is not a claim that KUI hides failure. A cluster that is down is shown as down, prominently, on
every screen that depends on it. The claim is narrower and more useful: the *blast radius* of that
failure is one cluster.

It is also not the same demonstration as the one in [`../compose/`](../compose/README.md). That one
kills KUI's **own services** — the gateway keeps serving while a feature's service is dead, and the
sidebar greys out the parts that cannot work. This one kills **clusters**. Both matter and they are
different properties; this stack runs KUI as a single all-in-one process precisely so that nothing
about KUI's own topology is involved in what you are watching.

## When the default ports are taken

`18080` is KUI. The development and production brokers are also published, so your own tools —
`kcat`, an IDE, a console consumer — can reach them on `19092` and `19093`–`19095`. If something is
already listening, pass your own numbers:

```
KUI_PORT=28080 KUI_DEMO_DEV_PORT=29092 deployment/demo/demo.sh
```

The script checks the ports before starting anything and, if one is busy, tells you which and
suggests free numbers, rather than letting Docker Compose fail halfway through with a message about
a container that means nothing to you. The variables are `KUI_PORT`, `KUI_DEMO_DEV_PORT` and
`KUI_DEMO_PROD_PORT_1` … `_3`.

Those broker ports are only for tools on *your machine*. KUI reaches the brokers across the private
Compose network by container name, so it is unaffected by which host port you pick. That is also
why each broker advertises two addresses, one for containers and one for you: a Kafka client always
reconnects to the address the broker advertises rather than the one it was originally given, which
is what produces the classic failure where the first connection works and every one after it hangs.

**The secured broker publishes no host port**, deliberately. A TLS listener reached through a
published port would have to advertise `localhost`, and then the certificate, the advertised
address and the address KUI connects to would all have to be made to agree — solvable, and a
distraction from what that cluster is here to show. Use the other two for your own tools.

## The commands

```
deployment/demo/demo.sh                    start everything and print the URL
deployment/demo/demo.sh status             what is running
deployment/demo/demo.sh logs               follow every container's log
deployment/demo/demo.sh stop <what>        stop one thing; see below
deployment/demo/demo.sh start <what>       start it again, with the data it had
deployment/demo/demo.sh down               remove containers, network and volumes
```

`<what>` is one of:

| | |
| --- | --- |
| `dev` | the single-broker development cluster |
| `prod` | the whole three-broker production cluster |
| `prod-broker` | one broker of the production cluster, leaving two running |
| `secured` | the SASL/TLS cluster |

## How KUI is configured here

One process, one file: [`kui-demo.yaml`](kui-demo.yaml). Read it — it is mostly explanation, and it
is the whole of the "zero configuration" promise, because it ships already naming all three
clusters. Nobody types an address, generates a password or copies a certificate.

The secured cluster's password and truststore password are written in it as `env:` **references**
rather than as values, and the Compose file supplies them. That is not ceremony for a
demonstration: it is the habit that makes a configuration file safe to commit, and the file is
written the way a real one should be so that copying it teaches the right thing.

Two settings in it are demonstration-only and are marked as such in the file:
`server.devInsecureCookies`, which plain HTTP on `localhost` needs and which no deployment reachable
by a network may ever have, and the fact that two of the three clusters have no authentication at
all.

**To copy something for your own use, copy an example instead**, and the examples are checked by
KUI's own loader on every build so they cannot quietly go stale:

| File | What it is |
| --- | --- |
| [`../examples/three-clusters.yaml`](../examples/three-clusters.yaml) | The same three-cluster shape as this stack, written to be copied: one plaintext cluster, one multi-broker cluster with the per-cluster tuning a large cluster wants, one secured. |
| [`../examples/minimal.yaml`](../examples/minimal.yaml) | One cluster, no security. The shortest file that works. |
| [`../examples/production.yaml`](../examples/production.yaml) | The distributed topology: separate gateway and service processes, shared signing keys, and KUI's own Kafka-backed metadata store. |

Every key KUI reads, with its type, its default, whether it is required and what happens when it is
wrong, is in [`../../docs/operations/configuration.md`](../../docs/operations/configuration.md).

## Pointing KUI at your own clusters

Copy `../examples/three-clusters.yaml`, replace the `bootstrapServers` lines with your own
addresses, and run the all-in-one image with your file mounted. The header of that file gives the
exact command and names the environment variables it expects. Nothing about this demonstration is
special: it is the shipped image, reading a shipped configuration, against ordinary Kafka brokers.

## How this relates to the other deployment directories

- [`../quickstart/`](../quickstart/README.md) — one cluster, thirty seconds, the smallest possible
  first look, and the seed data this stack reuses.
- [`../secured/`](../secured/README.md) — the SASL/TLS broker on its own, and
  `generate-certs.sh`, which this stack runs for you when `../secured/certs` is missing. Use it
  when you are changing something under `kui.clusters[].security` and want the shortest loop that
  exercises it.
- [`../compose/`](../compose/README.md) — the development and test topology, including the
  distributed shape, and the fault-isolation demonstration for KUI's *own* services.
- [`../examples/`](../examples/) — the configuration files to copy.
- [`../docker/`](../docker/README.md) — the published images and the conventions they follow.
