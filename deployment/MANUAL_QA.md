# Manual QA

`deployment/manual-qa.sh` is the reproducible entry point for browser testing the repository's
Compose deployments. It builds the checked-out backend and frontend by default, binds every
published port to `127.0.0.1`, waits on real HTTP readiness endpoints, and verifies that nginx and
the direct API report the same build.

This is a local QA tool, not a production deployment. The Compose files deliberately include demo
credentials, plain HTTP and development cookie settings.

## Prerequisites

- Docker Engine with Docker Compose 2.24.4 or newer (`!override` is used to make port binding
  unambiguous)
- `curl`, `jq`, `git` and a JDK suitable for the Mill build
- Node dependencies already installed under `frontend/` for `test-messages`

Run from the checkout root (the script itself resolves every other path absolutely):

```bash
deployment/manual-qa.sh help
deployment/manual-qa.sh quickstart up
```

`up` intentionally builds both current-source images. Use `--reuse-images` only when testing an
already-built artifact; the subsequent build-identity mismatch becomes a warning instead of a
failure.

## Commands

```bash
# Start current source and prove UI/API readiness and build identity.
deployment/manual-qa.sh quickstart up

# Re-run readiness/version checks without restarting.
deployment/manual-qa.sh quickstart check

# Drive the real message browser and print a bounded backend log snapshot afterwards.
deployment/manual-qa.sh quickstart test-messages

# Inspect state or the last 300 lines per relevant service; logs never follows.
deployment/manual-qa.sh quickstart status
deployment/manual-qa.sh quickstart logs
KUI_QA_LOG_TAIL=100 deployment/manual-qa.sh quickstart logs

# Render the exact merged Compose model, including loopback-only published ports.
deployment/manual-qa.sh quickstart config

# Remove only that Compose project, including its throwaway volumes.
deployment/manual-qa.sh quickstart down
```

Readiness waits up to 240 seconds by default. Set `KUI_QA_READY_TIMEOUT` to another whole number of
seconds for a slow machine. A check covers `/healthz`, direct and proxied
`/api/v1/health/ready`, and direct and proxied `/api/v1/info`. It also compares the running
`build.gitCommit` with the checkout's `HEAD`.

## Modes and ports

| Mode | Compose files, in merge order | Default published ports |
| --- | --- | --- |
| `quickstart` | `quickstart/docker-compose.quickstart.yml`, `frontend/docker-compose.frontend.yml` | UI 8090, API 8080, Kafka 9092, registry 8081, Connect 8083, ksqlDB 8088 |
| `auth` | quickstart files above, then `quickstart/docker-compose.auth.yml` | Same as quickstart |
| `secured` | `secured/docker-compose.secured.yml` | UI 18091, API 18081 |
| `demo` | `demo/docker-compose.demo.yml` | UI 18090, API 18080, registry 18081, brokers 19092-19095 |
| `allinone` | `compose/docker-compose.allinone.yml` | UI 8090, API 8080 |
| `distributed` | `compose/docker-compose.yml` | UI 8090, API 8080 |
| `observability` | distributed file, then `compose/docker-compose.observability.yml` | UI 8090, API 8080 |
| `distributed-e2e` | distributed file, then `compose/docker-compose.e2e.yml` | UI 8090, API 8080 |
| `storybook` | `storybook/docker-compose.storybook.yml` | Storybook 6006 |

The launcher honors the port variables already used by each Compose file. It additionally provides
`KUI_SECURED_PORT` and `KUI_SECURED_FRONTEND_PORT`, because the secured example's source file uses
fixed ports. Values must be numeric TCP ports. The loopback address is not configurable by design.

Useful examples:

```bash
KUI_PORT=28080 KUI_FRONTEND_PORT=28090 \
  deployment/manual-qa.sh quickstart up

KUI_SECURED_PORT=28081 KUI_SECURED_FRONTEND_PORT=28091 \
  deployment/manual-qa.sh secured up
```

## Which mode to use

- Use `quickstart` for consumer/message browsing, JSONPath-style field filters, Schema Registry
  decoding, publishing and the focused browser suite. This is the canonical product QA stack.
- Use `auth` to check sign-in and RBAC presentation against the same data. Demo accounts are
  `admin` / `quickstart-admin` and `viewer` / `quickstart-viewer`.
- Use `secured` to prove KUI can consume through SASL/SCRAM and TLS. The launcher generates the
  missing throwaway certificates with `secured/generate-certs.sh`. The broker fixture uses
  `kui` / `kui-scram-secret`; the truststore password is `kui-secured-demo`.
- Use `demo` for switching between development, production and secured clusters and for fault
  presentation. It is the heaviest stack (four brokers plus KUI and fixtures).
- Use `distributed` for service isolation and recovery. `observability` adds the collector, but its
  current overlay instruments the gateway and cluster services rather than every KUI service.
- Use `distributed-e2e` for the backend smoke-test timing model. It is not a substitute for the
  quickstart browser fixture.
- Use `allinone` only to inspect the two-container shell: its shipped config has no Kafka cluster,
  so it cannot test consumer mode.
- Use `storybook` for isolated component and accessibility review; it has no backend integration.

## Message consumer checklist

Start with a clean current-source quickstart:

```bash
deployment/manual-qa.sh quickstart up
deployment/manual-qa.sh quickstart test-messages
```

Then open <http://127.0.0.1:8090/ui/> and check these paths manually:

1. Select cluster `quickstart`, open `orders.v1`, choose **Messages**, seek to the beginning, and
   read. Stop and restart repeatedly; old streams must not append rows or overwrite the new
   session's progress.
2. Open **Filter with an expression**, choose **Field filter**, enter `$.method.type = card` on
   `payments.transactions`, and read. Every visible value must contain the matching nested field;
   the URL should preserve the filter source.
3. On `audit.log.raw`, use the value text filter `result=success`. This is a plain string topic and
   must use text matching rather than structured field access.
4. Repeat a nested field filter on all registry-backed topics using the Schema Registry serde:
   `orders.avro`, `orders.jsonschema`, and `orders.protobuf`. Confirm decoded field values render,
   schema metadata is visible, and malformed or incompatible expressions produce a recoverable
   error instead of terminating the browse session.
5. Combine key and value filters on `orders.v1`; verify the request registers one expression and
   the stream request carries both its id and source. Clear the filter and verify an unfiltered
   browse does not register an empty expression.
6. Exercise partition selection, beginning/end/timestamp seeks and a low record limit. **Pages**
   is the default: confirm its footer names partition-local offset ranges, Next follows the signed
   cursor, and Previous restores the cached page immediately. Switch to **Infinite scroll** and
   approach the end of the list; the next offset range should preload once and append in broker
   order. Confirm the progress indicator reaches a terminal state promptly, rows remain responsive,
   and changing a control cancels the previous stream.
7. Inspect the browser console and the Network panel for failed filter registration or SSE calls,
   then correlate the same run with bounded backend logs:

   ```bash
   deployment/manual-qa.sh quickstart logs
   ```

The automated message spec uses one worker because it mutates one shared Kafka cluster. Failure
traces, screenshots and videos remain in Playwright's normal output directory.

## Authentication and secured manual checks

The quickstart Playwright global setup assumes authentication is disabled, so `test-messages`
refuses every mode except `quickstart`. For auth QA, run `auth up`, sign in as both demo users and
confirm write/copy/reset controls are absent for the viewer but present for the administrator.
Always sign out before switching roles.

For secured QA, run `secured up`, open its UI, select the secured cluster and consume the seeded
topic. A successful UI render alone is insufficient: inspect `secured logs` for TLS hostname,
truststore, authentication or decode failures.

## Three-cluster failure walkthrough

The existing demo helper owns the targeted broker stop/start operations; those operations address
the same `kui-demo` Compose project started by the unified launcher.

1. Start and verify the current checkout with `deployment/manual-qa.sh demo up`.
2. Open <http://127.0.0.1:18090/ui/>, switch through Development, Production and Secured, and
   confirm each cluster has its distinct seeded topics.
3. Run `deployment/demo/demo.sh stop prod`; Production should become stale while the other two
   clusters and the UI remain responsive.
4. Run `deployment/manual-qa.sh demo logs` and confirm the outage is attributed to the Production
   upstream rather than a gateway crash.
5. Run `deployment/demo/demo.sh start prod`, refresh, then stop only one node with
   `deployment/demo/demo.sh stop prod-broker`; Production should keep serving while showing
   under-replicated partitions.
6. Restore the node with `deployment/demo/demo.sh start prod-broker`, then repeat a shorter outage
   with `stop secured` / `start secured` to exercise the TLS cluster's recovery.
7. Capture a final bounded log snapshot and remove the project with
   `deployment/manual-qa.sh demo down`.

If demo ports were overridden, pass the same environment to the old helper's stop/start commands
so its rendered Compose model remains consistent. Do not use `demo.sh logs` in automation because
it follows indefinitely.

## Coexistence and teardown

- `quickstart`, `auth`, `allinone`, `distributed`, `observability` and `distributed-e2e` all default
  to API 8080 and UI 8090. Move both ports before starting more than one.
- `quickstart` and `auth` are variants of the same `kui-quickstart` project; they cannot be treated
  as independent running stacks. Likewise, the three distributed modes share the `kui` project.
- `secured` API 18081 conflicts with the demo registry's default 18081. Move
  `KUI_SECURED_PORT` or `KUI_DEMO_REGISTRY_PORT`.
- `quickstart` and `demo` otherwise coexist on their defaults. Storybook coexists with every stack.
- Keep `KUI_BASE_PATH` empty for this runbook; its URLs and browser fixture assume root mounting.

Always tear down with the same mode and port environment used to start the stack. The Compose
project name identifies containers, but matching variables keep the rendered configuration and
diagnostics intelligible.

## Failure triage

1. Run `MODE status`, then `MODE logs`; neither command follows indefinitely.
2. Run `MODE check --reuse-images` only when an old artifact is intentional. Otherwise a commit
   mismatch is evidence that the wrong image is running.
3. For a browser failure, keep its trace/screenshot/video and match the failed request timestamp to
   `kui` (all-in-one) or `kui-gateway` plus `kui-message` (distributed) logs.
4. Render `MODE config` and inspect `ports` if startup reports a bind collision. Every published
   entry should show `host_ip: 127.0.0.1`.
5. Regenerate secured certificates by running `deployment/secured/generate-certs.sh`; it replaces
   only the throwaway contents of `deployment/secured/certs`.

Do not use these demo passwords, signing keys, plain-HTTP cookie settings or generated certificate
authority outside local QA.
