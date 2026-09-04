# KUI container images

Six images, built by the repository's own build tool. You do not need a Scala toolchain to run
them; you do need one to build them.

| Image          | What it is                                            | Listens on | Liveness path          |
| -------------- | ----------------------------------------------------- | ---------- | ---------------------- |
| `kui-allinone` | The gateway and every service in one process (ADR-005) | 8080       | `/api/v1/health/live`  |
| `kui-gateway`  | The one process a browser talks to                    | 8080       | `/api/v1/health/live`  |
| `kui-cluster`  | The cluster registry and topology service             | 8080       | `/health/live`         |
| `kui-topic`    | Topics, their partitions, their settings and their administration | 8080 | `/health/live` |
| `kui-message`  | Browsing records, producing, resending and purging    | 8080       | `/health/live`         |
| `kui-consumer` | Consumer groups, their lag and the offset reset       | 8080       | `/health/live`         |

The gateway serves its health probes under the public `/api/v1` prefix and a service serves them at
the root. That is not an inconsistency: everything a browser can reach lives under `/api/v1`, and a
service's paths belong to its own private world, which nothing outside the cluster network should be
reaching anyway.

## Building them

```
./mill deployment.docker.__.build          # all three
./mill deployment.docker.gateway.docker.build   # just one
```

The tag is the product version from the build, currently `0.1.0-SNAPSHOT`, so the images are
`kui-gateway:0.1.0-SNAPSHOT` and so on. Nothing is pushed anywhere; the images land in the local
Docker daemon.

## Running them

### All-in-one, the fastest thing to try

```
docker run --rm \
  -p 8080:8080 \
  --read-only --tmpfs /tmp \
  -v "$PWD/deployment/compose/kui.yaml:/etc/kui/kui.yaml:ro" \
  -e KUI_PRINCIPAL_KEY=change-me-to-something-at-least-32-bytes-long \
  -e KUI_TELEMETRY_LOGFORMAT=text \
  kui-allinone:0.1.0-SNAPSHOT
```

Then open <http://localhost:8080/ui/>, or check it from the shell:

```
curl -s localhost:8080/api/v1/info | jq -r .build.gitCommit
curl -s localhost:8080/api/v1/clusters | jq -r .clusters.status
curl -s localhost:8080/api/v1/capabilities | jq -r '.entries[0].state.status'
```

The all-in-one process reads `kui.gateway.services` and `kui.gateway.principalKeys` and then warns
that it is ignoring both, because it calls its services in memory and signs nothing. That is
expected when you point it at the shipped configuration file, which is written for the distributed
shape.

### The distributed pair

Use Docker Compose rather than two `docker run` commands: the gateway has to reach the service by
name and both need to be on one network. See [`../compose/README.md`](../compose/README.md).

## Conventions every image follows

These are decided once (INFRA-001) and apply to every service image KUI will ever publish.

**The configuration is mounted, never baked in.** The default command is
`--config /etc/kui/kui.yaml` and no such file exists in the image, so a container started without
the mount stops immediately and prints what was missing rather than starting on defaults and
pretending to work. Every key can also be given as an environment variable — `kui.server.port`
becomes `KUI_SERVER_PORT` — or as a flag, and flags beat the environment, which beats the file.

**It runs as uid 1001 and never as root.** Nothing KUI does needs privilege, and a container process
that is root is root on the host kernel if anything ever escapes the namespace.

The uid is numeric and no user account is created for it, so `whoami` inside the container fails
while `id` reports `uid=1001`. That is a deliberate trade: creating the account would write the
current day number into `/etc/shadow`, and the same commit would then produce a different image
tomorrow.

**The root filesystem can be read-only.** Add `--read-only --tmpfs /tmp` as in the example above.
The JVM is told to use `/tmp` for temporary files (`-Djava.io.tmpdir=/tmp`) and `HOME` points there
too, so nothing else needs to be writable. This is not the default — Docker does not make it one —
but it is how the images are meant to be run, and how Compose runs them.

**Memory is a fraction of the container's limit, and an out-of-memory JVM dies.**
`-XX:MaxRAMPercentage=75` sizes the heap against whatever limit the orchestrator applied.
`-XX:+ExitOnOutOfMemoryError` is the important one: a JVM that has run out of memory does not
recover, it thrashes — answering slowly, passing its own liveness probe while doing it. Exiting
turns a silent degradation into a restart that monitoring can see.

**There is a `HEALTHCHECK`.** It calls the liveness path with `curl`, which the base image already
provides, every ten seconds after a twenty-second grace period. `docker ps` then shows `(healthy)`
and Compose can wait on it.

**Only the listening port is exposed.** One `EXPOSE 8080/tcp` and nothing else.

**The labels say which build it is.** `docker inspect` answers the question every incident starts
with:

```
docker inspect kui-gateway:0.1.0-SNAPSHOT | jq -r '.[0].Config.Labels'
```

gives `org.opencontainers.image.{title,description,version,revision,source,licenses}`, where
`revision` is the full git commit the image was built from.

## Logging

The containers log one JSON object per line by default, which is what a log collector expects. For
reading a terminal, set `KUI_TELEMETRY_LOGFORMAT=text` and get short human lines instead. The
setting has to be chosen before the first log line is written, so it is an environment variable or a
configuration key and cannot be changed while the process runs.

## Reproducibility

Two builds of the same commit produce the same image digest, provided the build's own `BuildInfo`
has not been regenerated in between. Verified:

```
$ ./mill deployment.docker.__.build
$ docker inspect kui-gateway:0.1.0-SNAPSHOT --format '{{.Id}}'
sha256:e9a6731bec54ee1810d4549295a85cea6d2c84c24cecc47fc91ad57793031be2
$ ./mill clean deployment.docker && ./mill deployment.docker.__.build
$ docker inspect kui-gateway:0.1.0-SNAPSHOT --format '{{.Id}}'
sha256:e9a6731bec54ee1810d4549295a85cea6d2c84c24cecc47fc91ad57793031be2
```

Three things make that hold, and each one had to be dealt with separately:

1. **The assembled jar is normalised.** Mill stamps every entry in it with the moment it was
   written, so its bytes changed on every build and carried the layer hash with them. The build
   rewrites it with all entries at a fixed 1980-01-01 and in sorted order.
2. **BuildKit is told to rewrite timestamps.** `SOURCE_DATE_EPOCH=0` alone only fixes the image's
   `created` field; the exporter option `rewrite-timestamp=true` is what applies it to the files
   inside the layers.
3. **Attestations are off.** The provenance and SBOM attestations record when and where the build
   ran, which is different every time by definition.

### The caveat, stated plainly

The `builtAt` field that `GET /api/v1/info` reports is the moment the build ran, and it is compiled
into the image as source. So a build on a machine that has never built KUI before — a fresh CI
runner, for instance — produces a different digest from yours, even at the same commit. Everything
else about the image is a function of the source.

Making the digest depend on the commit alone means deriving `builtAt` from the commit's own
timestamp instead of from the clock. That is a change to what GW-010 decided `builtAt` means, so it
belongs in its own commit with that lane's agreement rather than as a side effect of packaging.

## Not in scope here

No Helm chart, no multi-architecture builds, no image signing and no SBOM. Those are M8. The images
are `linux/amd64` only, because that is what the build machine produces and nothing yet asks for
more.
