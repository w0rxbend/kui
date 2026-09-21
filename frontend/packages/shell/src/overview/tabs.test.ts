/**
 * The tab vocabulary, and the one agreement it has to keep.
 *
 * `paths.dashboard(cluster)` supplies the tab when a caller omits it, and `dashboardTab(undefined)`
 * decides what the omission means when the address comes back. Those are two defaults in two files,
 * and nothing in the type system connects them: if they ever disagree, `paths.dashboard(cluster)`
 * builds an address whose strip marks one tab and whose body draws another, and every other test in
 * this package goes on passing. So the agreement is asserted directly, against the real router.
 */

import { describe, expect, it } from "vitest";

import { createShellRouter } from "../routing/routes.jsx";
import { shellPaths } from "../routing/paths.js";
import { DASHBOARD_TABS, DEFAULT_DASHBOARD_TAB, dashboardTab } from "./tabs.js";

const noop = () => null;
const views = { home: noop, settings: noop, forbidden: noop, notFound: noop, feature: () => noop };

describe("which tab an address means", () => {
  it("reads the tabs the product can draw", () => {
    expect(dashboardTab("overview")).toBe("overview");
    expect(dashboardTab("traffic")).toBe("traffic");
    expect(dashboardTab("storage")).toBe("storage");
  });

  it("resolves an omitted tab to the default, not to nothing", () => {
    expect(dashboardTab(undefined)).toBe(DEFAULT_DASHBOARD_TAB);
  });

  it("resolves a tab nobody has to the default, because the segment is user-editable", () => {
    // A typo, a bookmark to a tab that has been removed, and a tab that has not shipped yet all
    // arrive here. None of them is an error state and none of them is a blank body.
    for (const nonsense of ["overwiew", "trafic", "alerts", "", "../../etc"]) {
      expect(dashboardTab(nonsense)).toBe(DEFAULT_DASHBOARD_TAB);
    }
  });

  it("agrees with the default `paths.dashboard` supplies", () => {
    const paths = shellPaths(createShellRouter("", views));
    expect(paths.dashboard("prod")).toBe(paths.dashboard("prod", DEFAULT_DASHBOARD_TAB));
  });

  it("has an address for every tab it draws", () => {
    const paths = shellPaths(createShellRouter("", views));
    for (const tab of DASHBOARD_TABS) {
      expect(paths.dashboard("prod", tab)).toBe(`/ui/clusters/prod/dashboard/${tab}`);
    }
  });
});
