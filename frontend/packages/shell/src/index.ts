/**
 * The shell: the application frame and the navigation that lives in it.
 *
 * What is exported here is the chrome — the parts of the screen that are the same whatever page you
 * are on. None of them fetches anything: they are told what to draw, which is what makes every one
 * of their states, including the ones that only happen when a service is down, reachable in
 * Storybook and in a test.
 */

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
