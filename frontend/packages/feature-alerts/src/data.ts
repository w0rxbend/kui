/**
 * The alerts feature's one HTTP call, and the reason the other one is not here.
 *
 * ## Reads are the shell's, writes are this package's
 *
 * The feed is fetched once for the whole application by `@kui/kernel`'s `createAlerts`, wired up in
 * `packages/shell/src/App.tsx` against `packages/shell/src/data/alerts.ts`'s `ALERTS_FEED_PATH`,
 * because the bell in the chrome and the card on this screen must never be able to disagree about
 * how many alerts are open. This screen reads that store through `useAlerts()` and issues no read
 * of its own. What is left for this file is the acknowledgement, which is a write on one event and
 * belongs to the screen that offers the control.
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
