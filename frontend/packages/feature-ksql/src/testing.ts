/**
 * Mounting, axe, and a permission answer that cannot lose the subject.
 *
 * ## The eight lines every feature copies, and why they are copied
 *
 * `mount` and `findViolations` are the same eight lines as the eight other copies in this
 * workspace. They are copied rather than shared because a feature must not depend on another
 * feature and the kernel's copy is not exported from `@kui/kernel` — deliberately, since a test
 * helper in a product bundle is dead weight every browser downloads. Counted rather than
 * remembered: `grep -rn 'export function mount(component' frontend/packages` matched **nine** files
 * before this one and ten with it (measured 2026-09-11). `feature-connect`'s copy records that
 * seven was already past the point where a `@kui/testing` package was the answer, and that adding a
 * package is not a feature packet's to do: `frontend/package.json` and `pnpm-workspace.yaml` are
 * unowned this wave as they were last wave.
 *
 * ## `permits` here is the product's own rule, not a stub of it
 *
 * This is the part that is **not** a copy, and it is what makes this packet's owned rule gateable
 * in both directions. It holds grants in the wire's own shape and evaluates them through
 * `@kui/kernel`'s `grantsAllow` / `grantsAllowAny` — the same two functions `state/session.ts`
 * calls in the product — so the subjectless question and the named one produce *different* answers
 * here exactly as they do against a real `/auth/me`.
 *
 * On `Resource.Ksql` that difference runs the opposite way from a topic's. `KSQL` is an **unnamed**
 * resource (`Vocabulary.scala:84`), so every grant over it has `value: undefined`, so `covers`
 * answers a *named* question `false` and an *any* question `true`. A statement editor that named a
 * subject would therefore be disabled for every principal who genuinely holds `KSQL:EXECUTE` — and
 * `ksql.test.tsx`'s permission cases go red the moment one is added. Wave 6 shipped two
 * consumer-group controls asking the weaker question and 273 green cases could not see it, because
 * that package's harness compared `{resource, action}` and threw the name away; this harness cannot
 * make that mistake in either direction.
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
 * it. For `KSQL` it is always `undefined`, because the configuration loader refuses a `KSQL`
 * permission that carries a value — a grant written here with one is a grant the server would never
 * have loaded, which is why the cases pass `undefined` and say so.
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
      /* The two questions, kept apart exactly as `session.ts` keeps them apart — which is the whole
         reason this harness exists rather than a boolean. */
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
  readonly body: unknown;
}

export interface Stub {
  readonly api: KuiApiClient;
  readonly calls: Call[];
  /** Replaces what the next read of this path answers with, so a case can watch a re-read land. */
  readonly answerWith: (path: string, document: unknown) => void;
}

/**
 * A client that answers a document per path and records every call.
 *
 * Cast at one boundary, like every stub client in this workspace: `KuiApiClient`'s methods are
 * typed from the OpenAPI document and a fake satisfying all of that would be a second copy of the
 * schema. What it records is the path the product passed, the parameters it filled in and the body
 * it sent, so a case can assert the address as a **literal** — comparing it against the constant
 * the product built it from would assert that a constant equals itself.
 *
 * It answers **per path** rather than one document for everything, because this screen makes two
 * different reads (the objects and the cluster's read-only flag) and a single-document stub would
 * feed the object decoder the cluster document and call the result a contract break.
 */
export function serving(
  documents: Readonly<Record<string, unknown>>,
  write?: () => Promise<unknown>,
): Stub {
  const calls: Call[] = [];
  const held = new Map<string, unknown>(Object.entries(documents));

  const answer =
    (method: "get" | "post") =>
    async (path: string, init: { params: { path: Record<string, string> }; body?: unknown }) => {
      calls.push({ method, path, params: init.params.path, body: init.body });
      if (method === "get") return { ok: true, value: held.get(path) ?? null };
      return write === undefined ? { ok: true, value: held.get(path) ?? null } : await write();
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
    answerWith: (path, document) => {
      held.set(path, document);
    },
  };
}
