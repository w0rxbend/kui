import { describe, expect, it } from "vitest";
import { CapabilityStatuses, ReasonCodes } from "@kui/api";

import type { CapabilityState } from "./frames.js";
import {
  deriveFeatureState,
  isDimmed,
  isHidden,
  isNavigable,
  STARTING_MESSAGE,
} from "./featureState.js";

const available: CapabilityState = { status: CapabilityStatuses.Available };
const degraded: CapabilityState = {
  status: CapabilityStatuses.Degraded,
  reason: {
    code: ReasonCodes.UpstreamTimeout,
    message: "the cluster is answering too slowly",
    suggestedPollIntervalMs: 60_000,
  },
};
const unavailable: CapabilityState = {
  status: CapabilityStatuses.Unavailable,
  reason: ReasonCodes.UpstreamUnavailable,
  message: "the cluster is not answering",
  since: "2026-09-05T09:00:00Z",
};
const notConfigured: CapabilityState = { status: CapabilityStatuses.NotConfigured };

describe("the feature-state fold", () => {
  it("is a table, and every row of it is checked", () => {
    // Written as a table on purpose: every dimmed navigation entry and every fallback panel in the
    // product is downstream of this one function, so it is checked exhaustively here rather than
    // through the screens that use it.
    const rows: ReadonlyArray<[CapabilityState | undefined, boolean, string]> = [
      [undefined, true, "degraded"],
      [available, true, "ready"],
      [degraded, true, "degraded"],
      [unavailable, true, "unavailable"],
      [notConfigured, true, "not_configured"],
      [undefined, false, "forbidden"],
      [available, false, "forbidden"],
      [degraded, false, "forbidden"],
      [unavailable, false, "forbidden"],
      [notConfigured, false, "forbidden"],
    ];

    for (const [capability, permitted, expected] of rows) {
      expect(deriveFeatureState(capability, permitted).kind).toBe(expected);
    }
  });

  it("says a capability nobody has reported is starting, never unavailable", () => {
    // Between the gateway starting and its first readiness poll it has no information. Reporting
    // "unavailable" would be a claim it cannot support, and every operator who restarted the
    // gateway would watch the whole navigation go red for one polling interval.
    expect(deriveFeatureState(undefined, true)).toEqual({
      kind: "degraded",
      code: ReasonCodes.Starting,
      message: STARTING_MESSAGE,
      suggestedPollIntervalMs: undefined,
    });
  });

  it("carries the structured reason through, so a screen can slow its polling down", () => {
    expect(deriveFeatureState(degraded, true)).toEqual({
      kind: "degraded",
      code: ReasonCodes.UpstreamTimeout,
      message: "the cluster is answering too slowly",
      suggestedPollIntervalMs: 60_000,
    });
  });

  it("keeps an unavailable feature reachable, and hides one that is simply not configured", () => {
    const down = deriveFeatureState(unavailable, true);
    // The page it leads to is the feature's fallback panel: what broke, since when, and a retry. A
    // dead link would leave the user with a dimmed word and no way to find out anything.
    expect(isNavigable(down)).toBe(true);
    expect(isDimmed(down)).toBe(true);
    expect(isHidden(down)).toBe(false);

    const absent = deriveFeatureState(notConfigured, true);
    // This deployment has no schema registry on this cluster. That is not a failure and must not be
    // rendered as one, or every operator goes hunting for an outage that does not exist.
    expect(isHidden(absent)).toBe(true);
    expect(isNavigable(absent)).toBe(false);
  });

  it("shows a forbidden feature disabled by default, and hides it where the deployment asks", () => {
    const forbidden = deriveFeatureState(available, false);
    expect(isHidden(forbidden)).toBe(false);
    expect(isHidden(forbidden, true)).toBe(true);
    expect(isNavigable(forbidden)).toBe(false);
    // And it carries nothing about the service's health: that is the information the state exists
    // to withhold.
    expect(Object.keys(forbidden)).toEqual(["kind"]);
  });
});
