# The SolidJS frontend, served against a real KUI backend.
#
# ## What this is, and what it is not
#
# It is a way to open the new TypeScript/SolidJS interface in a browser, talking to a real gateway,
# a real Kafka cluster and real seeded data — without changing what the product ships. The image the
# quickstart runs still serves the Scala.js frontend, because that is still the working product
# (several screens in the SolidJS tree are not wired to the API yet, and shipping a half-wired
# interface that *looks* complete is worse than shipping the old one).
#
# It is not the cutover. When the SolidJS tree reaches parity, `frontend.bundle` in `build.mill`
# becomes `frontend.solid.bundle` and this preview stops being necessary — the note at
# build.mill:1382 is where that swap is described.
#
# ## Why it proxies rather than being served by the gateway
#
# `packages/api/src/bootstrap.ts` falls back to `/api/v1` on the current origin when the gateway has
# not injected a `#kui-bootstrap` block — which it has not, because these assets are not being served
# by the gateway. So the browser asks this nginx for `/api/v1/...`, and this nginx passes it to the
# KUI container. Same origin, so no CORS, no cookie surprises, and the CSRF token flows exactly as it
# does in production.
FROM node:22.13.0-bookworm-slim AS build

# See `deployment/storybook/Dockerfile` for why this is not corepack: the corepack bundled with this
# Node carries signing keys npm no longer matches.
ARG PNPM_VERSION=11.25.0
RUN npm install --global --no-fund --no-audit "pnpm@${PNPM_VERSION}"

WORKDIR /build

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
COPY frontend/packages/api/package.json ./packages/api/
COPY frontend/packages/kernel/package.json ./packages/kernel/
COPY frontend/packages/shell/package.json ./packages/shell/
COPY frontend/packages/feature-clusters/package.json ./packages/feature-clusters/
COPY frontend/packages/feature-topics/package.json ./packages/feature-topics/
COPY frontend/packages/feature-messages/package.json ./packages/feature-messages/
COPY frontend/packages/feature-consumers/package.json ./packages/feature-consumers/

RUN --mount=type=cache,id=pnpm-store,target=/pnpm-store \
    pnpm config set store-dir /pnpm-store && \
    pnpm install --frozen-lockfile

COPY frontend/ ./

RUN pnpm exec vite build --outDir /ui

# ------------------------------------------------------------------------------------------------

# Unprivileged, for the reason `deployment/storybook/Dockerfile` gives: stock nginx chowns its cache
# directories at start-up and `cap_drop: ALL` forbids it.
FROM nginxinc/nginx-unprivileged:1.27-alpine

# The gateway mounts the application at `/ui/`, and `vite.config.ts` sets `base: "./"` so every asset
# URL in the built `index.html` is relative. Serving it from `/ui/` here keeps every path — the
# router's, the assets', the deep links' — identical to production, which is the only way this
# preview proves anything about production.
COPY --from=build /ui /usr/share/nginx/html/ui

RUN printf '%s\n' \
    'server {' \
    '  listen 8082;' \
    '  root /usr/share/nginx/html;' \
    '' \
    '  # Everything the browser asks the gateway for. `/api` is the REST surface; the capability and' \
    '  # message streams are under it too, which is why the SSE settings live on this one block.' \
    '  location /api/ {' \
    '    proxy_pass http://kui:8080;' \
    '    proxy_http_version 1.1;' \
    '    proxy_set_header Host $host;' \
    '    proxy_set_header X-Forwarded-Proto $scheme;' \
    '    # Server-sent events: without these three a stream is buffered by nginx and the browser sees' \
    '    # nothing until the response ends — which for a live tail is never.' \
    '    proxy_buffering off;' \
    '    proxy_cache off;' \
    '    proxy_read_timeout 1h;' \
    '  }' \
    '' \
    '  # A deep link such as /ui/clusters/quickstart/topics is the router s, not a file. Answer it' \
    '  # with the application, exactly as the gateway s single-page fallback does.' \
    '  location /ui/ { try_files $uri $uri/ /ui/index.html; }' \
    '  location = / { return 302 /ui/; }' \
    '' \
    '  location ~* \.(js|css|woff2?|png|svg)$ { expires 1y; add_header Cache-Control "public, immutable"; }' \
    '  location = /ui/index.html { add_header Cache-Control "no-cache"; }' \
    '}' > /etc/nginx/conf.d/default.conf

EXPOSE 8082/tcp
