/**
 * Which tab of the cluster dashboard is open, read from the address and from nowhere else.
 *
 * ## Why this is a module and not two lines inside the component
 *
 * `paths.dashboard(cluster, tab?)` defaults the tab in one place so that a hand-written link and
 * the tab strip cannot spell the same page two ways. This is the other end of that arrangement: the
 * *reading* has to default the same way, or `/dashboard` and `/dashboard/overview` would be one
 * address that highlights a tab and one that highlights none. Keeping the default and the tab list
 * beside each other is what makes it possible to check that they agree, which is a test rather than
 * a hope.
 *
 * ## Why an unrecognised tab is not an error
 *
 * The tab is a path segment, so it is user-editable, and somebody will type `/dashboard/overwiew`
 * or keep a bookmark to a tab that has since been removed. A 404 for a page that plainly exists, or
 * a blank body under a strip with nothing marked, are both worse than landing on the first tab: the
 * address is wrong and everything the reader came for is one glance away. So an unknown segment
 * resolves to the overview and the strip marks the overview, which is also what the strip's own
 * `aria-current` then tells a screen reader.
 *
 * ## Why there are three tabs and not the design's four
 *
 * SCREENS-V4.md §3.1 draws Overview · Traffic · Storage · Alerts, and its own "absent" rule is that
 * a tab whose data is not collected does not get drawn — "do not draw four tabs of the same
 * sentence". Traffic joined the list in wave 4 because `services/metrics` began answering a real
 * throughput series: the tab now has one card drawn from a broker metric and three that say what
 * they cannot measure, which is a tab with something on it rather than four segments of one
 * sentence. Alerts still needs an event store that does not exist, so it is still not drawn.
 */

/** The tabs the product can honestly draw today. Ordered as the strip draws them (§3.1). */
export const DASHBOARD_TABS = ["overview", "traffic", "storage"] as const;

export type DashboardTab = (typeof DASHBOARD_TABS)[number];

/**
 * The tab a missing or unrecognised segment means.
 *
 * The same string `shellPaths.dashboard` supplies when a caller omits the tab. If these two ever
 * disagree, `paths.dashboard(cluster)` builds an address whose strip marks a different tab from the
 * one its body draws, and nothing else in the product would notice.
 */
export const DEFAULT_DASHBOARD_TAB: DashboardTab = "overview";

/** What the route segment means. See the header: a typo is not an error state. */
export function dashboardTab(segment: string | undefined): DashboardTab {
  return DASHBOARD_TABS.find((tab) => tab === segment) ?? DEFAULT_DASHBOARD_TAB;
}
