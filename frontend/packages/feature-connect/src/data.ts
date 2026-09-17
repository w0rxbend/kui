/**
 * The Connect feature's one read and its three writes.
 *
 * ## Pause, resume and restart are three calls and not one with a verb in the body
 *
 * They are three endpoints on the server, three audit operation names and three separately
 * refusable mutations. A single endpoint taking an action word would put that decision inside a
 * request body where no route, no audit line and no gateway contract row can see it.
 */
import type { ApiResult, KuiApiClient } from "@kui/api";
import { decodeSection } from "@kui/api";
import { apiFailure, fromSection, type Fetched } from "@kui/kernel";

import {
  CONNECTORS_SECTION_KEY,
  decodeConnectorListing,
  Unreadable,
  type ConnectorListing,
} from "./wire.js";

export const CONNECTORS_PATH = "/api/v1/clusters/{clusterId}/connect/connectors";
export const PAUSE_PATH =
  "/api/v1/clusters/{clusterId}/connect/{connectName}/connectors/{connectorName}/pause";
export const RESUME_PATH =
  "/api/v1/clusters/{clusterId}/connect/{connectName}/connectors/{connectorName}/resume";
export const RESTART_PATH =
  "/api/v1/clusters/{clusterId}/connect/{connectName}/connectors/{connectorName}/restart";

/**
 * Every Connect cluster configured for one Kafka cluster, and what each of them said.
 *
 * The **outer** section is `not_configured` for a Kafka cluster with no `connect` block at all, and
 * it arrives with a 200 — a deployment that never intended to run Kafka Connect is not a broken
 * one, and ADR-032's rule is a sentence rather than a red panel. Every other outcome per worker is
 * inside the listing, one row each.
 */
export async function fetchConnectors(
  api: KuiApiClient,
  clusterId: string,
): Promise<Fetched<ConnectorListing>> {
  const answer = await api.get(CONNECTORS_PATH, { params: { path: { clusterId } } });
  if (!answer.ok) return apiFailure(answer.error);

  const section = answer.value[CONNECTORS_SECTION_KEY];
  if (section === undefined) {
    return {
      kind: "failed",
      message:
        "KUI could not read the connector list: the server's answer carried no connectors section.",
      code: "UNREADABLE_BODY",
    };
  }

  const decoded = fromSection(decodeSection<unknown>(section), (payload) => payload);
  if (decoded.kind !== "ready" && decoded.kind !== "stale") return decoded;

  const listing = decodeConnectorListing(decoded.value);
  if (listing === Unreadable) {
    /*
     * The refusal that stops this package repeating wave 5's worst defect — and the one this file's
     * first draft would have needed, because it read `items` where the service renders `workers`.
     * A payload this build cannot read is reported as unread, never as a cluster with no Connect
     * workers, which is a sentence about the deployment that an operator acts on.
     */
    return {
      kind: "failed",
      message:
        "KUI read the connectors section and did not recognise what was in it, so it cannot say " +
        "which Connect workers this cluster has. This build and the connect service disagree " +
        "about " +
        "the shape of that document.",
      code: "UNREADABLE_BODY",
    };
  }

  return decoded.kind === "stale"
    ? { kind: "stale", value: listing, reason: decoded.reason }
    : { kind: "ready", value: listing };
}

/** What a command names on the wire: the Connect cluster and the connector. */
export interface ConnectorRef {
  readonly connect: string;
  readonly name: string;
}

export type ConnectorCommand = "pause" | "resume" | "restart";

const PATHS = {
  pause: PAUSE_PATH,
  resume: RESUME_PATH,
  restart: RESTART_PATH,
} as const satisfies Record<ConnectorCommand, string>;

/**
 * One of the three commands, against one connector.
 *
 * The answer is dropped on purpose. `ConnectorOperationDto` says what was accepted and when, and
 * carries **no connector state**, because Connect answers all three with `202 Accepted` and an
 * empty body: the cluster applies the operation when its workers have agreed. A screen that painted
 * `PAUSED` from that answer would be painting the state from *before* the call wearing the result's
 * clothes. The route re-reads the list instead, which is the only thing that knows.
 */
export async function command(
  api: KuiApiClient,
  clusterId: string,
  connector: ConnectorRef,
  which: ConnectorCommand,
): Promise<ApiResult<void>> {
  const answer = await api.post(PATHS[which], {
    params: {
      path: { clusterId, connectName: connector.connect, connectorName: connector.name },
    },
  });
  return answer.ok ? { ok: true, value: undefined } : { ok: false, error: answer.error };
}
