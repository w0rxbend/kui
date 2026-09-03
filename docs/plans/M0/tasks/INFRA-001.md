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
