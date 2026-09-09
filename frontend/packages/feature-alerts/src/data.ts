/** The alerts feature's one write; reads come from the application-wide kernel store. */
import type { ApiResult, KuiApiClient } from "@kui/api";

export const EVENTS_PATH = "/api/v1/clusters/{clusterId}/alerts/events";
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
