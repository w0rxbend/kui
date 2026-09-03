# Kouncil — screen-by-screen UI/UX inventory

**Agent:** Research Agent H (UI/UX Inventory)
**Date:** 2026-09-03

## Questions

1. What screens does Kouncil have, and for each: route, purpose, data shown, actions, forms
   with validation, filters, empty/loading/error states, permission gating, real-time elements?
2. What does Kouncil do better or differently than Kafbat that KUI should adopt: table-style
   record browsing with JSON flattening into columns, the event tracking screen, the consumer
   group monitoring view, the resend flow, layout and information architecture?

## Method and sources

- Source read directly; claims cite `path:line` relative to `/tmp/kui-ref/consdata`.
  - Kouncil, `/tmp/kui-ref/consdata`, commit `6e2fb85e6ceac813c39f762eecd2f4bce1b31faf`
    (2026-08-04). Frontend is an Nx/Angular workspace (`kouncil-frontend/apps/kouncil`,
    `kouncil-frontend/libs/*`), Angular Material UI.
  - Docs: `/tmp/kui-ref/consdata/docs` (FEATURES.md, features/*, configuration/*).
- Comparison baseline is the Kafbat inventory in `research/kafbat/ui-analysis.md`.

## Findings

## Layout and information architecture

All paths below are relative to `/tmp/kui-ref/consdata`; `FE` = `kouncil-frontend`.

### Shell

- Root `AppComponent` is only a `<router-outlet>`; it eagerly loads the Monaco editor on start (`FE/apps/kouncil/src/app/app.component.ts:7-14`).
- Two top-level shells are declared in `FE/apps/kouncil/src/app/routing/routing.module.ts`:
  - `MainComponent` (path `''`, `canActivate: AuthGuard`, `resolve: config -> ServersService.load()`) wraps every feature screen (`routing.module.ts:74-257`, `config-resolver.ts:12-14`, `app.module.ts:137-139`).
  - `MainLoginComponent` (path `''`) wraps `login` and `changePassword` (`routing.module.ts:258-267`); its resolver `PermissionsConfigResolver` checks `arePermissionsNotDefined$()` and, if so, triggers `confirmTemporaryAccessToApp()` — the first-time-launch flow (`routing/permissions-config-resolver.ts:12-21`).
  - `oauth` -> `OAuthRedirectComponent` (`routing.module.ts:268`); `**` -> `PageNotFoundComponent` inside `MainComponent` (`routing.module.ts:269-281`).
- `RouteReuseStrategy` is replaced by `ReloadingRouterStrategy` that never reuses routes + `onSameUrlNavigation: 'reload'`, so re-navigating to the same URL (used when switching cluster) fully reloads the screen (`routing.module.ts:44-71, 286-288`).
- `MainComponent` template, top to bottom (`FE/apps/kouncil/src/app/main/main.component.ts:9-28`):
  1. `<app-demo>` strip when `backend === 'DEMO'`.
  2. `<app-banner>` when a temporary admin is logged in: "User permissions are not defined. You are currently logged in as a temporary user to define these permissions. After logout this user will be removed." (`main.component.ts:11-14`; banner is a bare content projector `banner/banner.component.ts:5-9`).
  3. `<app-kafka-navbar>` (toolbar).
  4. A flex row: `<app-sidebar>` + content column containing `<app-survey>`, `<app-progress-bar>` (global thin loading bar driven by `ProgressBarService`) and the `<router-outlet>`; content column gets `sidebarOpened`/`sidebarClosed` class (`main.component.ts:17-27`).

### Toolbar (`FE/apps/kouncil/src/app/toolbar/toolbar.component.ts`)

Left to right (`toolbar.component.ts:22-68`):
- Kouncil logo whose tooltip is the backend version fetched from `GET /api/info/version` (or `DEMO`) (`:23-24`, `:124-135`).
- Vertical divider, `Documentation` button opening `https://github.com/consdata/kouncil` in a new tab (`:28-31`, `:141-143`).
- Spacer, then a **global search input** (`placeholder="Search"`, `accesskey="/"`) bound to `SearchService.currentPhrase` — it is auto-focused after every router event and after view init (`:35-43`, `:92-94`, `:120-122`). This one box filters whichever list screen is active (topics, messages, consumer groups, track results…); each screen subscribes to `searchService.getPhraseState$('<screen>')`.
- **Cluster switcher** `mat-select` listing `serverId - label`; on change stores `lastSelectedServer` in `localStorage`, emits `selectedServerChanged()` and re-navigates to the current URL (forced reload) (`:44-54`, `:114-118`). It is hidden on routes flagged `data.hideClusterContext` (clusters admin, user groups, access-denied, 404) (`:96-102`; flags at `routing.module.ts:135,176,185,194,203,212,221,277`).
- `<app-notification-button>` (feat-notifications).
- `Logout` button (`:58-63`, `:145-150`); when not authenticated the Consdata logo is shown instead (`:65-67`).

### Sidebar (`FE/apps/kouncil/src/app/sidebar/sidebar.component.ts`)

- Collapsible left rail; `SidebarService` holds OPENED/CLOSED state; toggle button with `dock_to_right`/`dock_to_left` icon at bottom (`sidebar.component.ts:69-75`, `sidebar.service.ts:14-21`). Collapsed items show only the Material Symbols icon with a tooltip (`sidebar-menu-item.component.ts:8-14`).
- Menu items, each shown only when authenticated AND `authService.canAccess([...])` (`sidebar.component.ts:20-67`):
  | Label | Icon | Route | Function |
  |---|---|---|---|
  | Topics | topic | /topics | TOPIC_LIST |
  | Brokers | hub | /brokers | BROKERS_LIST |
  | Consumer Groups | device_hub | /consumer-groups | CONSUMER_GROUP_LIST |
  | Track | manage_search | /track | TRACK_LIST |
  | Schema Registry | code | /schemas | SCHEMA_LIST |
  | — separator (shown if any admin function) — | | | |
  | Clusters | storage | /clusters | CLUSTER_LIST |
  | User groups | group | /user-groups | USER_GROUPS_LIST |
  | User groups permissions | verified_user | /user-groups-permissions | USER_GROUPS |
  | Data masking policies | policy | /data-masking-policies | POLICY_LIST |
- IA takeaway: five "operational" screens and four "admin" screens separated by a divider; there is no per-cluster nesting in the URL — cluster is a global context chosen in the toolbar, and admin screens hide that selector.

### Route table (`FE/apps/kouncil/src/app/routing/routing.module.ts:73-282`)

| Route | Component | Guard role (`data.roles`) |
|---|---|---|
| `/brokers` | BrokersComponent | BROKERS_LIST |
| `/topics` | TopicsComponent | TOPIC_LIST |
| `/topics/messages/:topic` | TopicComponent | TOPIC_MESSAGES |
| `/consumer-groups` | ConsumerGroupsComponent | CONSUMER_GROUP_LIST |
| `/consumer-groups/:groupId` | ConsumerGroupComponent | CONSUMER_GROUP_DETAILS |
| `/track` | TrackComponent | TRACK_LIST |
| `/access-denied` | AccessDeniedComponent | (auth only) |
| `/schemas` | SchemasComponent | SCHEMA_LIST |
| `/schemas/edit/:subjectName/:version` | SchemaEditComponent | SCHEMA_UPDATE |
| `/schemas/create` | SchemaCreateComponent | SCHEMA_CREATE |
| `/schemas/:subjectName/:version` | SchemaDetailsComponent | SCHEMA_DETAILS |
| `/clusters` | ClustersComponent | CLUSTER_LIST |
| `/clusters/cluster` | ClusterFormCreateComponent | CLUSTER_CREATE |
| `/clusters/cluster/:clusterName` | ClusterFormViewComponent | CLUSTER_DETAILS |
| `/clusters/cluster/:clusterName/edit` | ClusterFormEditComponent | CLUSTER_UPDATE |
| `/user-groups` | UserGroupsComponent | USER_GROUPS_LIST |
| `/user-groups-permissions` | UserGroupsFunctionsMatrixComponent | USER_GROUPS |
| `/data-masking-policies` | PoliciesComponent | POLICY_LIST |
| `/data-masking-policy` | PolicyFormCreateComponent | POLICY_CREATE |
| `/data-masking-policy/:id/edit` | PolicyFormEditComponent | POLICY_UPDATE |
| `/data-masking-policy/:id` | PolicyFormViewComponent | POLICY_DETAILS |
| `/login`, `/changePassword` | LoginComponent / ChangePasswordComponent | – / auth |
| `/oauth` | OAuthRedirectComponent | – |
| `**` | PageNotFoundComponent | – |

`AuthGuard.canActivate`: unauthenticated -> `/login`; authenticated but `canAccess(roles)` false -> `/access-denied` (`routing/auth.guard.ts:15-30`).

### Drawer pattern (`FE/libs/common-utils/src/lib/util/drawer.service.ts`)

Detail/edit panels are not separate routes. `DrawerService.openDrawerWithPadding(component, width?)` opens a `MatDialog` pinned to the right edge (`position.right: 0`, `height: 100%`, default width `787px`, panelClass `app-drawer`) (`drawer.service.ts:13-33`). Used for: message preview, send event, resend events, broker details, etc. This keeps list context visible behind the panel.

### Real-time transport

A STOMP-over-WebSocket client (`@stomp/rx-stomp`) is created at app start (`FE/apps/kouncil/src/app/rx-stomp-service-factory.ts:4-9`) with `brokerURL = environment.websocketUrl`, outgoing heartbeat 20 s, reconnect delay 2 s (`rx-stomp.config.ts:7-15`). It is used by Track (async results) and notifications. Topic live-update and consumer-group monitoring use HTTP polling instead (see below).

---

## Topic messages view (table-style record browsing) — `/topics/messages/:topic`

Files: `FE/apps/kouncil/src/app/topic/topic.component.ts`, `topic/json-grid.ts`, `topic/toolbar/topic-toolbar.component.ts`, `topic/topic-partitions.component.ts`, `topic/topic-pagination.component.ts`, `topic/message/message-view.component.ts`, `topic/topic.backend.service.ts`.

### Purpose and layout
Browse the records of one topic as a **spreadsheet-like grid** where every JSON property of the key and value, plus every header, becomes its own sortable/resizable column. Layout (`topic.component.ts:27-65`): toolbar row (breadcrumb + toggles + partition select + offset box + action buttons) -> `<app-common-table>` -> footer pagination. When no rows: `<app-no-data-placeholder objectTypeName="Message">` (`:40-45`).

### Toolbar (`topic/toolbar/topic-toolbar.component.ts:13-53`)
- `<app-breadcrumb parentName="Topics" parentLink="/topics" name=topic>` (`:15-16`).
- Three `mat-slide-toggle`s: **`JSON`** (default on; show flattened JSON columns vs a single `value` column), **`Headers`** (default on; show `H[...]` columns), **`Live update`** with a pulsing `.circle` indicator (default off) (`:18-30`, `:67-69`).
- `<app-topic-partitions>`: `mat-select` with `All partitions` + one option per partition index; selecting calls `selectPartition`/`selectAllPartitions` which refetch (`topic-partitions.component.ts:9-14, 36-47`). Selecting a partition also clears the offset box (`topic-toolbar.component.ts:31-32, 88-90`).
- **Offset** numeric input (`placeholder="Offset"`, `min=0`) with a search icon button -> `goToOffset()` -> `GET /api/topic/messages/{topic}/{partition|all}?offset=N` (`:34-41`, `:84-86`; `topic.backend.service.ts:27-46, 93-95`).
- `Resend events` button (gated `TOPIC_RESEND_MESSAGE`) and `Send event` primary button (gated `TOPIC_SEND_MESSAGE`) (`:44-51`); both open right-side drawers (`topic.component.ts:193-209`).

### Data fetching
- On init: progress bar on; `isTopicExist$` (`GET /api/topic/is-topic-exist/{topic}`) — on error navigates back to `/topics` (`topic.component.ts:119-126, 341-346`; `topic.backend.service.ts:97-103`). Then `GET /api/topic/messages/{topic}/{selectedPartition|all}?serverId&page&limit[&offset]` (`topic.backend.service.ts:27-46`).
- Response `TopicMessages { messages, partitionOffsets, partitionEndOffsets, totalResults }` (`topic/topic-messages.ts:3-10`); `totalResults` feeds pagination, partition count derived from `partitionOffsets` (`topic.backend.service.ts:48-60`).
- `?page=N` query param is honoured on load and updated with `Location.go` (no navigation) on paging (`topic.component.ts:128-132, 336-339`).

### JSON flattening into columns (`topic/json-grid.ts`)
`JsonGrid.replaceObjects(objects, formatPath=true, addColumn=true)` (`json-grid.ts:53-123`):
- For each message: each header becomes column `H[<headerKey>]` (`:61-68`); each top-level value property is walked by `handleObject` with prefix `V[...]`, key properties with `K[...]` (`:70-95`, `formatPath` at `:241-243`).
- `handleObject` (`:133-180`): scalars/null -> column `V[a.b.c]` with the value HTML-escaped and truncated to 100 chars (`:138-144`, `:34-51`); arrays with <= 10 elements expand to `path[i]` columns, longer arrays collapse to the literal `[Array of N elements]` (`:145-157`, `EXPAND_LIST_LIMIT` `:15`); objects with <= 100 keys expand to `path.prop`, bigger ones collapse to `[Object with N properties]` (`:158-179`, `:16`); recursion stops at depth 3 (`MAX_OBJECT_DEPTH` `:17`, `:135-137`).
- Every row also carries hidden fields `kouncilKey`, `kouncilKeyFormat`, `kouncilKeyJson`, `kouncilOffset`, `kouncilPartition`, `kouncilTopic`, `kouncilTimestamp` (formatted `yyyy-MM-dd HH:mm:ss.SSS`), `kouncilTimestampEpoch`, `kouncilValue`, `kouncilOriginalValue`, `kouncilValueFormat`, `kouncilValueJson`, `headers` (`:96-108`, `:224-232`).
- Rows are capped at 1000 (`ROWS_LIMIT` `:13`, `:198-203`); rows newer than (newest ts − 1 s) are flagged `fresh` so live-update highlights them via `kafka-row-delta` class (`:14`, `:218-222`; `topic.component.ts:159-164`).
- Column ordering: all `H[` first, then `K[` (flat before nested), then `V[` (flat before nested) (`:205-216`). Each column also has a `nameShort` abbreviation (`abc~` per path segment, e.g. `V[cus~.add~.city]`) for narrow headers (`:182-196`, `:234-239`).
- Parsing: `TopicComponent.tryParseJson` returns `{}` for non-JSON so non-JSON payloads only show the `value` column (`topic.component.ts:110-117`).

### Columns (`topic.component.ts:230-300`)
Sticky common columns: `partition` (100px), `offset` (100), `key` (150), `timestamp` (215); then header columns (`H[...]`) if Headers toggle on; then either JSON columns (`K[...]`/`V[...]`, 200px, sortable, resizable, not draggable) or a single `value` column (200px) when JSON toggle off (`:267-277`, `:323-334`). All columns sortable via `matSort`; header drag re-order via `cdkDropList` horizontal (`:47-54`).

### Search/filter
Global toolbar search filters client-side: `JSON.stringify(row).toLowerCase().includes(phrase)` across all flattened fields (`:134-138`, `:304-311`).

### Live mode
Toggling `Live update` sets `paused=false` and starts a recursive `setTimeout(1000)` poll of `getMessages` (HTTP, not websocket); new rows are flagged fresh (`:147-156`, `:166-173`). Paused on route change/destroy (`:125`, `:144`).

### Pagination (`topic/topic-pagination.component.ts:10-46`)
Footer with ngx-datatable `datatable-pager` (first/prev/next/last), a `Page no:` numeric input (validated to `[1,totalPages]`, `updateOn: blur`) and `Items per partition:` select with `[1, 5, 10, 20, 50, 100, 500, 1000]` (default 10) (`:25-45`, `:55`, `:77-87`; default page in `topic.backend.service.ts:22`). Note the limit is **per partition** — with "All partitions" the backend returns up to `limit × partitions` rows, and `totalResults` is the sum of partition offsets.

### Message detail drawer ("Event preview") (`topic/message/message-view.component.ts:23-96`)
Opened by clicking a row (`topic.component.ts:176-191`) via `DrawerService`. Contents:
- Header "Event preview" with close icon (`:25-29`).
- `Headers` section: `<app-common-table>` with columns `header key`, `header value` (sortable/resizable/draggable) plus an action column with a copy button (tooltip "Copy header key and value to clipboard") (`:30-61`, `:106-135`). **Clicking a header row jumps to the Track screen** pre-filled with that header key/value, the message timestamp ±1 min and the topic (`:36`, `:185-189`; `track.service.ts:25-33`). A `.kafka-progress` spinner shows until dialog open animation completes (`:63`, `:177-183`).
- `Key (deserialized from {keyFormat} format)` and `Value (deserialized from {valueFormat} format)` rendered by `ngx-json-viewer` with nulls stripped (`:66-75`, `:191-193`).
- Actions: `Cancel`, `Copy to clipboard` (value JSON, snackbar "Copied successfully"), `Resend event` (gated `TOPIC_RESEND_MESSAGE`) which closes the preview and opens the Send drawer prefilled with this message (`:78-93`, `:162-175`).

### What is different from typical Kafka UIs
- Records are a **flat grid of typed columns derived from payload JSON**, not a list of collapsible JSON blobs; columns are sortable, resizable, hideable (JSON/Headers toggles) and searchable in one box.
- Headers are first-class columns (`H[traceId]`) and are directly clickable into event tracking.
- Offset jump, partition filter, per-partition page size and a page-number box coexist in one toolbar; live tail is a toggle on the same grid with fresh-row highlighting.

---

## Send / produce event drawer (`FE/libs/feat-send/src/lib/send/send.component.ts`)

Opened from topic toolbar `Send event` (empty message) or from the preview `Resend event` (prefilled) (`topic.component.ts:193-200`; `message-view.component.ts:171-175`).
- Title `Send event to {topicName}` (`send.component.ts:31`).
- Hint text: "Available placeholders: {{uuid}} {{count}}, {{timestamp}} … Each placeholder could be formatted (e.g. {{timestamp:YYYY}}) … Supported formats: date patterns (e.g. YYYY), decimal integer conversion (e.g. 04d)" (`:36-42`).
- Sections `Key` and `Value`: each an `<app-common-editor>` (Monaco) with `schemaName` `key`/`value` and `schemaType` = the topic's latest key/value `MessageFormat` from schema registry (`:43-46`, `:70-74`).
- `Headers` section with `+` add / `-` remove per row; inputs `Header key` / `Header value` (`:48-68`, `:261-271`).
- `Count` numeric (`min=1`, validators `required`, `min(1)`) with `-`/`+` steppers; hint "How many times you want to send this event?" (`:76-93`, `:118-121`, `:246-254`).
- Buttons `Cancel`, `Send event` (disabled while sending) (`:97-108`).
- **Schema-aware**: if the cluster has a schema registry (`getSchemasConfiguration$` → `hasSchemaRegistry`), latest key/value schemas are fetched (`getLatestSchemas$`) and registered into Monaco as JSON schemas, so the editor gives validation + autocomplete; otherwise formats fall back to `STRING` (`:170-181`, `:187-210`). If schema registry configured and no message given, the editors are prefilled with **example data generated from the schema** (`getExampleSchemaData$` → `exampleKey` / `exampleValue`) (`:130-156`).
- Submit blocked with snackbar `Schema validation error` if Monaco reports markers; else `POST /api/topic/send/{topic}/{count}?serverId` then snackbar `Successfully sent to {topic}` / `Error occurred while sending events to {topic}` (`:212-244`; `send.backend.service.ts:14-21`).

---

## Resend events drawer (`FE/libs/resend-events/src/lib/resend/resend.component.ts`)

Opened from topic toolbar `Resend events` (gated `TOPIC_RESEND_MESSAGE`). Title `Resend events from {topic}` (`resend.component.ts:14`).
Form (`resend-form.service.ts:31-58`):
- **Source topic** section: `Topic` autocomplete (`placeholder="Search topic"`, empty state option `No topics found`, prefilled with current topic) (`resend.component.ts:23-48`, `:156-163`), `Partition` select populated from the chosen topic's partition count, default `0` (`:50-61`; `resend.filter.service.ts:45-48, 63-74`), `Start offset` and `End offset` number inputs (`min=0`, `required`) (`:63-75`).
- **Destination topic** section: `Topic` autocomplete (`required`), `Partition` select with `None` (−1, let Kafka pick) + partitions (`:77-117`).
- Checkbox `Filter out headers` (default true) (`:119-120`, `resend-form.service.ts:50`).
- Cross-field validator: `offsetBeginning > offsetEnd` → `offsetBeginningBiggerThanEnd` (`resend-form.service.ts:52-57`). Submit disabled while invalid (`resend.component.ts:134-140`).
- On submit a confirm dialog: title `Resend messages`, subtitle `Are you sure you want resend N message(s):`, lines `from: <src>` / `to: <dest>` (`resend-form.service.ts:61-77`); then `POST /api/topic/resend?serverId` with `ResendDataModel { sourceTopicName, sourceTopicPartition, offsetBeginning, offsetEnd, destinationTopicName, destinationTopicPartition, shouldFilterOutHeaders }` (`resend.data.model.ts:2-10`; `resend.backend.service.ts:12-19`), snackbar `Successfully sent events from X to Y` (`:86-90`).
Distinctive: server-side copy of an **offset range** between topics/partitions with optional header stripping — most UIs only offer single-message re-produce.

---

## Event tracking — `/track`

Files: `FE/apps/kouncil/src/app/track/track.component.ts`, `track/track-filter/track-filter.component.ts`, `track/track-result/track-result.component.ts`, `track/track.service.ts`, `track/track.backend.service.ts`.

### Purpose
Find every event, across many topics, whose **header** matches a field/operator/value within a time window — i.e. correlate a business flow by `traceId`/`userId` etc. Layout: filter form on top, results grid below (`track.component.ts:5-8`).

### Filter form (`track-filter.component.ts:18-115`)
- Correlation row (tooltip: "Filter messages by specifying the name and value of a message header. You can select the matching method: ~ contains, !~ does not contain, is exact, is not, regex") (`:21`, `:142-143`):
  - `Correlation field` text (header name) (`:22-31`).
  - operator select from `TrackOperator` enum: `~`, `!~`, `is`, `is not`, `regex` (`:32-40`; `track-filter.ts:12-18`).
  - `Correlation value` text (`:41-49`).
- `Topics` multi-select autocomplete (`<app-common-autocomplete>`, empty msg `No topics found`) fed from `TopicsService.getTopics$` (`:52-61`, `:153-159`).
- `Track from` / `to` `datetime-local` inputs; validation `Invalid date range` when stop < start (`:63-87`, `:194-208`). Default window = last 5 minutes, operator `~` (`track.service.ts:47-57`).
- Buttons `Clear` and `Track events` (spinner + disabled while loading) (`:89-104`).
- `async` slide toggle with tooltip "By default, Kouncil uses Web Sockets and sends events to the browser in small chunks. If this does not work for you, turn it off, but then you have to wait for the whole search to complete." (`:107-114`, `:139-141`). Toggling activates/deactivates the STOMP client (`track.backend.service.ts:44-51`).
- Filter is reset when the selected cluster changes (`:166-168`); a filter stored by the message-preview header click is restored on entry (`:150`, `track.service.ts:25-41`).

### Execution (`track-result.component.ts:208-236`, `track.backend.service.ts:26-38`)
- Async mode: generate `asyncHandle` UUID, subscribe to STOMP destination `/topic/track/{handle}`, then `GET /api/track/async?serverId&topicNames=a,b&field&operator&value&beginningTimestampMillis&endTimestampMillis&asyncHandle`. Chunks arrive as JSON arrays over the websocket; an **empty array marks completion** (`:165-175`).
- Sync mode: `GET /api/track/sync?...` returning the whole array (`track.backend.service.ts:27`).
- Rows accumulate (`allRows = [...allRows, ...new]`) as chunks arrive (`:307`).

### Results grid (`track-result.component.ts:27-46`, `:63-110`)
Sticky columns `timestamp` (formatted `yyyy-MM-dd HH:mm:ss.SSS`), `topic`, `partition`, `offset`, `key`; plus one column per **top-level JSON property common to all events** (`generateGridColumnNames`, `:238-257`), flattened by the same `JsonGrid` but with raw property names (no `V[]` prefix) and without header columns (`:259-277`). Sortable, resizable, header drag re-order; global search filters `JSON.stringify(row)` (`:193-206`). Empty state `<app-no-data-placeholder objectTypeName="Message">` also reflects the current search phrase (`:28-33`, `:202-205`). Row click opens the same **Event preview** drawer (`:178-191`).
No timeline/graph view exists — results are tabular, ordered as received.

### Why it is notable
Cross-topic header-based correlation search with streamed results, one click away from any message's header, is not offered by AKHQ/kafka-ui/Conduktor OSS out of the box.

---

## Consumer groups — `/consumer-groups`

File: `FE/apps/kouncil/src/app/consumers/consumer-groups/consumer-groups.component.ts`.
- Data: `GET /api/consumer-groups?serverId` (`consumer-groups.backend.service.ts:19-25`).
- Grouped table (`groupedTable=true`, `groupByColumns=['group']`) with group headers `Favourites` / `All consumer groups` (`:35-45`, `:158-160`); favourites persisted in localStorage key `kouncil-consumer-groups-favourites` per server via `FavouritesService` (`:24`, `:167`, `:183-193`).
- Columns: `Group id` (500px; cell = star icon toggle (gray unless favourite) + link to `/consumer-groups/{id}`) (`:47-62`, `:96-106`), `Status` (190px, cell class `status-<status>` e.g. stable/empty for colour coding) (`:108-120`, `:237-239`), action column with `Delete` (red, gated `CONSUMER_GROUP_DELETE`) (`:69-84`, `:122-132`).
- Delete → confirm dialog `Delete consumer group` / `Are you sure you want to delete:` / groupId → `DELETE /api/consumer-group/{id}?serverId` → snackbar `Consumer group X deleted` or `Consumer group X couldn't be deleted` (`:195-227`).
- Search: global phrase substring on `groupId` (`:177-181`); custom sort via `ArraySortService` (`:233-235`); row click navigates to detail (`:229-231`); empty state `No data` placeholder `Consumer group` (`:31-33`).

## Consumer group monitoring — `/consumer-groups/:groupId`

File: `FE/apps/kouncil/src/app/consumers/consumer-group/consumer-group.component.ts`.
- Header: breadcrumb `Consumer Groups > {groupId}` + `Reset offset` button (`:21-30`).
- **Polling every 1 s**: `interval(1000).pipe(switchMap(GET /api/consumer-group/{groupId}?serverId))` (`:191-211`; `consumer-group.backend.service.ts:16-19`). Stops on destroy (`:185-189`).
- Columns (`:70-159`): `clientId`, `consumerId`, `host` (rendered via `<app-cached-cell showLastSeenTimestamp>`), then `topic`, `partition`, `offset`, `endOffset`, `lag` (all number-formatted), and **`pace`** = change in lag since the previous poll, rendered `=` / `↑ N` / `↓ N` (`:141-158`, `:226-236`).
- **Cached cell**: when a partition currently has no assigned consumer (`clientId/consumerId/host` empty), the cell shows the last known value from localStorage (key `{server}_{topic}_{partition}_{prop}`) in a muted style with `Last seen: yyyy-MM-dd HH:mm:ss`, or `NO CACHED DATA` (`cached-cell/cached-cell.component.ts:9-28`; `cached-cell.service.ts:42-91`). This lets operators see *which* consumer used to own a partition after it dropped.
- Lag is computed client-side as `endOffset - offset` (0 if no committed offset) (`:226-231`).
- Global search filters `JSON.stringify(assignment)` (`:213-224`); empty state placeholder `Consumer` (`:18-20`).
- **Reset offset dialog** (`consumer-group-reset-offset.component.ts:16-57`): title `Reset consumer group {id} offset`; `Reset offset type` select with `Earliest`, `Latest`, `Timestamp`, `Offset number` (`:62-64`; model `:11-16`); conditional `Timestamp` date+time field or `Offset number` number field; `Cancel` / `Reset offset` (disabled until valid; `resetType` required) (`:66-71`); `POST /api/consumer-group/{groupId}/reset` then snackbar `The offsets for consumer group X have been successfully reset.` (`:79-96`). Applies to the whole group (no per-topic/partition selection in the form).
- No charts or lag history are stored; the "history" is the live `pace` delta plus cached last-seen assignment per partition.

---

## Topics list — `/topics`

File: `FE/libs/feat-topics/src/lib/topics/list/topics.component.ts`.

**Purpose.** Lists all topics of the selected cluster with favourites grouping; entry point to messages, create/edit/delete. It is the post-login landing screen when permitted (`docs/features/TOPICS.md:6-10`; redirect precedence in `FE/apps/kouncil/src/app/login/login-util.ts:8-26`).

**Toolbar.** Rendered only if `authService.canAccess([TOPIC_CREATE])` (topics.component.ts:31), containing a single blue button **Create topic**, disabled when `servers.getSelectedServerId() === null` (topics.component.ts:34-37, 290-292).

**Columns.**
- `Name` / prop `name`, width 500, resizeable, sortable, draggable (topics.component.ts:112-122). Custom cell: anchor `routerLink=['/topics/messages', element.name]` preceded by a `star` icon, greyed unless `element.group === 'FAVOURITES'`, toggling favourites on click (topics.component.ts:64-72).
- `Partitions` / prop `partitions`, width 150 (topics.component.ts:126-134).
- Action column `' '` / `actions`, width 150, not resizeable/sortable/draggable (topics.component.ts:137-147).

**Row actions** (topics.component.ts:85-99): **Delete** (red) if `TOPIC_DELETE`; **Edit** (white) if `TOPIC_UPDATE` (same dialog in edit mode). Row click elsewhere navigates to `/topics/messages/{name}` (topics.component.ts:52, 214-216).

**Grouping / favourites.** `[groupedTable]="true"`, `[groupByColumns]="['group']"`; group header `Favourites` or `All topics` (topics.component.ts:53-57, 180-182). Favourites in localStorage key `kouncil-topics-favourites` (topics.component.ts:25), scoped per server as `serverId + ';' + caption` (`FE/libs/feat-favourites/src/lib/favourites.service.ts:19-21`); `applyFavourites` assigns `FavouritesGroup.GROUP_FAVOURITES`/`GROUP_ALL` and sorts favourites first then alphabetically (favourites.service.ts:33-53; enum literals `'FAVOURITES'`/`'ALL'` at favourites-group.ts:1-4). Favourites are client-side only, never sent to the backend (favourites.service.ts:10-31).

**Row styling.** `row-retry` when name contains `retry`, `row-dlq` when it contains `dlq` (topics.component.ts:223-234) — DLQ/retry topics are colour-coded in the list.

**Search.** `searchService.getPhraseState$('topics')`; case-sensitive `name.indexOf(phrase)` (topics.component.ts:170-173, 196-200). **Sort.** `ArraySortService.transform` keeps favourites pinned above non-favourites regardless of direction (topics.component.ts:218-220; `FE/libs/common-utils/src/lib/util/array-sort.service.ts:24-36`).

**Empty / loading.** `<app-no-data-placeholder objectTypeName="Topic">` when `filtered.length === 0` (topics.component.ts:42-47); progress bar during load (topics.component.ts:168, 191).

**Delete flow.** Confirm `{title: 'Delete topic', subtitle: 'Are you sure you want to delete:', sectionLine1: 'Topic {name}'}` (topics.component.ts:251-264); snack `Topic {name} deleted` / `Topic {name} couldn't be deleted` (topics.component.ts:273-286).

**Data.** `GET /api/topics?serverId=…` (`FE/libs/feat-topics/src/lib/topics/topics.backend.service.ts:15-21`).

### Topic create/edit form (dialog, 500px, panelClass `app-drawer`)
File: `FE/libs/feat-topic-form/src/lib/topic/topic-form.component.ts`; opened via `MatDialog.open(TopicFormComponent, {data: topicName, width: '500px'})` (topics.component.ts:236-249).
- Header `Create new topic` / `Update topic {name}` (topic-form.component.ts:62, 96), close icon (:15-21).
- Fields (all `required`): `Name` (text; readonly unless CREATE) (:26-28), `Partitions` (number) (:32-34), `Replication Factor` (number; readonly unless CREATE) (:38-41). Validators `Validators.required` on all three (:63-67); in edit mode `name` and `replicationFactor` are disabled (:98-99) so only partition count is mutable.
- Actions **Cancel** / **Save** (disabled while invalid) (:46-53). Submit: create → `POST /api/topic/create?serverId=` snack `Topic {name} was successfully created` / `Error occurred while creating topic {name}`; edit → `PUT /api/topic/partitions/update?serverId=` (:103-113; endpoints `FE/libs/feat-topic-form/src/lib/topic/topic.backend.service.ts:16-34`). No topic-config (retention etc.) editing exists.

---

## Brokers — `/brokers`

File: `FE/apps/kouncil/src/app/brokers/brokers.component.ts`.
- Empty state `<app-no-data-placeholder objectTypeName="Broker">` (:18-22).
- Base columns (:47-76): `ID` (150), `Host` (200), `Port` (150), `Rack` — sortable/resizeable/draggable.
- JMX columns appended **only if at least one broker reports `jmxStats`** (:150-157): `System`, `CPUs` (`availableProcessors`), `Load Average` (`toFixed(2)`, :96-103), `Free Mem`, `Total Mem` (via `FileSizePipe`, :104-121; `FE/apps/kouncil/src/app/brokers/filze-size.pipe.ts:54-77`).
- Data `GET /api/brokers?serverId=…` once (`FE/apps/kouncil/src/app/brokers/broker.backend.service.ts:21-27`); no polling and no refresh button — refresh by re-navigation/cluster switch. Progress bar (:134, :141).
- Search: `getPhraseState$('brokers')`, case-insensitive on `JSON.stringify(broker)` (:159-165). `matSort` + column drag (:25-27).
- Row click → `showBrokerDetails` gated on `BROKER_DETAILS` (silently no-op without it), `GET /api/configs/{id}?serverId=…`, then drawer (:24, :167-177).

### Broker details drawer (`FE/apps/kouncil/src/app/broker/broker.component.ts`)
Opened via `DrawerService.openDrawerWithoutPadding(BrokerComponent, {config}, '987px')` (brokers.component.ts:172-174). Header **"Broker details"** + close (:12-14). Config table columns `name` (350), `value`, `source`, sortable/resizeable/draggable (:40-66), `white-table-header`. Table renders after `dialogRef.afterOpened()` (progress bar until then) (:19-31, :76-82). `BrokerConfig.isSensitive`/`isReadOnly` exist in the model but are not displayed (`brokers/broker.ts:15-21`). No edit of broker config.

---

## Consumer groups: see sections above. Schema Registry follows.

## Schema Registry — `/schemas`

File: `FE/apps/kouncil/src/app/schemas/list/schemas.component.ts`.

**Toolbar** (:21-48): `Topics` multi-select autocomplete (`emptyFilteredMsg="No topics found"`) (:24-31, :215-217); **Search** button (black, `search` icon) → `loadSchemas()` (:33-36); **Clear filters** (:38-40); **Add new schema** (blue, `/schemas/create`) only if `SCHEMA_CREATE` **and** the cluster has a schema registry configured (`serverHasSchemaConnected`) (:42-45, :178-188).

**Columns** (:97-134): `Subject name` (300), `Topic name` (300), `Message format` (150), `Version` (150); action column (:136-146) with **Delete** (`SCHEMA_DELETE`) and **Edit** (`SCHEMA_UPDATE`, `/schemas/edit/{subject}/{version}`) (:70-84). Row click → `/schemas/{subject}/{version}` (:59, :241-243). Empty state placeholder `Schemas` (:51-55). No global-search filtering on this screen; the topic autocomplete is the filter.

**Delete.** Confirm `Delete schema version` / `Are you sure you want to delete:` / `Schema version {v} for subject {s}` (:190-195); snack `Schema version {v} for subject {s} deleted` / `… couldn't be deleted` (:225-237). Deletes only that version (`docs/features/SCHEMA_REGISTRY.md:56-59`).

**Data.** `GET /api/schemas/{serverId}?topicNames=a,b` (`FE/libs/schema-registry/src/lib/schema-registry.backend.service.ts:13-48`). Failed load has no error callback → progress bar stays on (:180-183).

### Schema form — create `/schemas/create`, edit `/schemas/edit/:subject/:version`, details `/schemas/:subject/:version`
File: `FE/apps/kouncil/src/app/schemas/form/form/schema-form.component.ts` shared via `ViewMode` (wrappers `schema-create.component.ts:9-29`, `schema-edit.component.ts:10-28`, `schema-details.component.ts:6-8`).
- Header: CREATE `Add new schema`; EDIT `Editing schema for {topic}-{key|value}`; VIEW `Details of schema for {topic}-{key|value}` (:223-235).
- Fields: `Topic` select (:43-47), `Subject type` select `KEY`/`VALUE` (:48-52), `Compatibility` select over BACKWARD, BACKWARD_TRANSITIVE, FORWARD, FORWARD_TRANSITIVE, FULL, FULL_TRANSITIVE, NONE (`FE/libs/schema-registry/src/lib/compatibility.ts:1-9`) with clear button (:55-60), `Versions` select (VIEW only; changing reloads that version) (:62-67, :215-217), `Message format` radio `JSON` / `AVRO` / `PROTOBUF` (:99-103), `Schema` Monaco editor, height 400, language follows format (:78-84).
- Validation: CREATE requires topicName, subjectType, messageFormat, plainTextSchema; EDIT requires only plainTextSchema (:180-189). VIEW disables all but `version`; EDIT disables topic/subjectType/messageFormat (:169-178). Redirects to `/schemas` if no registry configured (:158-162).
- Actions bar (`schema-form-actions.component.ts`): **Check compatibility** (EDIT only) with states `Check compatibility` → `Checking...` (spinner) → `Schema is compatible` / `Schema is not compatible`, auto-reset after 5 s (:11-46, :91-93; `POST /api/schemas/test-compatibility/{serverId}`); **Cancel**; **Edit** (VIEW); **Save** (CREATE: `POST /api/schemas/{serverId}`; EDIT: `PUT /api/schemas/{serverId}` = new version) (:48-62).
- Library: `MessageFormat` = JSON, PROTOBUF, AVRO, STRING (`message-format.ts:1-6`); `SchemaFacadeService.getExampleSchemaData$` generates sample payloads for the Send form (PROTOBUF via `ProtobufUtilsService`, JSON via `JSONSchemaFaker`, AVRO via `AvroUtilsService`) (`schema-facade.service.ts:24-53`), with random-value generators in `libs/schema-registry/src/lib/generators/`.

---

## Login, SSO, change password, OAuth

### Login shell `MainLoginComponent` (`FE/apps/kouncil/src/app/login/main-login.component.ts:7-15`)
Demo strip when DEMO, navbar with `hideForAuthenticated=true`, progress bar, outlet.

### Login — `/login` (`FE/apps/kouncil/src/app/login/login.component.ts`)
- Top notice: "⚠️ Please note: Active development of Kouncil has come to an end… Migration Guide." linking `docs/MIGRATION.md` (:13-17).
- `<app-common-login-icon>` (person icon) (:19), `<app-common-login>` form (:21), info slot shown only for `inmemory` provider: "Default users: superuser, admin, editor, viewer" (:23-25), `<app-common-login-sso>` when providers exist (:29-31).
- On init: `clearLoggedIn()`, `activeProvider$()`; `'inmemory'` vs `'sso'` (fetch `GET /api/sso-providers` + context path) (:46-56). `login()` → `POST /api/login`; if inmemory then `GET /api/first-time-login/{username}`; loads roles (`GET /api/user-roles`) + installation id; navigates `/changePassword` on first login else `LoginUtil.redirectUserAfterLogin` (:58-95; `FE/libs/common-auth/src/lib/auth/auth.backend.service.ts:40-45, 77, 105-110`).
- Form (`FE/libs/common-login/src/lib/login/common-login.component.ts`): heading "Log in to your account" (:11); `Login` text field (icon `person`, required) (:15-20, :47); `Password` (icon `lock`, required) (:22-27, :48); button **Log in** (:28-30). Field error "Field is required" when touched+invalid (`login-field/common-login-field.component.ts:18-20, 45-47`).
- SSO block (`login-sso/common-login-sso.component.ts`): caption "OR SIGN IN WITH" (:10), one icon button per provider; known providers `github` (GitHub mark) and `okta` (:33-48).
- Redirect precedence after login (`login/login-util.ts:8-26`): `/topics` if TOPIC_LIST → `/brokers` if BROKERS_LIST → `/clusters` if CLUSTER_LIST → `/user-groups` if USER_GROUPS_LIST → else snackbar "Access is denied" or `/access-denied`.

### Change password — `/changePassword` (`FE/apps/kouncil/src/app/login/change-password.component.ts:10-40`; form `FE/libs/common-login/src/lib/change-password/common-change-password.component.ts`)
Heading "First login password change" (:9); `Password` (required) (:11-16, :49); `Confirm password` (required) (:18-23, :50); mismatch error "Password did not match" (:25, :57-65); buttons **Change password** (:27-29) and link **Skip** (:31-33). `POST /api/change-default-password` / `GET /api/skip-change-default-password` (`auth.backend.service.ts:73-75, 83`).

### OAuth redirect — `/oauth` (`FE/apps/kouncil/src/app/oauth/o-auth-redirect.component.ts`)
Empty template (:10); reads `code`/`state`, calls `GET /login/oauth2/code/{provider}?code&state` with `localStorage.selectedProvider`, loads roles, redirects (:22-37; `auth.backend.service.ts:60-71`). No loading/error UI.

### Auth model (`FE/libs/common-auth/src/lib/auth/`)
- `SystemFunctionName` (33 values, `system-function-name.ts:1-45`): TOPIC_{LIST,CREATE,UPDATE,DELETE,MESSAGES,SEND_MESSAGE,RESEND_MESSAGE}, BROKERS_LIST, BROKER_DETAILS, CONSUMER_GROUP_{LIST,DELETE,DETAILS}, TRACK_LIST, SCHEMA_{LIST,DETAILS,CREATE,UPDATE,DELETE}, LOGIN, USER_GROUPS, USER_GROUPS_LIST, USER_GROUP_{CREATE,UPDATE,DELETE}, CLUSTER_{LIST,CREATE,UPDATE,DETAILS,DELETE}, POLICY_{LIST,CREATE,DETAILS,UPDATE,DELETE}.
- `canAccess(roles)` = ANY-of: `userRoles.some(r => roles.includes(r))`, roles cached in `localStorage.userRoles` (`auth.backend.service.ts:112-118`). localStorage keys `isLoggedIn`, `userRoles`, `temporaryAdmin`, `userId`, `installationId` (:16-20).
- Logout: temporary admin → `DELETE /api/delete-temporary-admin`; else `GET /api/logout` (:47-58).
- Demo auth grants every function (`auth.demo.service.ts:64-74`).
- `LoggedInUserUtil.isTemporaryAdminLoggedIn()` = `localStorage.temporaryAdmin === 'true'` (`logged-in-user-util.ts:3-9`).

### Access denied — `/access-denied` (`FE/apps/kouncil/src/app/access-denied/access-denied.component.ts:6-10`)
`lock` icon, "Access denied.", "You currently do not have access to this page." Cluster selector hidden.

### Page not found — `**` (`FE/apps/kouncil/src/app/page-not-found/page-not-found.component.ts:9-15`)
`not_listed_location` icon, "Page not found.", "Sorry, we can't find that page." Rendered inside the main shell (navbar + sidebar).

---

## First-time launch / temporary admin (`FE/libs/feat-first-time-app-launch/`)

- Triggered by `PermissionsConfigResolver` on the login route tree: `GET /api/permissions-not-defined`; if true, a confirm dialog **"Temporary access to application"** / "User groups and permissions are not defined. You will be logged in as a temporary user with access to define them." (`first-time-app-launch.backend.service.ts:20-30`).
- On **Yes**: `POST /api/create-temporary-admin` → mark logged in → load roles → navigate `/user-groups` → set `temporaryAdmin` flag (:38-50). Banner then shows in the main shell (see Layout) and the temporary user is deleted on logout. Docs: `docs/features/ONBOARDING.md` (images `docs/.github/img/features/onboarding/onboarding_popup.png`, `onboarding.png`).
- Demo: always `false` (`first-time-app-launch.demo.service.ts:10-15`).

---

## Clusters management

### Cluster list — `/clusters` (`FE/libs/feat-clusters/src/lib/clusters/clusters.component.ts`)
- Toolbar **Add new cluster** (blue, gated `CLUSTER_CREATE`) → `/clusters/cluster` (:27-30, :162-164).
- Columns: `Name` (300), `Brokers` (300, `brokers.map(b => b.bootstrapServer).join(', ')`) (:85-105); action column: **Delete** (`CLUSTER_DELETE`), **Edit** (`CLUSTER_UPDATE`, `/clusters/cluster/{name}/edit`) (:59-68, :107-117). Row click → `/clusters/cluster/{name}` (:46, :158-160). Sort + column drag (:43-45).
- Search key `'clusters'`, case-sensitive on `name` (:141-144, :166-170). Empty state `Clusters` (:36-40). Progress bar (:139, :154).
- Delete: confirm `Delete cluster` / `Are you sure you want to delete:` / `Cluster {name}`; `DELETE /api/cluster/{id}`; snack `Cluster {name} deleted` / `Cluster {name} couldn't be deleted`; then `ServersService.load()` re-run so the toolbar switcher updates (:172-217).
- Data `GET /api/clusters` (`clusters.backend.service.ts:14-16`). Cluster selector hidden on these routes.

### Cluster form — create `/clusters/cluster`, view `/clusters/cluster/:name`, edit `/clusters/cluster/:name/edit`
File: `FE/libs/feat-clusters/src/lib/cluster-form/cluster-form.component.ts` with `ViewMode` wrappers (`cluster-form-create/edit/view.component.ts:6-8`).
- Breadcrumb `Clusters > Create a new cluster | Edit {name} cluster | {name}` (:33-34, :248-258).
- Three expansion panels: **Cluster and broker data** (expanded), **Cluster security**, **Schema registry** (:38-40, :69-71, :78-80).
- Panel 1 fields: `Cluster name` (placeholder "Unique cluster name"; `required` + `noWhitespaces` + async unique via `GET /api/cluster/{name}/is-cluster-name-unique`; readonly unless CREATE) (:43-46, :103-107, :260-266; `cluster.backend.service.ts:31-33`); `Global JMX port`, `Global JMX user` (`noWhitespaces`), `Global JMX password` (:49-62, :130-136).
- **Brokers** sub-section (`sections/cluster-form-brokers/cluster-form-brokers.component.ts`): **Add broker** button (hidden in VIEW) (:20-28); per row `Bootstrap server` (placeholder "Broker bootstrap url and port"; required + regex `/.*:[0-9]+/` → "Field value is incorrect"), `JMX port`, `JMX user` (`noWhitespaces`), `JMX password`, remove button (:34-63, :82-94, :108-113). FormArray itself `required` (cluster-form.component.ts:129); CREATE auto-adds one row (:183-185).
- **Cluster security** (`sections/cluster-form-security/cluster-form-security.component.ts`): radio `Authentication method` = None / SASL / SSL / AWS MSK (`cluster.model.ts:54-59`). SASL: `Security protocol` (SASL plaintext / SASL SSL), `SASL Mechanism` (Plain / SCRAM-SHA-256 / SCRAM-SHA-512), `Username`, `Password` — required (:23-46; model :61-70). SSL (or SASL_SSL): `Keystore file location`, `Keystore password`, `Key password` (optional), `Truststore file location`, `Truststore password` (required) (:49-80). AWS MSK: `AWS Profile name` required (:83-90). Required-ness re-computed dynamically on method/protocol change, values cleared (:120-185); any change resets the Test-connection button (:121, :173).
- **Schema registry** (`sections/cluster-form-schema-registry/cluster-form-schema-registry.component.ts`): `Schema registry URL` (:14-16); radio `Authentication method` = None / SSL / SSL with basic auth (`cluster.model.ts:72-76`); SSL fields `Keystore file location/password/type (JKS|PKCS12)`, `Key password`, `Truststore file location/password/type` (required) (:28-62); basic-auth adds `Username`, `Password` (:65-73); dynamic validators (:99-125).
- Actions (`sections/cluster-form-actions/cluster-form-actions.component.ts`): **Test connection** (disabled while invalid) with states "Test connection" → "Connecting..." → "Connection successful" / "Connection failed", auto-reset 5 s; `POST /api/cluster/test-connection` (:12-46, :90-103); **Cancel**; **Save** (CREATE/EDIT); **Edit** (VIEW) (:48-65).
- Save: `PUT /api/cluster` if id else `POST /api/cluster`; snack `Cluster {name} was successfully created.` (also on update); navigate `/clusters` and reload `ServersService` (:216-245). VIEW disables all controls; EDIT disables `name` (:194-204).
- Docs: `docs/features/CLUSTERS.md`, screenshot `docs/.github/img/kouncil_clusters.png`.

### Servers / cluster switcher (`FE/libs/common-servers/`)
`Server {serverId, label}` (`server.ts:1-3`). `ServersBackendService.load()`: `GET /api/connection` (map id→label), select `localStorage.lastSelectedServer` or the first server, push `servers$`; also loads schema-registry configuration into `SchemaStateService` (`servers.backend.service.ts:15-52`). `selectedServerChanged$` subject lets screens (e.g. Track) react (`servers.service.ts:6-32`). Demo lists `first_server_local_9092`, `second_server_local_9092` (`servers.demo.service.ts:12-20`). UI is the toolbar `mat-select` (see Layout).

---

## User groups and permissions

### User groups list — `/user-groups` (`FE/libs/feat-user-groups/src/lib/user-groups/list/user-groups.component.ts`)
- **Add new group** (gated `USER_GROUP_CREATE`) (:19-28); column `Group name` (:77-88); row **Delete** (`USER_GROUP_DELETE`) / **Edit** (`USER_GROUP_UPDATE`) (:51-62). Empty state `User groups` (:31-33). No search wiring.
- Form dialog (500px, `user-groups/form/user-group-form.component.ts`): title `Create new user group` / `Update user group {name}` (:60, :97); fields `Code` (required, no-whitespace, async unique via `POST /api/user-group/is-user-group-code-unique`) and `Name` (required) (:33-39, :63-68, :140-149); **Cancel** / **Save** (:43-52); `POST`/`PUT /api/user-group`, snacks `User group {name} was successfully created|updated` / `Error occurred while creating|updating user group {name}` (:102-138).
- Delete: confirm `Delete user group` / `User group {name}`; `DELETE /api/user-group/{id}`; snacks `User group {name} deleted` / `… couldn't be deleted` (:145-181).

### Permissions matrix — `/user-groups-permissions` (`FE/libs/feat-user-groups/src/lib/user-groups-functions-matrix/user-groups-functions-matrix.component.ts`)
- Breadcrumb `User groups permissions` (:14); VIEW shows **Edit**; EDIT shows **Cancel** / **Save** (:18-32). Starts in VIEW with all checkboxes disabled (:80, :94).
- One expansion panel per function group `TOPIC, CONSUMER_GROUP, SCHEMA_REGISTRY, CLUSTER, ADMIN, DATA_MASKING` (`user-groups.model.ts:15-23`), header row = function labels from `GET /api/functions` (e.g. "Topic list", "Create new topic"), one row per user group with a `mat-checkbox` per function (:50-85, :109-126).
- Save → `POST /api/user-groups` with all groups; no feedback snackbar (:156-160). Cancel snapshot only covers columns, not group membership (:136-144). Empty state `Permissions` (:38-43). Docs: `docs/features/USER_GROUPS.md`; images `docs/.github/img/kouncil_user_groups.png`, `kouncil_user_group_permissions.png`, `kouncil_user_groups_permissions_relogin.png`.

---

## Data masking policies

### Policies list — `/data-masking-policies` (`FE/libs/feat-data-masking/src/lib/policies/policies.component.ts`)
**Add new policy** (gated `POLICY_CREATE`) (:23-32); columns `Name` (300), `Fields` (300, joined `PolicyField.field`) (:85-105); row **Delete** (`POLICY_DELETE`) / **Edit** (`POLICY_UPDATE`) (:57-69); row click → details (:45, :204-206). Search reuses key `'clusters'` (:139), filter on `name` (:155-159). Empty state `Policies` (:35-39). Delete confirm `Delete policy` / `Policy {name}`; snacks `Policy {name} deleted` / `… couldn't be deleted` (:162-196). `GET /api/policies` (`policies.backend.service.ts:15-17`).

### Policy form — create `/data-masking-policy`, edit `/data-masking-policy/:id/edit`, view `/data-masking-policy/:id` (`FE/libs/feat-data-masking/src/lib/policy/policy-form.component.ts`)
- Breadcrumb `Policies > Create a new policy | Edit {name} policy | {name}` (:29-33, :120-130).
- `Name` (required) (:36-38, :67-80).
- **Fields** section (`sections/policy-form-fields/policy-form-fields.component.ts`): **Add field**; per row text `Regex or full field name. Use dot (.) as field separator if need path to access your field.` + select `Masking type` = `Hide all` (ALL) / `Hide first 5 signs` (FIRST_5) / `Hide last 5 signs` (LAST_5) (`policy.model.ts:21-25`) + remove (:12-41, :55-56). Validator: at least one row, each with field + type (policy-form.component.ts:163-168).
- **Resources** section (`sections/policy-form-resources/policy-form-resources.component.ts`): checkbox `Apply policy to all resources` (:26-28); otherwise rows of `Cluster` select + `Regex or full topic name` text + remove (:35-48); validator requires rows unless apply-to-all (policy-form.component.ts:153-161).
- **User groups** section: autocomplete `User groups names`, placeholder "User groups for which the policy will be applied.", required (`sections/policy-form-user-groups/policy-form-user-groups.component.ts:15-21`).
- Actions **Cancel** / **Save** (disabled while invalid) / **Edit** (VIEW) (`sections/policy-form-actions/policy-form-actions.component.ts:10-28`). Endpoints `POST|PUT /api/policy`, `GET|DELETE /api/policy/{id}` (`policy-backend.service.ts:15-29`). Masked values are shown with `*` in message views for matching users (`docs/features/DATA_MASKING.md:48-57`; images under `docs/.github/img/features/data_masking/`).

---

## Notifications (`FE/libs/feat-notifications/`)
`<app-notification-button>` in the toolbar has an **empty template**; it only subscribes to STOMP `/notifications` (`notification-button/notification-button.component.ts:14, 27`). `PUSH_WITH_ACTION_REQUIRED` → 600px dialog `NotificationComponent` titled "Action required" with message and a **Logout** button (`LOGOUT` action logs out and goes to `/login`) (:44-63; `notification/notification.component.ts:7-38`). `PUSH` → persistent snackbar with `Close` action; ERROR style for `CLUSTERS_NOT_DEFINED`, else INFO (:65-86). Model `notification.model.ts:1-14`. There is no notification list/history screen.

## Confirm dialog (`FE/libs/feat-confirm/src/lib/confirm/confirm.component.ts:7-27`)
Title + close icon, subtitle, optional `sectionLine1`/`sectionLine2`, buttons **No** (white) / **Yes** (red). 600px, `ConfirmService.openConfirmDialog$(model)` returns `afterClosed()` (`confirm.service.ts:15-21`; `confirm.model.ts:1-6`).

## No-data placeholder (`FE/libs/feat-no-data/src/lib/no-data-placeholder/no-data-placeholder.component.ts:7-36`)
Renders only when progress bar is not loading; `search_off` icon, "No data to display", and `{objectTypeName} "{phrase}" not found` when a search phrase exists. Note: failed loads generally fall through to this same placeholder (error only via snackbar).

## Breadcrumb (`FE/libs/feat-breadcrumb/src/lib/breadcrumb/breadcrumb.component.ts:5-13`)
`parent` link + `arrow_forward_ios` + `name` with tooltip; inputs `parentLink`, `parentName`, `name`.

## Demo mode (`FE/apps/kouncil/src/app/demo/demo.component.ts:5-10`)
Strip "This is a demo version of Kouncil. Get the full version at kouncil.io" shown in both shells when `environment.backend === 'DEMO'`; every service has a `*.demo.service.ts` twin selected in `app-factories.ts` (e.g. `:52-72`) producing random topics/messages/consumer groups. Demo at `https://kouncil-demo.web.app/` (`docs/README.md:20-23`).

## Survey (`FE/apps/kouncil/src/app/survey/survey.component.ts`)
In-app survey panel above the progress bar: HTML message, scale questions (`survey-scale-question.component.ts:15-38`, follow-up textarea when value in `questionWhenSelectedRange`), buttons **Accept** / **Close** (:40-47); validation snackbar "Answer required questions" (:81-100). Backed by an external survey service (`survey.backend.service.ts:21-93`, `GET /api/survey/config` base URL; route-targeted via `triggers[].elementId` :99-121). `fetchSurvey$` is not invoked outside the survey folder, so it is dormant in this snapshot.

---

## Common components (`FE/libs/common-components/`)

- **`app-common-table`** (`table/table.component.ts`): `mat-table` with sticky header (:27), columns = `additionalColumns ++ columns ++ actionColumns` (:127-129), `TableColumn.name` doubles as matColumnDef key and header label (:150-152). **Column drag re-order** via `cdkDropList` (`drop()` clamps sticky columns to the sticky zone) (:154-170); **resize** via `ResizeColumnDirective` (`resize-column.directive.ts:26-67`, drag handle `span.resize-holder`, min-width applied to every cell of that index); **sort** via `MatSort` (:113-120); **grouping** rows (`TableGroup`, header cell `colspan=999` with `HIDE`/`SHOW` toggle) (:33-47, :179-256); row click emits only when target is not `MAT-ICON`/`BUTTON` (:259-264); `rowClass` input (:79-81). `TableColumn {name, prop, sticky, resizeable, sortable, draggable, width?, valueFormatter?, columnClass?}` (`table-column.ts:1-15`). `AbstractTableComponent` supplies `sort` and `drop()` (`abstract-table.component.ts:9-23`). Every column gets `mat-sort-header` regardless of `sortable` (`table-column.component.ts:7-29`).
- **Form fields**: `text-field` (label + `*`, errors "Field is required" / "Field value is not unique" / "Field value is incorrect") (`text-field/text-field.component.ts:6-28`); `number-field` (:6-22); `select-field` (`SelectableItem` options, optional clear button) (`select-field.component.ts:8-35`); `checkbox-field`; `radio-field`; `password-field`; `date-time-field`; `autocomplete` (multi-select with "select all" checkbox, `All selected` display, whitespace-split AND term matching) (`autocomplete.component.ts:16-132`). `SelectableItem(label, value, selected)` (`selectable-item.ts:1-14`).
- **Monaco editor** `app-common-editor` (`editor/editor.component.ts`): ControlValueAccessor + Validator; language by `MessageFormat` (JSON/AVRO → json, PROTOBUF → proto, STRING → plaintext) (:74-94); JSON schemas registered via `MonacoEditorService.addSchema/registerSchemas` for in-editor validation (`monaco-editor.service.ts`).
- `ProgressBarService`/`app-progress-bar` (`common-utils/src/lib/util/progress-bar.component.ts:5-12`), `SearchService` per-tab phrase memory (`common-utils/src/lib/search/search.service.ts:5-23`), `SnackBarComponent` with `INFO|SUCCESS|ERROR` (`snack-bar-data.ts:1-18`), `ViewMode` CREATE|EDIT|VIEW (`view-mode.ts:1-5`).

---

## Docs summary (`docs/`)
- `docs/README.md:1` deprecation banner; `:14-18` headline features: advanced record browsing, multiple cluster support, cluster monitoring, consumer group monitoring, event tracking; Docker quick start `:25-45`; default admin/admin `:52`.
- `docs/FEATURES.md` is a 9-line stub (heading + demo link) with no images. `docs/SUMMARY.md:31-33` links only `features/DATA_MASKING.md` and `features/ONBOARDING.md`.
- `docs/features/TOPICS.md`: topic list landing, create/change/delete (:6-10); record browsing per partition/whole topic with view-source/copy/repost (:16-21); event tracking (:31-34). Images `docs/.github/img/kouncil_topics.png`, `kouncil_topic_details_border.png`, `kouncil_topic_event_details.png`, `kouncil_event_tracking.png`.
- `docs/features/SCHEMA_REGISTRY.md`, `DATA_MASKING.md`, `ONBOARDING.md`, `CLUSTERS.md`, `CONSUMER_GROUPS.md`, `USER_GROUPS.md` as cited above; `docs/ROADMAP.md:3-15` version table (1.1 UI tweaks … 1.9 external DB/in-app clusters/granular permissions/OKTA; in-progress data masking; TODO alerts & notifications, helm/terraform).

## Cross-cutting observations
- No dedicated error states: failures surface only as snackbars; empty results and failed loads both show the same placeholder.
- Real-time: STOMP websocket for Track results and `/notifications`; 1 s HTTP polling for topic live-update and consumer-group monitoring; everything else refreshes via the no-reuse router strategy (every navigation re-runs `ngOnInit`).
- Favourites (topics, consumer groups) and cached consumer assignments are localStorage-only.
- IA: cluster is a global toolbar context, not a URL segment; detail/edit surfaces are right-side drawers over the list rather than separate routes; one global search box drives every list.

## What Kouncil does better or differently (summary for KUI)

Cross-references point at the sections above.

1. **Table-style record browsing with JSON flattening** (`json-grid.ts`, "Topic messages view").
   Each record's headers, key and value are flattened into `H[]`, `K[]`, `V[]` columns to a
   bounded depth, with array/object collapse limits and a hard row cap, so a topic reads like a
   spreadsheet and can be scanned column by column. Kafbat only offers one row per message with
   an expandable JSON body. KUI: adopt as a second view over the same message stream (DC-H4 in
   the Kafbat report).
2. **Event tracking** (`/track`). A multi-topic, time-bounded search by header/field value with
   results streamed over STOMP into a grid, reachable in one click from any header value in the
   record grid. Kafbat has no cross-topic correlation feature. KUI: schedule as a
   `kui-ui-messages` screen once the message service exposes a bounded multi-topic scan
   (open question in the Kafbat report).
3. **Consumer group monitoring** (`/consumer-groups/:groupId`). 1 s polling, per-partition lag
   with a computed `pace` delta and a localStorage cache of last-seen assignments so a
   rebalancing group does not blank the table. Kafbat polls lag on a user-chosen interval and
   has no pace/delta. KUI: keep the pace column and the last-seen cache; make the interval
   registry-driven (DC-H7).
4. **Resend flow** (resend drawer). Source topic/partition/offset range to a destination topic,
   with header stripping. Kafbat only has single-message "reproduce". KUI: adopt as a dialog
   in `kui-ui-messages` (screen 14 in the IA table).
5. **Layout and IA.** A global toolbar with a cluster switcher and global search, a role-gated
   flat sidebar, right-hand drawers for detail/produce/resend instead of page navigation, and a
   breadcrumb. Kafbat nests everything under a per-cluster sidebar tree. KUI: keep Kafbat's
   per-cluster tree (it scales to many clusters) but take Kouncil's drawers, breadcrumb and
   capability banner.
6. **Product hygiene Kafbat lacks:** first-launch temporary admin flow, in-app cluster
   management with "test connection", user-group permission matrix, data-masking policy
   editor, no-data placeholder component, demo mode.

## Decision candidates

**DC-H8 — Adopt Kouncil's flattening algorithm (depth cap, collapse limits, row cap) as the
spec for the KUI table view, implemented client-side in `kui-ui-messages`.**
Evidence: `json-grid.ts` is small, pure and already tuned by users; it works over any JSON
value so it needs no backend support. Tradeoff: very wide payloads produce many columns;
the column picker must default to a sensible subset. Reversibility: high.

**DC-H9 — Use right-hand drawers (Kouncil `DrawerService` pattern) for message detail,
produce, resend and filter editing; use full pages only for list and details screens.**
Evidence: Kafbat mixes a `SlidingSidebar` for produce with modals for filters and an
expandable row for message detail, three patterns for one job. Tradeoff: the kernel needs a
single `Drawer` component with stacking rules. Reversibility: high.

**DC-H10 — Consumer group detail keeps last-seen partition assignments in feature state
(and localStorage) and shows them greyed during rebalance and during Degraded/Unavailable.**
Evidence: Kouncil's localStorage cache; matches DC-H3 (stale data stays on screen).
Tradeoff: a stale-assignment badge is required so operators do not misread old owners as
current. Reversibility: high.

**DC-H11 — Event tracking is in scope for `kui-ui-messages`, but not before the message
service has a bounded multi-topic scan endpoint with the same budgets as `browse`.**
Evidence: Kouncil runs tracking as an unbounded server-side scan streamed over STOMP; PLAN §22
forbids unbounded consumption. Tradeoff: delays a differentiating feature by one milestone.
Reversibility: high.

**DC-H12 — Ship an in-app cluster management screen with "test connection" and a first-launch
admin bootstrap in `kui-ui-admin`, in addition to file-based configuration.**
Evidence: Kouncil's `feat-clusters` and `feat-first-time-app-launch`; Kafbat's wizard exists but
its restart flow is awkward and it has no bootstrap flow. Tradeoff: dynamic config must be
persisted by the gateway (PLAN §24 decides where). Reversibility: medium.

## Open questions

- Kouncil's tracking transport is STOMP over WebSocket; KUI standardises on SSE (PLAN §21).
  Confirm SSE is sufficient for the tracking result stream (it is one-directional, so it
  should be).
- Kouncil's data-masking policies are configured in the UI; PLAN §22 places masking in the
  message service. Decide whether policies are UI-editable in M2 or file-config only.
- Should the JSON-flattened columns be derivable server-side for very large payloads to reduce
  bytes over the wire (column projection), or is client-side always enough?

## Confidence

**High** for the inventory (read from source, cited by line). **Medium** for the
"better/different" judgements: they compare code, not user studies. **Medium** for DC-H11
because it depends on the message-service contract still being designed.
