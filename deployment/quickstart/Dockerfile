# Builds the `kui-allinone` image from source, using nothing but Docker.
#
# WHY THIS FILE EXISTS AT ALL. KUI does not publish its images to a registry yet, so there is
# nothing to pull. The repository's normal way of producing them — `./mill deployment.docker.__.build`
# — needs a Java Development Kit (JDK) on the machine, and the quickstart's promise is that a
# machine with only Docker installed is enough. The way out is to put the toolchain in a container:
# the first stage below is a throwaway JDK image that compiles KUI, and the second stage is the
# image that actually runs, containing one jar and no compiler.
#
# The second stage reproduces, by hand, the conventions `deployment.docker.KuiImage` in build.mill
# fixes for every KUI image (INFRA-001) — same base image, same uid, same JVM flags, same health
# check. It is a deliberate duplication, and the reason is that the Mill definition cannot be used
# here: it builds an image by calling Docker itself, and asking Docker to run Docker to build an
# image is a much bigger ask of a newcomer's machine than a plain `docker build`. The two must be
# kept in step; if you change one, change the other.
#
# Anyone who does have a JDK should use `./mill deployment.docker.allinone.docker.build` instead —
# it is faster, and it produces the byte-identical, reproducible image that build.mill goes to some
# length to guarantee. The quickstart script uses whichever image is already present before it
# builds anything.

# ------------------------------------------------------------------------------------------------
# Stage 1: compile.
# ------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /src

# Node and pnpm, because the browser is no longer Scala.js.
#
# Until the SolidJS cutover (ADR-048) this stage needed nothing but a JDK: Mill drove the Scala.js
# linker and the whole build was one toolchain. The interface is now built by Vite, so the jar
# cannot be assembled without Node — and the failure is not subtle. `frontend.solid.toolchain`
# checks for both and refuses with the versions it wants, which is how this gap was found rather
# than by shipping an image with no interface in it.
#
# The versions are the ones in `.tool-versions`, and the pnpm one is exact for the reason recorded
# there: `frontend/package.json` names the same number in `packageManager`, and pnpm 11.25 refuses
# to start on a Node below 22.13. `npm install -g` rather than corepack, which cannot verify its own
# signing key on these Node builds ("Cannot find matching keyid").
ENV NODE_VERSION=22.13.0 PNPM_VERSION=11.25.0
RUN set -eu; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
      amd64) node_arch=x64 ;; \
      arm64) node_arch=arm64 ;; \
      *) echo "no Node build for $arch" >&2; exit 1 ;; \
    esac; \
    # `.tar.gz`, not the smaller `.tar.xz`: this base image has no `xz`, and adding one through the
    # package manager to save a few megabytes of download in a stage that is thrown away is a worse
    # trade than the download.
    curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${node_arch}.tar.gz" \
      | tar -xz -C /usr/local --strip-components=1 --exclude=CHANGELOG.md --exclude=LICENSE --exclude=README.md; \
    npm install -g "pnpm@${PNPM_VERSION}"; \
    node --version; \
    pnpm --version

# The whole repository, minus what Dockerfile.dockerignore excludes. The sources are a few
# megabytes; the excluded `out/` and `node_modules/` directories are not.
COPY . .

# Mill downloads its own distribution and every dependency on first use, which is the slow part of
# a first run and the reason quickstart.sh warns before starting it. `-Dcoursier...` is not needed:
# the caches live under HOME, which is inside this throwaway stage.
RUN ./mill deployment.docker.allinone.assembly \
    && cp out/deployment/docker/allinone/assembly.dest/app.jar /app.jar

# ------------------------------------------------------------------------------------------------
# Stage 2: run. Nothing from stage 1 comes along except the jar.
# ------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

LABEL "org.opencontainers.image.title"="kui-allinone"
LABEL "org.opencontainers.image.description"="KUI all-in-one: the gateway and every service in one process"
LABEL "org.opencontainers.image.source"="https://github.com/kui/kui"
LABEL "org.opencontainers.image.licenses"="Apache-2.0"

# No user account is created for uid 1001, only the number, which is why `whoami` fails inside the
# container while `id` answers. Creating the account would write today's date into /etc/shadow and
# make the image a function of the day it was built. HOME points at /tmp so that anything looking
# for a home directory finds a writable one under the read-only root filesystem.
ENV HOME=/tmp
ENV TMPDIR=/tmp

EXPOSE 8080/tcp
COPY --from=build /app.jar /app.jar
USER 1001

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/api/v1/health/live || exit 1

# -XX:MaxRAMPercentage=75  the heap is a stated fraction of whatever limit the orchestrator applied
# -XX:+ExitOnOutOfMemoryError  an out-of-memory JVM does not recover, it thrashes while still
#                              passing its own liveness probe; exiting turns that into a restart
#                              somebody can see
# -Djava.io.tmpdir=/tmp  the image is meant to run with a read-only root and a tmpfs at /tmp
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app.jar"]

# The configuration is mounted, never baked in: no such file exists in the image, so a container
# started without the mount stops immediately and says what was missing, instead of starting on
# defaults and pretending to work.
CMD ["--config", "/etc/kui/kui.yaml"]
