---
name: kui-playwright
description: Run, extend, and debug KUI's Playwright UI tests against the real quickstart stack. Use for browser E2E, consumer/message UI verification, console failures, screenshots, traces, or frontend/backend integration regressions in this repository.
---

# KUI Playwright

Drive the deployed product, not a mocked dev page. The browser origin is `http://localhost:8090`; fixture API calls go directly to `http://localhost:8080`.

## Prepare current-source images

Build both images explicitly before starting quickstart. The start script reuses images that already exist, so skipping either build can test stale code.

```bash
./mill --no-server deployment.docker.allinone.docker.build
docker compose --project-directory deployment/quickstart \
  -f deployment/quickstart/docker-compose.quickstart.yml \
  -f deployment/frontend/docker-compose.frontend.yml build frontend
deployment/quickstart/quickstart.sh up
```

Confirm `/healthz`, `/api/v1/health/ready`, and `/api/v1/info` through both direct and proxied paths. The Playwright global setup also waits for the first Kafka scrape; do not replace that readiness check with a sleep.

## Run tests

From `frontend/`:

```bash
pnpm e2e
pnpm e2e:messages
pnpm typecheck
pnpm test
```

If the package-manager shim cannot run offline but `node_modules` is present, use `./node_modules/.bin/playwright test` and the corresponding local binaries. Do not install or upgrade dependencies merely to bypass a transient shim failure.

Use `KUI_E2E_UI` and `KUI_E2E_API` only when the stack intentionally uses non-default ports. Keep workers at one: the suite mutates one shared Kafka cluster.

## Diagnose failures

Always inspect bounded logs after a failed run; `quickstart.sh logs` follows forever and is not suitable for automation.

```bash
docker compose --project-directory deployment/quickstart \
  -f deployment/quickstart/docker-compose.quickstart.yml \
  -f deployment/frontend/docker-compose.frontend.yml \
  logs --tail=300 kui frontend schema-registry kafka avro-seed seed
```

Preserve Playwright's failure trace, screenshot, and video. Correlate the browser request with the gateway/message-service log before changing code. Add the smallest regression that reproduces the failure, fix its root cause, rerun the focused spec, then the full browser suite.

Prefer accessible role/label locators. Assert the user-visible result and, for integration seams, the corresponding request or response. Treat console errors and uncaught page exceptions as test failures. Save ad-hoc screenshots and scripts under `/tmp`, not in the repository.
