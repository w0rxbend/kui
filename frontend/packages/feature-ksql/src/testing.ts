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

import harmlessPlan from "./documents/statement-plan-harmless.json" with { type: "json" };

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
  /**
   * Makes every later read of this path fail the way an unreachable server does.
   *
   * The one state `answerWith` cannot produce. A cached answer plus a failing re-read is what
   * `useQuery` reports as `stale`, and `stale` is a state the ksqlDB screen reads the read-only
   * flag out of — so without this there was no way to mount the screen in it.
   */
  readonly refuse: (path: string) => void;
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
 *
 * ## The write hook is given the path, and may decline to answer
 *
 * A screen that plans before it applies makes **two** writes, so a hook that could not tell them
 * apart could only replace both. It is handed the path, and a hook that returns `undefined` lets
 * the held document answer as it would have anyway — which is what lets a case hold the *apply* in
 * flight, and only the apply, without restating what either address returns. W10-06's
 * double-submission case is that shape: it needs the first press to still be running when the
 * second one lands, and nothing else about the stub changed.
 */
export function serving(
  documents: Readonly<Record<string, unknown>>,
  write?: (path: string) => Promise<unknown>,
): Stub {
  const calls: Call[] = [];
  const held = new Map<string, unknown>(Object.entries(documents));
  const refused = new Set<string>();

  const answer =
    (method: "get" | "post") =>
    async (path: string, init: { params: { path: Record<string, string> }; body?: unknown }) => {
      calls.push({ method, path, params: init.params.path, body: init.body });
      if (refused.has(path)) {
        return { ok: false, error: { kind: "unreachable", cause: "KUI cannot reach the server." } };
      }
      if (method === "get") return { ok: true, value: held.get(path) ?? null };
      const written = write === undefined ? undefined : await write(path);
      return written ?? { ok: true, value: held.get(path) ?? null };
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
    refuse: (path) => {
      refused.add(path);
    },
  };
}

/* ---------------------------------------------------------------------------------------------- */
/* The push query's plan                                                                          */
/* ---------------------------------------------------------------------------------------------- */

/**
 * The plan document a `SELECT … EMIT CHANGES` produces, derived from a golden rather than written.
 *
 * ## Why this is not a file in `documents/`
 *
 * It was one — `statement-plan-push-query.json` — and it was the only fixture in this package that
 * **no encoder had ever produced**. House rule 12 exists to eliminate exactly that: every other
 * document here is a byte-for-byte copy of a golden `services/ksql/contract` renders from the
 * service's own encoder, held that way by `wire.golden.test.ts`, so a fixture cannot become a third
 * opinion about the wire. A hand-written one in the same directory looks identical to the ones that
 * were captured, and this one was already wrong: it carried `"warnings": []`, where `KsqlUseCases`
 * gives a push query's plan exactly one warning (`PushQueryElsewhere`, the sentence naming the
 * stream address that does answer).
 *
 * ## What this is instead, and what it deliberately does not invent
 *
 * The service commits no push-query plan golden, so there is nothing to copy. This takes the plan
 * golden it *does* commit — `statement-plan-harmless.json`, a non-destructive statement, held
 * byte-identical to the service's own by `wire.golden.test.ts` — and changes the **two** fields the
 * service itself changes for a push query: the statement, and the `shape` it is classified as.
 * Every other field is the encoder's.
 *
 * `warnings` is left as the golden's empty list and **no sentence is invented here**. Copying
 * `PushQueryElsewhere` out of `KsqlUseCases.scala` by eye would be a second copy of a Scala
 * constant with nothing comparing the two — the mistake `ALERTS_EVENT_NAME` is this project's
 * standing example of. It is also inert for every case that uses this: the screen draws a plan's
 * warnings only inside the confirmation, and a push query is not destructive, so no confirmation
 * ever opens over one. The field the cases actually route on is `shape`.
 *
 * When `services/ksql/contract` commits a push-query plan golden, this goes away and the document
 * comes back into `documents/` as a copy. `wire.golden.test.ts` holds the half of that which can be
 * held from this side: no plan document in this package may exist without a golden behind it.
 */
export const pushQueryPlan: unknown = {
  ...harmlessPlan,
  statement: "SELECT * FROM ORDERS EMIT CHANGES;",
  shape: "push_query",
};
