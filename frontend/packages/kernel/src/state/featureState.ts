/**
 * What the shell shows for one feature, right now (ADR-032).
 *
 * Five states rather than a boolean, because "is it up" is not a question a user interface can act
 * on. Each one calls for something different on screen, and collapsing any two of them loses
 * information the user needs:
 *
 * - `ready` — the navigation entry is normal and the page works.
 * - `degraded` — the entry gets a warning marker and the page works, with an inline banner. The
 *   reason is structured, so a lag or metrics screen can slow its polling down rather than making
 *   things worse.
 * - `unavailable` — the entry is dimmed and **still clickable**, and the route renders the feature's
 *   fallback panel: what broke, since when, a working retry, and what still works. A disabled link
 *   has nowhere to put any of that, which is why ADR-032 amended the original plan.
 * - `forbidden` — the entry is shown disabled with "you do not have permission", or hidden entirely
 *   in deployments that consider the existence of a feature sensitive.
 * - `not-configured` — the entry is hidden. This deployment has no schema registry on this cluster;
 *   that is not a failure and must not be rendered as one, or every operator goes hunting for an
 *   outage that does not exist.
 */
import { ReasonCodes, ReasonSentences, type KnownReasonCode } from "@kui/api";
import type { CapabilityStateValue, DegradedReason } from "./capability.js";

export type FeatureState =
  | { readonly kind: "ready" }
  | { readonly kind: "degraded"; readonly reason: DegradedReason }
  | {
      readonly kind: "unavailable";
      readonly reason: string;
      readonly message: string;
      /** RFC 3339, when the gateway said when. */
      readonly since?: string | undefined;
    }
  | { readonly kind: "forbidden" }
  | { readonly kind: "not-configured" };

/**
 * The message shown while the gateway has not yet polled an upstream.
 *
 * A sentence rather than "unknown", because the user is being told the truth: the feature is usable
 * and its health has not been established yet.
 */
export const StartingMessage = "Checking this service — it has not been polled yet.";

/** The stand-in reason for "we have not looked yet". */
export const startingReason: DegradedReason = {
  code: ReasonCodes.Starting,
  message: StartingMessage,
};

/**
 * ADR-032's rule, as one pure function.
 *
 * It is pure and total so that it can be tested as a table, one row per input combination, and so
 * that every dimmed navigation entry and every fallback panel in the product is downstream of code
 * that has been checked exhaustively rather than of conditionals scattered across screens.
 *
 * Two rows deserve their reasoning here rather than only in the ADR:
 *
 * - **`permitted === false` matches first, whatever the capability says** (ADR-032 amendment 1). A
 *   user who may not see the schema registry must not be able to learn from the navigation whether
 *   it is up, how long it has been down, or what its upstream error said. `forbidden` outranks every
 *   health state because the alternative leaks information.
 * - **A capability nobody has reported yet is `degraded` with the `STARTING` reason, never
 *   `unavailable`** (amendment 2). Between the gateway starting and its first readiness poll it has
 *   no information, and reporting `unavailable` would be a claim it cannot support — it has not
 *   asked. Every operator who restarts the gateway would watch the whole navigation go red for one
 *   polling interval, which trains people to ignore the colour that is supposed to matter.
 *
 * @param capability what the gateway last said, or `undefined` when it has said nothing.
 * @param permitted the RBAC decision, from the session's permissions.
 */
export function deriveFeatureState(
  capability: CapabilityStateValue | undefined,
  permitted: boolean,
): FeatureState {
  if (!permitted) return { kind: "forbidden" };
  if (capability === undefined) return { kind: "degraded", reason: startingReason };

  switch (capability.status) {
    case "available":
      return { kind: "ready" };
    case "degraded":
      return { kind: "degraded", reason: capability.reason };
    case "unavailable":
      return {
        kind: "unavailable",
        reason: capability.reason,
        message: capability.message,
        since: capability.since,
      };
    case "not_configured":
      return { kind: "not-configured" };
  }
}

/**
 * Whether clicking the navigation entry leads anywhere.
 *
 * `unavailable` is navigable on purpose: the page it leads to is the feature's fallback panel, which
 * is where the reason, the "since" and the retry live. Making it a dead link would leave the user
 * with a dimmed word and no way to find out anything.
 */
export function isNavigable(state: FeatureState): boolean {
  return state.kind === "ready" || state.kind === "degraded" || state.kind === "unavailable";
}

/**
 * Whether the entry is left out of the navigation entirely.
 *
 * `not-configured` always is. `forbidden` is only when the deployment asks for it: some
 * organisations consider the existence of a feature sensitive, and most find a visible-but-disabled
 * entry more helpful than a menu that changes shape per user, so the switch is off by default.
 */
export function isHidden(state: FeatureState, hideForbidden = false): boolean {
  if (state.kind === "not-configured") return true;
  return state.kind === "forbidden" && hideForbidden;
}

/** Whether the entry is drawn dimmed: reachable, but not currently working. */
export function isDimmed(state: FeatureState): boolean {
  return state.kind === "unavailable";
}

/**
 * The name written into `data-state`.
 *
 * It exists for the end-to-end tests, which assert ADR-032's five rules against a real browser, and
 * it is deliberately not a class name. Class names belong to the visual design and change whenever
 * the design does; this is a statement about state, and it has to stay true through any restyle or
 * the test is asserting on the wrong thing.
 */
export function stateName(state: FeatureState): FeatureState["kind"] {
  return state.kind;
}

/**
 * The reason code as one sentence, for the sidebar tooltip and the fallback panel.
 *
 * The sentences are generated from the Scala enum they come from (`ReasonSentences`), so the
 * wording an operator reads in the browser is the wording that was reviewed on the server. A reason
 * a newer gateway invents falls back to the `UNKNOWN` sentence rather than to a blank: an older
 * browser must degrade to "something is wrong" rather than say nothing at all.
 */
export function reasonSentence(code: string): string {
  const known = ReasonSentences[code as KnownReasonCode];
  return capitalise(known ?? ReasonSentences[ReasonCodes.Unknown]) + ".";
}

/**
 * What to explain about a feature, or nothing when it is working.
 *
 * The gateway's own message wins over the reason code's sentence when it sent one, because it is the
 * more specific of the two and it is the one that mentions the actual upstream.
 */
export function explanation(state: FeatureState, featureLabel: string): string | undefined {
  switch (state.kind) {
    case "ready":
    case "not-configured":
      return undefined;
    case "forbidden":
      return `You do not have permission to view ${featureLabel}.`;
    case "degraded":
      return state.reason.message.length > 0
        ? state.reason.message
        : reasonSentence(state.reason.code);
    case "unavailable":
      return state.message.length > 0 ? state.message : reasonSentence(state.reason);
  }
}

function capitalise(sentence: string): string {
  return sentence.length === 0 ? sentence : sentence[0]!.toUpperCase() + sentence.slice(1);
}
