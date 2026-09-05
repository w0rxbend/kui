/**
 * Every sentence the shell shows about a feature's health, in one place.
 *
 * ## Why the strings are centralised and why there is no translation layer
 *
 * KUI ships in English and has no internationalisation runtime, so "centralised" here means one
 * module rather than a message catalogue with lookups. The gain is not translation, it is
 * *consistency and review*: a reason is rendered identically wherever it appears — the drawer's
 * tooltip, the fallback panel, the cluster switcher — and the wording can be read and corrected as
 * prose in one file instead of being hunted for across screens.
 *
 * Each sentence is written for an operator trying to decide what to do next, not for a developer
 * reading a log. The reason *codes* keep their own sentences on the server, where they were reviewed,
 * and arrive in the browser through the generated `ReasonSentences`; what is written here is the
 * shell's own prose around them.
 */
import { ReasonCodes, ReasonSentences, type KnownReasonCode } from "@kui/api";
import type { FeatureState } from "@kui/kernel";

/**
 * A reason code as one sentence.
 *
 * A code a newer gateway invents falls back to the `UNKNOWN` sentence rather than to a blank: an
 * older browser must degrade to "something is wrong" rather than say nothing at all.
 */
export function reasonSentence(code: string): string {
  const known = ReasonSentences[code as KnownReasonCode] ?? ReasonSentences[ReasonCodes.Unknown];
  return `${known.charAt(0).toUpperCase()}${known.slice(1)}.`;
}

/**
 * What to say about a feature, or nothing when there is nothing to explain.
 *
 * The gateway's own message wins over the reason code's sentence when it sent one, because it is the
 * more specific of the two and it is the one that mentions the actual upstream.
 */
export function explanation(state: FeatureState, featureLabel: string): string | undefined {
  switch (state.kind) {
    case "ready":
    case "not_configured":
      return undefined;
    case "forbidden":
      return `You do not have permission to view ${featureLabel}.`;
    case "degraded":
    case "unavailable":
      return state.message.length > 0 ? state.message : reasonSentence(state.code);
  }
}

/** The heading of a feature's fallback panel. */
export function unavailableTitle(featureLabel: string): string {
  return `${featureLabel} is unavailable`;
}

/**
 * The spinner's label while a feature's chunk is downloading.
 *
 * Named rather than a bare spinner, because a page that says only "loading" gives a user staring at a
 * slow connection no way to tell whether the thing they clicked is the thing that is loading.
 */
export function loadingLabel(featureLabel: string): string {
  return `Loading ${featureLabel}…`;
}

/**
 * What a failed dynamic import says.
 *
 * It is a network failure, not a missing feature, and the difference decides whether retrying is
 * worth the user's time.
 */
export function moduleFailed(featureLabel: string, cause: string): string {
  return (
    `${featureLabel} could not be downloaded. This is usually a network problem rather than a ` +
    `fault in the service itself. (${cause})`
  );
}

export function notConfiguredNotice(): string {
  return "This is not configured in this deployment, so there is nothing to show here.";
}

export function notPermitted(featureLabel: string): string {
  return `You do not have permission to view ${featureLabel}.`;
}

/**
 * The banner above the content when the live connection has gone.
 *
 * The complementary rule matters as much as this one: an unknown state is never rendered as ready.
 * That is enforced upstream, where a capability nobody has reported becomes degraded-with-STARTING.
 */
export const StaleBanner =
  "KUI has lost its live connection to the gateway, so what follows may be out of date. It is still " +
  "trying to reconnect.";

export function degradedBanner(featureLabels: readonly string[]): string | undefined {
  if (featureLabels.length === 0) return undefined;
  if (featureLabels.length === 1) return `${featureLabels[0]!} is working, but not well.`;
  return `${featureLabels.join(", ")} are working, but not well.`;
}

export const NothingElseWorks = "Nothing else is available right now either.";
export const WhatStillWorks = "What still works";
export const RetryNow = "Retry now";
export const Retrying = "Checking…";

export function retryFailed(detail: string): string {
  return `KUI could not re-check the service: ${detail}`;
}
