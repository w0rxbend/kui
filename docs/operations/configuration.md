# Configuring KUI

Everything KUI reads at startup is in this document. There is no hidden default file, no system
property KUI looks at behind your back, and no key that is silently ignored — a key KUI does not
recognise fails the load and names itself.

If you would rather start from a file than a table, there are two you can copy:

| File | What it is |
| --- | --- |
| [`deployment/examples/minimal.yaml`](../../deployment/examples/minimal.yaml) | The simplest thing that works: one cluster, no security. |
| [`deployment/examples/production.yaml`](../../deployment/examples/production.yaml) | Several clusters, one secured with SASL over TLS, every secret read from the environment. |

Both were loaded through KUI's own configuration loader, under the strict production URL policy,
before being committed — an example that has never been loaded is a guess, and a wrong example
fails on somebody's first run, which is the worst possible moment. Nothing yet loads them
automatically on every build; wiring them into `libs.config.test` so they cannot drift is tracked
as follow-up work.

> **Where the product is today.** Milestone 0 is complete: KUI builds, runs as one process or as
> separate containers, serves its interface, and reports which of its services are reachable. It
> does **not** connect to Kafka yet — that is M1. So a configuration file with clusters in it loads
> today and KUI reports the cluster capability as *not yet available*. Keys in the tables below
> that belong to unfinished milestones are marked **M1** or **M6**, and the marking says whether
> the key is *accepted and ignored* or *not accepted at all*. The difference matters: one of them
> starts, the other refuses to.

## Where a value can come from

Four places, in order of who wins:

1. **A command-line flag** — `--kui.server.port=9090`, or `--kui.server.port 9090`. The `kui.`
   prefix is optional, so `--server.port=9090` is the same flag. `--config <path>` (or
   `--config=<path>`) adds a YAML file.
2. **An environment variable** — the key in capitals with `.` and `-` replaced by `_`:
   `kui.server.port` is `KUI_SERVER_PORT`, and `kui.gateway.services.schema-registry.url` is
   `KUI_GATEWAY_SERVICES_SCHEMA_REGISTRY_URL`.
3. **A YAML file** — one or more. A later file overrides an earlier one, and files named by
   `--config` come after the ones the process was started with.
4. **The built-in default** — listed for every key in the tables below.

A *bad* value in a higher-precedence place is an error, not a reason to fall through to the next
one. If `KUI_SERVER_PORT=abc`, KUI refuses to start; it does not quietly use the port from the
file, because you would have no way of noticing.

The environment is a first-class layer, not a way of patching a file. A deployment with no YAML
file at all is legitimate:

```
$ KUI_GATEWAY_SERVICES_CLUSTER_URL=https://kui-cluster.example.com kui-gateway
```

is enough to define an upstream service, with `timeout` and `maxConcurrent` taking their defaults.

## When something is wrong

KUI reports **every** problem it found, one per line, in key order, and exits with status 1. Each
line names the key, what was expected, what was found, and which of the four places supplied it:

```
kui.gateway.readinessIntervalMs: expected a positive number of milliseconds; '-1' is not a positive number of milliseconds (found '-1')   (file: /etc/kui/kui.yaml)
kui.gateway.services.cluster.maxConcurrent: expected a positive whole number; positiveInt must be at least 1, got '0' (found '0')   (file: /etc/kui/kui.yaml)
kui.gateway.services.cluster.timeout: expected a duration such as 10s; 'soon' is not a duration such as 10s or 500ms (found 'soon')   (file: /etc/kui/kui.yaml)
kui.server.basePath: expected a path such as / or /kui; must not be empty (found '')   (file: /etc/kui/kui.yaml)
kui.server.port: expected a port between 1 and 65535; port must be between 1 and 65535, got '70000' (found '70000')   (file: /etc/kui/kui.yaml)
kui.telemetry.hashUserIds: expected true or false; 'maybe' is not a boolean (found 'maybe')   (file: /etc/kui/kui.yaml)
```

There is no partially-valid start. A gateway running with three of its four upstreams configured is
harder to diagnose than one that refused to boot and told you why.

### Two stages, not one

Loading the configuration and wiring the process are separate steps, and a setting can be rejected
by either. The distinction is worth knowing when a message does not look like the ones above:

- **Load** checks that every key exists, that every value parses, and that every secret reference
  resolves. This is what produces the report above.
- **Wiring** checks the things that only make sense once the values are in hand — that a signing
  key is long enough for HS256, that a service was given the keys it needs at all. These arrive as
  a single sentence on startup, after the configuration has already been accepted.

A signing key that is too short is the case people hit. The loader accepts it, because it is a
perfectly well-formed string; wiring then refuses it:

```
signing key '2026-01' is 40 bits; HS256 needs at least 256
```

## The keys

Types: **string**, **int**, **boolean** (`true`/`yes`/`on` and `false`/`no`/`off`), **duration**
(`10s`, `500ms`), **secret** (see [Secrets](#secrets)), **instant** (RFC 3339, e.g.
`2026-01-01T00:00:00Z`).

### `kui.server` — how this process listens

Read by every KUI process.

| Key | Type | Default | Required | What happens when it is wrong or missing |
| --- | --- | --- | --- | --- |
| `kui.server.host` | string | `0.0.0.0` | no | A host name or IP address. Anything else fails the load. `0.0.0.0` is every interface, which is what a container needs — binding `127.0.0.1` inside a container makes the process unreachable from outside it. |
| `kui.server.port` | int | `8080` | no | Must be 1–65535. A port already in use fails the start; KUI never picks another one for you. |
| `kui.server.basePath` | string | `/` | no | The prefix every route is served under, for a reverse proxy that mounts KUI at `/kui`. Must not be empty — write `/` for no prefix. |
| `kui.server.devInsecureCookies` | boolean | `false` | no | Omits `Secure` from the session cookie so plain HTTP on localhost works. The process warns on every start while it is on. Never set it anywhere the network can reach. |

### `kui.gateway` — upstreams, identity and CORS

`services` and `cors` are read by the gateway. `principalKeys` is read by **every** process; see the
note under that table.

| Key | Type | Default | Required | What happens when it is wrong or missing |
| --- | --- | --- | --- | --- |
| `kui.gateway.services.<id>.url` | string (URL) | — | **yes**, once `<id>` is mentioned at all | The address of one downstream KUI service. Checked against the [URL policy](#which-urls-kui-will-call). Listing a service and omitting its URL fails the load with `kui.gateway.services.<id>.url is required`. |
| `kui.gateway.services.<id>.timeout` | duration | `10s` | no | The whole-call budget for that service. Must be positive and finite. |
| `kui.gateway.services.<id>.maxConcurrent` | int | `32` | no | The bulkhead: how many calls to that service may be in flight at once. Must be at least 1. It is what stops one slow service from consuming every thread and taking the others down with it. |
| `kui.gateway.readinessIntervalMs` | int (ms) | `10000` | no | How often the gateway polls each service's readiness, which is what keeps the greyed-out parts of the interface honest. Must be positive. |
| `kui.gateway.principalKeys.<n>.kid` | string | — | **yes**, once a key is listed | The key id that travels in the signed principal header. |
| `kui.gateway.principalKeys.<n>.key` | secret | — | **yes**, once a key is listed | The shared signing secret. Must resolve to a non-empty value of **at least 32 bytes** — a shorter key is refused at wiring, not at load (see [Two stages](#two-stages-not-one)). |
| `kui.gateway.principalKeys.<n>.notBefore` | instant | `1970-01-01T00:00:00Z` | no | When this key becomes usable for *signing*, which is what makes rotation a rolling change. Must be RFC 3339. |
| `kui.gateway.cors.enabled` | boolean | `false` | no | Whether pages from other origins may call this API. Off, because the gateway serves the interface from the same origin. |
| `kui.gateway.cors.origins` | list of string | *(empty)* | no | The explicit allow-list; comma-separated in the environment. `*` is refused at load: combined with credentials it would let any website read a signed-in user's Kafka data. |

**`principalKeys` is not a gateway setting despite its name.** It is the shared key set of one
deployment. The gateway signs the internal `X-Kui-Principal` header with the newest key whose
`notBefore` has passed, and every service accepts any key in the list (ADR-020). Give every process
the same list, reading the same secret. If they disagree, every call the gateway makes comes back
401.

**A service started with no keys refuses to start.** One that started anyway would trust an
`X-Kui-Principal` header from anyone who could reach its port, and it would do it silently:

```
kui-cluster cannot start; no principal signing keys are configured. A service that starts
without them would trust an X-Kui-Principal header from anyone who can reach its port.
Configure kui.gateway.principalKeys, or set KUI_ALLOW_UNSIGNED=true for local development only.
```

`KUI_ALLOW_UNSIGNED=true` accepts unsigned headers and writes a warning to the log every minute for
as long as it is in effect. It is an environment variable rather than a configuration key on
purpose: a security relaxation should be visible in the process's environment, where an operator or
an auditor sees it in one place, and impossible to arrive at by accident inside a large YAML file
somebody copied.

### `kui.telemetry` — logs, traces and metrics

Read by every KUI process.

| Key | Type | Default | Required | What happens when it is wrong or missing |
| --- | --- | --- | --- | --- |
| `kui.telemetry.otlpEndpoint` | string (URL) | *(unset)* | no | Where traces and metrics are exported. Unset means export nothing. A collector that is down or misconfigured never stops KUI starting or serving. Checked against the [URL policy](#which-urls-kui-will-call). |
| `kui.telemetry.prometheusPort` | int | *(unset)* | no | An extra port exposing **this process's own** telemetry in Prometheus format. Not the product's Kafka-cluster metrics endpoint; ADR-009 keeps those separate. Must be 1–65535. |
| `kui.telemetry.logFormat` | `json` \| `text` | `json` | no | `json` writes one object per line for a log system; `text` writes a short line for a human at a terminal. Anything else fails the load. |
| `kui.telemetry.hashUserIds` | boolean | `true` | no | Log and trace `user.id` as a salted hash rather than the login name. |

### `kui.auth` — authentication

| Key | Type | Default | Required | What happens when it is wrong or missing |
| --- | --- | --- | --- | --- |
| `kui.auth.type` | string | `disabled` | no | **`disabled` is the only accepted value today.** Anything else fails the load naming M6, rather than being ignored — so a file that asks for authentication can never quietly start a deployment that has none. |

### `kui.store` — KUI's own metadata store

Where KUI keeps the things it learns at runtime: registered clusters, settings, uploaded files.
`kui.store.kafka.bootstrapServers` is the on/off switch — set it and KUI uses compacted Kafka
topics, leave it unset and KUI reads a directory (or nothing) and reports every write as
`NotConfigured`. There is no separate `enabled` flag on purpose: two settings that have to agree
are two settings that will eventually disagree. See
[the metadata-store page](metadata-store.md) for the topics, the encryption key and the backup
procedure.

| Key | Type | Default | Required | What happens when it is wrong or missing |
| --- | --- | --- | --- | --- |
| `kui.store.topicPrefix` | string | `__kui_` | no | Every store topic name is built from it, so `__kui_config` becomes `<prefix>config`. Must match `^[a-z0-9_.-]{1,64}$`. Change it when two KUI installations share one cluster. |
| `kui.store.replicationFactor` | short | `3` | no | Used only when KUI *creates* a topic; an existing topic is never rewritten. `1` is accepted, because single-broker development is a supported mode. |
| `kui.store.minInSyncReplicas` | int | `2` | no | Must be at least 1 and no greater than `replicationFactor`; breaking that fails the load with both values in one message. |
| `kui.store.maxFileBytes` | long | `4194304` | no | The cap on one uploaded file. 1 KiB … 64 MiB. The broker's own `max.message.bytes` has to exceed it. |
| `kui.store.replayTimeout` | duration | `30s` | no | How long startup waits for the store's log to be replayed to its end before failing with `KUI-STORE-REPLAY-TIMEOUT`. 1s … 10m. This bound is what turns a hung startup into a named error. |
| `kui.store.writeTimeout` | duration | `10s` | no | How long a write waits to read its own record back from the log before giving up. 1s … 2m. |
| `kui.store.dir` | path | *(unset)* | no | The read-only file adapter's root, laid out as `<root>/<section>/<id>.json`. A path that does not exist is **not** an error: it is an empty store, and a volume that mounts a moment after the process starts is a real thing. |
| `kui.store.kafka.bootstrapServers` | list of string | *(unset)* | no | `host:port` entries; a YAML list, or comma-separated in the environment. **Setting it turns the Kafka store on.** |
| `kui.store.kafka.security.*` | — | `PLAINTEXT` | no | The same typed security model as a managed cluster (ADR-022): `protocol`, `mechanism`, `username`, `password`, the `ssl.*` block. See the `kui.clusters` table below for the full key list; the two are decoded by the same code and accept the same spellings. |
| `kui.store.kafka.properties.<name>` | map of string | *(empty)* | no | Raw Kafka client properties for the store's own clients, applied last. Not settable from the environment: a Kafka property name contains dots the `KUI_*` mapping cannot round-trip. Values whose key looks like a credential are redacted in every log line. |
| `kui.store.encryptionKey` | secret | *(unset)* | with the Kafka store | 32 random bytes, base64. `openssl rand -base64 32`. Takes a literal, `env:NAME` or `file:/path`. **Required whenever `kui.store.kafka.*` is set** — starting without it would work until the first secret and then fail at write time, which is the worst place to find out. |
| `kui.store.encryptionKeys` | secret | *(unset)* | no | The rotation form: `id:base64,id:base64`. Mutually exclusive with `encryptionKey`; setting both fails the load rather than merging them. |
| `kui.store.encryptionKeyId` | string | `k1` | with `encryptionKeys` | Which key new writes are encrypted under. Every key listed stays usable for *reading*, which is what makes a rotation a rolling change rather than a flag day. An id that is not among the configured keys fails the load, listing the ids that are. |

**Losing `kui.store.encryptionKey` makes every stored secret permanently unreadable.** There is no
recovery path and there cannot be one — that is what encryption means. Back the key up separately
from the topic, and read
[the metadata-store page's key section](metadata-store.md#42-the-encryption-key) before you rotate.

### `kui.clusters` — the clusters KUI manages

This is the list of Kafka clusters KUI connects to. Nothing here is checked against a broker at
startup: a cluster that is spelled correctly and unreachable is a valid configuration, and shows on
the dashboard as `Unavailable: <reason>`. If a bad address stopped KUI from starting, one dead
broker would take the whole console down.

Repeated sections use a dotted index, so one key has one spelling everywhere:

```yaml
kui:
  clusters:
    - name: Production EU
      bootstrapServers: broker-1.eu:9093,broker-2.eu:9093
      security:
        protocol: SASL_SSL
        mechanism: SCRAM-SHA-512
        username: kui
        password: env:KUI_PROD_PASSWORD
```

The same cluster, entirely from the environment:

```
KUI_CLUSTERS_0_NAME=Production EU
KUI_CLUSTERS_0_BOOTSTRAPSERVERS=broker-1.eu:9093,broker-2.eu:9093
KUI_CLUSTERS_0_SECURITY_PROTOCOL=SASL_SSL
KUI_CLUSTERS_0_SECURITY_MECHANISM=SCRAM-SHA-512
KUI_CLUSTERS_0_SECURITY_USERNAME=kui
KUI_CLUSTERS_0_SECURITY_PASSWORD=env:KUI_PROD_PASSWORD
```

The index must start at `0` and have no gaps. `kui.clusters.0` and `kui.clusters.2` with no `1` is
refused, naming the gap, because it nearly always means a deleted entry or a mistyped variable name
— and renumbering silently would hide both.

| Key | Environment name | Default | Meaning |
| --- | --- | --- | --- |
| `kui.clusters.<n>.name` | `KUI_CLUSTERS_<N>_NAME` | *(required)* | The display name. 1–64 characters. |
| `kui.clusters.<n>.id` | `KUI_CLUSTERS_<N>_ID` | *(slug of `name`)* | The URL slug. See "Renaming a cluster" below. |
| `kui.clusters.<n>.bootstrapServers` | `…_BOOTSTRAPSERVERS` | *(required)* | `host:port` entries; a YAML list, or comma-separated. |
| `kui.clusters.<n>.readOnly` | `…_READONLY` | `false` | Declared now, enforced in M5. Recorded on the profile and shown in the UI. |
| `kui.clusters.<n>.security.protocol` | `…_SECURITY_PROTOCOL` | `PLAINTEXT` | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT` or `SASL_SSL`. `SASL_PLAINTEXT` authenticates but does **not** encrypt. |
| `kui.clusters.<n>.security.mechanism` | `…_SECURITY_MECHANISM` | *(required for the two SASL protocols)* | See the mechanism table below. |
| `kui.clusters.<n>.security.username` | `…_SECURITY_USERNAME` | *(required for PLAIN and SCRAM)* | |
| `kui.clusters.<n>.security.password` | `…_SECURITY_PASSWORD` | *(required for PLAIN and SCRAM)* | A secret: literal, `env:NAME` or `file:/path`. |
| `kui.clusters.<n>.security.serviceName` | `…_SECURITY_SERVICENAME` | *(required for GSSAPI)* | `sasl.kerberos.service.name`. |
| `kui.clusters.<n>.security.principal` | `…_SECURITY_PRINCIPAL` | *(required for GSSAPI)* | |
| `kui.clusters.<n>.security.keytab` | `…_SECURITY_KEYTAB` | *(unset)* | A path inside the container. |
| `kui.clusters.<n>.security.useTicketCache` | `…_SECURITY_USETICKETCACHE` | `false` | Use an existing Kerberos ticket cache instead of a keytab. |
| `kui.clusters.<n>.security.tokenEndpoint` | `…_SECURITY_TOKENENDPOINT` | *(required for OAUTHBEARER)* | |
| `kui.clusters.<n>.security.clientId` | `…_SECURITY_CLIENTID` | *(required for OAUTHBEARER)* | |
| `kui.clusters.<n>.security.clientSecret` | `…_SECURITY_CLIENTSECRET` | *(required for OAUTHBEARER)* | A secret. |
| `kui.clusters.<n>.security.scope` | `…_SECURITY_SCOPE` | *(unset)* | |
| `kui.clusters.<n>.security.profile` | `…_SECURITY_PROFILE` | *(unset)* | AWS named profile, `AWS_MSK_IAM` only. |
| `kui.clusters.<n>.security.roleArn` / `.stsRegion` | `…_SECURITY_ROLEARN` / `…_STSREGION` | *(unset)* | `AWS_MSK_IAM` only. |
| `kui.clusters.<n>.security.namespace` | `…_SECURITY_NAMESPACE` | *(required for AZURE_ENTRA)* | The Event Hubs namespace. |
| `kui.clusters.<n>.security.ssl.truststore.location` | `…_SECURITY_SSL_TRUSTSTORE_LOCATION` | *(unset)* | A path. Mutually exclusive with `inline`. |
| `kui.clusters.<n>.security.ssl.truststore.inline` | `…_SECURITY_SSL_TRUSTSTORE_INLINE` | *(unset)* | Base64 of the store's bytes. A secret. |
| `kui.clusters.<n>.security.ssl.truststore.password` | `…_SECURITY_SSL_TRUSTSTORE_PASSWORD` | *(unset)* | A secret. |
| `kui.clusters.<n>.security.ssl.truststore.type` | `…_SECURITY_SSL_TRUSTSTORE_TYPE` | `PKCS12` | `PKCS12`, `JKS` or `PEM`. |
| `kui.clusters.<n>.security.ssl.keystore.*` | `…_SECURITY_SSL_KEYSTORE_*` | *(unset)* | The same four keys, for mutual TLS. |
| `kui.clusters.<n>.security.ssl.keyPassword` | `…_SECURITY_SSL_KEYPASSWORD` | *(unset)* | A secret. Must be a literal here. |
| `kui.clusters.<n>.security.ssl.verifyHostname` | `…_SECURITY_SSL_VERIFYHOSTNAME` | `true` | Leave it on. `false` also removes the check that the broker is who it claims to be. |
| `kui.clusters.<n>.security.ssl.enabledProtocols` / `.cipherSuites` | `…` | *(unset)* | Comma-separated lists. |
| `kui.clusters.<n>.admin.requestTimeout` | `…_ADMIN_REQUESTTIMEOUT` | `30s` | How long one request to a broker may take. Becomes `request.timeout.ms`. |
| `kui.clusters.<n>.admin.apiTimeout` | `…_ADMIN_APITIMEOUT` | `60s` | The whole-call budget, the client's own retries included. Becomes `default.api.timeout.ms`. |
| `kui.clusters.<n>.admin.chunkSize` | `…_ADMIN_CHUNKSIZE` | `200` | How many topics, partitions or config resources go into one admin request. |
| `kui.clusters.<n>.admin.groupChunkSize` | `…_ADMIN_GROUPCHUNKSIZE` | `50` | The same, for consumer groups. Read and validated today; the first code that uses it ships in M4. |
| `kui.clusters.<n>.admin.parallelism` | `…_ADMIN_PARALLELISM` | `4` | How many chunks are in flight at once against this one cluster. |
| `kui.clusters.<n>.properties.<kafka.property>` | *(not settable from the environment)* | *(empty)* | Raw Kafka client properties, applied last. |

**Tuning one cluster without touching the others.** The five keys under `admin` are per cluster, so
a cluster with ten thousand topics, or a broker on the other side of an ocean, can be tuned on its
own. The section is optional, and so is every key in it: `admin: { parallelism: 8 }` leaves the
other four at their defaults rather than resetting them.

| Key | Accepted range |
| --- | --- |
| `requestTimeout` | 1s … 5m |
| `apiTimeout` | 1s … 15m, and at least as long as `requestTimeout` |
| `chunkSize` | 1 … 1000 |
| `groupChunkSize` | 1 … 1000 |
| `parallelism` | 1 … 32 |

The defaults are not round numbers somebody liked. 30s and 60s are the Kafka client's own; 200, 50
and 4 are the values Kafbat arrived at after hitting the failures
`research/kafka/admin-capabilities.md` §0 records. Start from them.

If a cluster is timing out, reach for `chunkSize` **down** before `requestTimeout` **up**. A smaller
request that succeeds is better than a larger one that eventually does not, and the admin client has
a single network thread, so a longer timeout on a big request also holds up everything queued behind
it.

`apiTimeout` shorter than `requestTimeout` is refused rather than clamped. It describes a client
that gives up before its own single request can finish, which on a dashboard looks exactly like a
broken cluster. The error names the other key and its value — including when that value is the
default, which is the half an operator cannot see for themselves.

**Mechanism spellings, and how far each one is tested.** The values are upper-case and are exactly
the ones Kafka's own documentation uses, so they can be copied across without translation. The last
column is the honest answer to "does this actually work": a mechanism that is only unit-tested has
had its rendered client properties checked against the vendor's documented example, and has never
been pointed at a live broker by KUI's CI.

| `mechanism` | Also requires | Integration-tested against a real broker |
| --- | --- | --- |
| `PLAIN` | `username`, `password` | **yes** — SASL_PLAINTEXT container |
| `SCRAM-SHA-256` | `username`, `password` | no — the SHA-512 variant is, and the code path is shared |
| `SCRAM-SHA-512` | `username`, `password` | **yes** — SASL_PLAINTEXT container |
| `GSSAPI` | `serviceName`, `principal`, and `keytab` or `useTicketCache` | no — property rendering only (needs a KDC) |
| `OAUTHBEARER` | `tokenEndpoint`, `clientId`, `clientSecret` | no — property rendering only (needs an identity provider) |
| `AWS_MSK_IAM` | *(optional `profile`, `roleArn`, `stsRegion`)* | no — property rendering only (needs AWS) |
| `AZURE_ENTRA` | `namespace` | no — property rendering only (needs Azure) |
| `GCP` | *(nothing)* | no — property rendering only (needs Google Cloud) |

A `mechanism` set under `PLAINTEXT` or `SSL` is **refused**, not ignored. Ignoring it is how an
operator ends up with an unauthenticated connection they believe is authenticated: the credentials
were in the file, and nothing ever sent them.

**Renaming a cluster.** The id is what appears in every URL, every bookmark and every future RBAC
rule. By default it is derived from the name (ADR-031): `Production EU` becomes `production-eu`.
That means fixing a typo in a display name would otherwise change the id and break those links, so
set `id` explicitly and the name becomes free to edit:

```yaml
    - name: Production EU (Frankfurt)
      id: prod-eu
```

Two clusters whose names produce the same id are refused at startup, naming both, because one of
the two would otherwise be silently unreachable. A name with no letters or digits in it — `***`, or
a name written entirely in a non-Latin script — is also refused, with the same instruction to set
`id` explicitly: inventing `cluster-1` would put an identifier the operator never chose into their
URLs.

**The `properties` escape hatch.** Whatever the typed keys above render, the entries under
`properties` are applied last and win. It exists so a broker setting KUI has no typed key for — or
a mechanism it has not modelled yet — is usable without waiting for a release:

```yaml
      properties:
        ssl.cipher.suites: TLS_AES_256_GCM_SHA384
        sasl.login.callback.handler.class: com.example.MyHandler
```

Two things to know about it. It is **file-only**: a Kafka property name contains dots, and the
`KUI_*` environment mapping replaces dots with underscores, so `ssl.cipher.suites` could not be
spelled back out again — an environment variable under `properties` is therefore an error that says
so rather than a setting that quietly does nothing. And a value whose key looks like a credential
(anything containing `password`, `secret`, `key`, `token`, `credential`, `jaas`, `passwd` or
`auth`) is redacted in every log line and diagnostic. A secret inside `properties` still uses
`env:NAME`; the *value* travels through the environment, only the *key* cannot.

### `kui.clusterProfiles` — how a Kafka-facing service reaches the cluster service

Every KUI service that opens a Kafka connection — topics, and later messages, consumer groups and
security — gets the connection settings for a cluster from the **cluster service**, over
`/internal/v1`, rather than reading `kui.clusters[]` itself. Only the cluster service reads the
metadata store and only it holds `kui.store.encryptionKey` (ADR-036, ADR-046). These four keys are
what such a service reads; none of them is required, and the defaults are what a normal deployment
wants.

```yaml
kui:
  clusterProfiles:
    pollInterval: 60s          # how often the cluster list is re-read even when the change stream is up
    requestTimeout: 5s         # how long one profile fetch may take
    reconnectBackoff: 1s       # the first delay after the change stream drops
    maxReconnectBackoff: 30s   # the cap on that delay
```

`pollInterval` is a **fallback**, not the primary mechanism. With the change stream open, an edit to
a cluster reaches every service in milliseconds; the poll is what bounds the damage when the stream
has died without either end noticing, which is what a middlebox dropping an idle socket looks like —
exactly like a quiet cluster. Lowering it makes a broken stream less visible in its effects and does
not make a working one faster.

`maxReconnectBackoff` is capped at thirty seconds on purpose. A client that had backed off to ten
minutes would take ten minutes to notice a recovery that happened one second after its last attempt.

**What an operator must not do.** `/internal/v1` carries the clusters' credentials on this channel
and must not be reachable from outside the deployment network — not through an ingress, a load
balancer, or a service mesh gateway that terminates external traffic. `/api/v1` is the
browser-facing surface and carries no credential on any endpoint of any service. See
`docs/operations/metadata-store.md` §4.3 for the full statement and for the two mechanisms that
enforce it.

**When the cluster service is down.** A consuming service keeps working from the last profile it
saw, and reports its capability as `Degraded` with the reason and how long it has been failing. It
does not fail requests and it does not refuse to start: a service that crash-looped because the
cluster service was briefly restarting would turn one outage into two, and would make container boot
order a correctness requirement.

### `kui.rbac` — the authorization model (**M6**)

**Accepted and ignored.** Any key under `kui.rbac` loads today and is read by nothing, so a file
written for M6 still starts a build that has no authorization model yet.

## Which URLs KUI will call

Every URL-shaped key is checked before KUI will use it, because a URL in a configuration file is a
URL KUI's own network position will fetch. That makes each one a server-side request forgery risk:
the classic attack points KUI at `http://169.254.169.254/`, the address a cloud instance uses to
hand out its own credentials, and reads the answer back through KUI.

- `http` and `https` only. There is no development exception for schemes.
- No credentials in the URL (`http://user:pass@host`). Put them in their own keys, where they can
  be redacted.
- By default, no address that points at this machine (`localhost`, `127.0.0.1`, `[::1]`) and no
  address that is not routable on the public internet — the private ranges, the link-local range,
  and the cloud metadata addresses inside it. The disguises are caught too: `2130706433`,
  `0x7f000001`, `017700000001`, `127.1` and `[::ffff:127.0.0.1]` all reach the same host as
  `127.0.0.1`, and a check that refused one spelling and accepted another would not be a check.

**Only address literals are examined; a host name is never resolved.** So
`http://kui-cluster.kui.svc.cluster.local:8080` is accepted under the strict policy even though it
resolves to a private address on the network where it runs. This is deliberate: it means a
configuration file validates identically on every machine, rather than passing on a laptop and
failing in the cluster because a resolver disagreed. Ordinary service discovery keeps working, and
the rule still stops the literal addresses that make SSRF worth attempting.

The last rule is relaxed by `KUI_ALLOW_PRIVATE_UPSTREAMS=true` — exactly that value, nothing else
counts — which is what the Docker Compose topology and local development use, where every upstream
really is on loopback or a private network. Like `KUI_ALLOW_UNSIGNED`, it is an environment
variable so that the relaxation is visible in one place. It is not relaxed in production.

## Secrets

Any key that holds a secret accepts three forms:

```yaml
key: "s3cret"                  # a literal. Fine on a laptop; it is in version control anywhere else.
key: "env:KUI_SIGNING_KEY"     # read from an environment variable at startup
key: "file:/run/secrets/kui"   # read from a mounted file, which is how Kubernetes and Compose
                               # deliver secrets
```

A reference is resolved during the load, so a missing one is a startup failure that names the
variable rather than an empty secret nobody notices:

```
kui.gateway.principalKeys.0.key: references environment variable KUI_PRINCIPAL_KEY, which is not set
```

A `file:` reference is read and trimmed; one that cannot be read says only that it could not be
read, and never echoes the file's contents. A reference that resolves to an empty value is an
error, not an empty secret.

**The guarantee: a secret never appears in a log line, a span attribute, an error envelope or an
HTTP response body.** It is enforced by the type, not by a filter over the output: `Secret[A]` in
`libs/kernel` prints as `***` and has no other rendering, so there is no formatter to forget to
configure. `SecretRedactionSuite` in `libs/config/test` takes one secret value and asserts it is
absent from all four sinks at once — plus a negative control that the same value *without* the
wrapper does appear, so the test can fail.

If you add a fifth place a configuration value can end up — a metrics attribute, a health payload,
an audit record — add a case to that suite.

A configuration problem reported for a secret key prints `***` in place of the value. A YAML syntax
error reports only the parser's position and never the offending line, because at that point
nothing has been decoded and the redaction that protects every other path cannot apply — an
unclosed quote on a signing-key line would otherwise print the key into `docker logs`.

## Empty sections are legal

`services: {}`, `principalKeys: []`, `origins: []`, or a `telemetry:` block whose keys are all
commented out, are all accepted. An empty container supplies no value, so it cannot be a wrong
value, and refusing it would refuse a file that says exactly what it means. A **scalar** where a
section belongs (`telemetry: 7`) is still a mistake and is still named.

One shape that looks empty and is not: an indentation slip turns the list entry `- kid: k1` into a
map key `k1:`. That is reported as `kui.gateway.principalKeys.first: is not a list entry` rather
than being dropped, because a silently empty key list means a service refusing to start while the
operator is looking straight at a key in the file.

## What the cluster service reads

Every KUI process loads the same file and takes its own slice of it. The cluster service's slice is
three sections, and it is smaller than the gateway's on purpose — a process that read settings it
does not use would be a process an operator could not reason about:

| Section | What the cluster service does with it |
| --- | --- |
| `kui.server.*` | where it listens, and under which base path |
| `kui.telemetry.*` | where traces and metrics go, and whether log lines are JSON or text |
| `kui.gateway.principalKeys[]` | the keys it will accept a signed `X-Kui-Principal` from |

`services/cluster/app/resources/reference.yaml` is the same information as a commented file you can
copy. It is **not** loaded: a reference configuration silently merged underneath yours is a file
that changes behaviour when somebody edits it for a different deployment. `ClusterWiringSuite`
asserts that its values are the defaults the code actually uses, so it cannot go stale unnoticed.

## What is not here yet

Static configuration is read once at startup: there is no hot reload of the file and no
configuration wizard.

The cluster registry is the exception. Records in KUI's own Kafka-backed metadata store (ADR-036 as
amended by ADR-042) overlay `kui.clusters` at runtime, so a cluster can be added or edited without
restarting anything — see [the metadata store guide](metadata-store.md). That does not change what
is written here: the static file stays the canonical base, and the store is one more layer above it
in the same precedence chain. A cluster the store knows about but the file does not is added; a
cluster both describe is replaced whole by the store's version, never merged field by field, so
removing `security` from a stored record cannot silently inherit the file's credentials.

## User preferences are not configuration

The theme, the accent colour, the table density, the timezone and the screen refresh rate are set
by each person in the interface and are stored in that person's own browser, under `kui.*` keys in
`localStorage`. There is nothing to configure, nothing to deploy and nothing to back up: KUI has no
per-user store on the server, and these values never leave the browser they were set in. Somebody
who clears their browser data gets the defaults back, and a person on two machines sets their
preferences twice.
