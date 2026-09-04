# Kafbat UI — screen-by-screen UI/UX inventory

**Date:** 2026-09-03

## Questions

1. What screens does Kafbat UI have, and for each: route, purpose, data shown, actions, forms
   and their validation rules, filter/sort/search controls, empty/loading/error states,
   permission gating, real-time elements?
2. How is navigation structured (sidebar, cluster switcher, breadcrumbs) and what global
   elements exist (toasts, confirmation modals, theme)?
3. How does the cluster configuration wizard work?
4. Which KUI screens map to which microfrontend, how should the
   capability registry drive navigation, and what does every feature look like when its
   service is Unavailable or Degraded?

## Method and sources

- Source read directly (grep and sed over files; every claim cites `path:line`, paths relative
  to `/tmp/kui-ref/kafbat/frontend/src` unless stated otherwise):
  - Kafbat UI, `/tmp/kui-ref/kafbat`, commit `fa485c2bd45cac713cd994c62bc2d458abd3f328`
    (2026-09-03), frontend package version 1.0.0 (React 18, react-router v6, TanStack Table,
    react-query, react-hook-form + yup, styled-components).
  - Provectus kafka-ui, `/tmp/kui-ref/provectus`, commit
    `83b5a60cc08501b570a0c4d0b4cdfceb1b88d6b7` (2024-04-08), consulted only where Kafbat's
    tree lacked a feature (nothing material was found: Kafbat is a superset).
  - Kafbat docs tree `/tmp/kui-ref/kafbat/documentation` (compose examples and demo GIFs; no
    per-screen docs are checked in, so the inventory relies on source).
- Inventory split across four read passes (shell/dashboard/wizard/brokers; topics/messages;
  consumers/schemas/connect/ksql/acl; Kouncil) and merged here. Kouncil's inventory is in
  `research/kouncil/ui-analysis.md`.

## Findings

## Global shell

### Bootstrapping and providers

- Entry: `index.tsx:14-19` renders `<BrowserRouter basename={window.basePath || '/'}>` → `<ThemeModeProvider>` → `<App />`. `window.basePath` is declared in `lib/constants.ts:7-11`; every API client uses `BASE_PARAMS` (`lib/constants.ts:13-19`: `basePath`, `credentials: 'include'`, JSON content-type).
- `components/App.tsx:40-65`: single `QueryClient` with `QueryCache.onError` and `MutationCache.onError` both calling `showServerError` (global error toasts for every failed query/mutation), `networkMode: 'offlineFirst'`.
- `components/App.tsx:66-124` provider tree: `QueryClientProvider` → `ThemeProvider theme={isDarkMode ? darkTheme : theme}` → if `useMatch('/login')` render `<AuthPage/>` bare (no shell) else `GlobalSettingsProvider` → `Suspense(PageLoader fullSize)` → `UserInfoRolesAccessProvider` → `ConfirmContextProvider` → `GlobalCSS` → `S.Layout` → `PageContainer` → `<Routes>`; `<Toaster position="bottom-right"/>` (react-hot-toast) inside Layout; `<ConfirmationModal/>` mounted once globally; `ReactQueryDevtools` at bottom-right.
- Lazy chunks: AuthPage, Dashboard, ClusterPage, ClusterConfigForm, ErrorPage (`App.tsx:32-38`).

### Route table

Top level (`components/App.tsx:83-110`):

| Path | Element |
|---|---|
| `/`, `/ui`, `/ui/clusters` | `Dashboard` |
| `/ui/clusters/create-new-cluster/*` (`clusterNewConfigPath`, `lib/paths.ts:328-329`) | `ClusterConfigForm` (empty wizard) |
| `/ui/clusters/:clusterName/*` (`clusterPath()`, `lib/paths.ts:27-34`; name is `encodeURIComponent`-ed) | `ClusterPage` |
| `/403` (`accessErrorPage`) | `ErrorPage status=403 text="Access is Denied"` |
| `/404` (`errorPage`) | `ErrorPage` (default = "Unexpected error") |
| `*` | `Navigate to /404 replace` |
| `/login` | `AuthPage` (handled by `useMatch`, not a Route) |

Cluster-level (`components/ClusterPage/ClusterPage.tsx:82-141`, all relative to `/ui/clusters/:clusterName`):

| Relative path (`lib/paths.ts`) | Element | Gate |
|---|---|---|
| `brokers/*` (`:39`) | `Brokers` | always |
| `all-topics/*` (`:141`) | `Topics` | always |
| `consumer-groups/*` (`:78`) | `ConsumerGroups` | always |
| `schemas/*` (`:102`) | `Schemas` | `ClusterFeaturesEnum.SCHEMA_REGISTRY` |
| `create-new` (`:217`) | Connect `New` | `KAFKA_CONNECT` |
| `connects/:connectName/connectors/:connectorName/*` (`:216`) | Connect `DetailsPage` inside `SuspenseQueryComponent` | `KAFKA_CONNECT` |
| `kafka-connect/*` (`:212`) | `KafkaConnect` | `KAFKA_CONNECT` |
| `ksqldb/*` (`:304`) | `KsqlDb` | `KSQL_DB` |
| `acl/*` (`:332`) | `AclPage` | `KAFKA_ACL_VIEW` or `KAFKA_ACL_EDIT` |
| `config/*` (`:323`) | `ClusterConfigPage` | `GlobalSettingsContext.hasDynamicConfig` |
| `/` | `Navigate to brokers replace` | — |

Full path helpers in `lib/paths.ts`: brokers (`clusterBrokersPath`, `clusterBrokerPath(cluster, brokerId)`, `.../metrics`, `.../configs` `:43-70`), consumer groups (`.../consumer-groups/:consumerGroupID`, `.../reset-offsets` `:78-95`), schemas (`.../schemas/create-new`, `.../schemas/:subject`, `/edit`, `/compare` `:102-133`), topics (`.../all-topics`, `/create-new-topic`, `/copy`, `/:topicName` + `settings|messages|consumer-groups|statistics|connectors|acls|edit` `:141-202`), Kafka Connect (`.../kafka-connect`, `/clusters`, `/connectors`, `.../connects/:connectName/connectors/:connectorName` + `tasks|config|topics|edit` `:210-295`), KSQL (`.../ksqldb/query|tables|streams` `:304-320`), config (`.../config` `:323-326`), ACL (`.../acl`, `create-new-acl` `:332-336`). `RouteParams` enum `:12-20`; `getNonExactPath(p) = p + '/*'` `:22`; `gitCommitPath` `:9-10`.

`lib/hooks/useAppParams.tsx:4-19` wraps `useParams` and `decodeURIComponent`s `clusterName`.

### Contexts

- `ThemeModeContext` (`components/contexts/ThemeModeContext.tsx:11-58`): `isDarkMode`, `themeMode: 'auto_theme'|'light_theme'|'dark_theme'`, `setThemeMode`. Persists to `localStorage['mode']` (raw key, no prefix `:23-27,36-42`); `auto_theme` follows `prefers-color-scheme: dark` `:29-34`.
- `GlobalSettingsContext` (`GlobalSettingsContext.tsx:6-46`): `{ hasDynamicConfig }` derived from `useAppInfo().data.response.enabledFeatures.includes('DYNAMIC_CONFIG')` `:30-38`; if `info.data.redirect` (the `/api/info` response URL contains `auth`) it `navigate('login')` `:25-28`.
- `UserInfoRolesAccessContext` (`UserInfoRolesAccessContext.tsx:11-39`): `{ username, roles: Map<clusterName, Map<ResourceType, UserPermission[]>>, rbacFlag }` built by `modifyRolesData` from `useGetUserInfo()` (`lib/hooks/api/roles.ts:5-11`, `GET getUserAuthInfo`, refetch off). `useUserInfo()` (`lib/hooks/useUserInfo.ts`) returns it.
- `ConfirmContext` (`ConfirmContext.tsx:11-53`): `content`, `confirm`, `cancel`, `dangerButton`, `isConfirming`. `useConfirm(danger=false)` (`lib/hooks/useConfirm.ts:4-22`) returns `(message, callback) => void`; the wrapped confirm sets `isConfirming` around `await callback()` and always `cancel()`s in `finally`.
- `ClusterContext` (`ClusterContext.ts:3-24`) value computed in `ClusterPage.tsx:46-72`: `isReadOnly` (cluster.readOnly), `hasKafkaConnectConfigured`, `hasSchemaRegistryConfigured`, `isTopicDeletionAllowed` (`TOPIC_DELETION`), `hasKsqlDbConfigured`, `hasAclViewConfigured`, `ftsEnabled` (`FTS_ENABLED`), `ftsDefaultEnabled`, `messageRelativeTimestamp` (`MESSAGE_RELATIVE_TIMESTAMP`). `ClusterPage.tsx:74-76` shows `PageLoader` until `useClusters().isFetched`.
- `TopicActionsContext` (`TopicActionsContext.tsx`) — `openSidebarWithMessage(message)` for topic messages (out of scope here).

### Permissions

- Enums (contract `contract/src/main/resources/swagger/kafbat-ui-api.yaml:4138-4170`): `Action` = ALL, VIEW, EDIT, CREATE, DELETE, RESET_OFFSETS, EXECUTE, MODIFY_GLOBAL_COMPATIBILITY, ANALYSIS_VIEW, ANALYSIS_RUN, MESSAGES_READ, MESSAGES_PRODUCE, MESSAGES_DELETE, OPERATE, RESTART. `ResourceType` = APPLICATIONCONFIG, CLUSTERCONFIG, TOPIC, CONSUMER, SCHEMA, CONNECT, CONNECTOR, KSQL, ACL, AUDIT, CLIENT_QUOTAS.
- `lib/permissions.ts:75-112 isPermitted({roles, resource, action, clusterName, value, rbacFlag})`: returns `true` when `!rbacFlag`; otherwise looks up `roles.get(clusterName).get(resource)` and requires every requested action to be present in some permission whose `value` regex (`valueMatches` `:50-54`, `new RegExp(regexp).test(val)`; undefined regexp → true; missing value → false) matches — except resources in `ResourceExemptList` (KSQL, CLUSTERCONFIG, APPLICATIONCONFIG, ACL, AUDIT `:7-13`) which ignore `value`. `isPermittedToCreate` `:127-151` only checks `Action.CREATE` presence (no value matching).
- Hooks: `usePermission(resource, action|action[], value?)` (`lib/hooks/usePermission.ts:8-17`, takes clusterName from route); `useCreatePermission(resource)` (`lib/hooks/useCreatePermisson.ts:9-14`).
- `ActionComponent` family (`components/common/ActionComponent/*`): shared props `{ permission: {resource, action, value?}, message?, placement? }` (`ActionComponent.ts:4-12`), default tooltip text `"You don't have a required permission to perform this action"` (`:14-16`). Disabled state shows a floating tooltip on hover via `useActionTooltip(isDisabled, placement)` (`lib/hooks/useActionTooltip.ts:11-35`, floating-ui `offset(10)+autoPlacement`, only opens when disabled).
  - `ActionButton` (`ActionButton/ActionButton.tsx:10-16`): routes to `ActionCreateButton` when `action === CREATE` (uses `useCreatePermission`) else `ActionPermissionButton` (uses `usePermission`); both render `ActionCanButton` (`ActionCanButton.tsx:14-46`) which renders `<Button disabled={disabled || !canDoAction}>` wrapped in `S.Wrapper` plus `S.MessageTooltipLimited`. `ActionCanButton` is also used directly with an explicit `canDoAction` boolean (Dashboard).
  - `ActionDropdownItem` (`ActionDropDownItem/ActionDropdownItem.tsx:16-71`): supports `fallbackPermission` (OR logic), placement default `left`.
  - `ActionNavLink` (`ActionNavLink.tsx:13-60`): adds class `is-disabled`, `aria-disabled`, `preventDefault` on click.
  - `ActionPermissionWrapper` (`ActionPermissionWrapper.tsx:15-55`): wraps arbitrary child, `cursor: not-allowed`, runs `onAction` only when permitted.
  - `ActionSelect` (`ActionSelect.tsx:13-53`): disabled `Select` with tooltip.
- `PageContainer.tsx:38-43` and `Dashboard.tsx:88-93` compute `hasApplicationPermissions` = rbac disabled OR any permission with `resource === APPLICATIONCONFIG`.

### Layout: PageContainer, NavBar, Sidebar

- `components/PageContainer/PageContainer.tsx:21-71`: renders `<NavBar onBurgerClick={toggle}/>`, then `S.Container` with `S.Sidebar aria-label="Sidebar"` (`<Nav/>`), an `S.Overlay` (click/keydown closes sidebar), and `Suspense(PageLoader fullSize)` around page children. Sidebar defaults open on large screens (`useScreenSize().isLarge` = `innerWidth > breakpoints.M (1024)`; `theme/theme.ts:206-210` breakpoints S=768, M=1024, L=1440), auto-closes on route change when not large (`:34-36`). Redirect rule `:45-50`: if `hasDynamicConfig` and clusters list is empty and user has APPLICATIONCONFIG permission → `navigate('/ui/clusters/create-new-cluster')`. Layout constants `theme/theme.ts:211-216`: `minWidth 1200px`, `navBarWidth 240px`, `navBarHeight 51px`, `rightSidebarWidth 70vw`.
- `components/NavBar/NavBar.tsx:55-104` (`role="navigation" aria-label="Page Header"`): left — burger `Button buttonType="text"` with `MenuIcon`, link `/` with `Logo` + text "kafbat UI", `<Version/>`; right — `<UserTimezone/>`, theme `Select` (`isThemeMode`, options "Auto theme"/"Light theme"/"Dark theme" with Auto/Sun/Moon icons `:25-53`), social links GitHub (`https://github.com/kafbat/kafka-ui`), Discord, ProductHunt, `<UserInfo/>`.
- `components/Version/Version.tsx:10-56`: `useLatestVersion()` (`lib/hooks/api/latestVersion.ts:4-19`, `GET ${basePath}/api/info`, refetch off). Renders nothing while loading or no `build`; shows `WarningIcon` with title `Your app version is outdated. Latest version is <tag|UNKNOWN>` when `isLatestRelease === false`; `commitId` link (`title="Current commit"`, `href=gitCommitPath`); version label = `versionTag` if latest and version matches, else formatted `buildTime` in current timezone.
- `NavBar/UserInfo/UserInfo.tsx:9-25`: only when `username` present — `Dropdown` labelled `UserIcon + username + DropdownArrowIcon`, single `DropdownItem href="${basePath}/logout"` "Log out".
- `NavBar/UserTimezone/UserTimezone.tsx:10-76`: `Dropdown` (`aria-label="user-timezone-dropdown"`, align center) opened by a text `Button` showing `currentTimezone.UTCOffset` + chevron; content = `Input` (`id="user-timezone-search"`, placeholder "Search timezone...", `search`) filtering by value/offset/label, list of `DropdownItem` labels like `UTC+02:00 Europe/Warsaw`; select sets timezone and clears search. Timezone list from `Intl.supportedValuesOf('timeZone')` + "Plain UTC" (`lib/hooks/useTimezones.ts:10-105`), sorted by offset then name (`:107-129`); persisted via `useLocalStorage('timezone')` (`:152-178`, key prefix `kafbat-ui-`), default = system timezone (`getSystemTimezone` `:131-150`).
- `components/Nav/Nav.tsx:9-30` (`<aside aria-label="Sidebar Menu">`): primary `MenuItem` "Dashboard" → `/`, then one `ClusterMenu` per cluster from `useClusters()` (`lib/hooks/api/clusters.ts:5-10`, `GET getClusters`, no polling). `opened` = only one cluster OR cluster name matches current URL (`useCurrentClusterName`, `lib/hooks/useCurrentClusterName.ts`).
- `Nav/ClusterMenu/ClusterMenu.tsx:29-130`: open state persisted `useLocalStorage('clusterMenu-<name>-isOpen')`; color key persisted `useLocalStorage('clusterColor-<name>', 'transparent')`; clicking the cluster name opens the menu and navigates to Brokers (`:54-59`); chevron toggles. `MenuTab` (`Nav/Menu/MenuTab.tsx:18-62`): status dot `S.StatusIcon status={online|offline|initializing}` with `<title>{status}</title>`, cluster title, `MenuColorPicker` (`Nav/Menu/MenuColorPicker/MenuColorPicker.tsx:12-48`: dropdown of 10 color circles — transparent, gray, red, orange, lettuce, green, turquoise, blue, violet, pink; colors in `theme/theme.ts:86-108`), chevron. Sub-items (`ClusterMenu.tsx:76-127`): "Brokers", "Topics", "Consumers" always; "Schema Registry" (SCHEMA_REGISTRY), "Kafka Connect" (KAFKA_CONNECT; active for `kafka-connect`, `connectors`, `connects` paths), "KSQL DB" (KSQL_DB), "ACL" (KAFKA_ACL_VIEW or KAFKA_ACL_EDIT). Active detection = `location.pathname.includes(path)` (`:48-50`). `MenuItem` (`Nav/Menu/MenuItem.tsx`) = `NavLink` with `title`.
- Breadcrumbs: `PageHeading` (`components/common/PageHeading/PageHeading.tsx:15-42`) renders optional `S.Title` (cluster name), an `S.BackLink` when `backTo && backText`, `Heading text`, right-side children slot, and sets `document.title = buildPageTitle(text, title)` → `"<text> | <cluster> | Kafbat UI"` (`lib/pageTitles.ts:21-26`). `ResourcePageHeading` (`components/common/ResourcePageHeading/ResourcePageHeading.tsx:8-12`) injects `title={clusterName}` from route.

### Auth page

- `components/AuthPage/AuthPage.tsx:8-19`: `useAuthSettings()` (`lib/hooks/api/appConfig.ts:17-23`, `GET getAuthenticationSettings`, suspense, refetch off) → `<Header/>` (decorative pill grid + logo `AuthPage/Header/Header.tsx`) and `<SignIn authType oAuthProviders/>`.
- `SignIn/SignIn.tsx:13-25`: title "Sign in"; `BasicSignIn` when `authType` is `LDAP` or `LOGIN_FORM`; `OAuthSignIn` when `OAUTH2`.
- `SignIn/BasicSignIn/BasicSignIn.tsx:17-101`: react-hook-form with fields `username` (label "Username", placeholder "Enter your username") and `password` (type password, "Enter your password"); submit `Button` "Log in" (`buttonSize L`, full width, `disabled={!isValid}`, `inProgress`). Uses `useAuthenticate()` (`appConfig.ts:25-32`, `POST authenticateRaw` form-urlencoded). On success: if response URL contains `error` → root error "Username or password entered incorrectly" with `AlertIcon`; else invalidate `['app','info']` and navigate `/`.
- `SignIn/OAuthSignIn/OAuthSignIn.tsx:28-53`: error banner "Invalid credentials" when `location.search` includes `error`; one `AuthCard` per provider (`authPath=authorizationUri`, icon by `clientName` among github/google/cognito/keycloak/okta else generic `ServiceImage`).

### Error / loading primitives

- `components/ErrorPage/ErrorPage.tsx:16-44`: props `status, offsetY=154, text, resourceName, btnText='Refresh', onClick`. Copy from `ErrorPage/utils.tsx:6-36`: 404 → "Resource not found" / `Information about the <resourceName|resource> cannot be found.`; 403 → "Access Denied" / "You do not have permission to view this page."; 500/default → "Unexpected error" / "An unexpected error occurred. Please try again." Always renders a secondary `Button` with `btnText`.
- `SuspenseQueryComponent` (`components/common/SuspenseQueryComponent/SuspenseQueryComponent.tsx:5-27`): `ErrorBoundary` that `Navigate`s to `/${error.status || 404}`.
- `PageLoader` (`components/common/PageLoader/PageLoader.tsx:7-11`): `Spinner` in wrapper with `fullSize` / `offsetY=154`. `Spinner` (`Spinner/Spinner.tsx:7-20`): `role="progressbar"`, default size 80, borderWidth 10.
- Toasts: `lib/errorHandling.tsx:54-103`. `showAlert(type, {id,title,message})` → `toast.custom(<Alert .../>)`; `showSuccessAlert` (title default "Success"); `showServerError(response)` → error toast id=`response.url`, title `"<status> <statusText>"`, message = body.message or "An error occurred"; without status → id `server-error`, title "Something went wrong". `apiFetch`/`getResponse` (`:14-46`) normalise thrown `Response` into `ServerResponse {status, statusText, url, message}`. `Alert` (`components/common/Alert/Alert.tsx:15-25`): `role="alert"`, title (`role="heading"`), message (`role="contentinfo"`), close `CloseCircleIcon` button; types `success|error|loading|blank|custom|'warning'`. `AlertBadge` (`AlertBadge/AlertBadge.tsx:7-25`): `role="alert"` container with `.Icon` and `.Content`.
- `ConfirmationModal` (`components/common/ConfirmationModal/ConfirmationModal.tsx:7-41`): visible when context has both `content` and `confirm`; `role="dialog" aria-label="Confirmation Dialog"`, overlay click cancels, header "Confirm the action", body = content, footer "Cancel" (secondary) + "Confirm" (`danger` when `useConfirm(true)`, `inProgress={isConfirming}`).
- `Modal` (`components/common/Modal/Modal.tsx:15-45`): generic `isOpen/onClose/title/footer/maxWidth=65vw/maxHeight=80vh`, `role="dialog" aria-label="Modal"`, overlay click closes.
- `SlidingSidebar` (`components/common/SlidingSidebar/SlidingSidebar.tsx:12-29`): right drawer (`$open`), header title + `CloseCircleIcon` button (`aria-label="edit"`), width from `theme.layout.rightSidebarWidth`.

### Form / input primitives

- `Input` (`components/common/Input/Input.tsx:104-206`): props `name, hookFormOptions, search (SearchIcon prefix), inputSize S|M|L (default L), positiveOnly, integerOnly, withError (renders `@hookform/error-message` under field), label, hint, clearIcon, actions`. Auto-registers with react-hook-form when `name` and a `FormProvider` exist (`:125,171-176`). For `type="number"` blocks non-digit keys, leading `-` unless allowed, single `.` (`inputNumberCheck` `:27-70`) and sanitises pastes (`pasteNumberCheck` `:72-102`).
- `Checkbox` (`components/common/Checkbox/Checkbox.tsx:13-29`): RHF-registered checkbox with label, hint, error.
- `Select` (`components/common/Select/Select.tsx:29-130`): custom listbox (`role="listbox"`, options `role="option"`), `selectSize M|L`, `minWidth`, `placeholder`, `disabled`, `isThemeMode`, `formatSelectedOption`; closes on outside click. `ControlledSelect` (`Select/ControlledSelect.tsx:19-60`): RHF `Controller` + label + error message, `minWidth 270px`.
- `ControlledMultiSelect` (`components/common/MultiSelect/ControlledMultiSelect/index.tsx:11-31`): `react-multi-select-component` bound to RHF.
- `InputWithOptions` (`components/common/InputWithOptions/InputWithOptions.tsx:19-96`): free-text input with filtered option list (`role="listbox"`).
- `Switch` (`components/common/Switch/Switch.tsx:10-24`): checkbox-based toggle.
- `Search` (`components/common/Search/Search.tsx:23-101`): debounced 500 ms; when `onChange` given it is controlled, otherwise it writes `?q=` to URL search params and resets `page=1`; clear button (`data-testid="search-clear-button"`), optional `extraActions`, placeholder default "Search".
- `Fts` (`components/common/Fts/Fts.tsx:23-46` + `useFts.ts:14-74`): full-text-search toggle icon shown only when `ClusterContext.ftsEnabled`; state in `?fts=` and `localStorage['kafbat-ui_fts:<resource>']` for resources `topics|acl|consumer_groups|connects|schemas`; tooltip "Apply full text search"; toggling resets `page=1`.
- `RefreshRateSelect` (`components/common/RefreshRateSelect/RefreshRateSelect.tsx:5-34`): `Select` options Off/2 sec/5 sec/10 sec/15 sec, label `Refresh rate: <label>`, stored via `useLocalStorage(storageKey)` where key ∈ `consumer-groups-refresh-rate | topics-refresh-rate | consumer-group-<id>-refresh-rate`, default 0 (Off).
- `Button` (`components/common/Button/Button.tsx:14-39`): `buttonType primary|secondary|danger|text`, `buttonSize S|M|L`, `to` (wraps in `Link`), `inProgress` (disables + 16px `Spinner`).
- `Dropdown` (`components/common/Dropdown/Dropdown.tsx:15-81`): `@szhsin/react-menu` wrapper; default trigger is vertical-ellipsis button (`aria-label="Dropdown Toggle"`), or `label`/`openBtnEl`; align end, direction bottom, `offsetY 10`. `DropdownItem` (`DropdownItem.tsx:13-39`): `danger` styling, optional `confirm` node → routes through `useConfirm`.
- `Tooltip` (`components/common/Tooltip/Tooltip.tsx:19-61`): floating-ui hover tooltip with `value` (anchor) and `content`.
- `Tag` (`components/common/Tag/Tag.styled.tsx:4-31`): colors `green|gray|yellow|red|white|blue`, `role="widget"`; `MultiLineTag`; `getTagColor` (`Tag/getTagColor.ts:3-17`): RUNNING/STABLE → green, FAILED/TASK_FAILED/DEAD → red, EMPTY → white, else yellow.
- `Metrics` (`components/common/Metrics/*`): `Metrics.Wrapper` (flex, wrap), `Section` (`Section.tsx:9-14`, `role="group"`, optional title h5), `Indicator` (`Indicator.tsx:14-37`: `label`, `title` (native tooltip), `fetching` → `SpinnerIcon`, `isAlert` + `alertType success|error|warning|info` → colored dot), `LightText`, `RedText`.
- `Statistic` (`components/common/Statistics/Statistic/Statistic.tsx:13-39`): `role="cell"` card with title, count, spinner when loading, `AlertBadge` with `warningCount`.
- `PropertiesList` is styled-only (`components/common/PropertiesList/PropertiesList.styled.tsx`: 2-column grid `List`, `Label`). `ControlPanel` is styled-only (`components/common/ControlPanel/ControlPanel.styled.ts`: flex row, first child 38% width when `hasInput`).
- `Editor` (`components/common/Editor/Editor.tsx:15-80`): react-ace, mode `json5` for JSON/AVRO else `protobuf`, theme `tomorrow` restyled via styled-components, height 372px or line-based when `isFixedHeight`. `EditorViewer` (`EditorViewer/EditorViewer.tsx:19-50`): read-only Editor without gutter/line numbers, pretty-prints JSON/AVRO via `lossless-json` (tab indent), falls back to `<p>{data}</p>` on parse error. `SQLEditor` (`SQLEditor/SQLEditor.tsx:13-39`): ace mode `sql`, theme `dracula` in dark mode else `textmate`. `DiffViewer` (`DiffViewer/DiffViewer.tsx:15-52`): ace `diff` (read-only, wrap, theme textmate), auto height = max lines × 16.
- `DownloadCsvButton` (`components/common/DownloadCsvButton/DownloadCsvButton.tsx:12-58`): secondary M button "Export CSV" (ExportIcon) → "Downloading..." while awaiting `fetchCsv()`; file `<filePrefix>-<YYYY-MM-DD>.csv`.
- Yup (`lib/yupExtended.ts`): adds `string().isJsonObject()` (`:16-41`), `cacheTest` for async validators (`:46-61`), `topicFormValidationSchema` (`:65-118`: name ≤249 chars matching `/^[a-zA-Z0-9._-]+$/` "Only alphanumeric, _, -, and . allowed", partitions 1..2147483647 "Number of Partitions is required and must be a number", cleanupPolicy required, customParams name/value required + "Custom parameters must be unique"). Forms use `react-hook-form` + `@hookform/resolvers/yup`.

### NewTable (`components/common/NewTable/`)

- `Table.tsx:37-83` props: `data, columns (tanstack ColumnDef[]), pageCount, serverSideProcessing, getRowCanExpand, renderSubComponent, enableRowSelection, batchActionsBar, enableSorting, enableColumnResizing, columnSizingPersister, filterPersister, resetPaginationOnFilter=true, columnVisibility, emptyMessage, disabled, onRowClick, onRowHover, onMouseLeave, setRowId, onFilterRows`.
- State lives in URL search params: sorting `sortBy`/`sortDirection` (`:97-102`, `utils/updateSortingState.ts`), pagination `page` (1-based) / `perPage` (default `PER_PAGE=25`, `lib/constants.ts:59`; `utils/updatePaginationState.ts`). Row id = `setRowId` → `name` prop → index (`:225-235`). No virtualization; client-side sorting/pagination/filtering unless `serverSideProcessing` (`manualSorting/manualPagination` `:251-252`). Custom `filterFns.includesSome` and `noop` `:256-270`.
- Header (`:362-409`): clickable/keyboard sortable header (`role="button"`), sort indicator via `sortOrder`, `ColumnFilter` when `meta.filterVariant` is `multi-select` or `text` (`ColumnFilter/Filter.tsx:10-24`), column resizer handle (double-click resets). `enableRowSelection` adds `SelectRowHeader`/`SelectRowCell` checkboxes (`IndeterminateCheckbox`); expandable rows add `ExpanderCell` (`aria-label="Expand row"`).
- Filters: `FilterContainer` (`ColumnFilter/ui/FilterContainer/FilterContainer.tsx:20-72`) — funnel icon, portal-positioned panel, clear icon; `Text` variant = autofocus `Input`; `MultiSelect` variant = `SelectPanel` (`variants/MultiSelect/SelectPanel.tsx`, react-multi-select-component with `hasSelectAll`, options from `meta.filterValues` or faceted unique values) with selected-count badge. `useQueryPersister(columns)` (`ColumnFilter/lib/persisters/queryPersister.ts:69-127`) persists filter state to search params keyed by `meta.filterKey ?? accessorKey` (dots → underscores), multi-select values comma-joined, deletes `page` on change.
- Column sizing persistence: `useLocalStoragePersister(tableName)` (`ColumnResizer/lib/persister/localStoragePersister.ts:12-65`) under `localStorage['kafbat_tables'][tableName]`.
- Empty state (`:476-482`): single cell `colSpan=100` with `emptyMessage || 'No rows found'`. Pagination bar (`:486-545`, only when `pageCount > 1`): buttons `⇤`, `← Previous`, `Next →`, `⇥`, "Go to page:" numeric `Input` (positiveOnly, min 1, max pageCount), "Page X of Y".
- `TableProvider`/`useTableInstance` (`Provider/*.ts*`) expose the tanstack table instance to siblings (used for CSV export). `exportTableCSV(table, {prefix, filename, includeDate})` (`utils/exportTableCSV.ts:18-125`): exports selected rows, else all pre-pagination rows; columns need `accessorKey`; header from `meta.csv ?? header`; value from `meta.csvFn` → rendered cell innerText → raw value; file `<prefix>_<YYYY-MM-DD>.csv`.
- Cell helpers: `LinkCell` (NavLink, stops propagation), `TagCell`/`MultiLineTagCell` (colored via `getTagColor`), `TimestampCell` (formats in user timezone), `SizeCell` (`BytesFormatted`), `SizeCellCount` (`"<bytes>, <count> segment(s)"`), `ColoredCell` (`warn`/`attention` colors), `BreakableTextCell`.

### Theme

- `theme/theme.ts`: `Colors` palette (`:4-108`), `baseTheme` (`:110-414` — auth_page, heading, breakpoints, layout, alert, circularAlert, icons, tag, switch, tooltip, clusterConfigForm, …), `theme` light (`:416-978`) and `darkTheme` (`:983-1664`) both typed `ThemeType`; `ClusterColorKey` type `:980-981`. `theme/hexToRgba.ts` helper; `theme/index.scss`, `theme/minireset.css` global resets; `components/globalCss.ts` styled global CSS.

### Real-time / polling summary (this part)

- `useClusterStats(clusterName)` `refetchInterval: 5000` (`lib/hooks/api/clusters.ts:11-17`, suspense).
- `useBrokers(clusterName)` `refetchInterval: 5000` (`lib/hooks/api/brokers.ts:18-31`).
- `useClusters()` no interval; user info, auth settings, version info use `QUERY_REFETCH_OFF_OPTIONS` (`lib/constants.ts:75-79`). No SSE in this part.

## Dashboard

- Route `/`, `/ui`, `/ui/clusters` (`App.tsx:84-90`). File `components/Dashboard/Dashboard.tsx:22-149`.
- Heading: `PageHeading text="Dashboard"` (`:103`); document title "Dashboard | Kafbat UI".
- Metrics block (`:104-115`): `Metrics.Section` with two `Indicator`s labelled by `Tag color="green"` "Online" and `Tag color="gray"` "Offline", each `<count> clusters` (counts computed `:29-39`; offline = `status === ServerStatus.OFFLINE`; `ServerStatus` = online|offline|initializing).
- Toolbar (`:116-135`): `Switch name="switchRoundedDefault"` + label "Only offline clusters" (filters the table to offline clusters); when `hasDynamicConfig`, `ActionCanButton buttonType="primary" buttonSize="M" to="/ui/clusters/create-new-cluster"` "Configure new cluster", `canDoAction=hasPermissions` (APPLICATIONCONFIG permission or rbac off; disabled with default tooltip otherwise).
- Table (`:41-86, 136-144`): `enableSorting`, `enableColumnResizing` with `useLocalStoragePersister('KafkaConnect')` (note the reused storage key `:95`), row click → `clusterBrokersPath(name)` (`:97-99`). Columns: "Cluster name" (`name`, cell `ClusterName` → `Tag color="blue"` "readonly" prefix when `readOnly` (`Dashboard/ClusterName.tsx:8-20`), width 100%), "Version" (`version`, 100), "Brokers count" (`brokerCount`, 120), "Partitions" (`onlinePartitionCount`, 100), "Topics" (`topicCount`, 80), "Production" (`bytesInPerSec`, `SizeCell`, 100), "Consumption" (`bytesOutPerSec`, `SizeCell`, 116), and when `hasDynamicConfig` an unlabeled `actions` column (140) rendering `ClusterTableActionsCell` (`Dashboard/ClusterTableActionsCell.tsx:10-38`): `ActionCanButton secondary S` "Configure" → `clusterConfigPath(name)`, gated by APPLICATIONCONFIG permission, `stopPropagation` so row click does not fire.
- Empty/loading: `emptyMessage = clusters.isFetched ? 'No clusters found' : 'Loading...'` (`:143`). Data via `useClusters()` (no polling).

## Cluster config wizard

- Entry points: new cluster at `/ui/clusters/create-new-cluster/*` → `widgets/ClusterConfigForm` with empty `initialValues` (`App.tsx:91-94`); edit at `/ui/clusters/:clusterName/config/*` → `components/ClusterPage/ClusterConfigPage.tsx:8-40` which loads `useAppConfig()` (`lib/hooks/api/appConfig.ts:56-61`, `GET getCurrentConfig`, suspense), finds `properties.kafka.clusters[name === clusterName]`, converts with `getInitialFormData`, returns `null` when not found, and passes `hasCustomConfig` = any non-empty `customAuth` value. Both only reachable when `hasDynamicConfig` (`ClusterPage.tsx:132-137`); Dashboard/PageContainer gate the links by APPLICATIONCONFIG permission but the form itself has no `ActionComponent` gating.
- Form shell `widgets/ClusterConfigForm/index.tsx:38-197`: `useForm({ mode: 'all', resolver: yupResolver(formSchema), defaultValues: { bootstrapServers: [{host:'',port:''}], ...initialValues } })`. Sections in order, separated by `<hr/>`: KafkaCluster, (CustomAuthentication if `watch('customAuth') && hasCustomConfig` else Authentication), SchemaRegistry, Serdes, KafkaConnect, KSQL, Metrics, Masking. Whole `FlexFieldset` disabled while validating (`isFormDisabled`) or submitting.
- Buttons (`:154-190`): "Reset" (secondary L, `methods.reset()`, disabled while submitting); "Validate" (secondary L, disabled while submitting); "Submit" (primary L, `type=submit`, disabled when submitting or `!isDirty`, `inProgress`); "Delete" (danger L, only when editing i.e. `initialValues.name`, `inProgress={deleteCluster.isPending}`).
- Validate flow (`:104-127`): `trigger()` all fields with focus; if RHF valid → disable form → `transformFormDataToPayload` → `useValidateAppConfig().mutateAsync` (`appConfig.ts:124-131`, `POST validateConfig` with `{properties:{kafka:{clusters:[config]}}}`) → `getIsValidConfig(response, name)` (`utils/getIsValidConfig.ts:4-49`) shows one error toast per failing component: titles "Kafka Cluster", "Schema Registry", "KSQL DB", `Kafka Connect. <name>` with server `errorMessage`; success toast "Configuration is valid"; generic failure toast "Error validating application config".
- Submit flow (`:88-100`): `useUpdateAppConfig({ initialName })` (`appConfig.ts:82-112`): fetches current config, replaces (edit) or appends (create) the cluster in `properties.kafka.clusters` (`aggregateClusters` `:63-80`), then `POST restartWithConfig({ restartRequest: { config } })` — i.e. **saving restarts the application with the new config**; on success invalidates `['app','config']` and navigates `/`; failure toast id `app-config-update-error` "Error updating application config".
- Delete flow (`:70-86`): `useConfirm(true)` (danger) with "Are you sure want to delete this cluster?" → `useUpdateAppConfig({ initialName, deleteCluster: true })` filters the cluster out and restarts; navigate `/`.
- File uploads (`common/Fileupload.tsx:11-67`): `<input type="file">` → `useAppConfigFilesUpload()` (`appConfig.ts:114-122`, `POST /api/config/relatedfiles` multipart) → sets field to returned `location`; shows "Uploading..." while pending; once set shows disabled `Input` with the path and "Reset" button; error message under field.
- Shared sub-forms: `Credentials` (`common/Credentials.tsx:12-45`): `Checkbox <prefix>.isAuth` (default label "Secured with auth?") revealing "Username *" / "Password *" inputs; `SSLForm` (`common/SSLForm.tsx:11-25`): `Fileupload <prefix>.location` labelled `<title> Location` + password input `<title> Password`; `SectionHeader` (`common/SectionHeader.tsx:13-31`): h3 title + primary M button showing `addButtonText` when `adding` else "Remove from config".

### Sections and fields

1. **Kafka Cluster** (`Sections/KafkaCluster.tsx:16-117`): heading "Kafka Cluster"; `Input name` label "Cluster name *", hint "this name will help you recognize the cluster in the application interface"; `Checkbox readOnly` "Read-only mode", hint "allows you to run an application in read-only mode for a specific cluster"; "Bootstrap Servers *" (hint "the list of Kafka brokers that you want to connect to") field array `bootstrapServers[i].host` (placeholder "Host") / `.port` (placeholder "Port", number, positiveOnly), per-row remove icon (`aria-label="deleteProperty"`), "Add Bootstrap Server" button; array-level error; then `SectionHeader "Truststore"` / "Configure Truststore" toggling `truststore` → `SSLForm prefix="truststore"` ("Truststore Location" upload, "Truststore Password").
2. **Authentication** (`Sections/Authentication/Authentication.tsx:9-57`): `SectionHeader "Authentication"` / "Configure Authentication" toggles `auth.isActive`; `ControlledSelect auth.method` label "Authentication Method", placeholder "Select authentication method", options `AUTH_OPTIONS` (`lib/constants.ts:87-100`: SASL/JAAS, SASL/GSSAPI, SASL/OAUTHBEARER, SASL/PLAIN, SASL/SCRAM-256, SASL/SCRAM-512, Delegation tokens, SASL/LDAP, SASL/AWS IAM, SASL/Azure Entra, SASL/GCP IAM, mTLS); `ControlledSelect auth.securityProtocol` label "Security Protocol" (options SASL_SSL, SASL_PLAINTEXT, `:102-105`) shown for every method except "Delegation tokens" and "mTLS". Method-specific fields (`AuthenticationMethods.tsx:8-111`): SASL/JAAS → "sasl.jaas.config", "sasl.mechanism"; SASL/GSSAPI → "Kerberos service name", `Checkbox` "Store Key", `Fileupload` "Key Tab (optional)", "Principal *"; SASL/OAUTHBEARER → "Unsecured Login String Claim_sub *"; SASL/PLAIN, SCRAM-256, SCRAM-512, LDAP → `Credentials prefix="auth.props"`; Delegation tokens → "Token Id", "Token Value *"; SASL/AWS IAM → "AWS Profile Name", "AWS Role Arn", "AWS Role Session Name", "AWS STS Region"; mTLS → `SSLForm prefix="auth.keystore" title="Keystore"`; Azure Entra / GCP IAM → no extra fields.
   - **Custom Authentication** (`Sections/CustomAuthentication.tsx:7-43`): shown instead when existing config has raw `security.*`, `sasl.*`, `ssl.*` properties (`utils/getInitialFormData.ts:133-147`, keys stored with `.`→`___`); renders one text `Input` per property with label = original key; header button "Remove from config" clears `customAuth`.
3. **Schema Registry** (`Sections/SchemaRegistry.tsx:8-53`): toggle "Configure Schema Registry"; "URL *" (`schemaRegistry.url`, placeholder `http://localhost:8081`); `Credentials` "Is Schema Registry secured with auth?"; `SSLForm prefix="schemaRegistry.keystore" title="Keystore"`.
4. **Serdes** (`Sections/Serdes/Serdes.tsx:17-117`): toggle "Configure Serdes"; field array `serde[i]` with "Name *" (hint "Serde name"), "Class Name *", "File Path *", "Topic Keys Pattern *" (default `%s-key`), "Topic Values Pattern *" (default `%s-value`), nested "Serde properties" key/value array (`PropertiesFields.tsx:10-57`, "Add Property"), remove icon, "Add Serde".
5. **Kafka Connect** (`Sections/KafkaConnect.tsx:17-91`): toggle "Configure Kafka Connect"; array `kafkaConnect[i]`: "Kafka Connect name *" (hint "Given name for the Kafka Connect cluster"), "Kafka Connect URL *" (hint "Address of the Kafka Connect service endpoint"), `Credentials` "Is connect secured with auth?", `SSLForm .keystore "Keystore"`, remove icon, "Add Kafka Connect".
6. **KSQL DB** (`Sections/KSQL.tsx:8-48`): toggle "Configure KSQL DB"; "URL *" (`ksql.url`, placeholder `http://localhost:8088`); `Credentials` "Is KSQL DB secured with auth?"; `SSLForm ksql.keystore "KSQL DB Keystore"`. Note toggle sets `isActive: false` in both branches (`:16`), so `transformFormDataToPayload` (`:80`) never emits ksql from a freshly-added section unless `isActive` is set elsewhere — a latent bug worth noting.
7. **Metrics** (`Sections/Metrics.tsx:11-63`): toggle "Configure Metrics"; `ControlledSelect metrics.type` "Metrics Type" (JMX, PROMETHEUS; `lib/constants.ts:106-109`); "Port *" (number, positiveOnly); `Credentials prefix="metrics"`; `SSLForm metrics.keystore "Metrics Keystore"`.
8. **Masking** (`Sections/Masking.tsx:133-225`): toggle "Configure Masking"; array `masking[i]`: `ControlledSelect .type` "Masking Type *" (MASK, REMOVE, REPLACE; `lib/constants.ts:110-123`), "Field" array (`Fields` `:21-75`, "Add Field", remove only when >1), "Fields name pattern", "Field" array for masking chars replacement (`MaskingCharReplacement` `:77-131`, "Add Masking Chars Replacement"), "Replacement", "Topic Keys Pattern", "Topic Values Pattern"; remove icon; "Add Masking".

### Validation schema (`widgets/ClusterConfigForm/schema.ts`)

- `name`: required "required field", `min(3)` "Cluster name must be at least 3 characters" (`:276-278`); `readOnly` boolean required; `bootstrapServers` array `min(1)` of `{host: required, port: positive number ("positive only"/"numbers only"/"required")}` (`:6-14, 280`).
- `truststore`/keystores (`sslSchema` `:16-27`): `location` required only when `password` set.
- `schemaRegistry`/`ksql` (`urlWithAuthSchema` `:29-46`): `url` required; `username`/`password` required when `isAuth`; `keystore` sslSchema.
- `serde` (`:48-67`): name, className, filePath, topicKeysPattern, topicValuesPattern all required; properties key/value required.
- `kafkaConnect` (`:69-89`): name, address required; credentials when `isAuth`; keystore.
- `metrics` (`:91-109`): type oneOf JMX|PROMETHEUS required; port positive number; credentials when `isAuth`.
- `auth` (`:157-208`): `method` required and oneOf the 12 methods; `securityProtocol` oneOf SASL_SSL|SASL_PLAINTEXT and required for all SASL methods (not Delegation tokens / mTLS); `keystore.location` required when method is mTLS; `props` per method (`authPropsSchema` `:111-155`): JAAS → saslJaasConfig, saslMechanism required; GSSAPI → saslKerberosServiceName, principal required (keyTabFile, storeKey optional); OAUTHBEARER → unsecuredLoginStringClaim_sub required; PLAIN/SCRAM/LDAP → username, password required; Delegation tokens → tokenId, tokenValue required; AWS IAM → all four optional; Azure/GCP/mTLS → none.
- `masking` (`:210-273`): `type` required oneOf enum; cross-field test "Either fields or fieldsNamePattern is required" on both `fields[].value` and `fieldsNamePattern`; other strings optional.

### Payload mapping (`utils/transformFormDataToPayload.ts:50-300`)

`bootstrapServers` joined `host:port,…`; `ssl.truststoreLocation/Password`; `schemaRegistry` + `schemaRegistryAuth` + `schemaRegistrySsl` only when `isActive`; `ksqldbServer*` likewise; `serde[]` with properties map; `kafkaConnect[]` with `keystoreLocation/Password` + `username/password`; `metrics {type, port: Number, keystore, creds}`; `masking[]` (fields/maskingCharsReplacement flattened to string arrays); `properties` = custom auth props (`___`→`.`) then, when `auth.isActive`, overwritten per method with `security.protocol`, `sasl.mechanism` (GSSAPI, OAUTHBEARER, PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, AWS_MSK_IAM, …), `sasl.jaas.config` built by `getJaasConfig` (`utils/getJaasConfig.ts:1-35`, login-module map), `sasl.client.callback.handler.class` for AWS IAM (`software.amazon.msk.auth.iam.IAMClientCallbackHandler`), Azure Entra (`io.kafbat.ui.sasl.azure.entra.AzureEntraLoginCallbackHandler`), GCP IAM (`com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler`); mTLS → `security.protocol: SSL`, `ssl.keystore.location/password`. Reverse mapping in `utils/getInitialFormData.ts:39-150` (bootstrap split on `,`/`:`, credentials → `isAuth`, keystores, masking arrays → `{value}` objects, custom auth props).

## Brokers

- Router `components/Brokers/Brokers.tsx:7-12`: index → `BrokersList`; `:brokerId/*` → `Broker`.

### Brokers list (`/ui/clusters/:clusterName/brokers`)

- `components/Brokers/BrokersList/BrokersList.tsx:21-121`. Data: `useClusters()` (for `controller` type), `useClusterStats(clusterName)` (5 s polling, suspense), `useBrokers(clusterName)` (5 s polling, non-suspense so it exposes `isLoading/error/refetch`).
- Header `ResourcePageHeading text="Brokers"` (title = cluster name, document title "Brokers | <cluster> | Kafbat UI") with right-side secondary M button `ExportIcon` "Export CSV" → `exportTableCSV(table, { prefix: 'brokers' })` via `TableProvider` (`:62-78`).
- Metrics (`BrokersMetrics/BrokersMetrics.tsx:19-121`): Section "Uptime": "Broker Count", "Active Controller" (alert dot + `S.DangerText` "No Active Controller" when `activeControllers` undefined), "Version". Section "Partitions": "Online" (`<online> of <online+offline>`, red + error dot when offline > 0 else success dot), "URP" (title "Under replicated partitions"; light 0 / red count), "In Sync Replicas" (`<insync> of <total>`, red when not all in sync), "Out Of Sync Replicas", "Controller Type" (KRaft / ZooKeeper / Unknown from `cluster.controller`).
- States: `isLoading` → `PageLoader offsetY=300`; `error` → `ErrorPage offsetY=300 status=error.status text=error.message onClick=refetch`; `isSuccess` → table (`:92-113`). Table `enableSorting`, row click → `clusterBrokerPath(cluster, brokerId)`, `emptyMessage="No clusters are online"`.
- Columns (`BrokersList/lib/utils.ts:50-103`, rows built by `getBrokersTableRows` `:20-48` joining `clusterStats.diskUsage` by `brokerId`, `N/A` when missing): "Broker ID" (`LinkCell` to broker + `CheckMarkRoundIcon` tooltip "Active Controller" when `brokerId === activeControllers`; CSV appends `(Active)`), "Disk usage" (`SizeCellCount` precision 2 → `"<bytes>, <n> segment(s)"` or `N/A`), "In Sync Replicas" (`ColoredCell`, attention when `< replicas`), "Replicas", "Replicas skew" (header with InfoIcon tooltip "The divergence from the average brokers' value" `SkewHeader/SkewHeader.tsx`; value `xx.xx%` or `-`; warn ≥10%, attention ≥20% `TableCells/TableCells.tsx:63-76`), "Leaders" (`partitionsLeader`), "Leaders skew" (same coloring), "Port", "Host" (`BreakableTextCell`).

### Broker details (`/ui/clusters/:clusterName/brokers/:brokerId`)

- `components/Brokers/Broker/Broker.tsx:28-130`. `ResourcePageHeading text="Broker <id>" backTo=brokers backText="Brokers"`. `PageLoader` while `isLoading || isRefetching` (note: shows on every 5 s refetch); `ErrorPage status={error.status || 404} resourceName="Broker <id>" onClick=refetch` when error or broker not in list.
- Metrics row (`:66-84`): "Segment Size" (`BytesFormatted` precision 2 from `clusterStats.diskUsage`), "Segment Count", "Port", "Host".
- Tabs `Navbar role="navigation"` (`:86-110`, `components/common/Navigation/Navbar.styled.ts`, active class `is-active`): "Log directories" (index, `end`), "Configs" (`/configs`), "Metrics" (`/metrics`) as `ActionNavLink` gated by `{ resource: CLUSTERCONFIG, action: VIEW }` (disabled link + tooltip otherwise). Routes `:111-123` inside `Suspense(PageLoader)`.
- **Log directories** (`Broker/BrokerLogdir/BrokerLogdir.tsx:9-54`): `useBrokerLogDirs` (`brokers.ts:44-53`, `getAllBrokersLogdirs({broker:[id]})`, suspense, no polling). Table `enableSorting`, columns "Name" (`name`), "Error" (`error`), "Topics" (count of `topics`, not sortable), "Partitions" (sum of `topic.partitions.length`, not sortable); `emptyMessage="Log dir data not available"`.
- **Configs** (`Broker/Configs/Configs.tsx:18-57`): `useBrokerConfig` (`brokers.ts:55-64`, suspense). Local `Search` (controlled, placeholder "Search by Key or Value"; filter `lib/utils.tsx:14-24` case-insensitive on name or value). Rows sorted by source priority (`lib/constants.ts:15-26`: dynamic configs 1, static 2, default 3, unknown 4). Columns (`lib/utils.tsx:44-65`): "Key" (`name`), "Value" (`InputCell`), "Source" (header with InfoIcon tooltip explaining every `ConfigSource` `TableComponents/ConfigSourceHeader/ConfigSourceHeader.tsx:8-25`; cell text from `CONFIG_SOURCE_NAME_MAP` `lib/constants.ts:3-13`: "Dynamic topic config", "Dynamic broker logger config", "Dynamic broker config", "Dynamic default broker config", "Dynamic client metrics subscription config", "Static broker config", "Default config", "Unknown"). No pagination controls beyond default (25/page).
  - Inline edit (`TableComponents/InputCell/index.tsx:21-62`): view mode (`InputCellViewMode.tsx:20-59`) shows value (sensitive → `**********` with title "Sensitive Value"; `*.bytes` → `BytesFormatted` with title `Bytes: <raw>`; `*.ms` → `"<v> ms"`; `lib/utils.tsx:72-101`), dynamic-source values styled via `$isDynamic`, and `ActionButton primary S` `EditIcon` "Edit" gated by `{ resource: CLUSTERCONFIG, action: EDIT }`, `disabled={isReadOnly}` with message "Property is read-only" (else default permission message). Edit mode (`InputCellEditMode.tsx:15-53`): text `Input` (`aria-label="inputValue"`, size S), "Save" (`aria-label="confirmAction"`) and "Cancel" (`aria-label="cancelAction"`). Save with a changed value → `useConfirm` "Are you sure you want to change the value?" → `useUpdateBrokerConfigByName` (`brokers.ts:66-83`, `updateBrokerConfigByName({name, brokerConfigItem:{value}})`, invalidates `['clusters',c,'brokers',id,'settings']`).
- **Metrics** (`Broker/BrokerMetrics/BrokerMetrics.tsx:9-18`): `useBrokerMetrics` (`brokers.ts:33-42`, suspense) → `EditorViewer schemaType=JSON` of `JSON.stringify(metrics)` or text "Metrics data not available" (`Brokers/utils/getEditorText.ts`).
## Topics

Routing root: `components/ClusterPage/ClusterPage.tsx:88` mounts `<Topics />` at `getNonExactPath(clusterTopicsRelativePath)` where `clusterTopicsRelativePath = 'all-topics'` (`lib/paths.ts:141`). Inside, `components/Topics/Topics.tsx:18-30` defines: index -> `ListPage`; `create-new-topic` (`lib/paths.ts:142`) -> `New`; `copy` (`lib/paths.ts:143`) -> `New`; `:topicName/*` -> `Topic` (wrapped in `SuspenseQueryComponent`). All are `React.lazy` with `<PageLoader />` fallback (`Topics.tsx:12-17`).

Permission gating convention: `ActionButton` / `ActionDropdownItem` / `ActionNavLink` / `ActionCanButton` from `components/common/ActionComponent`. When the user lacks the permission the control is rendered disabled with the hover tooltip "You don't have a required permission to perform this action" (`components/common/ActionComponent/ActionComponent.ts:15`, `lib/hooks/useActionTooltip.ts:11-17`). `ActionButton` for `Action.CREATE` uses a special `ActionCreateButton` (`ActionButton/ActionButton.tsx:11-14`).

Cluster-level flags consumed by Topics screens come from `ClusterContext` (`components/contexts/ClusterContext.ts:3-11`): `isReadOnly`, `isTopicDeletionAllowed`, `ftsEnabled`, `ftsDefaultEnabled`, `messageRelativeTimestamp`.

### Topics list (`/ui/clusters/:clusterName/all-topics`)

Files: `components/Topics/List/ListPage.tsx`, `List/TopicTable.tsx`, `List/TopicTitleCell.tsx`, `List/ActionsCell.tsx`, `List/BatchActionsBar.tsx`. Data hook: `useTopics` (`lib/hooks/api/topics.ts:66-73`, `useQuery`, `placeholderData: previousData`, no polling).

Purpose: paginated, server-side sorted/filtered list of all topics in a cluster.

Header (`ListPage.tsx:78-99`): `ResourcePageHeading text="Topics"`; right side:
- "Add a Topic" primary button with `PlusIcon`, link to `create-new-topic`, hidden when `isReadOnly`, permission `{resource: TOPIC, action: CREATE}` (`ListPage.tsx:80-92`).
- `DownloadCsvButton` with `filePrefix="topics-<clusterName>"`, calls `topicsApiClient.getTopicsCsv(params)` with the same list params (`ListPage.tsx:72-74, 94-97`); file name `<prefix>-<date>.csv` (`components/common/DownloadCsvButton/DownloadCsvButton.tsx:30`).

Control panel (`ListPage.tsx:100-114`):
- `Search` placeholder "Search by Topic Name"; writes `q` URL param, debounced 500 ms, resets `page` to 1 (`components/common/Search/Search.tsx:40-55`). `extraActions` = `<Fts resourceName="topics" />` — full-text-search toggle icon with tooltip "Apply full text search", rendered only if `ClusterContext.ftsEnabled` (`components/common/Fts/Fts.tsx:22-46`); `isFtsEnabled` sent as `fts` param (`ListPage.tsx:69`).
- `Switch name="ShowInternalTopics"` labelled "Show Internal Topics"; checked = `!searchParams.has('hideInternal')`. Toggling persists `hideInternalTopics` in `localStorage`, sets/deletes `hideInternal` URL param and resets `page=1` (`ListPage.tsx:48-59`). On mount, `perPage` defaults to `PER_PAGE=25` (`lib/constants.ts:59`) and `hideInternal=true` is restored from localStorage (`ListPage.tsx:35-46`).

Request params (`ListPage.tsx:61-70`, `TopicTable.tsx:22-26`): `clusterName`, `showInternal`, `search=q`, `orderBy=sortBy` (`TopicColumnsToSort` enum), `sortOrder=sortDirection.toUpperCase()` (`SortOrder`), `fts`, `page`, `perPage`.

Table (`TopicTable.tsx:31-99`, `<Table enableSorting serverSideProcessing enableColumnResizing columnSizingPersister=useLocalStoragePersister('Topics') emptyMessage="No topics found">`). Columns (id = sort key):
| id (`TopicColumnsToSort`) | Header | accessor / render |
|---|---|---|
| `NAME` | "Topic Name" | `TopicTitleCell`: `NavLink` to topic; gray `Tag` "IN" prefix when `internal` (`TopicTitleCell.tsx:12-23`) |
| `TOTAL_PARTITIONS` | "Partitions" | `partitionCount` |
| `OUT_OF_SYNC_REPLICAS` | "Out of sync replicas" | computed: count of replicas with `!inSync` across partitions, 0 if none (`TopicTable.tsx:54-63`) |
| `REPLICATION_FACTOR` | "Replication Factor" | `replicationFactor` |
| `MESSAGES_COUNT` | "Number of messages" | `messagesCount ?? 'N/A'` |
| `SIZE` | "Size" | `segmentSize` via `SizeCell`; CSV export via `formatBytes(segmentSize, 0)` |
| `actions` | "" | `ActionsCell` dropdown |

Sorting: Table stores `sortBy`/`sortDirection` in URL params (`components/common/NewTable/Table.tsx:98-101, 118-120`). Pagination: `page`/`perPage` URL params; pagination bar shown when pageCount > 1 with buttons "⇤", "← Previous", "Next →", "⇥", "Go to page:" number input, and "Page X of Y" (`Table.tsx:486-540`).

Row selection: enabled when not read-only, only for non-internal rows (`TopicTable.tsx:119-121`). Batch actions bar (`BatchActionsBar.tsx:136-165`) rendered above table (`Table.tsx:332-339`):
- "Delete selected topics" — disabled when none selected; `canDoAction` requires `TOPIC:DELETE` for every selected topic (`BatchActionsBar.tsx:97-108`); confirm "Are you sure you want to remove selected topics?"; runs `useDeleteTopic` per topic in parallel then resets selection (`BatchActionsBar.tsx:37-48`).
- "Copy selected topic" — disabled unless exactly 1 selected; requires `TOPIC:CREATE`; navigates to `copy?<query>` with all truthy topic fields except `partitions` and `internal` serialized to the query string (`BatchActionsBar.tsx:72-94, 147-155`).
- "Purge messages of selected topics" — requires `TOPIC:MESSAGES_DELETE`; confirm "Are you sure you want to purge messages of selected topics?"; `useClearTopicMessages` per topic, then invalidates `topicKeys.all` (`BatchActionsBar.tsx:50-68`).

Row actions dropdown (`ActionsCell.tsx:34-96`; whole dropdown disabled if `internal || isReadOnly`):
- "Clear Messages" (danger) — disabled unless `cleanUpPolicy === DELETE`; hint "Clearing messages is only allowed for topics with DELETE policy"; confirm "Are you sure want to clear topic messages?"; permission `TOPIC:MESSAGES_DELETE` value=name.
- "Recreate Topic" (danger) — disabled if `!isTopicDeletionAllowed`; confirm "Are you sure to recreate **name** topic?"; permission `TOPIC:[VIEW, CREATE, DELETE]`.
- "Remove Topic" (danger) — disabled if `!isTopicDeletionAllowed` with hint "The topic deletion is restricted at the broker configuration level (delete.topic.enable = false)"; confirm "Are you sure want to remove **name** topic?"; permission `TOPIC:DELETE`.

States: `isLoading || isRefetching` -> `<PageLoader />` (`TopicTable.tsx:103-105`); error -> `<ErrorPage offsetY={201} status onClick=refetch>` (`TopicTable.tsx:107-109`); empty -> "No topics found".

Mutation feedback (`lib/hooks/api/topics.ts`): delete -> "Topic <name> successfully deleted!" (`:280-284`); clear -> "<name> messages have been successfully cleared!" (`:303-308`); recreate -> "Topic <name> successfully recreated!" (`:317-321`); all invalidate `['clusters', clusterName, 'topics']`.

### New topic / Copy topic (`.../all-topics/create-new-topic`, `.../all-topics/copy?<params>`)

Files: `components/Topics/New/New.tsx`, `components/Topics/shared/Form/TopicForm.tsx`, `shared/Form/TimeToRetain.tsx`, `shared/Form/TimeToRetainBtns.tsx`, `shared/Form/TimeToRetainBtn.tsx`, `shared/Form/CustomParams/CustomParams.tsx`, `shared/Form/CustomParams/CustomParamField.tsx`, validation `lib/yupExtended.ts:65-118`.

Heading: `ResourcePageHeading text={search ? 'Copy' : 'Create'} backText="Topics"` (`New.tsx:52-56`). Copy mode reads query params `name`, `partitionCount`, `replicationFactor`, `inSyncReplicas`, `cleanUpPolicy` (`New.tsx:13-39`) and passes them as form defaults. `useForm({ mode: 'onChange', resolver: yupResolver(topicFormValidationSchema) })` (`New.tsx:23-26`). On success navigates to `../<name>` (`New.tsx:41-48`). Submit via `useCreateTopic` -> `api.createTopic` with `formatTopicCreation` (`lib/hooks/api/topics.ts:138-176`): configs `cleanup.policy`, `retention.ms`, `max.message.bytes`, `min.insync.replicas` + custom params; empty-string values dropped; `replicationFactor` only sent if non-empty.

Form fields (`TopicForm.tsx:89-273`, `aria-label="topic form"`; outer fieldset disabled while submitting, inner fieldset disabled when `isEditing`):
| Label | name | Control | Notes |
|---|---|---|---|
| "Topic Name *" | `name` | text input, autofocus, placeholder "Topic Name" | disabled in edit |
| "Number of Partitions *" | `partitions` | number, default "1", min 1, `positiveOnly integerOnly` | hidden in edit |
| "Cleanup policy" | `cleanupPolicy` | Select options `Delete`/`Compact`/`Compact,Delete` (values `delete`, `compact`, `compact,delete`) (`TopicForm.tsx:34-38`) | disabled in edit |
| "Min In Sync Replicas" | `minInSyncReplicas` | number, min 1 | |
| "Replication Factor" | `replicationFactor` | number, min 1 | hidden in edit |
| "Time to retain data (in ms)" | `retentionMs` | number; default `MILLISECONDS_IN_WEEK` (604 800 000); `hookFormOptions.min=-1` message "must be greater than or equal to -1"; live hint via `pretty-ms` when >= 1000 ms (`TimeToRetain.tsx:22-48`) | quick-set buttons "1 hour", "3 hours", "6 hours", "12 hours", "1 day", "2 days", "7 days", "4 weeks" (`TimeToRetainBtns.tsx:20-59`), active button highlighted when value matches (`TimeToRetainBtn.tsx:19`) |
| "Max partition size in GB" | `retentionBytes` | Select: "Not Set"(-1), "1 GB", "10 GB", "20 GB", "50 GB" (`TopicForm.tsx:51-57`) | |
| "Maximum message size in bytes" | `maxMessageBytes` | number, min 1, placeholder "Maximum message size" | |
| "Custom parameters" heading | `customParams[]` | field array; each row: "Custom Parameter *" `InputWithOptions` (placeholder "Select") with options from `TOPIC_CUSTOM_PARAMS` keys sorted (`lib/constants.ts:25-47`; e.g. `compression.type`, `segment.bytes`, `min.cleanable.dirty.ratio`, `retention.bytes`, ...), option disabled if already used or (in edit) if config source != `DYNAMIC_TOPIC_CONFIG` (`CustomParamField.tsx:46-55`); "Value *" text input auto-filled with the param's default on name change (`CustomParamField.tsx:57-72`); delete icon button `title="Delete customParam field N"`; "Add Custom Parameter" button with `PlusIcon` (`CustomParams.tsx:75-86`) | |

Buttons: "Cancel" (resets and navigates to `clusterTopicPath`) and submit "Create topic" / "Update topic", disabled when `!isValid || isSubmitting || !isDirty` (`TopicForm.tsx:254-271`).

Validation (`lib/yupExtended.ts:65-118`): `name` string max 249, required "Topic Name is required", regex `/^[a-zA-Z0-9._-]+$/` message "Only alphanumeric, _, -, and . allowed" (`lib/constants.ts:21`); `partitions` number min 1 "Number of Partitions must be greater than or equal to 1", max 2147483647, required, typeError "Number of Partitions is required and must be a number"; `cleanupPolicy` required; `customParams[].name` required "Custom parameter is required"; `customParams[].value` required "Value is required"; uniqueness test "Custom parameters must be unique" attached to `.name` path. `replicationFactor`, `minInSyncReplicas`, `retentionMs`, `maxMessageBytes` are plain strings; `retentionBytes` number.

### Topic details shell (`.../all-topics/:topicName/*`)

File: `components/Topics/Topic/Topic.tsx`. Data: `useTopicDetails({retry:false})` (`Topic.tsx:86-87`), `useTopicConnectors` (suspense) (`Topic.tsx:88-91`). Document title via `getTopicPageTitle` (`lib/pageTitles.ts:94-114`).

Header (`Topic.tsx:110-206`): `ResourcePageHeading text={topicName} backText="Topics"`; actions:
- "Produce Message" primary `ActionButton`, disabled when `isReadOnly`, permission `TOPIC:MESSAGES_PRODUCE` value=topicName; opens `SlidingSidebar title="Produce Message"` containing `SendMessage` (`Topic.tsx:319-331`).
- Dropdown (disabled when `isReadOnly || internal`):
  - "Edit settings" hint "Pay attention! This operation has especially important consequences." -> navigates to `edit`; permission `TOPIC:EDIT`.
  - "Clear messages" (danger) disabled unless `cleanUpPolicy === DELETE`; hint "Clearing messages is only allowed for topics with DELETE policy"; confirm "Are you sure want to clear topic messages?"; permission `TOPIC:MESSAGES_DELETE`.
  - "Recreate Topic" (danger) confirm "Are you sure want to recreate **topic** topic?"; permission `TOPIC:[MESSAGES_READ, CREATE, DELETE]`.
  - "Remove Topic" (danger) disabled if `!isTopicDeletionAllowed` with hint "The topic deletion is restricted at the broker configuration level (delete.topic.enable = false)"; confirm "Are you sure want to remove **topic** topic?"; permission `TOPIC:DELETE`; on success navigates to topics list (`Topic.tsx:96-99`).

States: `isLoading || isRefetching` -> `PageLoader` (`Topic.tsx:208`); error -> `ErrorPage status onClick=refetch resourceName=topicName` (`Topic.tsx:210-216`).

Tab navbar (`Topic.tsx:220-285`), shown on success: "Overview" (index), "Messages" (`ActionNavLink` `TOPIC:MESSAGES_READ`), "Consumers" (`consumer-groups`), "Settings" (`settings`), "Statistics" (`ActionNavLink` `TOPIC:ANALYSIS_VIEW`), "ACLs" (`acls`, `ActionNavLink` `ACL:VIEW`), "Connectors" (`connectors`, only if topic has connectors, `TOPIC:VIEW`). Routes (`Topic.tsx:288-315`): index Overview; `messages`; `settings`; `consumer-groups`; `statistics`; `acls`; `connectors` (conditional); `edit`. Relative path constants at `lib/paths.ts:155-161`.

`TopicActionsProvider` (`components/contexts/TopicActionsContext.tsx:17-33`) exposes `openSidebarWithMessage(message)` used by message rows to prefill the produce sidebar; `useTopicActions` throws outside provider (`:35-43`). `useProduceMessage` (`lib/hooks/useProduceMessage.ts:15-54`) maps a `TopicMessage` to form data: `content`, `key`, `headers` (pretty JSON), `partition`, `valueSerde`, `keySerde`, `keySerdeParams.subject` / `valueSerdeParams.subject` from `*DeserializeProperties.subjects[0]`, `keepContents=false`. `SendMessage` is keyed `'with-message' | 'empty'` to remount (`Topic.tsx:326`).

### Overview tab (index)

File: `components/Topics/Topic/Overview/Overview.tsx`, `Overview/ActionsCell.tsx`. Data: `useTopicDetails`.

Metrics indicators (`Overview.tsx:88-146`): "Partitions" (`partitionCount`); "Replication Factor"; "URP" (title "Under replicated partitions", alert success when 0 else error, red text when non-zero); "In Sync Replicas" as `<inSyncReplicas> of <replicas>` (red when fewer than replicas; alert type success/error); "Type" gray Tag "Internal"/"External"; "Segment Size" (`BytesFormatted segmentSize`); "Segment Count"; "Clean Up Policy" gray Tag (`cleanUpPolicy || 'Unknown'`); "Message Count" = sum of `offsetMax - offsetMin` over partitions (`Overview.tsx:19-25`).

Partition table (`Overview.tsx:37-85, 147-152`, `enableSorting`, all columns `enableSorting:false`, `emptyMessage="No Partitions found "`): "Partition ID" (`partition`); "Replicas" — one `S.Replica` chip per broker id, styled `leader` (title "Leader") / `outOfSync` (`!inSync`), 0 if none; "First Offset" (`offsetMin`); "Next Offset" (`offsetMax`); "Message Count" (computed); actions column. Row dropdown (`Overview/ActionsCell.tsx:22-37`): disabled if `internal || isReadOnly || cleanUpPolicy !== 'DELETE'`; single item "Clear Messages" (danger, no confirm) -> `useClearTopicMessages(clusterName, [partition])`; permission `TOPIC:MESSAGES_DELETE`.

### Consumers tab (`consumer-groups`)

File: `components/Topics/Topic/ConsumerGroups/TopicConsumerGroups.tsx`. Data: `useTopicConsumerGroups` (suspense, `lib/hooks/api/topics.ts:97-102`) + `useGetConsumerGroupsLag({ids, pollingIntervalSec})` from `lib/hooks/api/consumers`.

Controls (`:121-128`): `Search placeholder="Search by Consumer Name"` (client-side filter on `groupId`, lowercase `indexOf`, `:29-35`); `RefreshRateSelect storageKey="topics-refresh-rate"` — options "Off"(0), "2 sec", "5 sec", "10 sec", "15 sec", displayed as "Refresh rate: X", persisted in localStorage (`components/common/RefreshRateSelect/RefreshRateSelect.tsx:5-33`). Lag polling interval taken from that key (`:37, 41-45`); lag trends computed via `computeLagTrends` and shown with `LagTrendComponent` (`:47-58, 90-97`).

Columns (`:65-117`, all non-sortable): "Consumer Group ID" (`LinkCell` to `/consumer-groups/<groupId>`); "Active Consumers" (`members`); "Consumer Lag" (lag for this topic from lag response + trend arrow); "Coordinator" (`coordinator.id`, 0 if undefined); "State" (`TagCell`). Empty: "No active consumer groups" (`:134`).

### Settings tab (`settings`)

File: `components/Topics/Topic/Settings/Settings.tsx`. Data: `useTopicConfig` (suspense, `lib/hooks/api/topics.ts:87-96`). Read-only table (no inline edit; editing lives on the Edit page). Columns (`:101-120`): "Key" (`name`, bold weight 500 when `value !== defaultValue`, `Settings.styled.ts:3-7`); "Value" — `**********` with title "Sensitive Value" when `isSensitive`; else raw value plus human-readable `FormattedValue` for `ms` (`formatDuration`, hidden when negative) or `bytes` (`formatBytes`, hidden when <= 0) units from `getConfigUnit(name)` (`:14-35, 49-70`); "Default Value" — blank when value equals default, masked if sensitive, else default + formatted (`:72-95`). No explicit empty message (Table default "No rows found", `components/common/NewTable/Table.tsx:479`).

### Statistics / Analysis tab (`statistics`)

Files: `components/Topics/Topic/Statistics/Statistics.tsx`, `Statistics/Metrics.tsx`, `Statistics/Indicators/Total.tsx`, `Statistics/Indicators/SizeStats.tsx`, `Statistics/PartitionTable.tsx`, `Statistics/PartitionInfoRow.tsx`.

Data: `useTopicAnalysis(params, enabled)` polls `getTopicAnalysis` every **1000 ms** with `throwOnError: true`, `retry: false`; non-404 errors shown via `showServerError` (`lib/hooks/api/topics.ts:344-367`). `useAnalyzeTopic` (`:368-376`) and `useCancelTopicAnalysis` (success alert "Topic analysis canceled", `:378-389`) invalidate `topicKeys.statistics`. Polling is enabled while `isAnalyzing`, which flips false when `data.progress` is absent (`Metrics.tsx:31-41`).

States:
- No analysis yet (404 thrown) -> `ErrorBoundary` fallback with single `ActionButton` "Start Analysis" (permission `TOPIC:ANALYSIS_RUN`), which runs analysis and resets boundary (`Statistics.tsx:19-46`).
- In progress (`data.progress`) (`Metrics.tsx:47-97`): big percent `Math.floor(completenessPercent)%`, `ProgressBar`, "Stop Analysis" secondary `ActionButton` (`ANALYSIS_RUN`), property list: "Started at" (time h:m:s in current timezone), "Passed since start" (`calculateTimer`), "Scanned messages" (`msgsScanned`), "Scanned size" (`BytesFormatted bytesScanned`).
- Finished (`data.result`) (`Metrics.tsx:108-154`): actions bar with `finishedAt` timestamp and "Restart Analysis" primary S button (`ANALYSIS_RUN`); if `totalStats.totalMsgs == null && partitionStats.length === 0` -> "No data available. The topic appears to be empty."; else `Total` section "Messages": "Total number", "Offsets min-max" (`min - max` or "N/A"), "Timestamp min-max" (formatted or "N/A"), "Null keys", "Unique keys" (title "Approximate number of unique keys"), "Null values", "Unique values" (`Total.tsx:28-54`); `SizeStats` sections "Key size" / "Value size" with "Total size", "Min size", "Max size", "Avg key", "Percentile 50/75/95/99/999" (`SizeStats.tsx:12-41`); `PartitionTable` sortable columns "Partition ID", "Total Messages", "Min Offset", "Max Offset", every row expandable (`PartitionTable.tsx:9-35`) into `PartitionInfoRow` with three lists: "Partition stats" (Total message, Total size, Min./Max. timestamp, Null keys amount, Null values amount, Approx. unique keys/values amount), "Keys sizes", "Values sizes" (Total/Min/Max/Avg + Percentile 50/75/95/99/999) (`PartitionInfoRow.tsx:33-111`).

### ACLs tab (`acls`) and Connectors tab (`connectors`)

`components/Topics/Topic/Acls/Acls.tsx:17-35`: `useTopicAcls` (`lib/hooks/api/topics.ts:118-127`), reuses `AclsTable` with principal/operation/permission/host columns. `components/Topics/Topic/Connectors/Connectors.tsx:10-18`: reuses `ConnectorsTable` with `topics` column hidden and sizing key `topic-connectors`; tab only present when `connectors.length > 0` (`Topic.tsx:106, 272-284, 307-312`).

### Edit page (`edit`)

Files: `components/Topics/Topic/Edit/Edit.tsx`, `Edit/topicParamsTransformer.ts`, `Edit/DangerZone/DangerZone.tsx`. Data: `useTopicDetails` and `useTopicConfig` with `QUERY_REFETCH_LIMITED_OPTIONS` (`refetchOnWindowFocus:false, refetchIntervalInBackground:false`, `lib/constants.ts:81-84`). No dedicated heading; renders inside topic shell.

Defaults (`Edit.tsx:23-32`, `topicParamsTransformer.ts:17-47`): `partitions`, `replicationFactor`, `cleanupPolicy` from topic; `maxMessageBytes` (`max.message.bytes`, default 1000012), `minInSyncReplicas` (`min.insync.replicas`, 1), `retentionBytes` (`retention.bytes`, -1), `retentionMs` (`retention.ms`, 1 week) from config; `customParams` = configs whose value differs from default and whose name is in `TOPIC_CUSTOM_PARAMS`. Same `TopicForm` in `isEditing` mode (name, partitions, cleanup policy, replication factor locked/hidden; submit label "Update topic"). On submit only dirty or non-DEFAULT_CONFIG entries are sent (`Edit.tsx:63-85`) via `useUpdateTopic` -> `formatTopicUpdate` configs `cleanup.policy`, `retention.ms`, `retention.bytes`, `max.message.bytes`, `min.insync.replicas` + custom (`lib/hooks/api/topics.ts:204-242`); success alert "Topic successfully updated." then navigate `../`.

Danger Zone (`DangerZone.tsx:85-173`): title "Danger Zone", warning "Change these parameters only if you are absolutely sure what you are doing."; two separate forms:
- `aria-label="Edit number of partitions"`: "Number of partitions *" number input (required message "Partiotions are required" [sic]), "Submit" disabled until dirty; client rule: value < current -> error "You can only increase the number of partitions!" (`:64-74`); confirm "Are you sure you want to increase the number of partitions? Do it only if you 100% know what you are doing!" -> `useIncreaseTopicPartitionsCount` success "Number of partitions successfully increased" (`lib/hooks/api/topics.ts:243-258`).
- `aria-label="Edit replication factor"`: "Replication Factor *" number input (required "Replication Factor are required"), "Submit"; confirm "Are you sure you want to update the replication factor?" -> `useUpdateTopicReplicationFactor` success "Replication factor successfully updated" (`:259-274`).

### Produce Message sidebar (`SendMessage`)

Files: `components/Topics/Topic/SendMessage/SendMessage.tsx`, `SendMessage/utils.ts`. Data: `useTopicDetails` (partitions), `useSerdes({use: SERIALIZE})` (suspense, no refetch, `lib/hooks/api/topicMessages.tsx:192-202`), `useSendMessage` (`lib/hooks/api/topics.ts:326-341`; success "Message successfully sent", error via `showServerError`).

Form (`useForm mode:'onChange'`, `SendMessage.tsx:79-87`), fields (`:229-372`):
- "Partition" `Select` options "Partition #N" (`utils.ts:44-48`), default first partition.
- "Key Serde" and "Value Serde" `Select`s (options = serde names, `utils.ts:50-57`); defaults = preferred serde (`utils.ts:30-42`), overridable by URL params `keySerde`/`valueSerde` (`:52-53, 67-77`). For each serde parameter with `allowedValues`, an extra `InputWithOptions` labelled `visibleName || name` with placeholder "Search <label>..." is rendered under the select (`:118-151`); params reset when serde changes (`:102-116`).
- "Key" `Editor` (40 px), "Value" `Editor` (280 px) — defaults generated from the preferred serde's JSON schema by `json-schema-faker` (`utils.ts:18-25`).
- "Headers" `Editor` (default `{}`).
- Switch "Keep contents after producing a message" with info tooltip "When enabled, the form will remain populated after sending a message." (`:348-362`).
- Submit "Produce Message", disabled while submitting.

Validation on submit (`:153-203`, `utils.ts:78-123`): key/value validated with Ajv against the selected serde schema (draft-04/06/2019/2020 detection); errors like `Error in parsing the "key" field schema`, `Error in parsing the "content" field value`, `<Key|Content><schemaPath> - <message>`; headers must be JSON else "Wrong header format"; all errors shown in one `showAlert('error', title 'Validation Error')` list. Payload: `key||null`, `value||null`, `headers`, `partition||0`, serdes, optional `keySerdeProperties`/`valueSerdeProperties`. When `keepContents` is off, key/value reset to defaults and sidebar closes (`:219-223`).

## Messages

### Messages tab (`.../all-topics/:topicName/messages`)

Files: `components/Topics/Topic/Messages/Messages.tsx`, `Messages/MessagesTable.tsx`, `Messages/Message.tsx`, `Messages/MessageContent/MessageContent.tsx`, `Messages/MessageContent/components/Serde/Serde.tsx`, `Messages/PreviewModal.tsx`, `Messages/Filters/*`, hooks `lib/hooks/api/topicMessages.tsx`, `lib/hooks/useMessagesFilters.ts`, `lib/hooks/useMessagesFiltersFields.ts`, `lib/hooks/useMessageFiltersStore.ts`, `lib/hooks/filterUtils.ts`.

Purpose: browse/tail topic messages streamed from the backend via Server-Sent Events, with seek mode, partition, serde, text and CEL smart filters, cursor pagination, export.

Composition (`Messages.tsx:9-29`): `useTopicMessages` provides `messages`, `isFetching`, `consumptionStats`, `phase`, `abortFetchData`; renders `<Filters>` then `<MessagesTable>`.

#### Streaming / live mode (`lib/hooks/api/topicMessages.tsx:31-190`)

- On every `searchParams` change the previous stream is aborted and a new `fetchEventSource` GET to `${basePath}/api/clusters/<cluster>/topics/<topic>/messages/v2` is opened with `openWhenHidden: true` (`:54-181`).
- Request params (`:66-104`): `limit` (URL `limit`, default `MESSAGES_PER_PAGE='100'`, `lib/constants.ts:60`), `mode`, plus `stringFilter`, `keySerde`, `smartFilterId`, `valueSerde` when present; `timestamp` for `TO_TIMESTAMP`/`FROM_TIMESTAMP`; `offset` for `TO_OFFSET`/`FROM_OFFSET`; `partitions` (comma list); `cursor` = stored `nextCursor` when URL cursor counter increased (`:105-117`).
- `onopen` 200 -> clears message list; 4xx (except 429) -> `showServerError` (`:123-131`). `onmessage` handles `TopicMessageEvent.type`: `MESSAGE` (prepend in `TAILING` mode, append otherwise), `PHASE` (sets `phase` name), `CONSUMING` (sets `consumptionStats`); each event's `cursor.id` stored in zustand `nextCursor` (`:132-159`). `onclose` -> `isFetching=false`; `onerror` -> clears cursor, stops, `showServerError` (`:160-174`).
- `abortFetchData` (`:46-52`) aborts the controller and creates a new one; exposed for the "Stop loading" button and Refresh in live mode.
- `useSerdes` (`:192-202`) suspense query with all refetch disabled; `useRegisterSmartFilter` (`:204-220`) POSTs `filterCode` and returns server filter id.

URL param keys (`lib/constants.ts:140-154`): `mode`, `timestamp`, `keySerde`, `valueSerde`, `limit`, `offset`, `stringFilter`, `partitions`, `smartFilterId`, `activeFilterId`, `activeFilterNPId`, `cursor`, `r` (toggled to force refresh).

#### Filters bar (`Filters/Filters.tsx:176-327`)

State via `useMessagesFilters(topicName)` (`lib/hooks/useMessagesFilters.ts:64-270`): on mount restores per-topic filters from localStorage key `message-filters-fields[<topic>:<cluster>]` when the URL has no params, otherwise writes URL params into localStorage (`useMessagesFiltersFields.ts:89-132`); forces `limit=100`, default `mode`, and deletes `cursor` (`useMessagesFilters.ts:76-89`). Every setter updates both URL and localStorage.

Row 1 (left):
- Seek mode `Select id="selectSeekType"` options (`lib/hooks/filterUtils.ts:3-11`): "Newest" (`LATEST`, default), "Oldest" (`EARLIEST`), "Live" (`TAILING`), "From offset" (`FROM_OFFSET`), "To offset" (`TO_OFFSET`), "Since time" (`FROM_TIMESTAMP`), "To time" (`TO_TIMESTAMP`). Changing mode clears offset and timestamp (`useMessagesFilters.ts:124-134`).
- Conditional input (`Filters/utils.ts:5-15`, `Filters.tsx:190-213`): text `id="offset"` placeholder "Offset" for offset modes; `react-datepicker` with time input ("Time:"), format "MMM d, yyyy", placeholder "Select timestamp" for time modes; none for Newest/Oldest/Live.
- Partitions `MultiSelect` options "Partition #N", placeholder "Select partitions", disabled while serdes load (`Filters.tsx:215-226`).
- "Key Serde" / "Value Serde" `Select`s (`id="selectKeySerdeOptions"`/`"selectValueSerdeOptions"`, min width 170 px) from `useSerdes({use: DESERIALIZE})` (`:227-246`).
- "Refresh" secondary button: in live mode while fetching aborts first, then toggles URL `r` param to re-run the stream (`:169-175`, `useMessagesFilters.ts:21-34`).

Row 1 (right): `Search placeholder="Search"` bound to `stringFilter` (debounced 500 ms); "Export" dropdown (`ExportIcon`, disabled while fetching or when no messages) with "Export JSON" and "Export CSV" (`:258-274`). Export payload per message: Value, Offset, Key, Partition, Headers, Timestamp (`:119-133`); CSV columns in that order with formula-injection guard (`:48-75`); file names `topic-messages_<ISO timestamp>.json|.csv` (`:77-78, 135-138`) saved via `useDataSaver.saveFile` (`lib/hooks/useDataSaver.ts:26-34`).

Row 2: "Add Filters" button with `PlusIcon` opens the smart-filter sidebar in add mode (`:282-289`); when a smart filter is active, chip `data-testid="activeSmartFilter"` shows its id with edit (`EditIcon`) and remove (`CloseIcon`) icons (`:290-308`).

Metrics row (`Filters/FiltersMetrics.tsx:27-64`, rendered when `consumptionStats` exists): phase message shown only in non-live mode while fetching; in live mode while fetching a "Loading messages..." indicator with spinner and "Stop loading" button (`Filters.styled.ts:292-311`) calling `abortFetchData`; then "Elapsed Time" (`<ms> ms`, clock icon), "Bytes Consumed" (`BytesFormatted`), "Messages Consumed" (`<n> messages consumed`), and "Errors" (`<n> errors`) when `filterApplyErrors` > 0.

#### Smart filter sidebar (`Filters/FiltersSideBar.tsx`, `Filters/AddEditFilterContainer.tsx`, `Filters/SavedFilters.tsx`, `Filters/InfoModal.tsx`, `Filters/QuestionInfo.tsx`)

`SlidingSidebar` titled "Add Filter" or "Edit Filter" (`FiltersSideBar.tsx:34-38`; edit mode when `filterName !== 'ADD_FILTER'`, `utils.ts:88-92`).

Form (`AddEditFilterContainer.tsx:160-222`, `aria-label="Filters submit Form"`): "Filter code" `Editor` (5–28 lines, no line numbers); "Display name" input placeholder "Enter Name"; `QuestionInfo` "?" button opening `InfoModal`; buttons "Cancel" and "Add Filter"/"Edit Filter" (disabled when `!isValid || isSubmitting || !isDirty`). Yup: `value` required, `id` optional (`:26-29`). Submit (`:127-158`): duplicate-name checks with alerts "The name “<name>” already exists. Please enter a unique name." / "Filter with the same name already exists" (`:47-80`); name defaults to first 32 chars of code (`:40-45, 82-92`); registers code on server via `useRegisterSmartFilter`, stores `{id, value, filterCode}` in zustand store persisted to localStorage `kafbat-ui-message-filters` (`useMessageFiltersStore.ts:32-69`, prefix `lib/constants.ts:66`); activates it (`setSmartFilter` writes `smartFilterId`=server code and `activeFilterId`=name to URL, `useMessagesFilters.ts:214-249`).

`InfoModal` (`InfoModal.tsx:10-70`): heading "We use CEL syntax for smart message filters"; variables list `key`, `value`, `keyAsText`, `valueAsText`, `header`, `partition`, `timestampMs`; JSON parsing note; three CEL examples (`record.keyAsText.matches(...)`, `record.key.name.first == 'user1'`, `record.headers.size() == 1 ...`); "Ok" button. (CEL only; no Groovy option in this version.)

`SavedFilters` (`SavedFilters.tsx:69-118`, shown only in add mode): header "Saved Filters" with "Clear all" (disabled when empty; clears store and all per-topic active filter fields); empty text "No saved filter(s)"; each saved filter row (highlighted if active) click -> activate & close; edit icon (`aria-label="edit"`) -> edit mode; delete icon (`aria-label="delete"`) -> confirm "Are you sure want to remove <id>?" plus "Warning: this filter is currently active in:" list of `cluster:topic` where it is active (`useMessagesFiltersFields.ts:20-58`).

#### Messages table (`MessagesTable.tsx:78-155`)

Plain `Table isFullwidth`. Header cells: (toggle), "Offset", "Partition", "Timestamp", "Key" with preview action text `Preview` / `Preview (N selected)`, "Value" with same, (actions). Empty: "No messages found" when not fetching; `PageLoader` row while fetching with no messages yet (`:127-138`).

Pagination (`:141-152`): single "Next →" button, disabled when live mode, fetching, or no `nextCursor`; `usePaginateTopics` increments URL `cursor` counter (`useMessagesFilters.ts:49-62`), which makes the stream re-open with the stored server cursor. There is no "Back" button; going back is via browser history/URL.

Preview modal (`PreviewModal.tsx:79-143`, opened per key/value column): lists existing `field : path` entries with edit/remove icons; inputs "Field" (placeholder "Field") and "Json path" (placeholder "Json Path"); errors "Field is required", "Json path is required", "Invalid JSONPath syntax" (validated with `jsonpath-plus`); buttons "Close", "Save". Filters persisted in localStorage `message-preview[<topic>]` (`MessagesTable.tsx:40-76`).

#### Message row (`Message.tsx:112-204`)

Clickable row toggles expansion (`MessageToggleIcon`). Cells: offset; partition; timestamp formatted with milliseconds in current timezone (`formatTimestamp withMilliseconds`, `:106-110`) — if `ClusterContext.messageRelativeTimestamp`, shows `timeAgo()` with tooltip of the full timestamp (`:127-131`); key and value in `Ellipsis` with `title` = full text; when preview filters exist the cell shows `field: <JSONPath result>` lines instead (`:83-104`); red warning icon with tooltip "Fallback serde was used" when `keySerde`/`valueSerde === 'Fallback'` (`:135-141, 148-154`). Hover-only dropdown (`:159-186`): "Copy to clipboard" (JSON `{Value, Offset, Key, Partition, Headers, Timestamp}` tab-indented; success "Copied successfully!" or warning about non-HTTPS, `useDataSaver.ts:7-25`), "Save as a file" (downloads `topic-message`), "Reproduce message" (`ActionDropdownItem`, permission `TOPIC:MESSAGES_PRODUCE`, opens produce sidebar prefilled via `TopicActionsContext`).

#### Expanded message (`MessageContent/MessageContent.tsx:79-143`)

Tabs "Key", "Value" (default), "Headers" (headers rendered as `JSON.stringify`); content shown in read-only `EditorViewer maxLines=28` with `schemaType` JSON when content starts with `{`/`[`, else PROTOBUF (plain text) (`:72-77`). Metadata column: "Timestamp" (with ms) + "Timestamp type: <CREATE_TIME|LOG_APPEND_TIME|...>"; `Serde` blocks "Key Serde" / "Value Serde" showing serde name — as a link (`GoToIcon`) to the Schema Registry subject page when serde is `SchemaRegistry` and `deserializeProperties.subjects` present (prefers subject containing topic name) (`Serde.tsx:13-50`) — and "Size: <bytes>" (`Serde.tsx:60-77`).
## Shared infrastructure used by every section below

Paths are relative to `/tmp/kui-ref/kafbat/frontend/src`.

- **Routing root**: `components/ClusterPage/ClusterPage.tsx:92-131` mounts each feature under `/ui/clusters/:clusterName/...`. Schemas route only renders when `hasSchemaRegistryConfigured` (`:95`), Kafka Connect routes only when `hasKafkaConnectConfigured` (`:101-118`), KSQL only when `hasKsqlDbConfigured` (`:120`), ACL only when `hasAclViewConfigured` (`:126`). Consumer groups is always mounted (`:92`). `isReadOnly` comes from `cluster.readOnly` (`:50`).
- **Permission gating**: `components/common/ActionComponent/*`. `ActionButton` (`ActionButton/ActionButton.tsx:10-16`) routes `Action.CREATE` to `ActionCreateButton` (uses `useCreatePermission(resource)`, `ActionCreateButton/ActionCreateButton.tsx:12`), everything else to `ActionPermissionButton` (`usePermission(resource, action, value)`). Disabled elements show a floating tooltip with `"You don't have a required permission to perform this action"` (`ActionComponent.ts:14-16`). `ActionDropdownItem` (`ActionDropDownItem/ActionDropdownItem.tsx:16-71`) supports `fallbackPermission` (OR logic, `:39-40`). `ActionSelect` (`ActionSelect/ActionSelect.tsx`) and `ActionPermissionWrapper` (`ActionPermissionWrapper/ActionPermissionWrapper.tsx`, wraps arbitrary child, cursor `not-allowed` when denied `:36`) exist too. Permission logic: `lib/permissions.ts:75-112` — no RBAC flag means always allowed (`:90`); `ResourceExemptList` (KSQL, CLUSTERCONFIG, APPLICATIONCONFIG, ACL, AUDIT) skips value regex matching (`:7-13`, `:108`).
- **Generated enums** (from `contract/src/main/resources/swagger/kafbat-ui-api.yaml`, the `generated-sources` dir is not checked in): `Action` = ALL, VIEW, EDIT, CREATE, DELETE, RESET_OFFSETS, EXECUTE, MODIFY_GLOBAL_COMPATIBILITY, ANALYSIS_VIEW, ANALYSIS_RUN, MESSAGES_READ, MESSAGES_PRODUCE, MESSAGES_DELETE, OPERATE, RESTART (yaml `:4138-4155`). `ResourceType` = APPLICATIONCONFIG, CLUSTERCONFIG, TOPIC, CONSUMER, SCHEMA, CONNECT, CONNECTOR, KSQL, ACL, AUDIT, CLIENT_QUOTAS (`:4157-4170`).
- **Search box**: `components/common/Search/Search.tsx` — debounced 500 ms (`:40-54`), writes `?q=` to URL and resets `page=1` (`:48-52`), clear icon (`:56-71`). `Fts` icon toggle (`components/common/Fts/Fts.tsx:23-44`, tooltip "Apply full text search") only when `ClusterContext.ftsEnabled`; persists per-resource in localStorage `kafbat-ui_fts:<resource>` and `?fts=` param (`useFts.ts:12-37`).
- **Table**: `components/common/NewTable` — supports `serverSideProcessing`, `emptyMessage`, `filterPersister` (column filters persisted to URL query), `enableColumnResizing` + localStorage persister, `getRowCanExpand`/`renderSubComponent`, `onRowClick`.
- **RefreshRateSelect** (`components/common/RefreshRateSelect/RefreshRateSelect.tsx:5-11`): options Off / 2 sec / 5 sec / 10 sec / 15 sec, displayed as `Refresh rate: <label>` (`:25`), persisted in localStorage under `storageKey` (`:21`).
- **Tag colors** (`components/common/Tag/getTagColor.ts`): RUNNING/STABLE green; FAILED/TASK_FAILED/DEAD red; EMPTY white; everything else yellow.
- **Standard page states**: `PageLoader` while `isLoading || isRefetching`; `ErrorPage` with status + retry (`onClick={refetch}`) on error; table with `emptyMessage` on success. Cited per screen below.

## Consumer groups

### Routes
- `lib/paths.ts:78-95`: list `/ui/clusters/:clusterName/consumer-groups`; details `.../consumer-groups/:consumerGroupID`; reset offsets `.../consumer-groups/:consumerGroupID/reset-offsets`.
- Router: `components/ConsumerGroups/ConsumerGroups.tsx:14-21` (index -> `List`, `:consumerGroupID` -> `Details`, `:consumerGroupID/reset-offsets` -> `ResetOffsets`).

### Screen: Consumer groups list (`components/ConsumerGroups/List.tsx`)
- Heading "Consumers" (`:152`) with **Download CSV** button (`DownloadCsvButton`, file prefix `consumers-<cluster>`, calls `consumerGroupsApiClient.getConsumerGroupsCsv` with current sort/search params, `:146-156`).
- Control panel: `Search` placeholder "Search by Consumer Group ID" with FTS toggle (`:159-163`); `RefreshRateSelect` storageKey `consumer-groups-refresh-rate` (`:164`).
- Server-side query: `useConsumerGroups` (`lib/hooks/api/consumers.ts:41-55`) with `orderBy` (`?sortBy`), `sortOrder` (`?sortDirection`), `search` (`?q`), `fts`, `page` (`?page`, default 1), `perPage` (`?perPage`, default `PER_PAGE`=25 `lib/constants.ts:59`), `state` (`?STATE=` comma list) (`:37-54`). `placeholderData` keeps previous page during refetch.
- Columns (`:62-141`), all sortable server-side except Coordinator:
  | Header | id / accessor | Notes |
  |---|---|---|
  | Group ID | `NAME` / `groupId` | `LinkCell` to details (encoded), width 600, wordBreak |
  | Num Of Members | `MEMBERS` / `members` | 140 |
  | Num Of Topics | `TOPIC_NUM` / `topics` | 140 |
  | Consumer Lag | `MESSAGES_BEHIND` / `consumerLag` | rendered from separate lag endpoint via `LagTrendComponent` (`:97-103`) |
  | Coordinator | `coordinator.id` | `enableSorting: false` |
  | State | `STATE` / `state` | `TagCell` inside `Tooltip` with `CONSUMER_GROUP_STATE_TOOLTIPS[state]`; column filter `multi-select` over `ConsumerGroupState` minus UNKNOWN (`:131-137`) |
- Sort enum `ConsumerGroupOrdering` = NAME, MEMBERS, STATE, MESSAGES_BEHIND, TOPIC_NUM (yaml `:3146-3152`).
- State badges: `ConsumerGroupState` = UNKNOWN, PREPARING_REBALANCE, COMPLETING_REBALANCE, STABLE, DEAD, EMPTY (yaml `:3075-3082`). Tooltips (`lib/constants.ts:125-134`): EMPTY "The group exists but has no members."; STABLE "Consumers are happily consuming and have assigned partitions."; PREPARING_REBALANCE "Something has changed, and the reassignment of partitions is required."; COMPLETING_REBALANCE "Partition reassignment is in progress."; DEAD "The group is going to be removed. It might be due to the inactivity, or the group is being migrated to different group coordinator."; UNKNOWN "".
- Table: `serverSideProcessing`, `enableSorting`, `enableColumnResizing` (persisted in localStorage key `Consumers`, `:143`), column filters persisted to query (`:144`), row click navigates to details (`:186-190`), `disabled` while fetching, `emptyMessage="No active consumer groups found"` (`:183`).
- States: loader while `isLoading || isRefetching` (`:166-168`); `ErrorPage` with `error.status`, `error.message`, retry (`:169-176`).
- Real-time: `useConsumerGroupsLagTrends` (`components/ConsumerGroups/lib/useConsumerGroupsLagTrends.tsx:14-118`) -> `useGetConsumerGroupsLag` (`lib/hooks/api/consumers.ts:147-195`): `refetchInterval = pollingIntervalSec*1000` when > 0, else no polling (`:180`); `refetchOnWindowFocus: false`; sends `lastUpdate` timestamp from previous response (`:172,176`); enabled only when `ids.length > 0`. Trend arrows: `LagTrendComponent` (`lib/consumerGroups.tsx:97-121`) renders `N/A` for null lag, `▲` for up, `▼` for down, colored by `theme.lag[trend]`; trends only computed when polling is on (`computeLagTrends`, `:42`).
- Permission gating: none on list (no create action exists).

### Screen: Consumer group details (`components/ConsumerGroups/Details/Details.tsx`)
- Heading = group id, back link "Consumers" (`:96-100`). Buttons: **Export CSV** (`exportTableCSV(table, {prefix:'connector-topics'})`, `:89-107`); when `!isReadOnly` a `Dropdown` (`:109-135`) with:
  - **Reset offset** – `ActionDropdownItem`, permission `CONSUMER`/`RESET_OFFSETS`/value=groupId, `disabled` when `consumerGroup.topics === 0` (`:111-121`), navigates to `reset-offsets`.
  - **Delete consumer group** – `danger`, `confirm="Are you sure you want to delete this consumer group?"`, permission `CONSUMER`/`DELETE`/value=groupId (`:122-133`); on success toast `Consumer <id> group deleted` and navigate `../` (`lib/hooks/api/consumers.ts:75-92`).
- Data: `useConsumerGroupDetails` (`consumers.ts:57-73`). Metrics indicators (`:151-200`): **State** (Tag + tooltip), **Members**, **Assigned Topics**, **Assigned Partitions** (`partitions.length`), **Coordinator ID**, **Total lag** (LagTrendComponent), and **Connector** link (only when groupId starts with `connect-`, Kafka Connect configured, and matching connector found via `useConnectors`, `:39-40,57-66,186-198`).
- Control panel: `Search` placeholder "Search by Topic Name" (client-side filter of topics via `?q`, `TopicsTable/lib/utils.ts:38`), `RefreshRateSelect` storageKey `consumer-group-<id>-refresh-rate` (`:201-207`).
- Topics table (`TopicsTable/TopicsTable.tsx`, columns in `TopicsTable/lib/utils.ts:46-67`): **Topic** (link to topic page, `cells/cells.tsx:19-29`), **Consumer lag** (sum of partition lags or server lag, with trend), unnamed actions column with `Dropdown` -> **Delete offsets** (`danger`, confirm "Are you sure you want to delete offsets from the topic?", permission `CONSUMER`/`RESET_OFFSETS`/value=groupId, `cells/cells.tsx:60-73`; success toast `Consumer <id> group offsets in topic <topic> deleted`, `consumers.ts:117-138`). `emptyMessage="No topics"` (`TopicsTable.tsx:51`). Every row expandable (`:48`).
- Expanded row = partitions table (`TopicContents/TopicContents.tsx:17-24`): columns **Partition**, **Consumer ID**, **Host**, **Consumer Lag**, **Current Offset**, **End offset**; client-side sortable via `TableHeaderCell` (default `partition` DESC, toggles ASC/DESC, `:102-112`); numeric comparator for partition/offsets/lag, IPv4 comparator for host, case-insensitive comparator for consumerId (`:46-89`). Lag cell uses per-partition lag + trend (`:169-178`).
- States: `PageLoader` (`:139`), `ErrorPage` with `resourceName="Consumer Group <id>"` and retry (`:141-147`).
- Real-time: `useGetConsumerGroupLagsInfo` (`Details/useGetConsumerGroupLagsInfo.tsx`) -> same polling hook with `includePartitions: true` and storage key `consumer-group-<id>-refresh-rate`.

### Screen: Reset offsets (`components/ConsumerGroups/Details/ResetOffsets/ResetOffsets.tsx`, `Form.tsx`)
- Shows `PageLoader` until details loaded (`ResetOffsets.tsx:22-23`). Heading = group id, back "Consumers", document title "Reset Offsets" (`:41-50`).
- Default values (`:32-37`): `resetType: EARLIEST`, `topic` = first partition's topic, `partitionsOffsets: []`, `resetToTimestamp: Date.now()`.
- Form (`Form.tsx`, react-hook-form `mode: 'onChange'`, `:49-52`):
  - **Topic** – `ControlledSelect`, placeholder "Select Topic", options = unique topics in group (`:109-114`). Changing topic clears partition selection (`:96-98`).
  - **Reset Type** – `ControlledSelect`, placeholder "Select Reset Type", options = `ConsumerGroupOffsetsResetType` values EARLIEST, LATEST, TIMESTAMP, OFFSET (yaml `:3736-3741`; `Form.tsx:36-38,115-120`).
  - **Partitions** – `react-multi-select-component` `MultiSelect`, options `Partition #<n>` for partitions of selected topic (`:71-77,122-132`).
  - **Timestamp** – only when resetType = TIMESTAMP and at least one partition selected (`:133-134`): `react-datepicker` with `showTimeInput`, `timeInputLabel="Time:"`, format `MMMM d, yyyy h:mm aa`, displayed in user timezone (`useTimezone`), rule `required: 'Timestamp is required'` (`:140-142`).
  - **Partition #<n> Offset** – only when resetType = OFFSET and partitions selected (`:167-168`): one numeric `Input` per selected partition (`useFieldArray partitionsOffsets`), rules `required: 'Offset is required'`, `min 0` message `must be greater than or equal to 0`, `shouldUnregister` (`:171-185`).
  - Submit button **Reset Offsets**, disabled when no partitions selected (`:191-198`).
- Submit: `useResetConsumerGroupOffsetsMutation` (`consumers.ts:94-115`) POSTs `ConsumerGroupOffsetsReset`; success toast `Consumer <id> group offsets reset`, invalidates groups, `navigate(-1)` (`Form.tsx:100-103`).
- Permission: page itself is not gated; entry point is the gated dropdown item on Details.

## Schema registry

### Routes
- `lib/paths.ts:102-133`: list `/schemas`; new `/schemas/create-new`; details `/schemas/:subject`; edit `/schemas/:subject/edit`; compare `/schemas/:subject/compare` (with `?leftVersion=&rightVersion=`).
- Router: `components/Schemas/Schemas.tsx:20-26` (List lazy-loaded with `Suspense` + `PageLoader`).

### Screen: Schemas list (`components/Schemas/List/List.tsx`)
- Heading "Schema Registry" (`:96`). When `!isReadOnly` (`:97-112`): `GlobalSchemaSelector` and **Create Schema** `ActionButton` (permission `SCHEMA`/`CREATE`, link to `create-new`).
- `GlobalSchemaSelector` (`List/GlobalSchemaSelector/GlobalSchemaSelector.tsx`): label "Global Compatibility Level: " + `ActionSelect` (permission `SCHEMA`/`MODIFY_GLOBAL_COMPATIBILITY`, `:63-66`), options = `CompatibilityLevelCompatibilityEnum` keys BACKWARD, BACKWARD_TRANSITIVE, FORWARD, FORWARD_TRANSITIVE, FULL, FULL_TRANSITIVE, NONE (yaml `:3563-3570`); disabled while fetching; on change opens confirm "Are you sure you want to update the global compatibility level and set it to <b>{level}</b>? This may affect the compatibility levels of the schemas." (`:35-46`) then `useUpdateGlobalSchemaCompatibilityLevel` (`lib/hooks/api/schemas.ts:171-199`, invalidates global level + schema list). Renders nothing until current level loaded (`:49`).
- Search: placeholder "Search by Schema Name" + FTS toggle (`:114-120`).
- Query `useGetSchemas` (`schemas.ts:47-82`): `page`, `perPage` (25), `search` (`?q`), `orderBy` (`?sortBy` as `SchemaColumnsToSort`), `sortOrder` (`?sortDirection`), `fts`; `placeholderData` previous.
- Columns (`:51-92`), ids = `SchemaColumnsToSort` (SUBJECT, ID, TYPE, VERSION, COMPATIBILITY): **Subject** (LinkCell, wordBreak), **Id** (120), **Type** (`schemaType`, 120), **Version** (120), **Compatibility** (`compatibilityLevel`, 160).
- Table: `enableSorting`, `serverSideProcessing`, `pageCount`, row click -> details, `emptyMessage="No schemas found"` (`:136-146`).
- States: loader `isLoading || isRefetching` (`:122-124`); `ErrorPage` status/message/retry (`:126-133`).
- Real-time: none (no polling).

### Screen: Schema details (`components/Schemas/Details/Details.tsx`)
- Queries: `useGetSchemasVersions` (all versions) and `useGetLatestSchema` (`:37-44`; hooks `schemas.ts:28-45, 84-103`).
- Heading = subject, back "Schema Registry" (`:67-71`). Buttons: **Go to topic "<topic>"** (only when `schema.data.topic` set, `:72-80`); **Compare Versions** (primary, to `compare?leftVersion=<latest>&rightVersion=<latest>`, `:81-90`); when `!isReadOnly`: **Edit Schema** `ActionButton` (permission `SCHEMA`/`EDIT`/value=subject, `:93-104`) and `Dropdown` -> **Remove schema** (`danger`, confirm "Are you sure want to remove <b>{subject}</b> schema?", permission `SCHEMA`/`DELETE`/value=subject, `:105-122`); delete via `useDeleteSchema` (`schemas.ts:201-224`) then `navigate('../')` (`:60-63`).
- Body (`:143-155`): `LatestVersionItem` (`Details/LatestVersion/LatestVersionItem.tsx`): heading "Actual version" with `EditorViewer` (read-only, `maxLines=28`), metadata labels **Latest version**, **ID**, **Type**, **Subject**, **Compatibility** (`:22-40`). Then `TableTitle` "Old versions" and table with columns **Version**, **ID**, **Type** (`:52-56`), `enableSorting`, every row expandable to `SchemaVersion` (`Details/SchemaVersion/SchemaVersion.tsx`, `EditorViewer` of that version's schema).
- States: loader when either query loading/refetching (`:127-130`); `ErrorPage` with `resourceName="Schema <subject>"` and retry of both (`:132-141`).

### Screen: Compare versions / diff (`components/Schemas/Diff/Diff.tsx`)
- Heading "<subject> compare versions", back "Schema Registry" (`:60-64`); secondary **Back** button (`navigate(-1)`, `:65-71`).
- Two `Select`s (`id="left-select"`, `id="right-select"`) with options `Version <n>` for every version (`:84-112, 125-155`); default = first version when URL param empty; changing one rewrites both `?leftVersion` and `?rightVersion` in the URL (`:90-105, 133-148`).
- `DiffViewer` (Ace side-by-side) with both schema contents; JSON schemas are pretty-printed with tab indentation, others raw (`:40-47`); `schemaType` from first version (`:48-50`); `isFixedHeight={false}` (`:162-172`).
- Loading: `PageLoader` while versions fetching (`:175-176`). No explicit error state.

### Screen: Create schema (`components/Schemas/New/New.tsx`)
- Heading "Create", back "Schema Registry" (`:74-78`). Form (`mode: 'onChange'`, yup resolver, default `schemaType: AVRO`, `:49-55`):
  - **Subject \*** – `Input` placeholder "Schema Name", autofocus; yup `required('Subject is required.')`, `matches(SCHEMA_NAME_VALIDATION_PATTERN, 'Only alphanumeric, _, -, and . allowed')` (`:33-40`; pattern `/^[.,A-Za-z0-9_/-]+$/` `lib/constants.ts:22`).
  - **Schema \*** – `Textarea`, `required: 'Schema is required.'` (`:41, 97-101`).
  - **Schema Type \*** – `Select` options AVRO / JSON / PROTOBUF (`:27-31`), `required('Schema Type is required.')` (`:42`).
  - **Submit** disabled when `!isValid || isSubmitting || !isDirty` (`:131-138`).
- Submit: `useCreateSchema` (`schemas.ts:124-140`) then navigate to details (`:63-70`).

### Screen: Edit schema (`components/Schemas/Edit/Edit.tsx`, `Edit/Form.tsx`)
- Loads latest schema with `QUERY_REFETCH_LIMITED_OPTIONS` (no refetch on focus, `lib/constants.ts:81-84`); on error navigates to `/404` (`Edit.tsx:26-30`); `PageLoader` while fetching (`:32-34`).
- Heading "<subject> Edit", back "Schema Registry" (`Form.tsx:97-101`). Fields:
  - **Type** – `Select` of `SchemaType` keys, **disabled** (`:106-124`).
  - **Compatibility level** – `Select` of `CompatibilityLevelCompatibilityEnum` keys, default current level (`:127-145`).
  - **Latest schema** – read-only `Editor`, height 372px, JSON pretty-printed with tabs (PROTOBUF left raw, `:43-47, 150-160`).
  - **New schema** – editable `Editor`, default = formatted latest (`:163-178`).
  - Validation (`:49-55`): `newSchema` `required()`; for non-PROTOBUF also `isJsonObject('Schema syntax is not valid')` (`lib/yupExtended.ts:16-41` — must start with `{`, end with `}`, and `JSON.parse`).
  - **Submit** disabled when `!isDirty || isSubmitting || !!errors.newSchema` (`:182-189`).
- Submit logic (`:72-93`): if compatibilityLevel dirty -> `useUpdateSchemaCompatibilityLayer` (`schemas.ts:142-169`); if newSchema/schemaType dirty -> `useCreateSchema` (registers new version); then navigate to details.
- Permission: page not gated itself; entry is gated **Edit Schema** button.

## Kafka Connect

### Routes
- `lib/paths.ts:210-300`: hub `/kafka-connect` (redirects to `/kafka-connect/clusters`, `components/Connect/Connect.tsx:41-46`); `/kafka-connect/clusters`; `/kafka-connect/connectors` (accepts `?connect=<name>` column filter); create `/create-new` (`clusterConnectorNewPath`, mounted at cluster level `ClusterPage.tsx:102`); connector details `/connects/:connectName/connectors/:connectorName` with sub-tabs `/config`, `/topics` (Tasks is index) (`ClusterPage.tsx:104-112`, `Details/DetailsPage.tsx:107-117`). An `/edit` path helper exists (`paths.ts:257-265`) but is not routed.
- Hub layout (`Connect.tsx:22-50`): `TableProvider` + `Header` + `Navbar` tabs **Clusters** / **Connectors**.

### Header (`components/Connect/Header/Header.tsx`)
- Heading "Kafka Connect" (`:52-55`). When `!isReadOnly`: **Create Connector** `ActionButton` (permission `CONNECT`/`CREATE`), disabled when no connects, wrapped in `Tooltip` "No Connects available" shown only when list empty (`:56-76`). **Export CSV** button exports current table (prefix `kafka-connect-clusters` or `kafka-connect-connectors`, `:24-36, 78-80`).

### Screen: Connect clusters list (`components/Connect/Clusters/Clusters.tsx`, `Clusters/ui/List/List.tsx`)
- Query `useConnects(clusterName, withStats=true)` (`lib/hooks/api/kafkaConnect.ts:71-84`).
- Statistics tiles (`Clusters/ui/Statistics/Statistics.tsx:13-31`): **Clusters** (count), **Connectors** (count + `warningCount` = failed connectors), **Tasks** (count + failed tasks).
- Columns (`List.tsx:16-45`): **Name** (text filter, `includesString`, 600), **Version** (sortable, multi-select filter), **Connectors** (`ConnectorsCell`: `running/total` text; `AlertBadge` with icon when failed > 0; null when 0 — `Cells/ConnectorsCell.tsx:18-33`), **Running tasks** (`TasksCell`, same pattern, `Cells/TasksCell.tsx`). CSV functions for count columns.
- Table: `enableSorting`, filters persisted to query, row click -> `/kafka-connect/connectors?connect=<name>` (`:59-61`), `emptyMessage="No kafka connect clusters"` (`:62`).
- States: loader (`Clusters.tsx:20`), `ErrorPage` status/message/retry (`:22-28`).

### Screen: Connectors list (`components/Connect/List/ListPage.tsx`, `List/ConnectorsTable/ConnectorsTable.tsx`, `connectorsColumns/columns.tsx`)
- Query `useConnectors(clusterName, ?q, fts)` (`kafkaConnect.ts:85-111`) — server search, result sorted by name client-side (`:99-108`), `placeholderData` previous.
- Statistics tiles over the **filtered** rows (`List/Statistics/Statistics.tsx:18-31`, fed by `FilteredConnectorsProvider` + `onFilterRows`): **Connectors** (count, warning = state FAILED), **Tasks** (count, warning = failed tasks) (`Statistics/models/computeStatistics.ts`).
- Search: placeholder "Search by Connect Name, Status or Type" + FTS (`ListPage.tsx:39-43`).
- Columns (`columns.tsx:14-95`):
  | Header | accessor | Cell / filter |
  |---|---|---|
  | Name | `name` | `KafkaConnectLinkCell` link to details; resizable |
  | Connect | `connect` | `BreakableTextCell`; multi-select filter (`includesSome`) |
  | Type | `type` | multi-select (`arrIncludesSome`), 120 |
  | Plugin | `connectorClass` | `BreakableTextCell`; multi-select |
  | Topics | `topics` | `TopicsCell` green `MultiLineTag` links to topic pages; multi-select filter; csv joined `, ` |
  | Status | `status.state` | `TagCell`; multi-select filter |
  | Consumers | `consumer` | `ConsumerGroupCell` gray chip link to consumer group or `-`; text filter |
  | Running Tasks | `tasksCount` (id `running_task`) | `RunningTasksCell` `running/total`, `AlertBadge` when failed > 0 |
  | (blank) | id `action` | `ActionsCell` dropdown, 60 |
- `ConnectorState` = RUNNING, FAILED, PAUSED, UNASSIGNED, TASK_FAILED, RESTARTING, STOPPED (yaml `:3781-3789`). `ConnectorType` = SOURCE, SINK, UNKNOWN (`:3693-3697`).
- Table: `enableSorting`, `enableColumnResizing` (localStorage key `KafkaConnect`), filters persisted to query, `setRowId = name-connect`, `emptyMessage="No connectors found"` (`ConnectorsTable.tsx:31-47`).
- Row actions dropdown (`cells/ActionsCell.tsx:87-226`), each `ActionDropdownItem` with permission `CONNECTOR`/<action>/value=`<connect>/<name>` and `fallbackPermission` `CONNECT`/<action>/value=`<connect>`; all disabled while any mutation is in flight (`useIsMutating`, `:29-30`):
  - **Resume** (only when state PAUSED or STOPPED) – `OPERATE`, `ConnectorAction.RESUME`.
  - **Pause** (only when RUNNING) – `OPERATE`, PAUSE.
  - **Stop** (only when RUNNING) – `OPERATE`, STOP.
  - **Restart Connector** – `OPERATE`, RESTART; additionally disabled when `isReadOnly` (`:146`).
  - **Restart All Tasks** – `OPERATE`, RESTART_ALL_TASKS.
  - **Restart Failed Tasks** – `OPERATE`, RESTART_FAILED_TASKS.
  - **Reset Offsets** – `danger`, `RESET_OFFSETS`; disabled unless state STOPPED (`:194`); confirm "Are you sure you want to reset the <b>{name}</b> connector offsets?" (`:78-85`).
  - **Delete** – `danger`, `DELETE`; confirm "Are you sure you want to remove the <b>{name}</b> connector?" (`:50-59`).
  - `ConnectorAction` enum = RESTART, RESTART_ALL_TASKS, RESTART_FAILED_TASKS, PAUSE, RESUME, STOP (yaml `:3792-3799`). Mutations: `useUpdateConnectorState` (`kafkaConnect.ts:152-172`, invalidates connectors list, connector, and topic connectors), `useDeleteConnector` (`:223-231`), `useResetConnectorOffsets` (`:233-241`).
- States: loader offset 370 (`ListPage.tsx:46`), `ErrorPage` status/message/retry (`:48-55`).
- Real-time: none (no polling).

### Screen: Connector details (`components/Connect/Details/DetailsPage.tsx`)
- Queries `useConnector` and `useConnectorTasks` (tasks sorted by `status.id`, `kafkaConnect.ts:112-151`). Wrapped in `SuspenseQueryComponent` at router level (`ClusterPage.tsx:107-109`).
- Heading = connector name, back "Connectors", dynamic document title (`:44-56`), with `Actions` component.
- **Actions** (`Details/Actions/Actions.tsx:80-226`): a primary-styled dropdown labelled **Restart** (chevron) containing **Pause** (RUNNING only), **Stop** (RUNNING only), **Resume** (PAUSED or STOPPED), **Restart Connector**, **Restart All Tasks**, **Restart Failed Tasks** — each `ActionDropdownItem` with `CONNECTOR`/`OPERATE`/`<connect>/<connector>` + fallback `CONNECT`/`OPERATE`/`<connect>`; whole dropdown disabled while mutating (`:83`). Second (kebab) dropdown: **Reset Offsets** (`danger`, `RESET_OFFSETS`, disabled unless STOPPED, confirm "Are you sure you want to reset the <b>{name}</b> connector offsets?", `:189-208`) and **Delete** (`danger`, `DELETE`, confirm "Are you sure you want to remove the <b>{name}</b> connector?", on success navigate to connectors list, `:37-52, 209-224`).
- **Overview** metrics (`Details/Overview/Overview.tsx:35-78`): **Worker** (if `status.workerId`), **Type**, **Class** (`config['connector.class']` if present), **State** (Tag; clickable when state FAILED and `status.trace` present -> opens `Modal` titled "Connector Error Details" showing `Worker: <id>` and the trace in a monospace scroll box, **Close** button, `:23-31, 80-105`), **Tasks Running**, **Tasks Failed** (`isAlert`, `alertType` error when > 0 else success), **Consumer Group** (link to consumer group details if `connector.consumer`).
- Tabs (`Navbar`, `:58-90`): **Tasks** (index), **Config**, **Topics**.
- **Tasks tab** (`Details/Tasks/Tasks.tsx`): columns **ID** (`status.id`), **Worker** (`status.workerId`), **State** (`TagCell`; `ConnectorTaskStatus` = RUNNING, FAILED, PAUSED, RESTARTING, UNASSIGNED yaml `:3772-3778`), **Trace** (truncated to 100 chars with `...`, not sortable, width 70%), blank actions column. Rows with non-empty trace expandable to full trace (`:53-58`). `emptyMessage="No tasks found"`, `enableSorting`. Row action (`Tasks/ActionsCellTasks.tsx:22-42`): dropdown -> **Restart task** (`danger`, confirm "Are you sure you want to restart the task?", permission `CONNECTOR`/`OPERATE` + fallback `CONNECT`/`OPERATE`), mutation `useRestartConnectorTask` (`kafkaConnect.ts:173-181`).
- **Config tab** (`Details/Config/Config.tsx`): `useConnectorConfig` (`kafkaConnect.ts:182-187`) pretty-printed JSON (tabs) into an `Editor` (`:39-51, 75-81`); yup `config: string().required().isJsonObject()` (`:20-22`, default message `${path} is not JSON object`); form `mode: 'onChange'`; warning banner "Please replace ****** with the real credential values to avoid accidentally breaking your connector config!" when config contains `"******"` (`:63-72`); **Submit** disabled when `!isValid || isSubmitting || !isDirty` (`:86-93`); on success toast "Config successfully updated." (`kafkaConnect.ts:194-196`) and form reset to submitted values. Form has `aria-label="Edit connect form"`. Not permission-gated in the UI.
- **Topics tab** (`Details/Topics/Topics.tsx`): single column **Topic** (link to topic page, `cells/TopicNameCell.tsx`), data from `connector.topics`, `emptyMessage="No topics found"`.
- States: loader offset 200 (`DetailsPage.tsx:92`), `ErrorPage` with `resourceName="Connector <name>"` and refetch of both queries (`:94-104`).

### Screen: Create connector (`components/Connect/New/New.tsx`)
- Heading "Create new connector", back "Connectors" (`:97-101`). Form `aria-label="Create connect form"`, `mode: 'all'`, yup resolver (`:25-28, 43-51`):
  - **Connect \*** – `Select` of connect names, default first; field hidden when 0 or 1 connect exists (`:106-127`, `S.Filed $hidden`).
  - **Name** – `Input` placeholder "Connector Name", autofocus; yup `required()` (`:26, 130-141`).
  - **Config** – `Editor` (Ace JSON); yup `required().isJsonObject()` (`:27, 145-155`).
  - **Submit** disabled when `!isValid || isSubmitting || !isDirty` (`:157-164`).
- Submit: `useCreateConnector` (`kafkaConnect.ts:201-221`) with `JSON.parse(config.trim())`; on success navigate to the new connector's details (`:66-88`). No validate-before-create step (comment `:211`).
- Permission: entry gated by **Create Connector** button (`CONNECT`/`CREATE`); page itself not gated.

## KSQL

### Routes
- `lib/paths.ts:304-320`: `/ksqldb` (index redirects to `tables`, `components/KsqlDb/KsqlDb.tsx:121-124`), `/ksqldb/tables`, `/ksqldb/streams`, `/ksqldb/query`.
- Router: `KsqlDb.tsx:120-135`; only mounted when `hasKsqlDbConfigured` (`ClusterPage.tsx:120-125`).

### Screen: KSQL DB hub (`components/KsqlDb/KsqlDb.tsx`)
- Heading "KSQL DB" with **Execute KSQL Request** `ActionButton` (permission `KSQL`/`EXECUTE`, links to `query`, `:53-63`). Note `KSQL` is in `ResourceExemptList`, so any KSQL EXECUTE grant applies regardless of value.
- Queries `useKsqlTables`, `useKsqlStreams` (`lib/hooks/api/ksqlDb.tsx:24-50`, plain `useQuery`, no polling).
- Metrics: **Tables** (count) and **Streams** (count), with `fetching` spinner flag (`:66-85`).
- Navbar tabs **Tables** / **Streams** (`:88-103`).
- `TableView` (`components/KsqlDb/TableView.tsx:13-36`) columns: **Name** (`BreakableTextCell`), **Topic** (`BreakableTextCell`), **Key Format**, **Value Format**, **Is Windowed** (`String(isWindowed)` for tables, `-` for streams); `enableSorting`; `emptyMessage` "Loading..." while fetching else "No rows found".
- States: loader offset 300 (`:105`), `ErrorPage` status/message, retry refetches both (`:107-117`). Routes (including Query) only render after both lists succeed (`:120`).

### Screen: Query editor (`components/KsqlDb/Query/Query.tsx`, `Query/QueryForm/QueryForm.tsx`)
- Form (`QueryForm.tsx`, `mode: 'onTouched'`, yup: `ksql: string().trim().required()`, `streamsProperties: array of {key: string().trim(), value: string().trim()}`, `:37-44`; defaults `ksql: ''`, one empty property row `:55-58`):
  - **KSQL** label with small primary **Clear** button that empties the editor (`:116-125`).
  - `S.SQLEditor` (react-ace SQL editor) bound to `ksql`; key binding Ctrl-Enter / Command-Enter submits (`:126-149`); read-only while fetching.
  - **Stream properties:** repeatable rows of `Input` placeholder "Key" and "Value" with a delete icon button (`aria-label="deleteProperty"`); deleting the last remaining row resets it to empty instead of removing (`:82-89, 155-180`). **Add Stream Property** button (secondary, PlusIcon) disabled while fetching or when any existing row has an empty key (`:91-92, 181-190`).
  - **Clear results** (secondary) disabled when `fetching || !isDirty || !hasResults` (`:194-201`); calls `resetResults` (sets `pipeId=false`) and refocuses editor.
  - **Execute** (primary submit) disabled while fetching (`:202-210`).
- Submission (`Query.tsx:22-41`): stream properties reduced to `Record<string,string>` and omitted entirely if first key is empty; `useExecuteKsqlkDbQueryMutation` (`ksqlDb.tsx:52-56`) POSTs `ksqlCommandV2` and returns `pipeId`.
- Streaming results via SSE (`useKsqlkDbSSE`, `ksqlDb.tsx:102-203`): `fetchEventSource` GET `${basePath}/api/clusters/<cluster>/ksql/response?pipeId=<id>` with `openWhenHidden: true` and an `AbortController` (`:106-118`). Message handling by `table.header` (`:131-161`): `Execution error` -> error toast built by `getFormattedErrorFromTableData` (title `[Error #<code>] <type>`, message combines entities, statement, message; `:58-95`); `Schema` -> replace data; `Row` -> append rows; `Query Result` -> success toast "Query succeed"; `Source Description`/`properties`/default -> replace data. 4xx (except 429) on open -> `showServerError`. While `pipeId` is set a `toast.promise` shows "Consuming query execution result..." with an **Abort** link (`StopLoading`) that aborts the controller (`:174-197`); resolves as "Cancelled". Effect cleanup aborts on unmount / pipeId change (`:199`).
- Results renderer (`Query/renderer/TableRenderer/TableRenderer.tsx`): `TableTitle` = `table.header`, header cells from `columnNames`, rows from `values` (object/array cells pretty-printed as JSON with 2-space indent, `:13-42`); shows "No tables or streams found" row when no columns (`:58-61`). Rendered only when `pipeId && sse.data` (`Query.tsx:51`).
- Real-time: SSE stream as above; no polling on tables/streams lists.

## ACLs

### Routes
- `lib/paths.ts:332-336`: `/acl` (index only; `clusterAclNewRelativePath = 'create-new-acl'` is defined but not routed — creation happens in a slide-over panel). Router `components/ACLPage/ACLPage.tsx:7-10`. Mounted only when `hasAclViewConfigured` (`ClusterPage.tsx:126-131`).

### Screen: ACL list (`components/ACLPage/List/List.tsx`)
- Heading "Access Control List" with **Create ACL** `ActionButton` (PlusIcon, permission `ACL`/`EDIT`, `disabled={isReadOnly}`, opens the form panel, `:78-91`). `ACL` is in `ResourceExemptList` (no value matching).
- Search: placeholder "Search by Principal Name" + FTS toggle (`:92-98`); effect resets `page=1` when `?q` set, else removes `q` (`:53-61`).
- Query `useAcls({clusterName, search, fts})` (`lib/hooks/api/acl.ts:18-39`, `placeholderData` previous, no polling).
- Columns (`Table/TableCells.tsx`), all client-side sortable, filters persisted to query (`Table/Table.tsx:10-18`):
  | Header | accessor | Cell / filter |
  |---|---|---|
  | Principal | `principal` | `BreakableTextCell`, 257 |
  | Resource | `resourceType` | lower-cased `EnumCell`; multi-select filter (`arrIncludesSome`), 145 |
  | Pattern | `resourceName` | value + chip `prefixed` (default style) or `literal` (secondary); text filter, 257 |
  | Host | `host` | text filter, 257 |
  | Operation | `operation` | lower-cased `EnumCell`; multi-select filter, 121 |
  | Permission | `permission` | chip `success` for ALLOW, `danger` otherwise, lower-cased, 111 |
  | (delete, id `delete`) | — | `ActionPermissionWrapper` (permission `ACL`/`EDIT`) around a hover-revealed `DeleteIcon`, 76 (`:150-158`) |
- Delete flow (`lib/useDeleteAcl.ts:12-18`): confirm modal "Are you sure want to delete this ACL record?" then `useDeleteAcl` (`acl.ts:134-157`; toast "ACL deleted", invalidates list).
- Table `emptyMessage="No ACL items found"` (`Table/Table.tsx:15`).
- States: loader offset 300 (`List.tsx:100`), `ErrorPage` status/message/retry (`:102-109`).
- Enums: `KafkaAclResourceType` = UNKNOWN, TOPIC, GROUP, CLUSTER, TRANSACTIONAL_ID, DELEGATION_TOKEN, USER (yaml `:4296-4304`); `KafkaAclNamePatternType` = LITERAL, PREFIXED (`:4307-4310`); operation enum = UNKNOWN, ALL, READ, WRITE, CREATE, DELETE, ALTER, DESCRIBE, CLUSTER_ACTION, DESCRIBE_CONFIGS, ALTER_CONFIGS, IDEMPOTENT_WRITE, CREATE_TOKENS, DESCRIBE_TOKENS (`:4211-4225`); permission enum = ALLOW, DENY (`:4226-4230`).

### Create ACL panel (`components/ACLPage/Form/Form.tsx`)
- Slide-over `S.Wrapper` (`data-testid="aclForm"`, `$open`) with heading "Create ACL" and close icon (`:32-38`); **Select ACL type** `Select` (size L, 270px) with options `Custom ACL`, `For Consumers`, `For Producers`, `For Kafka Stream Apps` (`Form/constants.ts:13-18`), default Custom (`:25`). Detailed forms are lazy-loaded (`:12-22`). Footer: **Cancel** (closes) and **Submit** (calls `formRef.current.requestSubmit()`, `:55-70`). Each detailed form closes the panel via `ACLFormContext.close` on success (`AclFormContext.ts`).
- All detailed forms use `mode: 'all'` + yup resolver, and swallow API errors (`catch {}`) since the API client shows toasts. Success toast "Your ACL was created successfully" and list invalidation (`acl.ts:41-48`).
- `MatchTypeSelector` (`Form/components/MatchTypeSelector.tsx`): radio EXACT / PREFIXED (`Form/constants.ts:6-11`), swaps the rendered control and calls `onChange`; parent forms clear the other field when switching (e.g. `ForConsumers/Form.tsx:43-57`).

**Custom ACL** (`Form/CustomACL/Form.tsx`, schema `CustomACL/schema.ts`): fields **Principal** (Input placeholder "Principal", required), **Host restriction** (placeholder "Host", required), **Resource type** (`ControlledSelect`, options = `KafkaAclResourceType` minus UNKNOWN, default first = TOPIC, `CustomACL/constants.ts:24-27,45-48`), **Operations** (`ControlledRadio` `permission` ALLOW green / DENY red + `ControlledSelect` `operation` = operations minus UNKNOWN, default ALL), **Matching pattern** (`ControlledRadio` `namePatternType` EXACT/PREFIXED + Input `resourceName` placeholder "Matching pattern"). Validation: every field `string().required()` (`schema.ts:3-11`). Request mapping `CustomACL/lib.ts:6-23` (EXACT -> LITERAL). Note: `permission` and `namePatternType` have no default value, so the form is invalid until the user picks them. Mutation `useCreateCustomAcl` (`acl.ts:50-69`).

**For Consumers** (`Form/ForConsumers/Form.tsx`, schema `ForConsumers/schema.ts`): **Principal** (required), **Host restriction** (required), **From Topic(s)** (`MatchTypeSelector`: exact = `ControlledMultiSelect topics` from `useTopicsOptions`; prefixed = Input `topicsPrefix` placeholder "Prefix..."), **Consumer group(s)** (exact = `ControlledMultiSelect consumerGroups` from `useConsumerGroupsOptions`; prefixed = Input `consumerGroupsPrefix`). Validation: `principal`, `host` required; topics/consumerGroups arrays of `{label,value}` optional; prefixes optional strings (`schema.ts:3-20`). Mapping `ForConsumers/lib.ts`. Mutation `useCreateConsumersAcl` (`acl.ts:71-90`).

**For Producers** (`Form/ForProducers/Form.tsx`, schema `ForProducers/schema.ts`): **Principal**, **Host restriction** (both required), **To Topic(s)** (exact multi-select `topics` / prefixed `topicsPrefix`), **Transaction ID** (exact Input `transactionalId` placeholder "Transactional ID" / prefixed Input `transactionsIdPrefix`), **Idempotent** checkbox with hint "Check it if using enable idempotence=true" (`:102-106`). Validation: principal/host required; rest optional; `idempotent: boolean()` (`schema.ts:3-16`). Mapping `ForProducers/lib.ts`. Mutation `useCreateProducerAcl` (`acl.ts:92-111`).

**For Kafka Stream Apps** (`Form/ForKafkaStreamApps/Form.tsx`, schema `ForKafkaStreamApps/schema.ts`): **Principal**, **Host restriction** (required), **From topic(s)** (`ControlledMultiSelect inputTopics`, required), **To topic(s)** (`ControlledMultiSelect outputTopics`, required), **Application.id** (Input placeholder "Application ID", required) (`schema.ts:3-9`). Mapping `ForKafkaStreamApps/lib.ts` (maps option values). Mutation `useCreateStreamAppAcl` (`acl.ts:113-132`).

- Option sources: `lib/useTopicsOptions.ts` (`useTopics({clusterName})` -> `{label,value}` by topic name), `lib/useConsumerGroupsOptions.ts` (`useConsumerGroups({clusterName, search:'', fts})` -> group ids).
- Real-time: none.

---

# KUI information architecture proposal

This section turns the Kafbat inventory above (and the Kouncil inventory in
`research/kouncil/ui-analysis.md`) into a concrete screen list for KUI, mapped to the
microfrontends, with the navigation model driven by the capability registry and an explicit degraded-state UX for every feature.

Vocabulary used below: a **capability** is a `(service, cluster)` pair whose
state is `Available | Degraded(reason) | Unavailable(reason, since)`. A **feature** is a
frontend module (`KuiFeature`) that declares `requiredCapabilities`. A feature's *effective
state* is the worst state among its required capabilities for the *current cluster*.

## IA.1 Screen list mapped to microfrontends

Routes keep Kafbat's `/ui/clusters/:cluster/...` shape so bookmarks and muscle memory from
Kafbat carry over (Kafbat's route table: `lib/paths.ts`). Column and action inventories are
in the sections above; this table records only *where each screen lives* and *what gates it*.

| # | Screen | Route (`/ui/clusters/:cluster` prefix unless noted) | Microfrontend | Required capabilities | Source of the pattern |
|---|--------|-----------------------------------------------------|---------------|-----------------------|-----------------------|
| 1 | Sign-in | `/login` (no cluster) | `kui-ui-shell` | none (gateway only) | Kafbat `AuthPage`; Kouncil SSO/form login |
| 2 | Dashboard (all clusters) | `/ui` | `kui-ui-clusters` | `cluster` (per row; partial by design) | Kafbat `Dashboard` |
| 3 | Cluster overview | `/` (redirect → brokers) | `kui-ui-clusters` | `cluster` | Kafbat `ClusterPage` |
| 4 | Brokers list | `/brokers` | `kui-ui-clusters` | `cluster` | Kafbat `BrokersList` |
| 5 | Broker details: log dirs / configs / metrics tabs | `/brokers/:id[/configs|/metrics]` | `kui-ui-clusters` (metrics tab delegates to `kui-ui-metrics`) | `cluster`; `metrics` for the metrics tab only | Kafbat `Broker` |
| 6 | Topics list | `/all-topics` | `kui-ui-topics` | `topics` | Kafbat `Topics/List`; Kouncil favourites |
| 7 | Create topic / Copy topic | `/all-topics/create-new-topic`, `/all-topics/copy` | `kui-ui-topics` | `topics` (write) | Kafbat `Topics/New` |
| 8 | Topic details: Overview / Consumers / Settings / Statistics tabs | `/all-topics/:topic[/consumer-groups|/settings|/statistics]` | `kui-ui-topics` (Consumers tab renders a `kui-ui-consumers` panel; Statistics requires `topic-analysis`) | `topics`; `consumers` (tab); `topic-analysis` (tab) | Kafbat `Topic/Details` |
| 9 | Edit topic settings | `/all-topics/:topic/edit` | `kui-ui-topics` | `topics` (write) | Kafbat `Topic/Edit` |
| 10 | Message browser (list view) | `/all-topics/:topic/messages` | `kui-ui-messages` | `messages`; `schemas` only to *suggest* serdes | Kafbat `Topic/Messages` |
| 11 | Message browser (table view with JSON-flattened columns) | `/all-topics/:topic/messages?view=table` | `kui-ui-messages` | `messages` | Kouncil topic table |
| 12 | Smart filter editor + saved filters | modal inside 10/11 | `kui-ui-messages` | `messages` (filter test endpoint) | Kafbat `Filters/AddEditFilterContainer` |
| 13 | Produce message | side panel inside 10/11 | `kui-ui-messages` | `messages` (write); `schemas` for schema-aware form | Kafbat `SendMessage`; Kouncil feat-send |
| 14 | Resend / copy events | dialog inside 10/11 | `kui-ui-messages` | `messages` (read + write) | Kouncil resend-events |
| 15 | Event tracking | `/track` | `kui-ui-messages` | `messages` | Kouncil track |
| 16 | Consumer groups list | `/consumer-groups` | `kui-ui-consumers` | `consumers` | Kafbat `ConsumerGroups/List` |
| 17 | Consumer group details + lag monitoring | `/consumer-groups/:group` | `kui-ui-consumers` | `consumers` | Kafbat `Details`; Kouncil consumer monitoring |
| 18 | Reset offsets wizard | `/consumer-groups/:group/reset-offsets` | `kui-ui-consumers` | `consumers` (write) | Kafbat `ResetOffsets` |
| 19 | Schema registry list / details / versions / compare | `/schemas[/:subject[/compare]]` | `kui-ui-schemas` (plugin) | `schemas` | Kafbat `Schemas` |
| 20 | Create / edit schema | `/schemas/create-new`, `/schemas/:subject/edit` | `kui-ui-schemas` | `schemas` (write) | Kafbat `Schemas/New`, `Edit` |
| 21 | Kafka Connect: connectors list | `/connectors` | `kui-ui-connect` (plugin) | `connect` | Kafbat `Connect/List` |
| 22 | Connector details: overview / tasks / config | `/connects/:connect/connectors/:connector[/tasks|/config]` | `kui-ui-connect` | `connect` | Kafbat `Connect/Details` |
| 23 | New connector | `/connectors/create-new` | `kui-ui-connect` | `connect` (write) | Kafbat `Connect/New` |
| 24 | ksqlDB: tables & streams / query | `/ksqldb[/query]` | `kui-ui-ksql` (plugin) | `ksql` | Kafbat `KsqlDb` |
| 25 | ACLs list / create (custom, consumer, producer, stream) | `/acl` | `kui-ui-security` | `acl` | Kafbat `ACLPage` |
| 26 | Quotas | `/quotas` | `kui-ui-security` | `quotas` (post-M8 if not in service catalog) | new |
| 27 | Cluster metrics graphs | `/metrics` | `kui-ui-metrics` | `metrics` | Kafbat metrics tab + Kouncil JMX stats |
| 28 | Cluster config wizard (create / edit cluster) | `/ui/clusters/create-new-cluster`, `/config` | `kui-ui-admin` | `admin-config` (gateway-local) | Kafbat `ClusterConfigForm` |
| 29 | Application config / restart | `/ui/config` | `kui-ui-admin` | `admin-config` | Kafbat `ClusterConfigPage` + restart |
| 30 | RBAC view (who can do what) | `/ui/admin/rbac` | `kui-ui-admin` | none (gateway) | new (Kafbat exposes it only via 403s) |
| 31 | Audit log viewer | `/ui/admin/audit` | `kui-ui-admin` | `audit` | new |
| 32 | User settings (theme, timezone, refresh rate, table density) | `/ui/settings` | `kui-ui-shell` | none | Kafbat NavBar `UserTimezone`, theme toggle |
| 33 | 403 / 404 / feature-fallback panels | `/403`, `/404`, any route | `kui-ui-shell` | none | Kafbat `ErrorPage` |

Rules encoded in the table:

- Every screen that mutates state lists the capability with "(write)". Write actions are gated
  twice: by RBAC (button disabled with a permission tooltip, Kafbat `ActionComponent` pattern)
  and by capability state (Degraded → allowed with warning; Unavailable → disabled with reason).
- A tab hosted inside another feature's page (topic Consumers tab, broker Metrics tab) is
  *rendered by the owning feature* via the kernel's `FeaturePanel` slot, so the host page
  never imports the guest feature's code and never breaks when the guest is Unavailable.

## IA.2 Navigation model driven by the capability registry

The shell owns the sidebar; features contribute `NavEntry` values. The shell subscribes to
`GET /api/v1/capabilities` + `/api/v1/capabilities/stream` (SSE) and derives, per cluster, one
`Signal[FeatureState]` per feature.

```
FeatureState = Ready | Degraded(reason) | Unavailable(reason, since) | Forbidden | NotConfigured
```

- `NotConfigured` is *not* a health state: it means the cluster has no schema registry / connect
  / ksql configured. Kafbat handles only this case (hides the entry entirely:
  `components/Nav/ClusterMenu/ClusterMenu.tsx:35-36, 93-119`). KUI keeps that behaviour: an
  entry that is `NotConfigured` is **hidden**, because showing it would be noise.
- `Forbidden` comes from the RBAC endpoint (`/api/v1/authorization`), not the registry. Entry is
  shown but disabled with the tooltip "You do not have permission to view X" so users learn what
  exists and whom to ask.
- `Unavailable` entries are **shown, dimmed, still clickable** (the original proposal called for
  disabling with the reason shown; the ADR candidate below argues for clickable-to-fallback because a disabled link gives
  no place to display `since`, the reason, and a retry). The click lands on the feature's
  fallback panel, never on a blank page.
- `Degraded` entries show a small amber dot; hovering explains the reason.

Sidebar structure (one section per cluster, collapsible, colour tag per cluster kept from
Kafbat `ClusterMenu.tsx:44-47`):

```
▸ [●] prod-eu                       ← cluster status pill (online / offline / partial)
    Brokers                          kui-ui-clusters
    Topics                           kui-ui-topics
    Consumers                        kui-ui-consumers
    Schema Registry        ⚠         kui-ui-schemas   (Degraded: "SR responding slowly, p95 4.2s")
    Kafka Connect          ○         kui-ui-connect   (Unavailable since 12:04: circuit open)
    ksqlDB                           kui-ui-ksql
    ACL                              kui-ui-security
    Metrics                          kui-ui-metrics
    Track events                     kui-ui-messages
▸ [●] staging
Dashboard                            kui-ui-clusters
Admin ▸ Config wizard · RBAC · Audit kui-ui-admin
```

Cluster switcher: the sidebar *is* the switcher (Kafbat model). A compact dropdown in the top
bar duplicates it for narrow screens; breadcrumbs (Kouncil `feat-breadcrumb`) show
`cluster › feature › resource › tab`. A global capability banner at the top of the content area
(Kouncil `banner` pattern) appears only when the *current cluster* has ≥1 Unavailable
capability: "2 services unavailable on prod-eu — Kafka Connect (circuit open, since 12:04),
Metrics (readiness failing). [Details]".

Route registration: features register `FeatureRoute`s with the Waypoint router at init. The shell
wraps every feature route in `FeatureGate(featureId)`, which renders the feature's page when
`Ready | Degraded`, and the feature's `unavailableView(reason)` otherwise. A feature that was
never loaded (option B lazy modules) gets the shell's generic fallback until its module arrives.

## IA.3 Degraded-state UX per feature

Common rules first (apply to every row):

1. **Never a blank page, never an app-wide error boundary.** Each feature route renders inside
   its own error boundary; a thrown error shows the feature fallback panel with "Reload feature".
2. **Reason display.** Fallback panel = icon + "X is unavailable on <cluster>" + reason string
   from the registry + `since` (relative, "since 12:04 (8 min)") + "Retry now" (forces a readiness
   probe via `POST /api/v1/capabilities/{service}/probe`, an ADR candidate) + "What still works"
   list. Degraded = amber inline banner at the top of the page with reason, page remains fully
   usable.
3. **Cached data.** When data was previously fetched in this session, Unavailable shows the
   stale data greyed with a "Last updated 12:03 — service unavailable" badge instead of the empty
   fallback; actions remain disabled. This is what makes "the UI stays usable" true
   in practice.
4. **Partial aggregates.** Pages that combine sections (dashboard, topic details) render each
   section with its own status. A failed section becomes a card-sized fallback, not a
   page-level one.

| Feature / screen | Unavailable (service down or circuit open) | Degraded (slow, partial, half-open) |
|------------------|--------------------------------------------|-------------------------------------|
| Shell, sign-in, settings | Only the gateway is required. If the gateway is unreachable the shell shows a full-screen "Cannot reach KUI gateway" with auto-retry countdown; this is the *only* full-screen state in the product. | n/a |
| Dashboard | Each cluster row carries its own status. Rows whose `cluster` capability is Unavailable render name + status pill "Unavailable: <reason>" and dashes in metric cells; row remains clickable to that cluster's brokers page (which shows its own fallback). Filter "Show unavailable only" (Kafbat's "Offline only" toggle, `Dashboard.tsx:26-39`). | Row shows amber pill; metrics that came back render; missing ones show "—" with tooltip. |
| Brokers list / details | Fallback panel. Cached list shown greyed if present. Config edit disabled. | Page renders; inline config edit stays enabled but confirm dialog warns "cluster service is degraded: <reason>; the change may time out". |
| Topics list | Fallback panel with cached list greyed if present. Search/sort work on cached rows (client-side). Batch actions and Create disabled. | Full page; Create/Delete/Purge enabled with warning banner. |
| Create / edit / copy topic | Form opens but Submit disabled with reason; fields still editable so the user can prepare the form. | Submit enabled; warning banner. |
| Topic details | Overview section: fallback card. Tabs that belong to other features (Consumers, Statistics) are evaluated independently: a Consumers tab whose `consumers` capability is Unavailable shows the tab with a dimmed dot and the tab body is a card fallback. | Amber banner on the tab that is degraded. |
| Message browser (list and table) | Fallback panel. Filter bar is visible but the Fetch/Live buttons are disabled with reason. Saved smart filters remain editable (they are local). Previously fetched messages stay visible greyed with "stream ended: service unavailable". | Browser works; the SSE phase indicator shows "degraded: <reason>"; time/byte budget warnings surface from the stream (`Consumed(stats)` events). |
| Live (tailing) mode | If the capability flips to Unavailable mid-stream, the stream is closed, rows keep their content, a toast says "Live mode stopped: message service unavailable", and the Live toggle turns off (not auto-resumed; the user re-enables). | Stream continues; header pill turns amber. |
| Produce / resend | Side panel opens; Send disabled with reason. Draft preserved in feature state. | Send enabled; warning. |
| Event tracking | Inputs editable; Track disabled with reason. Results from an earlier run stay on screen. | Runs; banner. |
| Consumer groups list / details / lag | Fallback with cached rows greyed. Lag charts freeze with a "paused" watermark. Delete disabled. | Live lag polling continues at a slower interval (registry can carry a `suggestedPollIntervalMs` in the Degraded reason payload — ADR candidate). |
| Reset offsets wizard | Wizard cannot be opened; button disabled with reason (offset reset needs an exact live view of the group). | Opens; the final confirm dialog repeats the degraded reason. |
| Schema registry | Sidebar dimmed; feature fallback. **Cross-feature effect:** the message browser must not break: the serde picker lists only serdes that do not need SR, and the "suggested serde" chip shows "SR unavailable" instead of a suggestion. Produce with a schema-aware form falls back to raw JSON editor. | Amber banner; compatibility level select shows the last known value with a "may be stale" hint. |
| Kafka Connect | Sidebar dimmed; fallback. Connector task actions disabled. | Banner; actions enabled; "restart" confirm warns. |
| ksqlDB | Fallback; query editor stays visible and editable (users draft queries), Execute disabled. | Execute enabled; result stream shows degraded pill. |
| ACLs / quotas | Fallback with cached rows greyed. Create/Delete disabled. | Banner. |
| Metrics | Graph panels render as empty cards with the reason; the broker Metrics tab shows the card fallback while other broker tabs work. | Graphs render; gaps in series are drawn as gaps (never interpolated), with a legend note "data gap: <reason>". |
| Admin: config wizard | Wizard requires only the gateway; always available. Validation of a cluster config ("Validate" step, Kafbat `ClusterConfigForm`) that needs cluster/SR/connect probes reports each probe's result independently. | n/a |
| Admin: audit | Fallback. | Banner. |

How reasons are shown (single rule for all of the above): the registry reason string is shown
verbatim, prefixed by the state word, in three places at most: sidebar tooltip, page banner or
fallback panel, and the disabled action's tooltip. Reasons are never turned into toasts on
their own (toasts are for user-initiated actions); the state *transition* Available→Unavailable
raises one toast per feature per cluster, deduplicated for the session.

## IA.4 Shared design-system components the kernel must provide

Derived from the Kafbat `components/common/*` inventory above plus what the degraded-state UX
needs. Items marked **(new)** have no Kafbat equivalent.

Layout and navigation
- `AppLayout` (sidebar + top bar + content + banner slot), `Sidebar`, `ClusterMenu`
  (collapsible, colour tag, status pill), `Breadcrumbs`, `Tabs` (Navigation), `PageHeading` /
  `ResourcePageHeading` (title, tags, actions area), `SlidingSidebar` (side panel used by
  produce/filters), `Portal`.
- **(new)** `FeatureGate`, `FeatureFallbackPanel`, `CapabilityBanner`, `CapabilityBadge`
  (Ready/Degraded/Unavailable/Forbidden dot with tooltip), `StaleDataOverlay`.

Data display
- `DataTable` (TanStack-equivalent: sortable columns, server/client pagination, row selection with
  indeterminate checkbox, expandable rows, column visibility, sticky header) plus
  `VirtualizedTable` for messages/topics/groups (KUI's own-virtualization rule).
- **(new)** `FlattenedJsonColumns` helper for the Kouncil-style table view (column derivation
  from JSON paths, column picker, per-column filter).
- `Metrics` (Section/Indicator/Light), `Statistics`, `PropertiesList`, `Tag`, `AlertBadge`,
  `ProgressBar`, `Ellipsis`, `BytesFormatted`, `DurationFormatted`, `Tooltip`, `DiffViewer`,
  `EditorViewer` (read-only code), `Editor` (code editor facade over a JS editor lib),
  `SQLEditor`, `Spinner`, `PageLoader`, `EmptyState` (Kouncil `feat-no-data` pattern),
  `Skeleton`.

Input and forms
- `Form` primitives with a validation model shared with Tapir/Iron constraints:
  `Input` (with `min/max/step`, byte and ms suffix variants), `Textbox`, `Select`,
  `MultiSelect`, `InputWithOptions` (combobox), `Checkbox`, `IndeterminateCheckbox`, `Radio`,
  `Switch`, `Search` (debounced, URL-synced), `RefreshRateSelect`, `DurationInput`,
  `DateTimeInput` (timezone-aware, uses the user's timezone setting), `KeyValueListEditor`
  (headers, custom params), `FormErrorSummary`.
- **(new)** `SchemaDrivenForm` (JSON Schema / Avro → form, Kouncil feat-send pattern) — optional,
  lives in `kui-ui-schemas` but the kernel provides the field primitives it composes.

Actions and feedback
- `Button` (primary/secondary/danger, sizes), `Dropdown` + `DropdownItem`, `ActionButton`,
  `ActionDropdownItem`, `ActionNavLink`, `ActionSelect`, `ActionPermissionWrapper` — the
  permission wrapper takes **both** an RBAC decision and a `FeatureState` and produces one
  tooltip.
- `ConfirmationModal` via a `ConfirmContext`-style kernel service (promise-returning
  `confirm(message, {danger, requireTyping})`), `Modal`, `Toast` bus (`notify.success/
  error/info`, deduplication key), `Alert` inline.
- `DownloadButton` (CSV/JSON; must work without `<a download>` in restricted hosts),
  `CopyToClipboard`.

State and infrastructure (non-visual, still kernel)
- `AuthState` (`Var[UserInfo]`), `CapabilityState` (`Signal[Map[(Service, Cluster), State]]`,
  fed by SSE with reconnect/backoff), `CurrentCluster`, `NotificationBus`,
  `SseStream` (Airstream wrapper over `EventSource` with phase events and cancellation),
  `ApiClient` base (sttp JS backend, auth header, correlation id, 401/403 interceptors),
  `LocalPrefs` (typed localStorage: theme, timezone, saved filters, column layouts, sidebar
  open state — Kafbat keeps all of these in localStorage today), `UrlState` (query-param sync
  for filters/pagination), `Theme` tokens (light/dark, Kafbat has both; Kouncil dark/light too).

## Decision candidates

**DC-H1 — Sidebar entries of Unavailable features are clickable and lead to a fallback panel,
not disabled links.**
Evidence: the original proposal called for showing entries disabled with the reason; a disabled `<a>` has nowhere to put
`since`, a retry action, or a "what still works" list, and Kafbat's own model
(`ClusterMenu.tsx:93-119`) hides the entry outright, which users of Kafbat report as confusing
when SR is merely misconfigured. Tradeoff: one extra click to discover the reason vs a
tooltip-only explanation; slightly more shell code. Reversibility: high (a flag in `NavEntry`).

**DC-H2 — `NotConfigured` is a distinct state, hidden from navigation; `Unavailable` is
always shown.**
Evidence: Kafbat's `features[]` on `Cluster` is exactly "configured or not"; conflating it with
health would make every cluster without ksqlDB look broken. Tradeoff: the registry must
distinguish "no config" from "config present, probe failing", which the cluster service must
report. Reversibility: high.

**DC-H3 — Stale data stays on screen (greyed, timestamped) when a feature becomes Unavailable.**
Evidence: KUI's "UI stays usable" principle; Kafbat drops to an error state on any 5xx via react-query
error boundaries. Tradeoff: feature-local state must retain the last successful response and its
timestamp (small); risk of users acting on stale rows is mitigated by disabling all actions.
Reversibility: medium (touches every feature's state design; decide before M1).

**DC-H4 — Message browser ships two views from M2: list (Kafbat) and table with JSON-flattened
columns (Kouncil), sharing one stream and one filter bar.**
Evidence: Kouncil's table view is the single most-cited differentiator in its docs
(`research/kouncil/ui-analysis.md`); the flattening is a pure client-side transform over the
same `MessageEvent` stream, so no backend change. Tradeoff: two renderers to maintain; the
virtualized table must support dynamic columns. Reversibility: high (view switch is a query
param).

**DC-H5 — Permission gating and capability gating share one `ActionPermissionWrapper` with a
single merged tooltip.**
Evidence: Kafbat's `ActionComponent/*` already centralises RBAC gating with a tooltip; adding a
second wrapper for capability state would produce nested tooltips and inconsistent styling.
Tradeoff: the wrapper needs both the RBAC signal and the capability signal (both are kernel
`Var`s, so no feature coupling). Reversibility: high.

**DC-H6 — Cross-feature panels (topic → consumers tab, broker → metrics tab) are rendered through
a kernel `FeaturePanel` slot keyed by feature id, never by direct import.**
Evidence: fault isolation is only real if the topic page compiles and renders without
the consumers module; Kafbat imports `ConsumerGroups` components directly into
`Topic/Details`, so an error there takes the topic page down. Tradeoff: an indirection and a
registry of panel ids; slightly weaker compile-time coupling between features (still typed via
the kernel's panel contract). Reversibility: medium (affects option B module boundaries).

**DC-H7 — The Degraded reason payload is structured (`code`, `message`, optional
`suggestedPollIntervalMs`, optional `p95Ms`), not a plain string.**
Evidence: the consumer-lag and metrics screens need to adapt polling; a string cannot drive that.
Tradeoff: slightly bigger registry contract (Tapir schema, so typed on both sides).
Reversibility: high before M1, low after (contract).

## Open questions

- Should `Forbidden` entries be shown-disabled (proposed) or hidden, given some deployments treat
  the existence of a Connect cluster as sensitive? Candidate: a global setting `rbac.hideForbidden`.
- Does the capability registry SSE carry per-cluster deltas or full snapshots? Affects
  `CapabilityState` reducer complexity only.
- Event tracking (Kouncil) needs a bounded multi-topic scan in `kui-message-service`; confirm it
  fits the `browse` stream contract or needs a sibling endpoint before assigning it
  to `kui-ui-messages` in M2.

## Confidence

**High** for the Kafbat inventory (read from source, cited by line) and for the mapping to
microfrontends. **Medium** for the degraded-state UX table: it is a design
proposal grounded in the two reference UIs, not in user testing; DC-H1 explicitly
deviates from the original disabled-link proposal and needs an ADR decision. **Medium** for the Kouncil parts
that depend on the Kouncil report's findings about backend endpoints (tracking, resend).
