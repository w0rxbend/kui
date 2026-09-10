/**
 * Mounting, axe, and a permission answer that cannot lose the subject.
 *
 * ## The eight lines every feature copies, and why they are copied
 *
 * `mount` and `findViolations` are the same eight lines as the seven other copies in this
 * workspace. They are copied rather than shared because a feature must not depend on another
 * feature and the kernel's copy is not exported from `@kui/kernel` — deliberately, since a test
 * helper in a product bundle is dead weight every browser downloads. Counted rather than
 * remembered: `grep -rn 'export function mount(component' frontend/packages` matched **eight**
 * files before this one and nine with it (measured 2026-09-10). `feature-alerts`' copy records that
 * seven was already past the point where a `@kui/testing` package was the answer, and that adding a
 * package is not a feature packet's to do: `frontend/package.json` and `pnpm-workspace.yaml` are
 * unowned this wave as they were last wave.
 *
 * ## `permits` here is the product's own rule, not a stub of it
 *
 * This is the part that is **not** a copy, and the reason is a measured defect. Wave 6 shipped two
 * consumer-group controls asking `kui.permits(action)` where they should have asked
 * `kui.permits(action, groupId)`, and 273 green cases could not see it — because that package's
 * harness compared `{resource, action}` and threw the name away, so the weaker question and the
 * stronger one produced identical answers in every test that existed.
 *
 * So this harness does not answer permission questions itself. It holds a list of grants in the
 * wire's own shape and evaluates them through `@kui/kernel`'s `grantsAllow`, which is the same
 * function `state/session.ts` calls in the product — including its connector fallback, where a
 * grant on the connect cluster `payments` covers every connector named `payments/…`. A control that
 * drops the subject then asks a question this harness answers differently, and the case goes red.
 */

import { render } from "@solidjs/web";
import type { JSX } from "@solidjs/web/jsx-runtime";
import axe from "axe-core";
import type { KuiApiClient } from "@kui/api";
import {
  EVERY_CLUSTER,
  grantsAllow,
  grantsAllowAny,
  type KuiContextValue,
  type PermissionGrant,
} from "@kui/kernel";

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
  return violations
    .map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.html).join(", ")})`)
    .join("\n");
}

/* ---------------------------------------------------------------------------------------------- */
/* The feature context                                                                            */
/* ---------------------------------------------------------------------------------------------- */

export const TEST_CLUSTER = "quickstart";

/**
 * A grant, in the shape the permission store holds them.
 *
 * `pattern` is the resource-name regular expression an operator wrote, exactly as the store reads
 * it — `undefined` for the unnamed resources, `"payments"` for one connect cluster. It is a full
 * match there, so `payments` does not cover `payments-dlq`, and a case written here is a case
 * against the product's own matching rather than against this file's idea of it.
 */
export function grant(
  resource: string,
  actions: readonly string[],
  pattern: string | undefined,
  clusters: readonly string[] = [EVERY_CLUSTER],
): PermissionGrant {
  return { resource, actions: [...actions], clusters: [...clusters], value: pattern };
}

/**
 * What `useKui()` answers inside a test.
 *
 * `grants` of `undefined` permits everything, which is what a case about rendering rather than
 * about permissions wants. A case about permissions passes grants and gets the product's own
 * evaluation.
 */
export function testContext(
  api: KuiApiClient,
  grants?: readonly PermissionGrant[],
): KuiContextValue {
  return {
    api,
    cluster: () => TEST_CLUSTER,
    permits: (action, name) => {
      if (grants === undefined) return true;
      /* The two questions, kept apart exactly as `session.ts` keeps them apart. A control that
         passes no name gets the weaker one and a control that passes one gets the stronger one —
         which is the whole reason this harness exists rather than a boolean. */
      return name === undefined
        ? grantsAllowAny(grants, TEST_CLUSTER, action)
        : grantsAllow(grants, TEST_CLUSTER, action, name);
    },
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

export interface Call {
  readonly method: "get" | "post";
  readonly path: string;
  readonly params: Record<string, string>;
}

export interface Stub {
  readonly api: KuiApiClient;
  readonly calls: Call[];
  /** Replaces what the next read answers with, so a case can watch a re-read land. */
  readonly answerWith: (document: unknown) => void;
}

/**
 * A client that answers one document to reads and records every call.
 *
 * Cast at one boundary, like every stub client in this workspace: `KuiApiClient`'s methods are
 * typed from the OpenAPI document and a fake satisfying all of that would be a second copy of the
 * schema. What it records is the path the product passed and the parameters it filled in, so a case
 * can assert the address as a **literal** — comparing it against the constant the product built it
 * from would assert that a constant equals itself.
 *
 * @param body what a read answers, until `answerWith` replaces it
 * @param write what a mutation answers. The default is a success; pass a failure to refuse one.
 */
export function serving(body: unknown, write?: () => Promise<unknown>): Stub {
  const calls: Call[] = [];
  let document = body;

  const answer =
    (method: "get" | "post") =>
    async (path: string, init: { params: { path: Record<string, string> } }) => {
      calls.push({ method, path, params: init.params.path });
      if (method === "get") return { ok: true, value: document };
      return write === undefined ? { ok: true, value: undefined } : await write();
    };

  const api = {
    get: answer("get"),
    post: answer("post"),
    put: answer("post"),
    delete: answer("post"),
    patch: answer("post"),
    raw: {},
  } as unknown as KuiApiClient;

  return {
    api,
    calls,
    answerWith: (next) => {
      document = next;
    },
  };
}
