/**
 * The alerts feature's two HTTP calls, and the reason the feed is not one of them.
 *
 * ## The feed is the shell's, the write and the write's gate are this package's
 *
 * The feed is fetched once for the whole application by `@kui/kernel`'s `createAlerts`, wired up in
 * `packages/shell/src/App.tsx` through `packages/shell/src/data/alerts.ts`'s `loadAlertFeed`,
 * because the bell in the chrome and the card on this screen must never be able to disagree about
 * how many alerts are open. This screen reads that store through `useAlerts()` and issues no read
 * of the feed. What is left for this file is the acknowledgement, which is a write on one event and
 * belongs to the screen that offers the control — and the one fact that decides whether the control
 * is offered at all, ADR-047's read-only flag.
 *
 * ## Why the read that fills this screen does not ask the server to mark the feed read
 *
 * `AlertsEndpoints`' `markRead` query defaults to `false`, and the store calls `load(false)`
 * everywhere except `markAllRead()`. The reason is not that the screen polls — it does not: there
 * is no interval anywhere in this package or in the store, and `useQuery` is not given one. The
 * reason is that the read is **re-issued by events the reader did not cause**: every frame on the
 * alerts stream makes the store re-read the feed
 * (`@kui/kernel`'s `store.ts`, the subscriber's `onEvent`), and so does every successful
 * acknowledgement. `unreadCount` is per-principal, so a `markRead=true` on that path would mean
 * somebody who left this tab open had their unread marker cleared by an alert opening on the
 * cluster — the bell going quiet at the exact moment it should have rung.
 *
 * Marking read is a deliberate act with its own control, `markAllRead()`, in the shell's chrome
 * beside the bell it silences.
 */
import type { ApiResult, KuiApiClient } from "@kui/api";
import { apiFailure, type Fetched } from "@kui/kernel";

/** `AlertsEndpoints.acknowledge`: the event's own sub-resource, and no request body. */
export const ACKNOWLEDGEMENT_PATH =
  "/api/v1/clusters/{clusterId}/alerts/events/{eventId}/acknowledgement";

export async function acknowledge(
  api: KuiApiClient,
  clusterId: string,
  eventId: string,
): Promise<ApiResult<void>> {
  const answer = await api.post(ACKNOWLEDGEMENT_PATH, {
    params: { path: { clusterId, eventId } },
  });
  return answer.ok ? { ok: true, value: undefined } : answer;
}

/** `ClusterEndpoints.get`: the cluster's own document, which carries ADR-047's flag. */
export const CLUSTER_PATH = "/api/v1/clusters/{clusterId}";

/** The one fact about the cluster itself that the acknowledge control needs. */
export interface ClusterWriteState {
  readonly readOnly: boolean;
}

/** What `GET /api/v1/clusters/{clusterId}` carries. Only the one field this screen gates on. */
interface ClusterDetailPayload {
  readonly cluster?: { readonly readOnly?: boolean } | null;
}

/**
 * Whether this deployment has the cluster marked read-only.
 *
 * Not a section: `cluster.get` answers a plain document, so there is no ADR-039 envelope to unwrap
 * and a failure is a failure. The caller decides what an unanswered question means — see
 * `AlertsRoute`, which treats it as "not read-only" rather than disabling a control on a fact KUI
 * does not have.
 *
 * A second reader of one endpoint, and deliberately so: `feature-topics` carries the same three
 * lines. A feature may not import another feature — that edge is what the microfrontend split
 * exists to prevent — and the alternative, hoisting it into the kernel, would put a cluster-service
 * address in the package that is meant to know about no service at all.
 */
export async function fetchClusterWriteState(
  api: KuiApiClient,
  clusterId: string,
): Promise<Fetched<ClusterWriteState>> {
  const answer = await api.get(CLUSTER_PATH, { params: { path: { clusterId } } });
  if (!answer.ok) return apiFailure(answer.error);
  const payload = answer.value as ClusterDetailPayload;
  return { kind: "ready", value: { readOnly: payload.cluster?.readOnly === true } };
}
