/**
 * What the shell shows for one feature, right now (ADR-032).
 *
 * Five states rather than a boolean, because "is it up" is not a question a user interface can act
 * on. Each one calls for something different on screen, and collapsing any two of them loses
 * information the user needs:
 *
 * - `ready` — the entry is normal and the page works.
 * - `degraded` — the entry gets an amber dot and the page works, with an inline banner. The reason
 *   is structured, so a lag or metrics screen can slow its polling down rather than making things
 *   worse.
 * - `unavailable` — the entry is dimmed and **still navigable**, and the route renders the feature's
 *   own fallback panel: what broke, since when, a working retry, and what still works. A disabled
 *   link has nowhere to put any of that, which is why ADR-032 amended the original plan.
 * - `forbidden` — the entry is shown disabled with "you do not have permission", or hidden entirely
 *   in deployments that consider the existence of a feature sensitive.
 * - `not_configured` — the entry is hidden. This deployment has no schema registry on this cluster;
 *   that is not a failure and must not be rendered as one, or every operator goes hunting for an
 *   outage that does not exist.
 */
import { CapabilityStatuses, ReasonCodes, ReasonSentences } from "@kui/api";

import type { CapabilityState } from "./frames.js";

export type FeatureState =
  | { readonly kind: "ready" }
  | {
      readonly kind: "degraded";
      readonly code: string;
      readonly message: string;
      /** What the gateway suggests this screen's polling interval should become, when it says. */
      readonly suggestedPollIntervalMs: number | undefined;
    }
  | {
      readonly kind: "unavailable";
      readonly code: string;
      readonly message: string;
      /** When it broke, RFC 3339 — the gateway's timestamp, kept exactly as it arrived. */
      readonly since: string | undefined;
    }
  | { readonly kind: "forbidden" }
  | { readonly kind: "not_configured" };

/**
 * The message shown while the gateway has not yet polled an upstream.
 *
 * A sentence rather than "unknown", because the user is being told the truth: the feature is usable
 * and its health has not been established yet.
 */
export const STARTING_MESSAGE = ReasonSentences[ReasonCodes.Starting];

/**
 * ADR-032's rule, as one pure function.
 *
 * Pure and total so that it can be tested as a table, one row per input combination, and so that
 * every dimmed navigation entry and every fallback panel in the product is downstream of code that
 * has been checked exhaustively rather than of conditionals scattered across screens.
 *
 * Two rows deserve their reasoning in the code rather than only in the ADR:
 *
 * - **`permitted = false` matches first, whatever the capability says** (amendment 1). A user who
 *   may not see the schema registry must not be able to learn from the navigation whether it is up,
 *   how long it has been down, or what its upstream error said. `forbidden` outranks every health
 *   state because the alternative leaks information.
 * - **A capability nobody has reported yet is `degraded`, never `unavailable`** (amendment 2).
 *   Between the gateway starting and its first readiness poll it has no information, and reporting
 *   `unavailable` would be a claim it cannot support — it has not asked. Every operator who
 *   restarted the gateway would watch the whole navigation go red for one polling interval, which
 *   trains people to ignore the colour that is supposed to matter.
 */
export function deriveFeatureState(
  capability: CapabilityState | undefined,
  permitted: boolean,
): FeatureState {
  if (!permitted) return { kind: "forbidden" };
  if (capability === undefined) {
    return {
      kind: "degraded",
      code: ReasonCodes.Starting,
      message: STARTING_MESSAGE,
      suggestedPollIntervalMs: undefined,
    };
  }

  switch (capability.status) {
    case CapabilityStatuses.Available:
      return { kind: "ready" };
    case CapabilityStatuses.Degraded:
      return {
        kind: "degraded",
        code: capability.reason.code,
        message: capability.reason.message,
        suggestedPollIntervalMs: capability.reason.suggestedPollIntervalMs,
      };
    case CapabilityStatuses.Unavailable:
      return {
        kind: "unavailable",
        code: capability.reason,
        message: capability.message,
        since: capability.since === "" ? undefined : capability.since,
      };
    case CapabilityStatuses.NotConfigured:
      return { kind: "not_configured" };
  }
}

/**
 * Whether clicking the navigation entry leads anywhere.
 *
 * `unavailable` is navigable on purpose: the page it leads to is the feature's fallback panel, which
 * is where the reason, the `since` and the retry live. Making it a dead link would leave the user
 * with a dimmed word and no way to find out anything.
 */
export function isNavigable(state: FeatureState): boolean {
  return state.kind === "ready" || state.kind === "degraded" || state.kind === "unavailable";
}

/**
 * Whether the entry is left out of the navigation entirely.
 *
 * `not_configured` always is. `forbidden` only when the deployment asks for it: some organisations
 * consider the existence of a feature sensitive, and most find a visible-but-disabled entry more
 * helpful than a menu that changes shape per user.
 *
 * @param hideForbidden the `kui.ui.hideForbidden` switch of ADR-032.
 */
export function isHidden(state: FeatureState, hideForbidden = false): boolean {
  if (state.kind === "not_configured") return true;
  return state.kind === "forbidden" && hideForbidden;
}

/** Whether the entry is drawn dimmed: reachable, but not currently working. */
export function isDimmed(state: FeatureState): boolean {
  return state.kind === "unavailable";
}
