# Frontend research: Scala.js + Laminar shell and microfrontends

**Title:** KUI frontend platform research (Laminar / Waypoint / Scala.js module splitting / facades / CSS / testing)
**Date:** 2026-09-03

## Questions

1. What does the Kafbat React frontend actually contain (routes, pages, shared components, state, API client, SSE, theming, i18n, a11y), so that KUI's feature matrix and kernel scope are grounded in facts rather than memory?
2. Which Kouncil UX patterns differ from Kafbat and are worth adopting?
3. What are the current Laminar / Airstream / Waypoint versions and idioms for state, components, routing, forms, error handling, SSE and HTTP?
4. Which microfrontend loading option (A / B / C) is right for Scala.js 1.x + Mill, with evidence?
5. Which Scala.js facades exist (or must be written) for a virtualized table, JSON viewer/editor, code editor, date picker and charts?
6. Which CSS / design-system / dark-mode strategy should KUI use?
7. How do we unit-test Scala.js code and run Playwright E2E from Mill?

## Method and sources

Local reference checkouts (read-only):

- `/tmp/kui-ref/kafbat/frontend` (React 18 SPA), `/tmp/kui-ref/kafbat/e2e-playwright`, `/tmp/kui-ref/kafbat/contract-typespec`
- `/tmp/kui-ref/consdata/kouncil-frontend` (Angular 18 Nx monorepo)

Web sources (fetched 2026-09-03):

- Maven Central search API (`search.maven.org/solrsearch`) for artifact versions; note its index lagged several months behind GitHub tags, so GitHub tags/`project/Versions.scala` were used as the authority where they disagreed.
- GitHub: `raquo/Laminar` (tags, `project/Versions.scala`, `project/plugins.sbt`, draft blog post `website/blog/2026-09-30-laminar-v18.0.0.md`), `raquo/Airstream` (README, tags), `raquo/Waypoint` (README, CHANGELOG, tags), `com-lihaoyi/mill` `libs/scalajslib/src/mill/scalajslib/ScalaJSModule.scala`.
- Docs: `laminar.dev/blog`, `scala-js.org/news`, `scala-js.org/doc/project/module.html`, `scala-js.org/doc/project/js-environments.html`, `mill-build.org/mill/scalalib/web-examples.html` (Mill 1.1.8), `mill-build.org/mill/javascriptlib/intro.html`, `scalablytyped.org`, `tapir.softwaremill.com/en/latest/client/sttp4.html`, `sttp.softwaremill.com/en/latest/backends/javascript/fetch.html`.
- Community: `lolgab/mill-scalablytyped`, `scala-js/vite-plugin-scalajs`, `incrementum/vite-scala-js-mill`, `gmkumar2005/scala-js-env-playwright`, `indoorvivants/weaver-playwright`, `raquo/scala-dom-testutils`, `sherpal/LaminarSAPUI5Bindings`, `nguyenyou/ui5-webcomponents-laminar`, `raquo/laminar-shoelace-components`, `sjrd/scalajs-sbt-vite-laminar-chartjs-example`, `japgolly/scalacss`.

Version table (as of 2026-09-03):

| Library | Latest stable | Latest pre-release | Source |
| --- | --- | --- | --- |
| Scala.js | 1.22.0 (2026-06-20; sbt 2 support, Wasm backend stable) | — | scala-js.org/news |
| Laminar | 17.2.1 (2025-03-26) | 18.0.0-M5 | GitHub tags; laminar.dev/blog |
| Airstream | 17.2.1 | 18.0.0-M5 | GitHub tags |
| Waypoint | 9.0.0 (Dec 2024) | 10.0.0-M7 (targets Laminar 18, URL-DSL 0.7.0) | Waypoint CHANGELOG |
| scala-js-dom | 2.8.0 on Maven index; Laminar master pins 2.8.1 | — | `project/Versions.scala` |
| Laminar's own toolchain | Scala 3.3.7 / 2.13.18, sbt-scalajs 1.20.2 | — | `project/Versions.scala`, `project/plugins.sbt` |
| Mill | 1.1.8 (docs); Scala.js examples use Scala.js 1.20.2, Scala 3.8.2 | — | mill-build.org |
| sttp client4 core (sjs) | 4.0.3+ (index lag; tapir docs reference client4) | — | Maven index / tapir docs |
| tapir-sttp-client4 (sjs) | 1.11.25 on index; tapir docs cite 1.13.31 | — | Maven index / tapir docs |
| MUnit (sjs) | 1.1.1 | — | Maven index |
| Iron (sjs) | 3.0.2 | — | Maven index |
| ScalaCSS | 1.0.0 (Nov 2022, last release) | — | GitHub releases |
| scala-dom-testutils | 19.0.0 (used by Laminar master) | — | `project/Versions.scala` |
| scala-js-env-jsdom-nodejs | 1.0.0 | — | scala-js.org js-environments |
| scala-js-env-playwright | 0.1.18 | — | Scaladex |

Caveat: Laminar 18 is at M5 with a *draft* release post dated 2026-09-30 in the repo (`website/blog/2026-09-30-laminar-v18.0.0.md`, still containing `#TODO` markers). Treat 18.0.0 final as "imminent but not shipped" at the time of writing.

---

## Findings

### 1. Kafbat frontend inventory (React)

Root: `/tmp/kui-ref/kafbat/frontend`.

#### 1.1 Stack (`package.json`)

| Library | Version | Line |
| --- | --- | --- |
| react / react-dom | 18.2.0 | :29, :32 |
| react-router-dom | 6.30.3 | :39 |
| @tanstack/react-query | 5.95.2 | :12 |
| @tanstack/react-table | 8.21.3 | :14 |
| zustand | 4.5.7 | :44 |
| styled-components | 6.3.12 | :41 |
| ace-builds / react-ace | 1.43.6 / 11.0.1 | :15, :30 |
| react-hook-form / @hookform/resolvers / yup | 7.72.0 / 2.7.1 / 1.7.1 | :34, :10, :43 |
| @microsoft/fetch-event-source | 2.0.1 | :5 |
| @floating-ui/react (tooltips) | 0.26.13 | :4 |
| react-hot-toast | 2.4.1 | :36 |
| jsonpath-plus / lossless-json | 10.4.0 / 2.0.11 | :19, :20 |
| ajv (+draft-04, formats) / json-schema-faker | 8.18.0 / 3.0.1 / 0.5.6 | :16-18, :21 |
| react-datepicker / react-multi-select-component / use-debounce | 7.4.0 / 4.3.4 / 10.1.0 | :31, :38, :42 |
| vite / @vitejs/plugin-react-swc | 6.4.2 / 3.11.0 | :121, :86 |
| jest / jest-environment-jsdom / @swc/jest | 29.7.0 | :110, :111, :51 |
| @testing-library/{dom,react,user-event,jest-dom} | 10.4.1 / 14.3.1 / 14.6.1 / 6.9.1 | :53-56 |
| @openapitools/openapi-generator-cli | 2.31.0 | :50 |

Absent: no i18n library, no msw (HTTP mocking is `fetch-mock` 9.11.0 :107), no Playwright in `frontend/` (lives in `e2e-playwright/`), no virtualization library.

Scripts (`package.json:60-73`): `gen:sources` (:63) removes `src/generated-sources`, builds the TypeSpec contract in `../contract-typespec/api` and runs `openapi-generator-cli generate`; `build` (:64) = `gen:sources && tsc --noEmit && vite build`; `test` (:70) = `jest --watch`; `test:CI` (:72) adds coverage.

#### 1.2 Routing tree

Top-level router `src/components/App.tsx:83-110`: `Dashboard` at `/`, `/ui`, `/ui/clusters` (:84-90); `ClusterConfigForm` at `clusterNewConfigPath/*` (:91-94); `ClusterPage` at `clusterPath()/*` (:95-98); `/403` (:99); `/404` (:105); catch-all redirect (:106). All pages are `React.lazy` (:32-38) under one `Suspense` (:77).

`src/lib/paths.ts` constants: `RouteParams` enum :12-20 (`:clusterName`, `:consumerGroupID`, `:subject`, `:topicName`, `:connectName`, `:connectorName`, `:brokerId`); `clusterPath` :27 (`/ui/clusters/<name>`); brokers :39-63; consumer groups :78-95 (`consumer-groups`, `reset-offsets`); schemas :102-133 (`schemas`, `create-new`, `edit`, `compare`); topics :141-202 (`all-topics`, `create-new-topic`, `copy`, tabs `settings|messages|consumer-groups|statistics|connectors|acls|edit`); Kafka Connect :210-295; KsqlDB :304-320 (`ksqldb`, `query`, `tables`, `streams`); cluster config :323-329; ACL :332-336.

Nesting: `App` -> `ClusterPage` (`src/components/ClusterPage/ClusterPage.tsx:82-142`) mounts feature routes conditionally on `ClusterFeaturesEnum` flags computed at :46-72 (schema registry :95, connect :101-114, ksqlDB :120, ACL :126, dynamic config :132), default redirect to `brokers` (:138). This is exactly KUI's "capability-gated navigation" idea, implemented with per-cluster feature flags rather than a service capability registry. -> `Topics` (`src/components/Topics/Topics.tsx:18-30`) -> `Topic.tsx` renders a `Navbar role="navigation"` (:220-285) plus inner `<Routes>` (:291+).

Page directories (`src/components/`): `ACLPage/`, `AuthPage/` (login rendered outside providers, `App.tsx:68,73`), `Brokers/` (list, broker metrics/configs), `ClusterPage/`, `Connect/` (Clusters, Details, List, New), `ConsumerGroups/` (List, Details incl. reset offsets), `Dashboard/`, `ErrorPage/`, `KsqlDb/` (Query editor + results, TableView), `Nav/` (sidebar), `NavBar/` (top bar, theme dropdown, UserInfo, UserTimezone), `PageContainer/`, `Schemas/` (List, Details, New, Edit, Diff), `Topics/` (List, New, shared/Form, Topic/{Messages, SendMessage, Settings, Statistics, Overview, ConsumerGroups, Connectors, Acls, Edit/DangerZone}), `Version/`, `contexts/` (Cluster, Confirm, GlobalSettings, ThemeMode, UserInfoRolesAccess), `src/widgets/ClusterConfigForm/` (dynamic cluster-config wizard).

#### 1.3 Shared components (`src/components/common/`)

- **Table (modern)** `NewTable/Table.tsx:11-21`: TanStack Table v8 with core/expanded/faceted/filtered/pagination/sorted row models. Sorting and pagination state live in `URLSearchParams` (:87-102; `page`, `perPage`, `sortBy`, `sortDirection`; `PER_PAGE = 25` at `src/lib/constants.ts:59`); `serverSideProcessing` prop (:46). Features: expandable rows (:49-50), row selection + batch bar (:53-54), column resizing with persister (:60-61, `ColumnResizer/lib/persister`), column visibility (:67), row click/hover (:75-78), CSV export (`utils/exportTableCSV.ts`). Cell kit: `BreakableTextCell, ColoredCell, ExpanderCell, LinkCell, MultiLineTagCell, SelectRowCell, SizeCell, TagCell, TimestampCell`. **No virtualization anywhere** (no `@tanstack/react-virtual`, zero `virtual` matches in `src`). Legacy `common/table/` (styled `<Table>` + `TableHeaderCell`) is still used by the message list.
- **Editor** `Editor/Editor.tsx:1-38`: `react-ace`, modes `json5` / `protobuf` (:20-24), theme `tomorrow` (:25), searchbox ext (:2). `SQLEditor/SQLEditor.tsx:21` mode `sql`. `EditorViewer/EditorViewer.tsx:4` read-only viewer using `lossless-json`. `DiffViewer/` for schema compare.
- **Forms** `Input/Input.tsx:2-4,14` binds `useFormContext` + `@hookform/error-message`; `Select/Select.tsx:1-20` custom listbox; plus `Checkbox, Radio, Switch, MultiSelect, InputWithOptions, Textbox`. Pattern: `src/components/Topics/New/New.tsx:23-25,57` `useForm({ resolver: yupResolver(topicFormValidationSchema) })` inside `<FormProvider>`; yup extended with `isJsonObject` in `src/lib/yupExtended.ts:5-40`.
- **Modals** `ConfirmationModal/ConfirmationModal.tsx:7-30` is a singleton mounted once (`App.tsx:114`), driven by `ConfirmContext` (`contexts/ConfirmContext.tsx:11-30`) and `useConfirm()` (`src/lib/hooks/useConfirm.ts:4-21`). `SlidingSidebar/` hosts produce dialog and filters.
- **Toasts** `react-hot-toast` `<Toaster position="bottom-right"/>` (`App.tsx:112`); `showAlert/showServerError/showSuccessAlert` (`src/lib/errorHandling.tsx:54-60`); query/mutation errors funnel through `QueryCache`/`MutationCache` `onError` (`App.tsx:40-64`).
- **Misc** `Tooltip/Tooltip.tsx:1-30` (floating-ui), `Dropdown/Dropdown.tsx:1-25` (`@szhsin/react-menu`), `PageLoader`, `Search/Search.tsx:40-54` (500 ms debounce writes `q` and resets `page`), `Metrics/{Wrapper,Section,Indicator}`, `ActionComponent/` (permission-aware Button/NavLink/Select wrappers), `RefreshRateSelect`, `DownloadCsvButton`, `SuspenseQueryComponent`.

#### 1.4 State management

React Query hook files `src/lib/hooks/api/`: `acl.ts, appConfig.ts, brokers.ts, clusters.ts, consumers.ts, kafkaConnect.ts, ksqlDb.tsx, latestVersion.ts, roles.ts, schemas.ts, topicMessages.tsx, topics.ts`.

Representative keys:
- `topicKeys` factory `topics.ts:43-61`: `all = ['clusters', cluster, 'topics']`, `list = [...all, filters]`, `details = [...all, topicName]`, then `config|schema|consumerGroups|statistics|connectors|acls`.
- Consumer groups `consumers.ts:50` `['clusters', clusterName, 'consumerGroups', rest]`; detail :66.
- Serdes `topicMessages.tsx:196` `['clusters', c, 'topics', t, 'serdes', use]` via `useSuspenseQuery`, refetch disabled (:198-200).
- Schemas: string constants `src/lib/queries.ts:4-8`.

Invalidation: mutations call `queryClient.invalidateQueries` in `onSuccess`; topics invalidate the whole `topicKeys.all` subtree (`topics.ts:187,239,255,271,284,308,321,335`), consumers invalidate `['clusters', c, 'consumerGroups']` (`consumers.ts:87-88,110-111,133-134`), schemas use predicate invalidation (`schemas.ts:133-136,162-165,188-194`).

Zustand: a single store `src/lib/hooks/useMessageFiltersStore.ts:32-68` with `persist` middleware, localStorage key `${prefix}-message-filters` (:65; prefix `kafbat-ui` at `constants.ts:66`), `partialize` persists only `filters` (:66) so `nextCursor` (:36,:62) stays in memory. Everything else is React context or URL search params.

Mapping to KUI: react-query "server state + keys + invalidation" maps to Airstream `Signal`s fed by `EventStream`s from the Tapir client, with an explicit per-feature `QueryCache` keyed by a Scala ADT (see §3.2). The zustand persisted store maps 1:1 to Airstream 17.2+ `WebStorageVar` (localStorage-backed `Var`, Airstream README "LocalStorage" section).

#### 1.5 API client

Generated by `openapi-generator-cli` (`typescript-fetch`) into `src/generated-sources`, config `frontend/openapitools.json` (generator 5.3.0, glob `../contract-typespec/build/tsp/api/openapi.yaml`, `enumPropertyNaming: UPPERCASE`, `withInterfaces`). `src/lib/api.ts:18-31` builds one `Configuration(BASE_PARAMS)` and exports singletons (`topicsApiClient`, `messagesApiClient`, `clustersApiClient`, `schemasApiClient`, `kafkaConnectApiClient`, `consumerGroupsApiClient`, `authApiClient`, `appConfigApiClient`, `aclApiClient`, `ksqlDbApiClient`, `brokersApiClient`, `internalApiClient`). `BASE_PARAMS` (`src/lib/constants.ts:13-19`): `basePath: window.basePath || ''`, `credentials: 'include'`. No fetch middleware, no 401 redirect in code: errors normalized in `src/lib/errorHandling.tsx:14-46` and surfaced as toasts; 403 is a route (`App.tsx:99-104`). Dev proxy for `/login`, `/logout`, `/api` in `vite.config.ts:90-116`.

#### 1.6 SSE / message streaming

`src/lib/hooks/api/topicMessages.tsx:31-190` (`useTopicMessages`). Uses `fetchEventSource` (:2, :119), not `EventSource`, against `GET /api/clusters/{c}/topics/{t}/messages/v2` (:62-64), `openWhenHidden: true` (:122). Params (:66-104; names in `constants.ts:140-155`): `limit` (default `100`, `constants.ts:60`), `mode`, optional `stringFilter`, `keySerde`, `valueSerde`, `smartFilterId`, `timestamp`/`offset` per mode (:83-99), `partitions` (:101-104), `cursor` from the zustand `nextCursor` (:105-117).

Events (:132-159) typed `TopicMessageEvent`: `cursor.id` pushed to store (:136-138); `MESSAGE` appends, or prepends in `PollingMode.TAILING` (:141-150); `PHASE` sets phase (:151-152); `CONSUMING` sets stats (:154-155); `DONE`/`EMIT_THROTTLING` ignored (:157). `onopen` clears list on 200 and toasts 4xx != 429 (:123-131); `onclose` resets (:160-163); `onerror` clears cursor and toasts (:164-173). Abort via `AbortController` ref (:41, :46-52), aborted before every new fetch and in effect cleanup (:177-181). Memory: page messages kept in a plain `useState<TopicMessage[]>` (:36); bound is the server-side `limit` (100) per cursor page.

Implication for KUI: the reason Kafbat uses `fetch-event-source` instead of native `EventSource` is that the endpoint needs custom headers/credentials and abort. Native `EventSource` is GET-only, no custom headers, auto-reconnects. KUI must decide per stream (see §3.6).

#### 1.7 Theming / CSS

`src/theme/theme.ts` (1664 lines): `Colors` palette :3+, `baseTheme` :112, light `theme` :416, `darkTheme` :983. Applied through styled-components `ThemeProvider` (`App.tsx:72`). Dark mode: `contexts/ThemeModeContext.tsx:11-58` with `auto_theme | light_theme | dark_theme`, `matchMedia('(prefers-color-scheme: dark)')` (:20,:29-34), persisted to `localStorage['mode']` (:39), rehydrated in `useLayoutEffect` (:24-27). Global styles `src/components/globalCss.ts` (Inter 14px base, theme-driven background :10). Plain CSS is minimal (`src/theme/minireset.css`, `src/theme/index.scss`, vendor `react-datepicker.css` at `Messages/Filters/Filters.tsx:1`). Styled-components everywhere via co-located `*.styled.ts`.

#### 1.8 i18n and accessibility

- i18n: none. Zero matches for `i18next`, `react-intl`, `useTranslation`. All copy is hardcoded English.
- a11y: `eslint-plugin-jsx-a11y` 6.10.2 (`package.json:99`) is the only tooling. Counts in `.tsx`: `aria-label` x42, `aria-disabled` x15, `aria-hidden` x14, `aria-labelledby` x10, `aria-describedby` x2; `role=` in 27 files; only 8 keyboard handlers. Example `ConfirmationModal.tsx:14-15` (`role="dialog" aria-label=...`). Tests rely on `data-testid` heavily (`Search.tsx:88`).

#### 1.9 Message browser UI (`src/components/Topics/Topic/Messages/`)

`Messages.tsx:9-29` = `useTopicMessages` -> `<Filters>` + `<MessagesTable>`. `Filters/Filters.tsx`: mode select (`src/lib/hooks/filterUtils.ts`), partitions multi-select (:9), serde selects (:14-15), `Search` (:12), `react-datepicker` for timestamp modes, all mirrored to URL params by `useMessagesFilters` (`src/lib/hooks/useMessagesFilters.ts:64-80`, `PER_PAGE = 100` :17) and per-topic localStorage. Client-side export to JSON/CSV with formula-injection escaping (:48-78). Smart filter (CEL) editor `Filters/AddEditFilterContainer.tsx` (RHF + yup :5-11, ace editor :9, `useRegisterSmartFilter` :12 -> `topicMessages.tsx:204-219` POST `registerFilter` returning `smartFilterId`), saved filters in the zustand store. `MessagesTable.tsx:29+`: legacy table, live mode `useIsLiveMode`, "load more" via `nextCursor` (:33-38), preview columns via JSONPath persisted in localStorage `message-preview` (:40-46, `PreviewModal.tsx`). `Message.tsx`: cells :119-126, expandable `MessageContent/MessageContent.tsx` (key/value/headers through `EditorViewer` :107), row dropdown copy/save (`useDataSaver` :161-170), permission-gated re-send (:171-183). Produce dialog `SendMessage/SendMessage.tsx:46+`: sliding sidebar, RHF `Controller`/`useWatch`, ace editors for key/value, serde selects from `useSerdes(SERIALIZE)` (:55-59) with per-serde parameters (:37-44), partition select, schema validation via ajv/json-schema-faker (`utils.ts:29`), submit via `useSendMessage`.

#### 1.10 Tests

Unit: Jest 29 + jsdom (`frontend/jest.config.ts:20`), `@swc/jest` (:22), 137 spec files co-located in `__test__`/`__tests__` (e.g. `src/components/Topics/Topic/Messages/__test__/{Messages,MessagesTable,Message,PreviewModal}.spec.tsx`, `src/components/common/NewTable/__test__/`), `fetch-mock` for HTTP, `src/lib/testHelpers.tsx`, fixtures `src/lib/fixtures/`.

E2E: separate module `/tmp/kui-ref/kafbat/e2e-playwright`: `@playwright/test` ^1.48.2 driven by Cucumber (`@cucumber/cucumber` ^11.3.0). Layout: `src/features/*.feature` (Brokers, KafkaConnect, KsqlDb, navigation, SchemaRegistry, Topics, TopicsActions, TopicsMessages), `src/steps/*.steps.ts`, page objects `src/pages/` (`BaseLocators.ts`, Brokers, Connectors, Consumers, Dashboard, KSQLDB, Panel, SchemaRegistry, Topics), `src/support/PlaywrightWorld.ts`, `src/hooks/hooks.ts`, `src/playwright.config.ts` (only `timeout: 30_000`), `config/cucumber.js`, scripts `package.json:5-13` (`test`, `test:stage`, `test:sp`, `debug`, `test:failed`, report generation), Dockerfile.

### 2. Kouncil frontend: differing UX patterns

Root: `/tmp/kui-ref/consdata/kouncil-frontend` (Nx, Angular 18.2.13, Angular Material, `package.json:16-25`).

- **Record browsing as a JSON-flattened grid.** `apps/kouncil/src/app/topic/topic.component.ts:25` uses `JsonGrid` (`topic/json-grid.ts`) to flatten message JSON into one dynamic column per JSON path, plus header columns prefixed `H[` (:288-294). Fixed columns partition/offset/key/timestamp are sticky (:230-266); columns are drag-reorderable (`cdkDropList` :48-50) and resizable/sortable. This is a richer default than Kafbat's opt-in JSONPath "preview" columns and is worth adopting as an optional "expand JSON into columns" mode.
- **Per-partition paging + offset jump.** Pager with "Items per partition" `[1,5,10,20,50,100,500,1000]` (`topic/topic-pagination.component.ts:53`), page in query params (`topic.component.ts:334`), offset jump box (`topic/toolbar/topic-toolbar.component.ts:33-42`), partition selector (`topic/topic-partitions.component.ts`).
- **Live mode by polling**, not SSE: `LiveUpdateState` toggle (`topic-toolbar.component.ts:6,25`), `setTimeout(...,1000)` delta loop (`topic.component.ts:145-155`), new rows highlighted via `kafka-row-delta` class (:157). Kafbat's SSE tailing is the better transport; Kouncil's "highlight new rows" is the better UX detail.
- **Message detail drawer** (right-side `DrawerService`, `topic/message/message-view.component.ts:22`): headers table with per-header copy, key/value via `ngx-json-viewer` with "deserialized from {format}" labels (:66-76), copy and **Resend event** (:83-91); clicking a header navigates to Track prefilled.
- **Track (event tracking) view** `apps/kouncil/src/app/track/`: `track-filter.component.ts:18` = correlation field/operator/value triple, multi-topic autocomplete, `datetime-local` range with cross-field validation, async toggle (:107). Backend `track.backend.service.ts:26` hits `/api/track/{async|sync}`; async results stream over STOMP WebSocket `rxStompService.watch('/topic/track/' + asyncHandle)` (`track-result.component.ts:210-227`) into the same JsonGrid table (OnPush to avoid flicker, :48). This is a cross-topic correlation search Kafbat lacks; KUI can offer it later over SSE (in the message service) without a WebSocket stack.
- **Send dialog with schema-aware Monaco**: `libs/feat-send/src/lib/send/send.component.ts:27` uses two Monaco editors (key + value) with JSON-schema validation/completion fed from Schema Registry (:184-213); samples generated via `json-schema-faker` and typed randomizers (`libs/schema-registry/src/lib/generators/*`). Monaco is loaded lazily from `./assets/monaco-editor/min/vs` (`libs/common-components/src/lib/editor/monaco-editor.service.ts:31`).
- **Layout**: collapsible icon+label sidebar gated per `SystemFunctionName` (`sidebar/sidebar.component.ts:11,69-73`); toolbar with global search (accesskey `/`), cluster `mat-select` persisted to `localStorage['lastSelectedServer']` then re-navigating the current URL (`toolbar/toolbar.component.ts:113-117`). No dark mode (single SCSS palette `apps/kouncil/src/styles/_palette.scss`). No virtual scroll: `MatTableDataSource` renders all rows (`libs/common-components/src/lib/table/table.component.ts:62,84-90`); large lists rely on server-side per-partition paging.
- Other differentiators: favourites pinned to top of lists (`libs/feat-favourites/src/lib/favourites.service.ts:24,33`), resend between topics with offset ranges (`libs/resend-events/src/lib/resend/resend.component.ts:11`), data-masking policies UI (`libs/feat-data-masking/`), permissions matrix screen (`libs/feat-user-groups/src/lib/user-groups-functions-matrix/`), route-level roles in `routing/routing.module.ts:72-246` (`data.roles: SystemFunctionName[]`), global `ReloadingRouterStrategy` disabling route reuse (:44-70).

### 3. Laminar / Airstream / Waypoint: versions and idioms

#### 3.1 Versions and compatibility

- Laminar 17.2.1 / Airstream 17.2.1 (Mar 2025) are the current stable pair; Waypoint 9.0.0 pairs with Laminar 17.2.x. Laminar master pins Scala 3.3.7, scala-js-dom 2.8.1, sbt-scalajs 1.20.2, scala-dom-testutils 19.0.0 (`project/Versions.scala`, `project/plugins.sbt`).
- Laminar 18.0.0-M5 / Airstream 18.0.0-M5 / Waypoint 10.0.0-M7 form the next pair. Draft release notes (`website/blog/2026-09-30-laminar-v18.0.0.md`) list: MathML support; `split` deprecated in favour of `splitSeq` (single `KeyedStrictSignal` argument, `.now()` for initial value; lines 228-290); `handleCase`/`handleType` signature change (:292-295); `Var.zoom` without owner (:297); `Signal.changes` renamed to `updates` (:341); N-arity combinators to 22 (:350); `Signal.toStream`; **`EventStream.dynamicImport(resource)` / `stream.dynamicImport(ev => resource)` and signal variants that wrap Scala.js `js.dynamicImport` for module splitting** (:350-353, fixes Waypoint#24). Breaking: `child(el) <-- boolSignal` removed, numeric style props read as `Double | Int`, `htmlProp` takes `reflectedAttrName`, codec objects relocated (:206-225).
- Waypoint 10 master: `endOfSegments` becomes the default (URL-DSL 0.7.0), `Router` takes by-name `currentUrl` instead of `initialUrl`, fragment paths not starting with `/` are accepted (Keycloak `#state=` fix), `Route.matchRelativeUrl -> matchAbsoluteUrl` (Waypoint CHANGELOG `master` section). Waypoint 9.0.0 added `Route.Total`/`Route.Partial` (`route.argsFromPageTotal`, `route.relativeUrlForPage`), `Router.replacePageTitle`, and depends on Laminar itself (not just Airstream). 10.0.0-M1 added `navigateTo(Signal[Page])`, `Router.In[-Page]`/`Out[+Page]`/`All[Page]`, `WaypointException`.
- Scala.js 1.22.0 (Jun 2026) is the current release; 1.21.0 (Apr 2026) disabled Google Closure Compiler by default and requires JDK 17+. Since KUI uses `ModuleKind.ESModule`, GCC was never applicable ("the Google Closure Compiler cannot be used with them", module.html), so 1.21's change costs nothing.

Recommendation: start M0 on **Laminar 17.2.1 / Airstream 17.2.1 / Waypoint 9.0.0 / Scala.js 1.20.2** (the exact combination Laminar itself builds and tests with) and plan an upgrade task to the 18 / 10 line once 18.0.0 final ships. The 18 `dynamicImport` operators and `splitSeq` are attractive but not blocking: the kernel can wrap `js.dynamicImport` in a five-line helper today (see §4).

#### 3.2 State idioms (Var / Signal / EventStream)

Airstream README distinctions: `EventStream` = lazy stream of discrete events, no current value; `Signal` = time-varying value that always has a current value ("state"); `Var` = writable Signal source; `Val` = constant Signal; `EventBus` = writable EventStream source. Subscriptions require an `Owner` ("It is impossible to create a subscription without specifying when it shall be destroyed"); in Laminar every element is a `DynamicOwner`, so `<--` bindings die with the element (README "Ownership", "Dynamic Ownership").

KUI mapping of Kafbat concepts:

| Kafbat | KUI (Airstream) |
| --- | --- |
| react-query `useQuery(key)` | `QueryCache[K, A]` in kernel: `Map[K, Var[Status[K, Either[ApiError, A]]]]`, using Airstream's `Status`/`Pending`/`Resolved` (README "Async Status Operators") and `flatMapWithStatus`; TTL/staleness stored alongside |
| `invalidateQueries(prefix)` | `cache.invalidate(k => k.startsWithPrefix(...))` re-emitting on an `EventBus[K]` that active queries `sample` |
| `useSuspenseQuery` | `Signal[Status[...]]` rendered with `child <-- status.splitStatus(...)` |
| zustand `persist` | `WebStorageVar.localStorage(key)(codec)` (Airstream 17.2 "LocalStorage" section) |
| URL search params state | Waypoint `Page` case classes carrying params; `router.currentPageSignal` |
| React context | Kernel-owned `Var`s: `AuthState`, `Capabilities`, `CurrentCluster`, `Notifications` (KUI's kernel allows exactly these) |
| `AbortController` | `FetchStream.get(url, _.abortStream(stream))` (README "FetchStream") or sttp `cancel` via `Future`+`AbortSignal` |

Feature-local state is a plain Scala class (`final class TopicsState(api: TopicsApi, cache: QueryCache)`) holding `Var`s and derived `Signal`s; no global singletons, matching KUI's rule of "no global mutable singletons except the kernel's documented `Var`s".

#### 3.3 Component patterns

- A component is a function returning `HtmlElement` (or a small class exposing `element` plus streams). Props are `Signal[A]`/`Observer[A]`; children lists via `children <-- items.split(_.id)((id, initial, sig) => row(sig))` (17.x) or `splitSeq` (18).
- Page switching: Airstream "Splitting With Pattern Match" (`pageSignal.splitMatchOne.handleValue(HomePage){...}.handleType[UserPage]{...}.toSignal`, Scala 3 only, README lines 1670-1790) supersedes Waypoint's `SplitRender` (Waypoint README:97-183 still documents `collectStatic`/`collectSignal`). Use `splitMatchOne` so each page element is created once per page instance and receives a refined `Signal[UserPage]`.
- Lifecycle: `onMountCallback`, `onMountBind`, `onMountInsert`, `onUnmountCallback` for third-party DOM widgets (CodeMirror, charts) that need the real element.
- Web components: Laminar has first-class custom-element support (`slot` attr, custom CSS props since 0.10.2; Shoelace bindings `laminar-shoelace` 0.1.0 by raquo; SAP UI5 bindings `sherpal/LaminarSAPUI5Bindings` `web-components-ui5` 1.21.x and a CEM-generated successor `nguyenyou/ui5-webcomponents-laminar`). This is the pragmatic route to date pickers, dialogs, menus, tooltips without writing them from scratch (see §5, §6).

#### 3.4 Routing with Waypoint (nested routes, params)

- Define a sealed `Page` ADT per feature; each feature contributes `List[Route[_ <: Page, _]]` (KUI's `FeatureRoute` convention). The shell concatenates routes into one `Router[Page]` (`Router(routes, getPageTitle, serializePage, deserializePage, routeFallback, deserializeFallback)` — README "Router").
- Nesting in Waypoint is by data, not by component tree: `case class TopicPage(cluster: ClusterName, topic: TopicName, tab: TopicTab)` with a route pattern `root / "ui" / "clusters" / segment[String] / "topics" / segment[String] / segment[String] / endOfSegments`. The shell renders the cluster frame from `currentPageSignal.map(_.cluster).distinct`, the topics feature renders the tab body from a refined `Signal[TopicPage]`. Query params via `? param[Int]("page").?` and `listParam` (URL-DSL). Waypoint 9 `Route.Total` gives compile-time totality for `relativeUrlForPage` (used for `href` generation in nav).
- Encode/decode of `Page` for `history.state`: Waypoint requires `serializePage: Page => String` and `deserializePage`; use circe (already in the stack, ADR-007) via `Codec[Page]` in the shell.
- Route fallback: `routeFallback = url => NotFoundPage(url)` and `deserializeFallback`. Per KUI's capability-gated navigation design, the shell wraps every feature route render in `capabilityGate(feature)(render)` which shows the `Unavailable(reason)` panel when the capability registry says so.
- Server must serve `index.html` for every SPA path (Waypoint README:409). The gateway (§20) needs a catch-all `GET /ui/**` -> static index route; alternatively `Route.fragmentBasePath` for hash routing (README:304-320) — not recommended for a product UI.

#### 3.5 Forms and validation

- Laminar has no form library; the idiom is `Var[FormModel]` + `controlled(value <-- ..., onInput.mapToValue --> ...)` per input, and a derived `Signal[Either[Errors, ValidModel]]`. Iron 3.0.2 (Scala.js artifact present) provides refined types and `refineEither`, and supports error accumulation via Cats `Validated` (skill `scala-validation`), replacing yup. Cross-field validation is a plain function on the model signal.
- Field-level errors: `Var.zoom` (owner-free in 18; in 17.x `zoomLazy`/`bimap` on derived Vars, blog 17.1/17.2) to bind sub-fields; `distinct` to avoid re-render churn.
- JSON-schema-aware produce dialog (Kafbat `validateBySchema`, Kouncil Monaco+schema): keep the validation on the server side via the message service's `validate` endpoint (Tapir), and use CodeMirror's `@codemirror/lint` with a linter that calls it debounced. Avoids shipping ajv.

#### 3.6 Error boundaries equivalent, SSE, HTTP

- No React-style error boundaries exist; instead Airstream errors propagate as error channel on observables. Idiom: (1) `AirstreamError.registerUnhandledErrorCallback` in the shell to log/report; (2) `stream.recover { case e => Some(fallback) }` / `recoverToTry` at feature boundaries; (3) Laminar element rendering of a page wrapped in `Try` inside `splitMatchOne` so a throwing page renders the fallback panel instead of blanking the app. Airstream 18 adds `tapEachError`.
- SSE: Airstream has no EventSource stream (`Websockets`: "no official websockets integration yet", README:972). Kernel wrapper in ~40 lines: `EventStream.withCallback` (README:513) or a custom `EventStream` (README "Custom Event Sources") that opens `new dom.EventSource(url, withCredentials=true)` on start and `close()`s on stop, mapping `onmessage`/named `addEventListener("MESSAGE")` into a `Either[SseError, ServerEvent]`. Native `EventSource` reconnects automatically (a plus for `/api/v1/capabilities/stream`, §16) but cannot send headers or abort a POST. For the message browser (§22) where Kafbat uses `fetch-event-source` with abort, use `FetchStream.raw.get(url, _.abortStream(...))` and parse `response.body` as a `ReadableStream` of `text/event-stream` chunks (a small SSE line parser in the kernel), or `dom.fetch` with `AbortController`. Decision: both wrappers live in the kernel as `Sse.eventSource(url)` and `Sse.fetchStream(request)`; features choose.
- HTTP: Tapir endpoints in cross-compiled `contract` modules; the client is `SttpClientInterpreter().toClient(endpoint, Some(baseUri), backend)` with sttp `FetchBackend` (`sttp.client4.fetch.FetchBackend()` returning `Future`; tapir docs "Using as an sttp client (v4)"; needs `io.github.cquiroz::scala-java-time` on JS). `Future` -> `EventStream.fromFuture(f)` / `Signal.fromFuture`. Caveats documented by tapir: `Set-Cookie` outputs cannot be decoded in the browser; streaming endpoints need `StreamSttpClientInterpreter`. Auth: cookie session with `credentials: include` (as Kafbat) or bearer from the kernel `AuthState`; a kernel `ApiClient` wraps the backend to add the header, map 401 to `AuthState.expired`, and 503/capability errors to the capability registry.

### 4. Microfrontend loading: evaluating options A / B / C

Facts from scala-js.org `module.html`:

- `ModuleKind.ESModule` is required for dynamic import; `ModuleSplitStyle` options: `FewestModules` (default), `SmallestModules` (one module per class), `SmallModulesFor(packages)` (small modules only for listed packages, fewest for everything else).
- `js.dynamicImport[A](body: => A): js.Promise[A]` "acts as a border for the Scala.js linker to split out a module that will be dynamically loaded"; only code reachable *exclusively* through the body ends up in the lazy module — anything also reachable from `main` stays in `main.js` (Airstream 18 notes repeat this: "effective to the extent that the types ... are not already used elsewhere in your app").
- Public modules come from entry points (`@JSExportTopLevel(..., moduleID = ...)` and `ModuleInitializer.withModuleID`); internal modules are created by the linker for shared code; file names via `scalaJSOutputPatterns`; all under `scalaJSLinkerOutputDirectory`.
- "Scala.js only splits modules along class boundaries."

Facts from Mill (`ScalaJSModule.scala`, docs 1.1.8): `def moduleKind: T[ModuleKind]` (default `NoModule`), `def moduleSplitStyle: T[ModuleSplitStyle]` (default `FewestModules`), `def esFeatures`, `def jsEnvConfig: T[JsEnvConfig]` (default `NodeJs()`; variants `NodeJs, JsDom, ExoegoJsDomNodeJs, Phantom, Selenium, Playwright`), `def scalaJSOutputPatterns`, `def scalaJSSourceMap` (true), `def scalaJSMinify` (true), `def scalaJSImportMap: T[Seq[ESModuleImportMapping]]`, `def scalaJSUseWebAssembly`. Tasks `fastLinkJS`, `fullLinkJS`, `run`, `test`. Mill 1.1.8 also ships `TypeScriptModule` with npm dependency management (`javascriptlib/intro.html`), and third-party `lolgab/mill-scalablytyped` and `nafg/mill-bundler` exist. Mill docs contain no Vite/esbuild example; `incrementum/vite-scala-js-mill` shows `vite-plugin-mill` adapting the official `@scala-js/vite-plugin-scalajs` (which is sbt-only) so Vite triggers `mill fastLinkJS` on reload.

#### Option A — single link, static registry

One `ScalaJSModule` (`kui-ui-app`) depending on all feature modules; features register in a `List[KuiFeature]`. Full DCE, one link, one `main.js` (plus linker-internal modules with `FewestModules`). Fastest to build correctly; bundle grows linearly with features (rough estimate from comparable Laminar apps: 1–3 MB fullLink for the whole product, gzip ~300–600 KB). No runtime plugin story. Zero build complexity.

#### Option B — one link, `SmallModulesFor` + `js.dynamicImport`

Same single link, `moduleKind = ESModule`, `moduleSplitStyle = SmallModulesFor(List("kui.ui.topics", "kui.ui.messages", ...))`, and the shell's feature registry holds `FeatureId -> () => js.Promise[KuiFeature]` where the thunk is `js.dynamicImport(new TopicsFeature())`. Evidence it works: this is the documented purpose of `js.dynamicImport`; Airstream 18 ships `EventStream.dynamicImport` specifically for Waypoint page-level lazy loading (Waypoint#24). Constraints: (1) the shell must not reference feature classes statically (only through the thunk), otherwise they fold into `main.js`; (2) the kernel (Laminar, sttp, circe, contract modules) is shared and deduplicated automatically because it is one link; (3) cross-feature calls go through kernel-owned traits/buses, never direct class references (this is also the right architecture). Build: identical compile/link to A, `fastLinkJS` output is a directory of `.js` modules; must be served as static ES modules (`<script type="module">`), which the gateway already needs for A. Dev: `fastLinkJS` + any static server works because there is no bundling step; Vite is optional. Test: MUnit under Node with `ModuleKind.ESModule` works (Scala.js test adapter supports ESModule since 1.x).

#### Option C — separately linked plugins

Each plugin is its own `ScalaJSModule` link with its own copy of Laminar/Airstream/circe. Scala.js has no cross-link sharing: each link is a closed world, so Airstream would be duplicated *and* would be a different runtime instance (two `Transaction` schedulers, two `Owner` hierarchies) — observables from one bundle cannot safely be subscribed in another. The stable boundary would have to be pure JS (`js.Object` props, DOM nodes, JS callbacks), i.e. the plugin contract becomes a Web Component / JS API, not `KuiFeature`. This is viable for genuine third-party plugins but is a different product feature (plugin SDK), with 3–5x bundle duplication per plugin and no type safety across the boundary.

#### Recommendation

**Adopt B now for all KUI-owned features, structured so that A is a one-line fallback** (`moduleSplitStyle = FewestModules` and `dynamicImport` degrading to eager `js.Promise.resolve`), and **defer C to a post-M8 "Plugin SDK" ADR**, where the boundary is a Web Component contract, not `KuiFeature`. Rationale with evidence:

1. B costs nothing extra in build tooling versus A (same Mill module graph, one extra setting) and keeps one shared runtime for Airstream, which C cannot.
2. B's payoff is real: features gated `Unavailable` by the capability registry (§16) are never downloaded; the messages feature will carry the heaviest facades (CodeMirror), and `SmallModulesFor` lets the linker isolate it.
3. The risk in B is accidental static references pulling features into `main.js`. Mitigation: a Mill test task that asserts `main.js` size and that `kui.ui.<feature>` module files exist after `fullLinkJS`; plus the architectural rule that the shell depends on features only via `moduleDeps` for compile and the thunk registry at runtime.
4. Airstream 18's `dynamicImport` operators indicate the ecosystem is converging on exactly this pattern; when KUI upgrades to 18 the kernel helper can be replaced by the library operator.
5. The Vite question is orthogonal: with ESModule output and no npm-bundled dependencies, a static server is enough for dev. Vite becomes necessary only when npm libraries (CodeMirror, uPlot) are consumed as ES modules; then `scalaJSImportMap` (Mill) or an import map in `index.html` can point bare specifiers at `esm.sh`/vendored copies, or a Vite step bundles `main.js` + npm deps. Recommended M0 setup: Mill `fastLinkJS` -> `frontend/dist/`, `TypeScriptModule`-free; a tiny Mill task `devServer` runs `python3 -m http.server`/`npx vite` on the linker output with `/api` proxied to the gateway; CI `fullLinkJS` copied into the gateway's static resources (Mill "webserver integration" example).

Reversibility: A <-> B is a config toggle; B -> C requires designing a JS-level plugin API but does not touch `KuiFeature`-based features.

### 5. Facades: virtualized table, JSON viewer/editor, code editor, date picker, charts

Facade acquisition strategy: ScalablyTyped via `lolgab/mill-scalablytyped` (Mill plugin; supports Scala 3.7.x, Scala.js 1.20.1; generates a separate module from `package.json`) — slow initial generation (minutes) and large generated code, so use it once to *generate*, then vendor the trimmed facade into `kui-ui-kernel` as hand-written `@js.native` traits (ScalablyTyped docs explicitly bless publishing pre-generated code "completely free-standing with no dependency on the plugin"). This keeps the plugin out of the routine build.

- **Virtualized table (thousands of rows).** No Laminar virtualization library exists (search found only vanilla JS `hyperlist`, `virtualized-list`). Neither Kafbat (TanStack Table without react-virtual) nor Kouncil (MatTableDataSource) virtualize. KUI writes its own in the kernel ("own virtualized table" is the plan): fixed-row-height windowing (`scrollTop` -> visible index range as `Signal[(Int, Int)]`, container `height = rows * rowHeight`, translateY offset), rows rendered with `children <-- visibleRows.split(_.key)`. Needs: sticky header, column resize (persist widths in `WebStorageVar`, as Kafbat's persister), sort/filter state in `Page` params (as Kafbat's URLSearchParams), row selection, expand row (messages detail), keyboard navigation (aria `role="grid"`). ~400 lines Scala; property tests on the window math (MUnit + ScalaCheck).
- **JSON viewer/editor.** Viewer: a tiny Laminar tree component over `circe.Json` (collapsible objects/arrays, copy path, "expand as columns" from Kouncil) — no facade needed; circe-core is already cross-compiled (`circe-core_sjs1_3`). Large payload safety: lazy children via `child <-- expanded`. Editor: CodeMirror (below) with `@codemirror/lang-json` + `@codemirror/lint`; note Kafbat's `lossless-json` use exists because JS numbers lose 64-bit precision — circe on Scala.js keeps `JsonNumber` as a string-backed value, so no equivalent is needed if the viewer renders from circe rather than `JSON.parse`.
- **Code editor.** Options: Ace (Kafbat; legacy, single `ace.js` global — easiest facade, dated), Monaco (Kouncil; excellent JSON-schema support, ~5 MB, worker setup, AMD loader — heavy for a lazy feature module), CodeMirror 6 (modular ES packages, ~300 KB for json+sql+lint+search, tree-shakeable, first-class ESM which matches `ModuleKind.ESModule`). No maintained Scala.js CM6 facade exists (`antonkulaga/scala-js-facades` covers CM5). Recommendation: CodeMirror 6 with a hand-written facade for `EditorState`, `EditorView`, `Compartment`, `lang-json`, `lang-sql`, `lint`, `search`, `oneDark` (~150 lines), generated initially via ScalablyTyped and trimmed. Modes needed: JSON (messages, schemas, configs), Protobuf (schemas; use `@codemirror/legacy-modes/mode/protobuf`), SQL (ksql), CEL (smart filters; start with plain text + `StreamLanguage` keyword highlighter). This matches the plan's "CodeMirror facade" item.
- **Date picker / selects / dialogs / tooltips / menus.** Prefer web components bound from Laminar: Shoelace (`laminar-shoelace` by raquo, `sl-input type="date"`, `sl-select`, `sl-dialog`, `sl-tooltip`, `sl-dropdown`, `sl-tab-group`, `sl-alert` toasts) or SAP UI5 (`web-components-ui5` 1.21, has `ui5-date-time-picker`, `ui5-table`, `ui5-multi-combobox`). Shoelace fits a custom design system better (CSS custom properties, light/dark themes built in, small), UI5 is more "enterprise complete" but opinionated Fiori look. Recommendation: Shoelace for M0–M2 widgets; native `<input type="datetime-local">` (Kouncil uses it) as the zero-dependency fallback for timestamp seek.
- **Charts (metrics).** No maintained Scala.js facade for uPlot/ECharts; `sjrd/scalajs-sbt-vite-laminar-chartjs-example` shows Chart.js via ScalablyTyped + Vite. Recommendation: **uPlot** (tiny, canvas, made for time series, trivial API `new uPlot(opts, data, el)`) with a ~60-line hand facade, mounted via `onMountCallback`; fall back to inline SVG sparklines drawn from Laminar `svg.*` for dashboard tiles (no dependency).

### 6. CSS strategy, design system, dark mode

- ScalaCSS: last release 1.0.0 (Nov 2022), Scala 3 supported, but effectively unmaintained (55 open issues; author notes unpaid OSS). It also generates styles at runtime and increases bundle size. Not recommended for a new project.
- Recommendation (ADR candidate): **plain CSS files owned per module, BEM-ish class naming with a per-feature prefix (`kui-topics__row--selected`), design tokens as CSS custom properties, and a Scala `object Css` per module with `val row = "kui-topics__row"` string constants** so Laminar code references classes through typed names (typo-safe enough, zero runtime cost). CSS files live next to Scala sources (`src/main/resources/css/*.css`) and are concatenated by a Mill task into `kui.css` (order: tokens -> reset -> kernel -> features). No preprocessor; nesting and custom properties are native in all evergreen browsers.
- Design system: kernel owns tokens (`--kui-color-bg`, `--kui-color-fg`, `--kui-space-*`, `--kui-font-*`), a small primitives set (button, input, tag, card, tabs, table shell, toast, dialog) either as Laminar components or Shoelace wrappers themed through Shoelace's own CSS custom properties (`--sl-color-primary-*`), and the layout shell. Kafbat's `theme.ts` palette (neutral/green/brand/red scales, `src/theme/theme.ts:3+`) is a reasonable token list to port.
- Dark mode: replicate Kafbat's three-state model (`auto | light | dark`, `ThemeModeContext.tsx:11-58`): `data-theme` attribute on `<html>` set from a `WebStorageVar[ThemeMode]`, `auto` resolved by `window.matchMedia("(prefers-color-scheme: dark)")` and its `change` event via `DomEventStream`; tokens redefined under `:root[data-theme="dark"]` and `@media (prefers-color-scheme: dark) :root:not([data-theme="light"])`. Shoelace's `sl-theme-dark` class follows the same attribute.
- Accessibility floor (Kafbat has little): every interactive kernel primitive gets keyboard handling and ARIA roles; table uses `role="grid"`; dialogs trap focus (Shoelace does this). Add an axe-core pass in Playwright smoke tests.
- i18n: Kafbat has none. KUI should at least centralize strings in a kernel `Messages` object per feature from day one (cheap now, expensive later), without a runtime i18n library.

### 7. Testing

- **Unit (Scala.js, MUnit under Node).** MUnit 1.1.1 has a `_sjs1_3` artifact. Mill: `object test extends ScalaJSTests with TestModule.Munit`. For DOM-touching tests (Laminar components), set `def jsEnvConfig = Task { JsEnvConfig.JsDom() }` and `npm install jsdom` (community notes: with Scala 3 the separate `scalajs-env-jsdom-nodejs` sbt plugin is not required in Mill since Mill bundles `JsEnvConfig.JsDom`); `raquo/scala-dom-testutils` 19.0.0 (what Laminar itself tests with) provides `expectNode(div.of(...))` DOM assertions. Alternative `JsEnvConfig.Playwright` (Mill wraps `scala-js-env-playwright` 0.1.18) runs unit tests in a real Chromium — slower but faithful for `EventSource`/`fetch`; use for the kernel's SSE and fetch wrappers only. State/view logic (feature `State` classes, pagination math, table windowing) is pure Scala and needs no DOM: test with plain MUnit + ScalaCheck.
- **Contract tests in the browser.** Tapir client against a Tapir stub server is JVM-side; on JS, test the kernel `ApiClient` using sttp's `BackendStub` for client4 (works on Scala.js) to assert header injection, 401 handling, and decoding.
- **E2E with Playwright from Mill.** Two viable shapes: (1) **TypeScript Playwright tests** in `frontend/e2e/` as a Mill `TypeScriptModule` (Mill 1.1.8 `javascriptlib`) with a custom `Task` `e2e` that depends on `allInOne.assembly` + `fullLinkJS`, starts the all-in-one JAR with Testcontainers-style docker-compose (`Bash`-driven), runs `npx playwright test`, and publishes the HTML report; Kafbat's page-object layout (`e2e-playwright/src/pages/*`, `BaseLocators.ts`) is a good template minus Cucumber. (2) **Scala Playwright** via `com.microsoft.playwright:playwright` (JVM) driven by `indoorvivants/weaver-playwright` or plain MUnit on the JVM — keeps everything in Scala/Mill (`object e2e extends ScalaModule with TestModule.Munit`) and lets tests share the `contract` models and Testcontainers-scala setup with backend integration tests. Recommendation: **(2) JVM Playwright + MUnit in a Mill `e2e` module**, because it reuses the existing JVM test infrastructure (Testcontainers Kafka/SR/Connect), needs no Node toolchain in CI beyond `playwright install`, and the fault-isolation E2E tests ("stop its container and assert the shell still works") are natural in Scala with Testcontainers handles. The Scala.js Playwright facade research item can be closed: not needed for E2E; `JsEnvConfig.Playwright` covers browser-run unit tests.
- CI: `mill frontend.__.test` (Node + jsdom), `mill frontend.kui-ui-shell.fullLinkJS`, bundle-size assertion task, `mill e2e.test` behind a docker-available guard.

---

## Decision candidates (Appendix D format)

### ADR-011 — Laminar + Waypoint frontend (versions and idioms)

- **Decision:** Laminar 17.2.1 / Airstream 17.2.1 / Waypoint 9.0.0 / scala-js-dom 2.8.x on Scala.js 1.20.2 and Scala 3.3.x LTS for M0; scheduled upgrade to Laminar 18 / Waypoint 10 after their final release. State: kernel-owned `Var`s for auth/capabilities/cluster/notifications only; feature-local `State` classes; server state through a kernel `QueryCache` built on Airstream `Status`; persisted UI state via `WebStorageVar`; page switching via `splitMatchOne`; routing by a sealed `Page` ADT with per-feature route lists concatenated in the shell; forms as `Var[Model]` + Iron validation.
- **Evidence:** §3.1 version table (GitHub tags, `project/Versions.scala`), Airstream README sections (Status operators, LocalStorage, Splitting with pattern match), Waypoint CHANGELOG (Total routes, `currentUrl`, `endOfSegments` default in 10), tapir sttp4 client docs.
- **Tradeoff:** No off-the-shelf forms/table/virtualization; more kernel code up front versus React ecosystem. Pattern-match splitting is Scala 3 only (fine: KUI is Scala 3).
- **Reversibility:** Medium. Airstream idioms permeate feature code; switching UI libraries later is a rewrite. Version upgrades within Laminar (17 -> 18) are mechanical (deprecation-guided).

### ADR-012 — Microfrontend loading strategy

- **Decision:** Option B: single Scala.js link, `ModuleKind.ESModule`, `ModuleSplitStyle.SmallModulesFor(featurePackages)`, features loaded by the shell via a kernel `Lazy.feature(() => js.dynamicImport(new XFeature))` registry keyed by `FeatureId`, gated by the capability registry. Option A remains a config-level fallback; Option C deferred to a post-M8 "Plugin SDK" ADR with a Web-Component boundary.
- **Evidence:** scala-js.org module docs (`js.dynamicImport` as linker split border; class-boundary splitting; ESModule requirement), Mill `ScalaJSModule` settings (`moduleKind`, `moduleSplitStyle`, `scalaJSImportMap`), Airstream 18 `dynamicImport` operators (draft release notes) confirming ecosystem direction, Kafbat's `React.lazy` per page (`App.tsx:32-38`) as the equivalent in the reference.
- **Tradeoff:** Requires discipline that the shell never references feature classes statically; needs a bundle-shape CI check. No true third-party plugins.
- **Reversibility:** High between A and B (one setting). Moving to C later is additive.

### ADR-019 (new) — CSS strategy and design system

- **Decision:** Plain CSS with per-module files, BEM-style prefixed classes referenced through Scala constants, CSS custom-property tokens, `data-theme` three-state dark mode, Shoelace web components (via `laminar-shoelace`) for complex widgets (date/select/dialog/tooltip/dropdown/tabs/toast). Reject ScalaCSS.
- **Evidence:** ScalaCSS last release Nov 2022 (GitHub releases); Kafbat theming model (`ThemeModeContext.tsx:11-58`, `theme.ts`); Laminar web-component support and Shoelace bindings (Laminar 17.0.0 release post).
- **Tradeoff:** Class-name typos are caught only by convention/tests, not the compiler; Shoelace adds an npm dependency (served as static ESM + CSS, ~100 KB gz).
- **Reversibility:** High for CSS files; medium for Shoelace (wrapped behind kernel primitives so swapping to UI5 or hand-rolled is local).

### ADR-020 (new) — Code editor, JSON viewer, charts facades

- **Decision:** CodeMirror 6 with a hand-written facade (generated once with `mill-scalablytyped`, then vendored/trimmed) for JSON/Protobuf/SQL/CEL; kernel-native JSON tree viewer over circe `Json`; uPlot hand facade for metrics graphs with inline-SVG sparklines for tiles; kernel-native virtualized table.
- **Evidence:** §5 (no maintained CM6/uPlot facades; Kouncil Monaco lazy-loading and size; Kafbat `lossless-json` motivation; ScalablyTyped "free-standing generated code" guidance; `mill-scalablytyped` Scala 3.7/Scala.js 1.20 support).
- **Tradeoff:** Maintaining ~300 lines of facades; Monaco's JSON-schema completion (Kouncil) is lost — replaced by server-side validation + CM lint.
- **Reversibility:** High; facades are isolated in the kernel behind `CodeEditor`/`Chart` traits.

### ADR-018 addendum — Frontend test tooling

- **Decision:** MUnit + ScalaCheck on Scala.js under Node (`JsEnvConfig.JsDom()` for DOM tests, `scala-dom-testutils` for element assertions, sttp `BackendStub` for client tests); E2E via JVM Playwright (`com.microsoft.playwright`) in a Mill `e2e` Scala module using Testcontainers-scala and the all-in-one JAR; drop the "Scala.js Playwright facade" research item (use `JsEnvConfig.Playwright` only for browser-run unit tests of `EventSource`/`fetch` wrappers).
- **Evidence:** Mill `ScalaJSModule.jsEnvConfig` variants; scala-js.org JS environments page; Kafbat e2e layout (`e2e-playwright/src/{features,steps,pages,support}`); KUI's fault-isolation E2E test plan.
- **Tradeoff:** Two Playwright runtimes are not needed; Cucumber-style feature files (Kafbat) are dropped in favour of plain Scala tests.
- **Reversibility:** High.

## Open questions

1. Laminar 18 final date and whether Waypoint 10 will pin URL-DSL 0.7 `endOfSegments` semantics that affect KUI route definitions (write routes with explicit `endOfSegments` now to be forward compatible).
2. Whether the gateway serves the SPA (catch-all `GET /ui/**`) or a separate static server does; affects dev-server setup and cookie auth (`credentials: include`).
3. Exact SSE transport for the message browser: native `EventSource` (GET + query params, as Kafbat's endpoint already is GET) versus fetch-streaming with abort. Kafbat's endpoint is GET, so native `EventSource` may suffice if the gateway accepts cookie auth; needs a spike in M1.
4. Message-browser memory bound: Kafbat keeps ~100 messages per page; KUI should define a hard client cap (e.g. 5,000 rows in the virtualized table with server cursor paging) — to be decided with §22 owner.
5. Whether to vendor Shoelace as static ESM in the gateway resources or bundle via a Vite step; depends on import-map support policy for target browsers (all evergreen browsers support import maps).
6. mill-scalablytyped compatibility with Mill 1.1.x (plugin lists Scala.js 1.20.1 support; Mill 1.x API changes need verification in a spike).

## Confidence

**Medium-high.** Kafbat/Kouncil inventories are direct file reads with line citations (high). Library versions come from GitHub tags and Laminar's own `Versions.scala` (high) but Laminar 18 details come from an in-repo *draft* post (medium: content may change before release). Module-splitting behaviour is from official Scala.js docs (high); the ESModule-only requirement for dynamic import is implied by the docs rather than stated verbatim (medium). The CSS, facade and E2E recommendations are judgement calls supported by ecosystem state (medium): no maintained CM6/uPlot facades and an unmaintained ScalaCSS are verified facts, but the effort estimates for kernel-native components are estimates.
