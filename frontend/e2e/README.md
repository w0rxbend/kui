# Browser end-to-end tests

These tests drive the shipped frontend against the real quickstart Kafka stack. They do not mock the gateway, message service, registry, or broker.

## One-time setup

From `frontend/`:

```bash
pnpm install
pnpm e2e:install
```

The repository pins Playwright and Chromium versions. Do not use a globally installed browser as a substitute.

## Start the current working tree

From the repository root, build both images before starting quickstart. The start script reuses existing image tags, so this avoids accidentally testing stale code.

```bash
./mill --no-server deployment.docker.allinone.docker.build
docker compose --project-directory deployment/quickstart \
  -f deployment/quickstart/docker-compose.quickstart.yml \
  -f deployment/frontend/docker-compose.frontend.yml build frontend
deployment/quickstart/quickstart.sh up
```

Then run, from `frontend/`:

```bash
pnpm e2e
pnpm e2e:messages
pnpm e2e:ui
```

`KUI_E2E_UI` and `KUI_E2E_API` override the default `http://localhost:8090` and `http://localhost:8080` endpoints. The global setup checks both endpoints, checks that nginx proxies the same gateway, and waits for Kafka's first scrape.

The suite intentionally uses one worker because tests share and sometimes mutate one cluster. Failures retain a Playwright trace, screenshot, and video. Every spec also fails on uncaught page exceptions or application `console.error` output. Chromium's generic console line for non-2xx fetches is excluded because several scenarios intentionally render 409/501 API responses; those outcomes are asserted by the scenario itself.

## Debugging

Inspect bounded logs after a failure:

```bash
docker compose --project-directory deployment/quickstart \
  -f deployment/quickstart/docker-compose.quickstart.yml \
  -f deployment/frontend/docker-compose.frontend.yml \
  logs --tail=300 kui frontend schema-registry kafka avro-seed seed
```

Avoid `quickstart.sh logs` in automation because it follows indefinitely. Fix a reproduced failure with a focused spec first, then rerun the full suite.
