# The SolidJS frontend, against a real backend

```bash
docker compose \
  -f deployment/quickstart/docker-compose.quickstart.yml \
  -f deployment/solid-preview/docker-compose.solid.yml \
  up --build

open http://localhost:8090/ui/     # the TypeScript / SolidJS interface
open http://localhost:8080/ui/     # the Scala.js one the product still ships, for comparison
```

Both are talking to the same gateway, the same Kafka and the same seeded data, so the two can be put
side by side.

```bash
docker compose \
  -f deployment/quickstart/docker-compose.quickstart.yml \
  -f deployment/solid-preview/docker-compose.solid.yml \
  down -v
```

## What it proves, and what it does not

It proves the seam: the SolidJS build, served from a static server, reaches a real gateway, decodes
real responses and renders real figures. Loading `/ui/clusters` issues nine API calls — `auth/me`,
`auth/settings`, the capability stream, the cluster list, brokers, log dirs, consumer groups,
topics — and renders the cluster with its real broker count, topic count and partition count.

It does not prove parity. Several screens in the SolidJS tree still render fixtures or are not wired
at all, which is exactly why the product still ships the Scala.js frontend and why this is a
separate compose file rather than a service in the quickstart. Somebody trying KUI for the first
time should get what KUI ships; a second, half-wired interface in that stack would be the most
confusing possible introduction.

## How it is put together

`vite build` in one stage, `nginx-unprivileged` in the next, serving the bundle at `/ui/` and
proxying `/api/` to the `kui` container on the quickstart network.

The proxy is what makes it work at all. `packages/api/src/bootstrap.ts` falls back to `/api/v1` on
the current origin when the gateway has not injected a `#kui-bootstrap` block — and it has not,
because these assets are not being served by the gateway. Same origin means no CORS, no cookie
surprises, and the CSRF token flows exactly as it does in production.

Three settings on that proxy block are not optional:

```
proxy_buffering off;
proxy_cache off;
proxy_read_timeout 1h;
```

Without them nginx buffers a server-sent-event stream and the browser sees nothing until the
response ends — which, for a capability stream or a live message tail, is never. The symptom is an
interface that looks like it has lost its connection while the connection is perfectly healthy.

The bundle is served from `/ui/` rather than `/`, deliberately: `vite.config.ts` sets `base: "./"`
so every asset URL is relative, and mounting it where the gateway mounts it keeps every path — the
router's, the assets', the deep links' — identical to production. A preview served from `/` would
work and would prove nothing about the thing that ships.

## When this goes away

At the cutover. `frontend.bundle` in `build.mill` becomes `frontend.solid.bundle`, the gateway
serves the TypeScript build directly, and this directory is deleted. The note at `build.mill:1382`
describes that swap; it is one line, and it is deliberately not taken until the SolidJS tree is at
parity.
