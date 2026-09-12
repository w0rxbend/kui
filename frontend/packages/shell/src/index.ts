/**
 * The shell: the application frame, the navigation that lives in it, and the routing behind both.
 *
 * Two layers, deliberately separate. The *chrome* is told what to draw and fetches nothing, which is
 * what makes every one of its states — including the ones that only happen when a service is down —
 * reachable in Storybook and in a test. `App` is the composition root above it: it is the one place
 * that holds a client, a session and a capability store, and it hands the chrome plain data.
 */

export { App } from "./App.jsx";

export { BrandBlock, type BrandBlockProps } from "./chrome/BrandBlock.jsx";
export { Breadcrumb, type BreadcrumbProps } from "./chrome/Breadcrumb.jsx";
export { ClusterStatusCard, type ClusterStatusCardProps } from "./chrome/ClusterStatusCard.jsx";
export { NavDrawer, type NavDrawerProps } from "./chrome/NavDrawer.jsx";
export { NavItem, type NavItemProps } from "./chrome/NavItem.jsx";
export {
  SearchField,
  detectPlatform,
  shortcutHint,
  type SearchFieldProps,
  type SearchResult,
  type SearchResultGroup,
} from "./chrome/SearchField.jsx";
export { TabStrip, type TabStripProps } from "@kui/kernel";
export { TopBar, type ThemeMode, type TopBarProps } from "./chrome/TopBar.jsx";
export { healthWord } from "./chrome/types.js";
export type {
  BadgeTone,
  ClusterHealth,
  ClusterSummary,
  Crumb,
  NavBadge,
  NavCount,
  NavCounts,
  NavDestination,
  NavGroup,
  NavRank,
  NavState,
  Tab,
} from "./chrome/types.js";

/**
 * The routing and the navigation model, exported for the tests and for anything that has to build a
 * KUI address. Nothing here concatenates a URL: every path comes from the router's typed proxy, so a
 * renamed segment is a compile error rather than a link that quietly 404s.
 */
export {
  UiPath,
  clusterInUrl,
  createShellRouter,
  landingFor,
  shellRoutes,
  type RouteViews,
  type ShellRouter,
} from "./routing/routes.jsx";

export {
  CLUSTER_GROUP,
  ECOSYSTEM_GROUP,
  NAV_GROUP_ORDER,
  badgeOf,
  countBadge,
  degradedLabels,
  destinationFor,
  navigationGroups,
  stillWorking,
  type FeatureStatus,
  type NavigationInput,
} from "./nav/navigation.js";

/**
 * The drawer's topic tree, and the cluster the frame draws it for.
 *
 * Wave 1 wrote all of this and published none of it: nothing outside `nav/` and `data/` could
 * reach `prefixes`, the count vocabulary or the cluster store, so the drawer went on drawing
 * skeletons over a fold that was finished and tested. Publishing it is the first act of the packet
 * that wires the frame, and the split it implies is deliberate: the store's *behaviour* is owned
 * next to the store, in `data/clusterStore.ts`, and only its name is published here. A consumer
 * that needs to change what `countsOf` refuses changes that file, not this line.
 *
 * `countFor` is not a function and so is not on this list: it is the field on
 * {@link NavigationInput} through which a caller supplies the figures, and it reaches callers with
 * that type. `badgeOf` and `countBadge` above are the rules it feeds.
 */
export {
  INTERNAL_GROUP,
  MAX_PREFIX_GROUPS,
  OTHER_GROUP,
  isInternalTopic,
  prefixes,
  type PrefixGroup,
} from "./nav/prefixes.js";
/* `TopicTreeInput` is not on this line, and that is the correction rather than an omission. It was
   published for two waves and named by nothing outside its own declaration and the two signatures
   that take it — `App.tsx` calls the fold with an object literal, and so does its test — so the
   export was a promise to a consumer that never arrived. It stays exported from its own module,
   which is what the signatures' declaration emit needs, and off the shell's public surface. */
export { topicGroupHref, topicSubtree, topicTree } from "./nav/topicTree.js";
export {
  brokerStorageOf,
  createClusterStore,
  type ClusterFacts,
} from "./data/clusterStore.js";
export {
  StorageMeter,
  brokerState,
  type BrokerStorage,
  type StorageMeterProps,
} from "./chrome/StorageMeter.jsx";
export {
  AppearancePopover,
  type AppearancePopoverProps,
  type AppearancePreferences,
} from "./chrome/AppearancePopover.jsx";
/**
 * The words for the three appearance preferences, published once.
 *
 * There are two controls over these three preferences — the top bar's popover and the settings page
 * — and each carried its own option list until now. They had already drifted on the option that
 * most needed explaining: the popover called the default theme "Auto" and the settings page called
 * it "Match the system", which is one preference under two names and no way for a reader to tell
 * that it is one preference. Both draw this table now, and a third control added later takes it
 * too.
 */
export {
  ACCENT_OPTIONS,
  DENSITY_OPTIONS,
  THEME_OPTIONS,
  appearanceHelp,
  type AppearanceOption,
} from "./chrome/appearance.js";
export {
  NotificationBell,
  NotificationPanel,
  type Notice,
  type NoticeCategory,
  type NoticeFeed,
  type NoticeSeverity,
} from "./chrome/Notifications.jsx";
export { EnvRail, tileLetter, type EnvRailProps, type RailDestination } from "./chrome/EnvRail.jsx";

export { featureRegistry, registrationOf } from "./features/registry.js";
export { FeatureGate, type FeatureGateProps } from "./features/FeatureGate.jsx";
export { FallbackPanel, relative, type FallbackPanelProps } from "./features/FallbackPanel.jsx";
export { ForbiddenPage, GatewayUnreachablePage, NotFoundPage, countdown } from "./pages/errorPages.jsx";
export {
  createHealth,
  backoffAfter,
  FailuresBeforeGivingUp,
  FirstBackoffMs,
  MaxBackoffMs,
  type CallScope,
  type Connectivity,
  type Health,
} from "./health.js";
export * from "./messages.js";
export { readBootstrap, bootstrapElementId, type Bootstrap } from "./bootstrap.js";
