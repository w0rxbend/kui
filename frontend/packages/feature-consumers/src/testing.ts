/**
 * Mounting and axe, for the consumer screens' tests.
 *
 * The same eight lines as `packages/kernel/src/components/testing.ts` and
 * `packages/shell/src/chrome/testing.ts`. It is copied a third time rather than shared because a
 * feature must not depend on another feature and the kernel's copy is not exported from
 * `@kui/kernel` — deliberately, since a test helper in a product bundle is dead weight the browser
 * downloads. The kernel's copy carries the full reasoning; the two rules are repeated here because
 * they are the ones a Solid 2 test fails on:
 *
 * - `flush()` before asserting. A setter queues and the DOM catches up on the next microtask, so a
 *   test that asserts immediately reads the previous value and fails as though the component were
 *   broken.
 * - Dispose at the end. Reactive primitives need an owner; leaving one alive leaks listeners
 *   between cases, and the symptom is a later test failing because of an earlier one.
 *
 * If a fourth copy is ever wanted, that is the moment to make a `@kui/testing` package.
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
/* The feature context                                                                             */
/* ---------------------------------------------------------------------------------------------- */

/**
 * What `useKui()` answers inside a test.
 *
 * `useKui`'s own error message points here: the shell provides the context around every route, so a
 * test that mounts a *route* rather than a component has to provide one. Mounting the route is the
 * point — `GroupsScreen` is where the page number, the server's total and the mapping meet, and a
 * test that drives `GroupList` alone cannot see any of that wiring.
 *
 * The api is supplied by the caller and everything else is the smallest honest answer: `paths`
 * builds the one address this screen links to, and there is no `report` behaviour because the
 * shell's connectivity tracker is not mounted.
 *
 * `permits` defaults to yes, and is a **parameter** because the route is where a permission is
 * turned into a control. `GroupDetail` renders a disabled button with a reason when it is handed
 * no callback, and that rendering has a case; what decides whether it is handed one is a ternary in
 * `GroupRoute`, and a suite that can only mount the route permitted cannot observe it at all. So
 * the deciding half was a constant `true` for two waves, and replacing the whole gate with `true`
 * left every case green.
 */
export function testContext(
  api: KuiApiClient,
  permits: KuiContextValue["permits"] = () => true,
): KuiContextValue {
  return {
    api,
    cluster: () => undefined,
    permits,
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
