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
export { ClusterSelector, type ClusterSelectorProps } from "./chrome/ClusterSelector.jsx";
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
export { TabStrip, type TabStripProps } from "./chrome/TabStrip.jsx";
export { TopBar, type ThemeMode, type TopBarProps } from "./chrome/TopBar.jsx";
export type {
  BadgeTone,
  ClusterHealth,
  ClusterSummary,
  Crumb,
  NavBadge,
  NavDestination,
  NavGroup,
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
  degradedLabels,
  destinationFor,
  navigationGroups,
  stillWorking,
  type FeatureStatus,
  type NavigationInput,
} from "./nav/navigation.js";

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
