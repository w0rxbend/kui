# KUI frontend roadmap (post-Scala.js)

**Written:** 2026-09-06. This document describes the frontend as it stands after the Scala.js and
Laminar implementation was deleted and replaced by a pnpm/TypeScript/SolidJS 2 workspace
(ADR-048). It supersedes the frontend content of `docs/ROADMAP.md`, whose milestone table still
names `ui-kernel`, `ui-shell` and `ui-clusters` — Mill modules that no longer exist.

Every claim below is traceable to a file in this tree. Where a survey could not verify something,
the text says so instead of rounding it up to a fact.

## Where this is

The frontend is eight pnpm packages under `frontend/packages/` — `api`, `kernel`, `shell`, and
`feature-{clusters,topics,messages,consumers,schemas}`. It builds with Vite
(`pnpm build` at `frontend/`, Node 22.13 and pnpm 11.25 per `frontend/package.json`), and the
Mill build does not participate: `build.mill:765-776` states the interface "is **not built here**"
and that the backend build "needs nothing but a JDK". The single remaining backend-to-frontend
task is `frontend.apiConstants`, which writes the CSRF header name and the error-code vocabulary
into `frontend/packages/api/src/constants.generated.ts`. The browser's request and response types
are generated on the frontend side from the committed `docs/api/openapi.browser.json` with
`pnpm --filter @kui/api run generate`.

Sixteen routes are declared in `frontend/packages/shell/src/routing/routes.tsx:56-147`, and
fifteen of them render a component that calls the gateway for real data — overview, settings,
clusters, cluster administration, brokers, topics, one topic, message browsing, message tracking,
consumer groups, one consumer group, schema subjects, one subject, plus the two error pages.
Forty-two of the fifty-six operations in `docs/api/openapi.browser.json` have a production call
site. No shipped call site names a path that is absent from that document: every request goes
through `api.get/post/put/delete/patch` with a path literal typed against the generated
`schema.d.ts`, so a wrong path fails `tsc`. The two exceptions are hand-built stream URLs
(`shell/src/App.tsx:146`, `feature-messages/src/MessagesRoute.tsx:102`); both currently match.

What **has** been driven against a running gateway, and what has not, is worth separating
carefully, because the two are usually conflated.

Driven, in a browser, against the quickstart stack with real Kafka data, during the migration:
creating, emptying and deleting a topic; editing and resetting one configuration key; producing a
record; the consumer group detail page and the offset-reset wizard, including the broker's refusal
of a reset on a group with live members; the schema registry's subject list, one subject and its
compatibility level; cluster administration, including the connection test against a real broker
and the server's refusal to persist without a metadata store; message tracking; signing in against
an auth-enabled deployment, including a wrong password and a reload; the topic list's server-side
search, sort and paging; the cards view; and the whole interface served from its own container
image, including deep links, the injected build version and the capability stream through nginx.

Not driven: any of it **automatically**. Every check above was a Playwright script written for the
occasion and thrown away. There is no committed harness, so none of it is a gate, and nothing
notices when one of them stops being true. That is the difference this roadmap's M2 is about, and
it is a real one — "it worked when I looked" and "it is checked" are not the same claim.

The Scala Playwright suite in `e2e/` is red, and for structural reasons rather than flaky ones. Its
fixtures point the browser at the gateway, and the gateway no longer has an interface to serve, so
every browser test fails on its first `waitForSelector("[data-testid='brand-link']")` — a testid
that also no longer exists anywhere in `frontend/packages`. Of the 26 distinct UI selectors the
suite uses, 12 are absent or renamed.

The frontend's own tests are green: 746 Vitest tests across 44 files, including recorded-document
tests in four features that decode responses captured from a running gateway. Storybook covers 542
stories and the accessibility sweep over all of them, in both themes, reports no violations.

Running the two halves together is one command, `deployment/quickstart/quickstart.sh`, which brings
up the backend stack and the frontend image and waits for both. Storybook is a second command,
`docker compose -f deployment/storybook/docker-compose.storybook.yml up --build`, on `:6006`.

## Milestones

Ordering rule: make the thing runnable, then make it verifiable, then add features. A feature
milestone whose exit criterion is "an e2e test passes" cannot be checked until M2 exists, so the
harness comes first.

Each milestone is marked **FRONTEND** (nothing outside `frontend/` and `deployment/` changes),
**BACKEND** (a service or endpoint has to be written first), or **BOTH**.

---

### M1 — The two halves run together on one machine. **DONE**

**Goal:** one command starts a gateway and the SolidJS frontend, and a browser can reach a working
interface.

Done on 2026-09-06, in commits `b94cde3`, `9cff3ba` and `eaa03ea`. What that took, since the list
is a fair record of what "make it runnable" actually costs:

- `deployment/solid-preview` became `deployment/frontend`, with the compose file renamed to match
  and the stale Dockerfile path fixed.
- The gateway address, the mount point and the build version are read from the environment at
  container *start* rather than baked in, so one image runs in every environment. The entrypoint
  renders both the nginx configuration and `index.html`'s two markers into tmpfs, which is what
  lets the image's own filesystem stay read-only.
- The entrypoint checks its own substitution took and exits with a message. Without the base href
  the application does not load past the root, and the symptom is a blank page with three 404s and
  nothing saying why.
- The bootstrap marker is substituted, so the settings page shows the real build rather than the
  word "dev". A bug report naming no build names nothing.
- `/healthz`, a strict CSP and the security headers, hashed assets cached for a year and
  `index.html` never — set on the `index.html` location rather than the directory, because
  `try_files` performs an internal redirect and nginx's `add_header` does not inherit into a block
  that declares any of its own.
- The Node and pnpm install came out of the backend image. It was there for exactly one commit,
  while the interface was bundled into the jar.
- `quickstart.sh` starts both halves, publishes the interface on 8090 and the API on 8080, and
  waits for each. `--wait` came off the `up`: it reports a one-shot seeder's exit as a failure
  unless its health check happened to pass first, so adding one service was enough to turn a green
  start-up into an error with nothing wrong.

**Still open from this milestone:** `deployment/compose/docker-compose.yml`, the stack that
demonstrates fault isolation, has no interface. Adding the frontend service there is the remaining
task and belongs with M2, which needs that stack anyway.

---

### M2 — An end-to-end suite that is green. **FRONTEND**

**Goal:** restore the quality gate. Today it is red, which means it enforces nothing.

The recommendation from the e2e survey is to replace the Scala suite rather than repair it, and
the reasons are worth recording because the decision is not obvious:

- Almost none of the browser half survives. 12 of 26 selectors are gone or renamed, including
  `brand-link`, which every test waits on first. `ClustersPage.scala` has no counterpart in the
  new interface at all — there is no summary strip, and `DataTable` emits no per-row testid.
- The fixture work is the larger job and is identical in either language: both shapes need a
  second origin for the UI, because the interface and the API are no longer the same server. In
  TypeScript that is `webServer` and `baseURL` in `playwright.config.ts` rather than the ~300
  hand-rolled lines in `e2e/src/kui/e2e/fixtures/`.
- Repairing in place would require adding a UI image build to `e2e.test.forkEnv`, which puts a
  pnpm and Node dependency back into the Mill build that `build.mill:765-776` deliberately
  removed. That is the decisive argument.
- `playwright 1.63.0` is already in `frontend/package.json` devDependencies for `@vitest/browser`,
  so no new dependency, no `installBrowser` task, and no version pin to keep in step with a Scala
  binding.

Tasks:

1. `frontend/playwright.config.ts` with two projects: one against `vite preview` with `/api`
   proxied to a gateway, one against the M1 compose stack.
2. Port the page objects that still have a counterpart: `FallbackPanel`, `ErrorPage`,
   `NavigationPanel`, `SettingsPage`. Fix the two attribute-level mismatches while porting —
   `fallback-since time` carries `datetime`, not `data-datetime`
   (`shell/src/features/FallbackPanel.tsx:82`), and the tooltip element `#nav-<feature>-reason`
   does not exist, the reason being carried in `aria-label` only, so today's assertion degrades
   silently to `None`.
3. Add the testids the suite needs and the interface does not have, or drop the assertions
   deliberately: `brand-link` (`shell/src/chrome/BrandBlock.tsx` renders `.kui-brand` with no
   testid and no link), `build-version` (the build string is an unlabelled `<dd>` in
   `shell/src/pages/SettingsPage.tsx:110-125`), and `settings-build`. Note the two that were
   renamed rather than removed: `theme-switch` is now `theme-control`
   (`shell/src/chrome/TopBar.tsx:99`) and `page-clusters-dashboard` is now `clusters`
   (`feature-clusters/src/ClusterList.tsx:149`). Toasts changed shape, not name:
   `kernel/src/components/Toast.tsx` emits `toast-${id}`, so the selector must be a prefix match.
4. Write a clusters suite against the interface that exists, rather than porting one written for
   a screen that does not.
5. Port the fault-isolation suites (`CircuitBreakerSuite`, `ClusterServiceDownSuite`): those
   assert on the fallback panel and the nav `data-state` vocabulary, both of which survived intact
   (`shell/src/chrome/NavItem.tsx:84,101`, values in `chrome/types.ts:65`).
6. Delete `e2e/` and its `build.mill` block once the replacement is green — not before.
7. Replace `ShellSmokeSuite`'s bundle-shape assertion with a build-time check of
   `dist/.vite/manifest.json`. Its current form reasons about the Scala.js linker and `main.js`;
   Vite emits `assets/<package>-<hash>.js`, and the existing `.endsWith(".js") && contains("clusters")`
   heuristic passes by coincidence.

**Exit criterion:** `pnpm test:e2e` from `frontend/` is green against the M1 stack, and CI runs it.

Not verified by the survey: whether the nav destination ids are literally `clusters` and
`settings` at runtime — they come from capabilities and configuration, not from a literal in the
source — and whether `ClusterList` reads the gateway or a fixture on the path the suite exercises.
Both change what a ported clusters assertion may claim, and both are answerable by running M1.

---

### M3 — Documentation that describes this repository. **FRONTEND** (docs only)

**Goal:** a new contributor who reads `README.md` is not sent to files that do not exist.

The docs survey found the frontend prose wrong in roughly a dozen files. It is placed here, ahead
of features, because M1 and M2 change the commands, and rewriting twice is waste — but it must not
slip past M2, since `README.md:36` currently opens with "It is Scala from the browser down."

Tasks, in descending order of how many readers they mislead:

1. `README.md` — lines 36-38, 165-175, 217, 289-305, 314-323, 343-350, 449-463. The last of these
   is a whole section describing a gateway that serves `main.js` and `kui.css` from
   `services/gateway/api/resources/web/`, a directory that does not exist.
2. `docs/frontend/README.md`, `features.md`, `components.md`, `style-surface.md` — these describe
   Laminar, Airstream, Waypoint, `frontend.css`, `ModuleSplitStyle` and `checkBundleShape`.
   `docs/frontend/features.md` is an inoperable procedure end to end. `docs/frontend/tokens.md` is
   already correct and cites `frontend/packages/kernel/styles/10-tokens.css`; leave it.
3. `docs/development/toolchain.md` — the document exists to explain that Mill needs Node. The
   truth is now the reverse. `deployment/storybook/README.md:23,67-70` already carries the correct
   pins (Node 22.13.0, pnpm 11.25); make that the single source.
4. `ARCHITECTURE.md` lines 35, 39, 100, 455, 636, 877-879, 991, 1013.
5. `DEPENDENCY_MATRIX.md` — remove the nine `js`-scoped Scala.js rows (L21, L131-137), the two
   `frontend/ui-kernel` module columns (L46, L53), the import-map npm rows (L138-139), the
   `mill-scalablytyped` rows (L140, L190) and the Laminar upgrade watch (L178); add rows for
   SolidJS 2, TypeScript, Vite, Vitest and pnpm, which have none.
6. ADR status lines. ADR-011 and ADR-012 are correctly marked. ADR-048 declares that it also
   amends ADR-018, ADR-024 and ADR-025, and none of those three says so in its own status line —
   a reader arriving at ADR-024 has no way to learn it was amended. ADR-001's title and decision
   still name Scala.js 1.22.0 and are not marked amended.
7. `docs/FEATURE_MATRIX.md` — the MFE column legend (L22-23) and ~35 feature rows use the dead
   `ui-*` names; KU-004 is marked COMPLETE against Scala.js module splitting (L384); AU-001's
   evidence (L278) cites `frontend/ui-shell/.../LoginPage.scala`, so the recorded evidence points
   at nothing.
8. `frontend/README.md` and `frontend/packages/api/README.md` — both were written mid-migration
   and describe a coexistence that has ended. `frontend/README.md:57-62` lists eight
   `frontend.solid.*` Mill targets, none of which exist.
9. `deployment/frontend/README.md` — frames itself as a preview alongside a Scala.js frontend that
   is now deleted.
10. `services/gateway/api/src/kui/gateway/api/static/StaticRoutes.scala:12-31` — scaladoc citing
    ADR-011 and Waypoint. Out of the docs survey's stated scope, but it is the file a reader
    reaches when `/ui/` misbehaves.

**Exit criterion:** `grep -rn "Scala.js\|Laminar\|Airstream\|Waypoint\|ui-shell\|ui-kernel\|fastLinkJS\|fullLinkJS" README.md ARCHITECTURE.md CONTRIBUTING.md docs/ frontend/ deployment/ --include=*.md`
returns only ADR bodies that are deliberately historical, and every Mill or pnpm command quoted in
a Markdown file can be run.

---

### M4 — Topic management completed. **FRONTEND**

**Goal:** close the topic endpoints that exist on the server and have no consumer. This is the
largest single block of unused backend surface.

All five endpoints already exist in `docs/api/openapi.browser.json`; nothing here needs a service
written.

| Endpoint | What it gives the interface |
|---|---|
| `GET …/topics/{topicName}/partitions` | The full partition table. The topic page reads only `/overview`, whose OpenAPI summary says it carries "the head of its partition table". |
| `POST …/topics/{topicName}/partitions/plan` and `POST …/partitions` | Raise a partition count, through the same plan-then-confirm pair every other mutation uses. Table-stakes in both reference products. |
| `GET …/topics/{topic}/consumer-groups` | The per-topic consumers tab: every group reading this topic, with its lag on this topic alone. |
| `GET …/topics/{topicName}` | Unclear. The topic page uses `/overview` exclusively. Either this is adopted or it is a deletion candidate on the server; the survey could not determine which was intended, and this milestone must decide before it builds against it. |

`PlannedActionDialog` in `feature-topics` already implements the plan-token pattern for purge and
delete, so add-partitions reuses it rather than inventing a second confirmation shape.

**Exit criterion:** an e2e test raises a topic's partition count from 1 to 3 through the interface
and the partition table shows three rows.

---

### M5 — Sign-out, and the rest of the shell's own surface. **FRONTEND**

**Goal:** close the shell-level gaps, including one that is a security-relevant omission.

1. `POST /api/v1/auth/logout` has no call site anywhere. Grepping for `auth/logout`, `signOut` and
   `sign-out` finds nothing outside `schema.d.ts`. There is a sign-in page and no way out of it.
2. `GET /api/v1/info` — the version and deployment surface. `shell/src/bootstrap.ts:30,47` reads
   `buildVersion` from injected bootstrap JSON instead, which M1 makes real; whether both are
   wanted is a decision this milestone makes rather than assumes.
3. `POST …/clusters/{clusterId}/refresh` and `POST …/topics/refresh` — a manual refresh
   affordance on the cluster and topic screens.
4. `BrokerDetail` is unreachable. `feature-clusters/src/BrokerDetail.tsx:83` is exported from the
   package index and used by stories and tests only; `ClustersRoute.tsx:59` branches on
   `clusterId` alone, so `/clusters/:c/brokers/:brokerId` renders the broker *list*. The link
   exists and lands on the wrong screen. The file says so: `ClustersRoute.tsx:15`, "one broker
   (not yet built here)". Wire the route, and give it
   `GET …/brokers/{brokerId}/configs`, which also has no consumer today.
5. Collapse the five copies of `useFetch` — `ClustersRoute.tsx:203`, `TopicsRoute.tsx:93`,
   `ConsumersRoute.tsx:50`, `SchemasRoute.tsx:54` and an inline one in `GroupRoute.tsx:46` — onto
   `kernel/src/data/query/cache.ts`, which exists and is used by no route. Each copy carries a
   comment saying the kernel's cache should replace it; the third and fifth copies count
   themselves in their comments.

**Exit criterion:** an e2e test signs in, signs out, and is returned to the sign-in page;
`/clusters/:c/brokers/:id` renders broker detail; `grep -c "function useFetch" frontend/packages/*/src/*.tsx`
totals one.

---

### M6 — Message browsing, completed. **FRONTEND**

**Goal:** three endpoints that make the message screen match the reference products.

- `POST …/messages/filters` and `POST …/messages/filters/test` — server-side smart filters, and
  the try-it-against-one-record preview that makes writing them tolerable. The pair is only useful
  together.
- `POST …/topics/{topicName}/messages/resend` — copy a range of records into another topic.
  Replay is a Kouncil and Kafbat parity feature and one of the reasons an operator opens this
  screen.

**Exit criterion:** an e2e test registers a filter, previews it against a record, browses with it
applied, and resends one record to a second topic where a second browse finds it.

---

### M7 — Consumers and schemas, completed. **FRONTEND**

- `GET …/consumer-groups/lag` — "which groups' lag changed since the given token". The consumers
  list currently refetches the whole group list, which is the expensive call on a cluster with
  many groups.
- `POST …/schemas/subjects/{subject}/versions/{version}/compatibility` — check a proposed schema
  before registering it. Without this the subject page can display compatibility settings but
  cannot answer the question an operator actually has.

**Exit criterion:** the consumers list refreshes lag without refetching the group list (assert on
the request count in a Playwright route handler); the subject page rejects an incompatible schema
before any write is attempted.

---

### M8 — Connect. **BACKEND first, then frontend**

There is no Connect service. `grep -n "object connect" build.mill` returns nothing; the eleven
service trees under `services/` do not include one. `kernel/src/components/ConnectorCard.tsx` and
its only consumer `TaskBar.tsx` exist, are exported from `kernel/src/index.ts`, and are referenced
by nothing but their own stories.

The backend work — bounded context, contract, connectors and tasks, restart and pause operations,
fault isolation, capability registration — is a full service milestone and belongs in
`docs/ROADMAP.md`, not here. The frontend work afterwards is one feature package registered in
`shell/src/features/registry.ts`, following the shape of `feature-schemas`.

**Exit criterion (frontend half):** `/clusters/:c/connect` lists connectors read from the gateway,
and the nav entry shows `unavailable` rather than disappearing when the Connect service is down.

---

### M9 — ksqlDB. **BACKEND first, then frontend**

Identical situation. No ksql service exists. `kernel/src/components/KsqlWorkspace.tsx` is
Storybook-only. Same split: the service is a backend milestone; the frontend is one feature
package afterwards.

---

### M10 — Security, metrics and configuration screens. **BACKEND first**

`shell/src/features/registry.ts:34-111` registers five features. The architecture names twelve
services. ACL and quotas, metrics and configuration have neither a service nor a frontend
component, so there is nothing to Storybook and nothing to wire. Listed here so the gap is
recorded rather than discovered.

---

## Known gaps that are deliberate

- **Connect and ksqlDB are Storybook-only.** `ConnectorCard`, `TaskBar` and `KsqlWorkspace` were
  built from the design reference before the services existed. They stay unregistered because a
  nav entry that leads to a screen with no data source is worse than an absent one. See M8 and M9.
- **`FilterChips` has no consumer.** It is re-exported from `kernel/src/components/index.ts` and
  used by its own story only. It is intended for the smart-filter work in M6.
- **The notifications panel is hard-coded empty.** `shell/src/App.tsx:484` passes
  `{ kind: "ready", notices: [] }` with the comment "There is no notification service yet." The
  panel renders its empty state honestly rather than being hidden, so the shape is proven and the
  wiring is one line when a source exists.
- **The storage meter shows "not known".** `shell/src/App.tsx:447-455` gives `NavDrawer` no disk
  figures, because per-broker disk is not on the overview's model. `GET …/log-dirs` is already
  called by `overview/load.ts:192`, so this is a model-shape question and not a missing endpoint —
  it is deliberately deferred rather than blocked.
- **Global search is inert.** `shell/src/App.tsx:466-476` passes a static value and a no-op
  handler. The `⌘K` shortcut focuses a field that does nothing. There is no search endpoint in
  `docs/api/openapi.browser.json`; the affordance exists because the design has it and removing it
  would have to be undone.
- **Every `fixtures.ts` and `recorded/*.json` file is test-only.** Verified: each is imported only
  from `*.stories.tsx`, `*.test.tsx` or `recorded.test.ts`. No route renders fixture data. Three
  comments (`feature-clusters/src/index.tsx:24`, `feature-consumers/src/index.tsx:27`,
  `kernel/src/feature/context.tsx:13`) record that this was once true and no longer is; they
  should be corrected in M3, but the state they describe is already gone.
- **`GET /api/v1/auth/oidc/callback` is not counted as a gap.** It is a browser redirect target,
  not a fetch. Whether the shell handles the redirect landing was not verified and should be
  confirmed during M1, when a real OIDC provider can be pointed at the stack.

## Not doing

- **Repairing the Scala e2e suite in place.** ~400 lines of Scala churn plus the same harness work
  as the replacement, and it ends with a pnpm and Node dependency added back to `e2e.test.forkEnv`
  — the exact coupling `build.mill:765-776` was written to remove. It also leaves the selectors in
  `.scala` while they live in `.tsx`, so the person who renames a testid is not the person whose
  test breaks.
- **Serving the interface from the gateway jar in production.** The two-image split is the point of
  ADR-048's deployment shape: the interface can be rebuilt or rolled back without reassembling a
  jar. The `./mill dev` symlink (`build.mill:3024-3050`, `out/dev-assets/solid/web -> frontend/dist`)
  stays, because a single-process development loop is worth the special case; the release path
  does not get one.
- **A brotli-enabled nginx image.** `nginxinc/nginx-unprivileged:1.27-alpine` has no brotli module,
  and building one would mean maintaining a custom base image. `gzip_static` over Vite's output is
  the cheaper answer when compression is addressed; it is not addressed in M1, which is about
  making the stack run at all.
- **Restoring cross-compiled contracts as the browser's type source.** Types now come from the
  committed `docs/api/openapi.browser.json`. That is a weaker guarantee than compilation gave —
  `docs/domain/context-map.md:41` still describes the old one and needs correcting in M3 — but the
  replacement is a checkable one (`frontend.apiConstants --check`, plus regenerating the types in
  CI and failing on a diff), and it is what keeps Mill free of Node.
- **Removing the `<!--KUI_BOOTSTRAP-->` and `<!--KUI_BASE_HREF-->` markers from
  `frontend/index.html`.** They look vestigial now that `IndexHtml.scala` is not the only thing
  filling them, but they are how the frontend container learns its base path and build version at
  start. M1 makes the second filler real rather than deleting the mechanism.
