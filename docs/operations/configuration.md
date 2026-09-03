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

### `kui.clusters` — the cluster registry (**M1**)

**Accepted and ignored.** Any key under `kui.clusters` loads today and is read by nothing, so a
file written for M1 still starts an M0 build instead of failing on a key that does not exist yet.
KUI will not connect to a broker until M1 ships.

The shape M1 implements is ADR-022's typed security model, and
[`deployment/examples/production.yaml`](../../deployment/examples/production.yaml) is written in it:

| Key | Type | Notes |
| --- | --- | --- |
| `kui.clusters[].name` | string | Display name; the cluster id is derived from it (ADR-031). |
| `kui.clusters[].bootstrapServers` | string | Comma-separated `host:port` list. |
| `kui.clusters[].security.protocol` | `plaintext` \| `ssl` \| `saslPlaintext` \| `saslSsl` | `saslPlaintext` authenticates but does **not** encrypt. |
| `kui.clusters[].security.mechanism` | `plain` \| `scramSha256` \| `scramSha512` \| `gssapi` \| `oauthBearer` \| `awsMskIam` \| `azureEntra` \| `gcpManagedKafka` | The SASL mechanism. |
| `kui.clusters[].security.username` / `.password` | string / secret | For `plain` and the SCRAM mechanisms. |
| `kui.clusters[].security.ssl.verifyHostname` | boolean | Leave on. Turning it off also removes the check that the broker is who it claims to be. |
| `kui.clusters[].security.ssl.truststore` / `.keystore` | secret | Needed when the brokers use a private certificate authority. |
| `kui.clusters[].properties` | map of string | Raw Kafka client properties, applied last. The escape hatch for a broker setting KUI has no typed key for. Prefer the typed keys: these are neither validated nor redacted. |

### `kui.store` — the metadata store (**M1, not accepted yet**)

**These keys do not exist today.** This is the one section where a file written for M1 does *not*
load on an M0 build: `kui.store` has no tolerance rule, so every key under it is reported as
`is not a KUI configuration key` and the process refuses to start. Keep the block commented out
until you are running an M1 build — `deployment/examples/production.yaml` ships it commented out
for exactly this reason.

From M1, `kui.store.*` configures the Kafka cluster holding KUI's own `__kui_*` topics, and the
cluster registry moves into it so it can be edited without a restart. The keys and the operational
detail — replication, the encryption key you cannot recover, why the config topic has one partition
— are in [the metadata store guide](metadata-store.md). Running with no store at all remains
supported: KUI then uses the file adapter and static configuration is the whole story.

### `kui.rbac` — the authorization model (**M6**)

**Accepted and ignored**, on the same terms as `kui.clusters`.

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

Static configuration is all of it today: files, environment variables and command-line flags, read
once at startup. There is no hot reload and no configuration wizard.

From M1 the cluster registry moves into KUI's own Kafka-backed metadata store (ADR-036 as amended
by ADR-042), so `kui.clusters` can be changed without restarting anything. That does not change
what is written here: the static file stays the canonical base, and the store is inserted as one
more layer above it in the same precedence chain.
