# The KUI interface, as its own image.
#
# ## Why the frontend is a separate image at all
#
# It talks to the backend over the same HTTP API every other client uses, so there is nothing it
# needs from being inside the gateway's jar — and two things it loses. The backend then builds and
# tests with nothing but a JDK, which is a real saving for anybody working on a Kafka adapter who
# should not have to install Node to run a suite. And the interface can be rebuilt, rolled forward
# or rolled back on its own: a stylesheet fix does not reassemble a Scala jar or restart a process
# holding Kafka connections.
#
# ## Why it proxies the API rather than the browser calling the gateway directly
#
# Same origin. The session is a cookie and every mutation carries a CSRF header (ADR-019); pointing
# the browser at a second origin means CORS, `SameSite` decisions and a preflight on every mutation,
# all to avoid one `proxy_pass`. So the browser asks this nginx for `/api/…` and this nginx asks the
# gateway. The cookie and the CSRF token then behave exactly as they do when one process serves both.
#
# ## Configuration
#
# Everything that varies between deployments is read at *container start*, not baked in at build
# time, so one image runs in every environment:
#
#   KUI_GATEWAY_URL   where the API is            (default http://kui:8080)
#   KUI_BASE_PATH     mount point behind a proxy  (default empty, i.e. served at /)
#   KUI_BUILD_VERSION shown on the settings page  (default "unknown")
#
# ------------------------------------------------------------------------------------------------
# Stage 1: build the interface.
# ------------------------------------------------------------------------------------------------
FROM node:22.13.0-bookworm-slim AS build

# Not corepack: the corepack bundled with this Node carries signing keys npm no longer matches, and
# it fails with "Cannot find matching keyid" — a message that sends people looking at their registry.
ARG PNPM_VERSION=11.25.0
RUN npm install --global --no-fund --no-audit "pnpm@${PNPM_VERSION}"

WORKDIR /build

# The manifests first, so a source change does not re-run the install. Each package's own manifest
# has to be here for `pnpm install --frozen-lockfile` to resolve the workspace.
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
COPY frontend/packages/api/package.json ./packages/api/
COPY frontend/packages/kernel/package.json ./packages/kernel/
COPY frontend/packages/shell/package.json ./packages/shell/
COPY frontend/packages/feature-clusters/package.json ./packages/feature-clusters/
COPY frontend/packages/feature-topics/package.json ./packages/feature-topics/
COPY frontend/packages/feature-messages/package.json ./packages/feature-messages/
COPY frontend/packages/feature-consumers/package.json ./packages/feature-consumers/
COPY frontend/packages/feature-schemas/package.json ./packages/feature-schemas/

RUN --mount=type=cache,id=pnpm-store,target=/pnpm-store \
    pnpm config set store-dir /pnpm-store && \
    pnpm install --frozen-lockfile

COPY frontend/ ./

# Typecheck before building. `vite build` transpiles without checking types, so without this a type
# error ships as a runtime error in somebody's browser.
RUN pnpm exec tsc --build --pretty false
RUN pnpm exec vite build --outDir /ui

# ------------------------------------------------------------------------------------------------
# Stage 2: serve it.
# ------------------------------------------------------------------------------------------------
#
# Unprivileged nginx: the stock image chowns its cache directories at start-up, which `cap_drop: ALL`
# forbids, and the container then restart-loops with an error about a directory nobody configured.
FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY --from=build /ui /usr/share/nginx/html/ui

COPY deployment/frontend/entrypoint.sh /docker-entrypoint.d/40-kui-configure.sh
USER root
RUN chmod +x /docker-entrypoint.d/40-kui-configure.sh
USER nginx

EXPOSE 8082/tcp

# `/healthz` rather than `/ui/`: it answers without reading a file from disk, so it distinguishes
# "nginx is up" from "nginx is up and the interface is missing", which are different faults.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
  CMD ["/bin/sh", "-c", "wget -qO- http://127.0.0.1:8082/healthz >/dev/null || exit 1"]
