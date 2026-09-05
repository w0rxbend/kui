# The KUI interface, as its own image

```bash
docker compose \
  -f deployment/quickstart/docker-compose.quickstart.yml \
  -f deployment/frontend/docker-compose.frontend.yml \
  up --build

open http://localhost:8090/ui/
```

## Why the frontend ships separately

It talks to the backend over the same HTTP API every other client uses, so there is nothing it needs
from living inside the gateway's jar — and two things it loses by living there.

The backend then builds and tests with **nothing but a JDK**. Somebody working on a Kafka adapter does
not have to install Node to run a suite, and `./mill __.test` does not stop because a lockfile moved.

And the interface can be released on its own. A stylesheet fix, or a rollback of one, does not
reassemble a Scala jar or restart a process that is holding Kafka connections and serving other
clients.

## How the two halves meet

The browser only ever talks to this container. nginx serves the built interface at `/ui/` and passes
`/api/…` through to the gateway.

That is one `proxy_pass` and it buys same-origin. The session is a cookie and every mutation carries a
CSRF header (ADR-019); pointing the browser at a second origin means CORS, `SameSite` decisions and a
preflight on every mutation. The proxy makes the cookie and the token behave exactly as they do when
one process serves both.

Server-sent events go through the same block, which is why `proxy_buffering off` is set on it: without
that, nginx buffers a stream and the browser sees nothing until the response ends — which, for a live
tail, is never.

## Configuration

Read at **container start**, not baked in at build time, so one image runs in every environment.
Baking a gateway address into the build means an image per environment, which is how a staging build
reaches production.

| Variable | Default | What it does |
| --- | --- | --- |
| `KUI_GATEWAY_URL` | `http://kui:8080` | Where the API is. A service name on the compose network; the gateway never needs to be reachable from the host. |
| `KUI_BASE_PATH` | *(empty)* | Mount point behind an outer proxy. `/kui` serves the interface at `/kui/ui/` and proxies `/kui/api/`. |
| `KUI_BUILD_VERSION` | `unknown` | Shown on the settings page and quoted in bug reports. |

The entrypoint writes the nginx configuration and finishes `index.html` from these, into tmpfs — the
image's own filesystem stays read-only.

### The two markers

`frontend/index.html` carries `<!--KUI_BASE_HREF-->` and `<!--KUI_BOOTSTRAP-->`. When the gateway
serves the interface itself it substitutes them per request; here the entrypoint does it at start.

Both matter. Without the base href the application does not load past the root: `vite.config.ts` sets
`base: "./"`, so every asset URL is relative, and a deep link such as
`/ui/clusters/quickstart/topics` resolves `./assets/index-abc.js` against *that* directory — a 404.
The page renders blank, the console shows three 404s, and nothing says what is wrong. The entrypoint
checks its own substitution took and exits with a message rather than starting a server that serves a
blank page.

Without the bootstrap block the browser falls back to `/api/v1` and the build version reads `dev` — a
reasonable default and a bad thing to ship, because a bug report that names no build names nothing.

## What the image does

- Serves `/ui/` with a single-page fallback, so a deep link is answered with the application.
- Redirects `/` to `/ui/`.
- Answers `/healthz` without touching the disk, so "nginx is up" and "nginx is up and the interface is
  present" stay distinguishable.
- Caches hashed assets for a year and `index.html` not at all. Vite writes a content hash into every
  asset filename, so an asset URL is immutable by construction; `index.html` is the one file whose
  name is stable and therefore the one that must never be cached.
- Sets a strict `Content-Security-Policy`, `X-Content-Type-Options`, `Referrer-Policy` and
  `X-Frame-Options` on the document. `unsafe-inline` is allowed for styles only — the bundler emits a
  style element; there is no inline script, and the bootstrap block is `application/json`, which is
  data and never executed.
- Runs as an unprivileged user with a read-only filesystem, no capabilities and `no-new-privileges`.

## Building it by hand

```bash
docker build -f deployment/frontend/Dockerfile -t kui-frontend:dev .
docker run --rm -p 8090:8082 -e KUI_GATEWAY_URL=http://host.docker.internal:8080 kui-frontend:dev
```

The build typechecks before it bundles. `vite build` transpiles without checking types, so without
that step a type error ships as a runtime error in somebody's browser.

## Developing against it

Do not use this image for a development loop — it rebuilds the whole bundle on every change. Run the
API and the Vite server separately:

```bash
./mill devStart          # the API on :8080
cd frontend && pnpm dev  # the interface, with hot reload, proxying /api to it
./mill devStop
```
