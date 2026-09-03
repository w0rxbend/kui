# INFRA-001 — Docker images for gateway, cluster and all-in-one

- **ID:** INFRA-001
- **Title:** Docker images for gateway, cluster and all-in-one
- **Milestone / Feature:** M0 / KU-008
- **Owner role:** Infrastructure Lead
- **Size:** M
- **Dependencies / blocked by:** SVC-004, GW-010, AIO-001

## Goal (user value)

Anyone can run KUI without a Scala toolchain, and the fault-isolation demo (stopping one
container) becomes possible.

## Scope

1. `mill-contrib-docker` image definitions for three images: `kui-gateway`, `kui-cluster`,
   `kui-allinone`, all on `eclipse-temurin:21-jre` (ADR-001).
2. Image conventions, decided here and applied to every future service:
   - non-root user (`uid 1001`), read-only root filesystem, a writable `/tmp`;
   - `ENTRYPOINT` is the JVM with `-XX:MaxRAMPercentage=75` and
     `-XX:+ExitOnOutOfMemoryError` (an OOM must kill the container, not limp);
   - `HEALTHCHECK` calling `/health/live`;
   - `EXPOSE` the configured port only;
   - OCI labels: `org.opencontainers.image.{title,version,revision,source,licenses}` from the
     build info of GW-010;
   - the config file is mounted, never baked in.
3. Reproducibility: the same commit produces the same image digest (fixed timestamps, sorted
   layer contents).
4. A `deployment/docker/README.md` documenting the tags and the run command per image.

## Non-goals

No Helm chart (M8). No multi-architecture builds (record as a question for M8). No image
signing or SBOM (M8). No JLink / custom runtime image.

## Design references

ADR-001 (`mill-contrib-docker` 1.1.8, temurin 21 base), ADR-005 (the all-in-one image),
`ARCHITECTURE.md` §16, PLAN §49.

## Files to create

```
build.mill                                  (docker module definitions)
deployment/docker/README.md
deployment/docker/entrypoint.sh             (only if the JVM flags need a wrapper; prefer none)
```

## Acceptance criteria

```
$ ./mill deployment.docker.__.build
$ docker images | grep kui
kui-allinone   0.1.0-SNAPSHOT   ...   ~220MB
kui-gateway    0.1.0-SNAPSHOT   ...
kui-cluster    0.1.0-SNAPSHOT   ...
$ docker run --rm -p 8080:8080 -v $PWD/deployment/compose/kui.yaml:/etc/kui/kui.yaml \
    kui-allinone:0.1.0-SNAPSHOT --config /etc/kui/kui.yaml
$ curl -s localhost:8080/api/v1/info | jq -r .build.gitCommit
$ docker inspect kui-gateway:0.1.0-SNAPSHOT | jq -r '.[0].Config.User'
1001
$ docker inspect kui-gateway:0.1.0-SNAPSHOT | jq -r '.[0].Config.Labels["org.opencontainers.image.revision"]'
```

Two consecutive builds of the same commit produce the same digest — assert once and record it.

## Tests required

No MUnit suite (this is build output). The acceptance commands above run in CI's `docker
build` stage (BUILD-004), plus:

- a CI step asserting the image runs as non-root and its `HEALTHCHECK` reports healthy within
  30 seconds;
- a CI step asserting the image contains no `.scala` source files and no test classes.

## Observability

The container logs JSON to stdout by default (`telemetry.logFormat = json`), which is what a
log collector expects. Document the `KUI_TELEMETRY_LOGFORMAT=text` override for local reading.

## Degraded behavior

A container started without a config file fails fast with the accumulated configuration errors
on stderr and a non-zero exit — it must not start with defaults and pretend to work, because a
silently mis-configured KUI is worse than one that refused to start.

## Docs to update

`deployment/docker/README.md`; `README.md` (running with Docker).

## Deviations

### 1. `mill-contrib-docker`'s Jib mode was not used

The contributed module offers two builders. The Jib one needs no Docker daemon, and it also cannot
express a `HEALTHCHECK`, which this task requires. `ClassicDockerConfig` was used instead, with the
generated Dockerfile replaced wholesale so that every instruction is in one readable place and the
jar always lands at `/app.jar` rather than at a path derived from the module name.

### 2. Non-root is a numeric uid with no user account

The spec says "non-root user (`uid 1001`)". `RUN useradd -u 1001 ...` would create the account, and
it would also write the current day number into `/etc/shadow` — so the same commit would produce a
different image tomorrow, and requirement 3 (reproducibility) would be unsatisfiable.

`USER 1001` with no account satisfies `docker inspect .Config.User == "1001"`, adds no layer, and is
a function of the source. The cost is that `whoami` inside the container fails while `id` works.
Documented in `deployment/docker/README.md`.

### 3. Read-only root filesystem is a run flag, not an image property

Docker has no image-level way to declare "run me read-only". What the image can do is be *able* to
run that way, and it is: `-Djava.io.tmpdir=/tmp`, `HOME=/tmp` and `TMPDIR=/tmp` mean nothing outside
`/tmp` is ever written. Verified by running the all-in-one image with `--read-only --tmpfs /tmp`
(transcript in the implementation report). The Compose files of INFRA-002 pass the flags.

### 4. Reproducibility needed three fixes, and one caveat remains

The requirement as written — "fixed timestamps, sorted layer contents" — turned out to describe only
the third of three independent sources of drift:

1. **The assembled jar was not byte-stable.** Mill stamps each zip entry with the moment it was
   written. `KuiImage` now overrides `assembly` to rewrite the archive with every entry at a fixed
   local 1980-01-01 (`setTimeLocal`, not `setTime`, so the result does not depend on the building
   machine's time zone) and entries in sorted order.
2. **`SOURCE_DATE_EPOCH` alone does nothing to layer contents.** It fixes the image configuration's
   `created` field and leaves every copied file stamped with the moment it was copied. The exporter
   option `rewrite-timestamp=true` is what actually applies it, and it conflicts with the docker
   exporter's default `unpack`, so `unpack=false` goes with it.
3. **Attestations are generated per build.** `--provenance=false --sbom=false`.

Applying `rewrite-timestamp` required overriding `build` to call `docker buildx build` directly
rather than the contributed module's plain `docker build`.

**The caveat.** `GET /api/v1/info` reports `builtAt`, which GW-010 defines as the moment the build
ran and which the build compiles into the image as source. A build on a machine that has never built
KUI before therefore produces a different digest at the same commit. Everything else about the image
is a function of the source, and the assertion recorded above holds for a rebuild that does not
regenerate `BuildInfo` — which is the case CI actually needs, since a CI job builds once.

Making the digest a function of the commit alone means deriving `builtAt` from the commit's own
timestamp. That changes what GW-010 decided the field means, so it is left for that lane rather than
taken unilaterally here. **INFRA-004 should pick this up or record it as accepted.**

### 5. Image size

The spec's estimate was `~220MB` for `kui-allinone`. The actual content sizes are:

```
kui-allinone:0.1.0-SNAPSHOT  201MB
kui-cluster:0.1.0-SNAPSHOT   199MB
kui-gateway:0.1.0-SNAPSHOT   185MB
```

Close enough that no action is needed; recorded so the number in the spec is not mistaken for a
measurement.

### 6. The CI steps are described but not wired

The two CI assertions this task lists (runs as non-root; contains no `.scala` sources or test
classes) belong to BUILD-004's `docker build` stage, which does not exist yet. Both were verified by
hand and the transcripts are in the implementation report: `docker inspect` reports `User` as
`1001`, and the assembly jar contains no `.scala` entry, no MUnit and no ScalaCheck class. Whoever
builds BUILD-004 should turn those two commands into steps.
