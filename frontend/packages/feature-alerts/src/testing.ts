/**
 * Mounting and axe, for the alerts screens' tests.
 *
 * The same eight lines as the other six copies in this workspace. It is copied rather than shared
 * because a feature must not depend on another feature and the kernel's copy is not exported from
 * `@kui/kernel` — deliberately, since a test helper in a product bundle is dead weight the browser
 * downloads. The kernel's copy carries the full reasoning; two rules are repeated here because they
 * are the ones a Solid 2 test fails on:
 *
 * - `flush()` before asserting. A setter queues and the DOM catches up on the next microtask, so a
 *   test that asserts immediately reads the previous value and fails as though the component were
 *   broken.
 * - Dispose at the end. Reactive primitives need an owner; leaving one alive leaks listeners
 *   between cases, and the symptom is a later test failing because of an earlier one.
 *
 * Counted rather than remembered: grepping the workspace for `export function mount(component`
 * matched **seven** files before this one and eight with it. `feature-schemas`' copy records that
 * seven was already well past the point where a `@kui/testing` package was the answer, and that a
 * package is not a feature packet's to add — `frontend/package.json` and the workspace list are
 * unowned this wave as they were last wave. The count and the command are written down so the next
 * packet neither rediscovers the reason nor inherits a stale number.
 */

import { render } from "@solidjs/web";
import type { JSX } from "@solidjs/web/jsx-runtime";
import axe from "axe-core";
import type { KuiApiClient } from "@kui/api";
import type { KuiContextValue } from "@kui/kernel";

export interface Mounted {
  readonly container: HTMLElement;
  readonly dispose: () => void;
}

export function mount(component: () => JSX.Element): Mounted {
  const container = document.createElement("div");
  document.body.appendChild(container);
  const dispose = render(component, container);
  return {
    container,
    dispose: () => {
      dispose();
      container.remove();
    },
  };
}

/**
 * Runs axe and returns the violations.
 *
 * `color-contrast` is off because jsdom has no layout and no painted colours: it reports every
 * element as black on transparent and then produces confident nonsense. Contrast is proved by the
 * token-level contrast suite and by looking at the stories.
 */
export async function findViolations(container: HTMLElement): Promise<axe.Result[]> {
  const results = await axe.run(container, { rules: { "color-contrast": { enabled: false } } });
  return results.violations;
}

export function describeViolations(violations: axe.Result[]): string {
  return violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.html).join(", ")})`).join("\n");
}

/* ---------------------------------------------------------------------------------------------- */
/* The feature context                                                                              */
/* ---------------------------------------------------------------------------------------------- */

/**
 * What `useKui()` answers inside a test.
 *
 * `permits` takes the whole predicate rather than a boolean, because this screen's one write is
 * gated on `ALERTS:ACKNOWLEDGE` and a single boolean cannot tell "may not acknowledge" from "may not
 * see the cluster at all" — the shape wave 5 found ungated across two feature packages. A case that
 * wants an unpermitted principal passes a predicate that answers `false` for that one action and
 * `true` for everything else, so what it is asserting is unambiguous.
 */
export function testContext(
  api: KuiApiClient,
  permits: (action: { readonly resource: string; readonly action: string }) => boolean =
    () => true,
): KuiContextValue {
  return {
    api,
    cluster: () => undefined,
    permits: (action) => permits(action),
    paths: {
      home: () => "/ui",
      settings: () => "/ui/settings",
      clusters: () => "/ui/clusters",
      manageClusters: () => "/ui/clusters/manage",
      dashboard: (cluster, tab) => `/ui/clusters/${cluster}/dashboard/${tab ?? "overview"}`,
      brokers: (cluster) => `/ui/clusters/${cluster}/brokers`,
      broker: (cluster, brokerId) => `/ui/clusters/${cluster}/brokers/${brokerId}`,
      topics: (cluster) => `/ui/clusters/${cluster}/topics`,
      topic: (cluster, name) => `/ui/clusters/${cluster}/topics/${encodeURIComponent(name)}`,
      topicMessages: (cluster, name) =>
        `/ui/clusters/${cluster}/topics/${encodeURIComponent(name)}/messages`,
      trackMessages: (cluster) => `/ui/clusters/${cluster}/messages/track`,
      consumerGroups: (cluster) => `/ui/clusters/${cluster}/consumer-groups`,
      consumerGroup: (cluster, groupId) =>
        `/ui/clusters/${cluster}/consumer-groups/${encodeURIComponent(groupId)}`,
    },
    report: () => {},
  };
}
