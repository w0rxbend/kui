# Configuring KUI

Everything KUI reads at startup is in this document. There is no hidden default file, no
system property KUI looks at behind your back, and no key that is silently ignored — a key
KUI does not recognise fails the load and names itself.

The worked example is `deployment/compose/kui.yaml`. It sets every key to the value KUI
would have used anyway, so you can copy it and change only the lines you care about.

## Where a value can come from

Four places, in order of who wins:

1. **A command-line flag** — `--kui.server.port=9090`, or `--kui.server.port 9090`. The
   `kui.` prefix is optional, so `--server.port=9090` is the same flag. `--config <path>`
   adds a YAML file.
2. **An environment variable** — the key in capitals with `.` and `-` replaced by `_`:
   `kui.server.port` is `KUI_SERVER_PORT`, and `kui.gateway.services.schema-registry.url`
   is `KUI_GATEWAY_SERVICES_SCHEMA_REGISTRY_URL`.
3. **A YAML file** — one or more. A later file overrides an earlier one, and files named by
   `--config` come after the ones the process was started with.
4. **The built-in default** — listed for every key in the table below.

A *bad* value in a higher-precedence place is an error, not a reason to fall through to the
next one. If `KUI_SERVER_PORT=abc`, KUI refuses to start; it does not quietly use the port
from the file, because you would have no way of noticing.

## When something is wrong

KUI reports **every** problem it found, one per line, and exits with status 1. Each line
names the key, what was expected, what was found, and which of the four places supplied it:

```
kui.gateway.services.cluster.url: expected an http or https URL this deployment is allowed to call; url must be http or https, got 'ftp://x' (found 'ftp://x')   (file: /etc/kui/kui.yaml)
kui.server.port: expected a port between 1 and 65535; port must be between 1 and 65535, got '0' (found '0')   (file: /etc/kui/kui.yaml)
kui.telemetry.logFormat: expected json or text; 'yaml' is neither json nor text (found 'yaml')   (file: /etc/kui/kui.yaml)
```

There is no partially-valid start. A gateway running with three of its four upstreams
configured is harder to diagnose than one that refused to boot and told you why.

## The keys

| Key | Environment name | Default | Meaning |
| --- | --- | --- | --- |
| `kui.server.host` | `KUI_SERVER_HOST` | `0.0.0.0` | Which network interface to bind. `0.0.0.0` is every interface, which is what a container needs. |
| `kui.server.port` | `KUI_SERVER_PORT` | `8080` | The TCP port. A port already in use fails the start; KUI never picks another one for you. |
| `kui.server.basePath` | `KUI_SERVER_BASEPATH` | `/` | The path prefix every route is served under, for a reverse proxy that mounts KUI at `/kui`. `/` means no prefix. |
| `kui.gateway.services.<id>.url` | `KUI_GATEWAY_SERVICES_<ID>_URL` | *(required if the service is listed)* | The address of one downstream KUI service. Checked against the URL policy below. |
| `kui.gateway.services.<id>.timeout` | `KUI_GATEWAY_SERVICES_<ID>_TIMEOUT` | `10s` | The whole-call budget for that service. |
| `kui.gateway.services.<id>.maxConcurrent` | `KUI_GATEWAY_SERVICES_<ID>_MAXCONCURRENT` | `32` | How many calls to that service may be in flight at once (the bulkhead). |
| `kui.gateway.readinessIntervalMs` | `KUI_GATEWAY_READINESSINTERVALMS` | `10000` | How often the gateway polls each service's readiness. |
| `kui.gateway.principalKeys.<n>.kid` | `KUI_GATEWAY_PRINCIPALKEYS_<N>_KID` | *(required if a key is listed)* | The key id that travels in the signed principal header. |
| `kui.gateway.principalKeys.<n>.key` | `KUI_GATEWAY_PRINCIPALKEYS_<N>_KEY` | *(required if a key is listed)* | The shared signing secret. See "Secrets" below. |
| `kui.gateway.principalKeys.<n>.notBefore` | `KUI_GATEWAY_PRINCIPALKEYS_<N>_NOTBEFORE` | `1970-01-01T00:00:00Z` | When this key becomes usable for signing, so keys can be rotated without downtime. |
| `kui.gateway.cors.enabled` | `KUI_GATEWAY_CORS_ENABLED` | `false` | Whether pages from other origins may call this API. Off, because the gateway serves the UI from the same origin. |
| `kui.gateway.cors.origins` | `KUI_GATEWAY_CORS_ORIGINS` | *(empty)* | The explicit allow-list, comma-separated in the environment. `*` is refused at startup. |
| `kui.auth.type` | `KUI_AUTH_TYPE` | `disabled` | The only value M0 accepts. Real authentication arrives with the identity service in M6. |
| `kui.telemetry.otlpEndpoint` | `KUI_TELEMETRY_OTLPENDPOINT` | *(unset)* | Where traces and metrics are exported. Unset means export nothing; the process still starts. |
| `kui.telemetry.prometheusPort` | `KUI_TELEMETRY_PROMETHEUSPORT` | *(unset)* | An extra port exposing this process's own telemetry in Prometheus format. Not the Kafka-metrics endpoint. |
| `kui.telemetry.logFormat` | `KUI_TELEMETRY_LOGFORMAT` | `json` | `json` for a log system, `text` for a human at a terminal. |
| `kui.telemetry.hashUserIds` | `KUI_TELEMETRY_HASHUSERIDS` | `true` | Log and trace `user.id` as a hash rather than the login name. |
| `kui.clusters` | — | `[]` | Declared, read by nothing yet. The cluster registry is M1. |
| `kui.rbac` | — | `{}` | Declared, read by nothing yet. The authorization model is M6. |

Keys under `kui.clusters` and `kui.rbac` are accepted and ignored, so a file written for a
later milestone still loads today. A key anywhere else that KUI does not recognise is an
error.

## Which URLs KUI will call

Every URL-shaped key is checked before KUI will use it, because a URL in a configuration
file is a URL KUI's own network position will fetch:

- `http` and `https` only. There is no development exception.
- No credentials in the URL (`http://user:pass@host`). Put them in their own keys, where
  they can be redacted.
- By default, no address that points at this machine (`localhost`, `127.0.0.1`, `[::1]`) and
  no address that is not routable on the public internet — the private ranges, the
  link-local range, and the cloud metadata addresses inside it such as
  `http://169.254.169.254/`, which is how a cloud instance hands out its own credentials.

The last rule is relaxed in development and in the Docker Compose topology, where every
upstream really is on loopback or a private network. It is not relaxed in production.

## Secrets

Any key that holds a secret accepts three forms:

```yaml
key: "s3cret"                  # a literal. Fine on a laptop; it is in version control anywhere else.
key: "env:KUI_SIGNING_KEY"     # read from an environment variable at startup
key: "file:/run/secrets/kui"   # read from a mounted file, which is how Kubernetes and Compose
                               # deliver secrets
```

**The guarantee: a secret never appears in a log line, a span attribute, an error envelope
or an HTTP response body.** It is enforced by the type, not by a filter over the output:
`Secret[A]` in `libs/kernel` prints as `***` and has no other rendering, so there is no
formatter to forget to configure. That is proved by `SecretRedactionSuite` in
`libs/config/test`, which takes one secret value and asserts it is absent from all four
sinks at once — plus a negative control that the same value *without* the wrapper does
appear, so the test can fail.

If you add a fifth place a configuration value can end up — a metrics attribute, a health
payload, an audit record — add a case to that suite.

A configuration problem reported for a secret key prints `***` in place of the value, and a
`file:` reference that cannot be read says only that it could not be read. Neither ever
echoes the file's contents.

## What is not here yet

Static configuration is all of it in M0: files, environment variables and command-line
flags, read once at startup. There is no hot reload and no configuration wizard.

From M1 the cluster registry moves into KUI's own Kafka-backed metadata store (ADR-036 as
amended by ADR-042), so `kui.clusters` can be changed without restarting anything. That
does not change what is written here: the static file stays the canonical base, and the
store is inserted as one more layer above it in the same precedence chain.
