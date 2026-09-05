import { describe, expect, it, vi } from "vitest";
import { flush } from "solid-js";
import { ReasonCodes } from "@kui/api";
import {
  capabilityKeyOf,
  createCapabilityStore,
  decodeCapabilityFrame,
  decodeCapabilityState,
  type CapabilityEntry,
  type CapabilityStreamHandlers,
} from "./capability.js";
import { deriveFeatureState, explanation, isDimmed, isHidden, isNavigable } from "./featureState.js";
import { createSession } from "./session.js";
import { createCurrentCluster, CurrentClusterStorageKey, soleClusterChoice } from "./currentCluster.js";

/**
 * Runs a test body with no reactive owner, which is where these stores are actually written to.
 *
 * Two Solid 2 behaviours shape every test below, and both were observed rather than recalled.
 *
 * **Updates are batched into a microtask.** A signal written here still reads its old value until
 * something flushes, so every assertion that follows a write calls `flush()` first. That is not
 * ceremony: a test that happened to pass without it would be asserting on a race.
 *
 * **A synchronous write inside an owned scope is an error in development**
 * (`REACTIVE_WRITE_IN_OWNED_SCOPE`). Wrapping these tests in `createRoot` therefore fails on the
 * first `select()` or `accept()` — even though the same call is correct in the product, where it
 * happens in an event handler or a promise continuation, both of which are outside the owning
 * scope. So the body runs unowned, which is the shape the real writes have.
 */
function inRoot<T>(body: () => T): T {
  return body();
}

describe("decoding a capability state", () => {
  it("reads the status discriminator the wire carries", () => {
    expect(decodeCapabilityState({ status: "available" })).toEqual({ status: "available" });
    expect(decodeCapabilityState({ status: "not_configured" })).toEqual({ status: "not_configured" });
  });

  /* The one that matters most: the generated types make these two shapes identical, so a decoder
   * that went by shape rather than by `status` would render "this deployment has no such thing" as
   * a working feature, or the reverse. */
  it("tells available and not-configured apart, which their generated shapes cannot", () => {
    const available = decodeCapabilityState({ status: "available" });
    const absent = decodeCapabilityState({ status: "not_configured" });
    expect(available).not.toEqual(absent);
  });

  it("keeps an unavailable frame usable when it is missing its reason", () => {
    expect(decodeCapabilityState({ status: "unavailable", message: "the broker went away" })).toEqual({
      status: "unavailable",
      reason: ReasonCodes.Unknown,
      message: "the broker went away",
    });
  });

  it("refuses a status this build has never heard of rather than guessing one", () => {
    expect(decodeCapabilityState({ status: "on_fire" })).toBeUndefined();
    expect(decodeCapabilityState(null)).toBeUndefined();
    expect(decodeCapabilityState("available")).toBeUndefined();
  });
});

describe("decoding a capability frame", () => {
  it("tells a snapshot from a delta by the field it carries", () => {
    const snapshot = decodeCapabilityFrame(
      JSON.stringify({
        generatedAt: "2026-09-05T00:00:00Z",
        entries: [{ key: { service: "topic" }, state: { status: "available" } }],
      }),
    );
    expect(snapshot?.kind).toBe("snapshot");

    const delta = decodeCapabilityFrame(
      JSON.stringify({ entry: { key: { service: "topic" }, state: { status: "available" } } }),
    );
    expect(delta?.kind).toBe("delta");
  });

  it("keeps the readable entries of a snapshot that has one bad one", () => {
    const frame = decodeCapabilityFrame(
      JSON.stringify({
        entries: [
          { key: { service: "topic" }, state: { status: "available" } },
          { key: {}, state: { status: "available" } },
          { key: { service: "message" }, state: { status: "on_fire" } },
        ],
      }),
    );
    expect(frame?.kind === "snapshot" ? frame.entries.map((e) => e.key.service) : []).toEqual(["topic"]);
  });

  it("never throws on rubbish", () => {
    expect(decodeCapabilityFrame("not json at all")).toBeUndefined();
    expect(decodeCapabilityFrame("[]")).toBeUndefined();
  });
});

/** Drives a store's stream by hand, with no `EventSource` and no clock. */
function testStore(poll: () => Promise<readonly CapabilityEntry[] | undefined> = async () => undefined) {
  let handlers: CapabilityStreamHandlers | undefined;
  const timers: (() => void)[] = [];
  const store = createCapabilityStore({
    openStream: (given) => {
      handlers = given;
      return () => {
        handlers = undefined;
      };
    },
    poll,
    schedule: (_delay, run) => timers.push(run),
  });
  store.start();
  return {
    store,
    frame: (value: unknown) => handlers?.onFrame(JSON.stringify(value)),
    rawFrame: (value: string) => handlers?.onFrame(value),
    open: () => handlers?.onOpen(),
    close: (reason: string) => handlers?.onClosed(reason),
    runTimers: () => {
      const due = timers.splice(0, timers.length);
      for (const run of due) run();
    },
  };
}

describe("the capability store", () => {
  it("applies a snapshot and then a delta over it", () => {
    inRoot(() => {
      const driver = testStore();
      driver.frame({
        entries: [
          { key: { service: "topic" }, state: { status: "available" } },
          { key: { service: "message" }, state: { status: "available" } },
        ],
      });
      flush();
      expect(driver.store.stateOf("topic")?.status).toBe("available");

      driver.frame({
        entry: {
          key: { service: "topic" },
          state: { status: "unavailable", reason: ReasonCodes.UpstreamUnavailable, message: "gone", since: "x" },
        },
      });
      flush();
      expect(driver.store.stateOf("topic")?.status).toBe("unavailable");
      /* The delta touched one key; the other must be exactly as it was. */
      expect(driver.store.stateOf("message")?.status).toBe("available");
    });
  });

  /* The defect this rule exists for: one unreadable frame used to be able to blank the whole
   * navigation, turning every feature from "working" to "unknown" over a typo in one delta. */
  it("leaves the picture untouched when a frame cannot be read", () => {
    inRoot(() => {
      const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
      const driver = testStore();
      driver.frame({ entries: [{ key: { service: "topic" }, state: { status: "available" } }] });
      flush();

      driver.rawFrame("{ this is not json");
      flush();

      expect(driver.store.stateOf("topic")?.status).toBe("available");
      expect(warn).toHaveBeenCalled();
      warn.mockRestore();
    });
  });

  /* Nothing has been observed to break; only the updates have stopped. Marking every feature
   * unavailable would take a working product off the air because one connection failed. */
  it("does not mark anything unavailable when the stream drops", () => {
    inRoot(() => {
      const driver = testStore();
      driver.frame({ entries: [{ key: { service: "topic" }, state: { status: "available" } }] });
      driver.close("the connection was reset");
      flush();

      expect(driver.store.stateOf("topic")?.status).toBe("available");
      expect(driver.store.connection()).toEqual({ kind: "closed", reason: "the connection was reset" });
    });
  });

  it("falls back to polling once the stream is closed, and stands down when it reopens", async () => {
    const polled: number[] = [];
    await inRoot(async () => {
      const driver = testStore(async () => {
        polled.push(1);
        return [{ key: { service: "topic" }, state: { status: "available" } }];
      });
      driver.close("gone");
      await Promise.resolve();
      flush();

      expect(polled.length).toBe(1);
      expect(driver.store.stateOf("topic")?.status).toBe("available");

      driver.open();
      driver.runTimers();
      expect(polled.length).toBe(1);
    });
  });

  it("remembers a display name and never unlearns it", () => {
    inRoot(() => {
      const driver = testStore();
      driver.frame({
        entries: [{ key: { service: "cluster", cluster: "prod" }, name: "Production EU", state: { status: "available" } }],
      });
      flush();
      expect(driver.store.names().get(capabilityKeyOf({ service: "cluster", cluster: "prod" }))).toBe(
        "Production EU",
      );

      /* A later frame carrying no name must leave the label standing rather than blank it. */
      driver.frame({
        entry: { key: { service: "cluster", cluster: "prod" }, state: { status: "degraded", reason: { code: "X", message: "slow" } } },
      });
      flush();
      expect(driver.store.names().get(capabilityKeyOf({ service: "cluster", cluster: "prod" }))).toBe(
        "Production EU",
      );
    });
  });
});

describe("deriving what the shell shows", () => {
  it("puts forbidden ahead of every health state", () => {
    /* A user who may not see a service must not learn from the navigation whether it is up. */
    expect(deriveFeatureState({ status: "available" }, false).kind).toBe("forbidden");
    expect(
      deriveFeatureState({ status: "unavailable", reason: "X", message: "down since Tuesday" }, false),
    ).toEqual({ kind: "forbidden" });
  });

  it("calls an unreported capability degraded-starting, never unavailable", () => {
    const state = deriveFeatureState(undefined, true);
    expect(state.kind).toBe("degraded");
    expect(state.kind === "degraded" ? state.reason.code : "").toBe(ReasonCodes.Starting);
  });

  it("maps each status to its rendering rule", () => {
    expect(isNavigable(deriveFeatureState({ status: "available" }, true))).toBe(true);
    /* Dimmed and still clickable: the fallback panel is the only place the reason and the retry are. */
    const down = deriveFeatureState({ status: "unavailable", reason: "X", message: "" }, true);
    expect(isDimmed(down)).toBe(true);
    expect(isNavigable(down)).toBe(true);
    /* Not configured is hidden, always. It is not a failure and must not be drawn as one. */
    expect(isHidden(deriveFeatureState({ status: "not_configured" }, true))).toBe(true);
    /* Forbidden is visible-but-disabled unless the deployment asks otherwise. */
    expect(isHidden(deriveFeatureState({ status: "available" }, false))).toBe(false);
    expect(isHidden(deriveFeatureState({ status: "available" }, false), true)).toBe(true);
  });

  it("prefers the gateway's own message and falls back to the reason's sentence", () => {
    const withMessage = deriveFeatureState(
      { status: "unavailable", reason: ReasonCodes.UpstreamUnavailable, message: "broker-1 refused" },
      true,
    );
    expect(explanation(withMessage, "Topics")).toBe("broker-1 refused");

    const bare = deriveFeatureState(
      { status: "unavailable", reason: ReasonCodes.UpstreamUnavailable, message: "" },
      true,
    );
    expect(explanation(bare, "Topics")).toBe("The cluster is not answering.");

    /* A reason a newer gateway invented still renders a sentence rather than nothing. */
    const future = deriveFeatureState({ status: "unavailable", reason: "SOLAR_FLARE", message: "" }, true);
    expect(explanation(future, "Topics")).toBe("KUI could not refresh this.");
  });
});

describe("the session", () => {
  const anonymous = { kind: "anonymous", name: "anonymous" };
  const person = { kind: "session", name: "ada", roles: ["operator"] };

  function session() {
    const settled: (string | undefined)[] = [];
    const state = createSession({
      settleCsrf: (token) => settled.push(token),
      invalidateCsrf: () => settled.push("invalidated"),
    });
    return { state, settled };
  }

  /* The demonstration environment's front door. A locked door here is worse than any other bug. */
  it("shows no sign-in screen when authentication is disabled", () => {
    inRoot(() => {
      const { state } = session();
      state.acceptSettings({ authType: "disabled", rbacEnabled: false });
      state.accept({ authType: "disabled", csrfToken: "", principal: anonymous });
      flush();
      expect(state.mustSignIn()).toBe(false);
    });
  });

  it("shows no sign-in screen while the settings call is still in flight", () => {
    inRoot(() => {
      const { state } = session();
      expect(state.mustSignIn()).toBe(false);
    });
  });

  it("shows no sign-in screen when the settings call fails outright", () => {
    inRoot(() => {
      const { state } = session();
      state.acceptSettings(undefined);
      state.accept({ authType: "oidc", csrfToken: "", principal: anonymous });
      flush();
      expect(state.mustSignIn()).toBe(false);
    });
  });

  it("asks an anonymous visitor to sign in when the deployment is configured for it", () => {
    inRoot(() => {
      const { state } = session();
      state.acceptSettings({ authType: "oidc", rbacEnabled: true });
      state.accept({ authType: "oidc", csrfToken: "", principal: anonymous });
      flush();
      expect(state.mustSignIn()).toBe(true);
    });
  });

  it("does not ask a signed-in user to sign in again", () => {
    inRoot(() => {
      const { state } = session();
      state.acceptSettings({ authType: "oidc", rbacEnabled: true });
      state.accept({ authType: "oidc", csrfToken: "t", principal: person });
      flush();
      expect(state.mustSignIn()).toBe(false);
      expect(state.signedIn()).toBe(true);
    });
  });

  it("hands the CSRF token on, and treats an empty one as no token", () => {
    inRoot(() => {
      const { state, settled } = session();
      state.accept({ authType: "oidc", csrfToken: "", principal: anonymous });
      expect(settled).toEqual([undefined]);
      state.accept({ authType: "oidc", csrfToken: "abc", principal: person });
      expect(settled).toEqual([undefined, "abc"]);
    });
  });

  it("empties the identity and the token when the session lapses", () => {
    inRoot(() => {
      const { state, settled } = session();
      state.accept({ authType: "oidc", csrfToken: "abc", principal: person });
      state.markExpired();
      flush();
      expect(state.identity()).toBeUndefined();
      expect(state.signedIn()).toBe(false);
      expect(settled).toContain("invalidated");
    });
  });

  it("answers what a control may do from the grants the session carried", () => {
    inRoot(() => {
      const { state } = session();
      state.accept({
        authType: "oidc",
        csrfToken: "abc",
        principal: person,
        permissions: [
          { resource: "topic", actions: ["view", "edit"], clusters: ["prod"] },
          { resource: "cluster", actions: ["view"], clusters: ["*"] },
        ],
      });
      flush();
      expect(state.permits("topic", "edit", "prod")).toBe(true);
      expect(state.permits("topic", "edit", "staging")).toBe(false);
      expect(state.permits("topic", "delete", "prod")).toBe(false);
      expect(state.permits("cluster", "view", "anywhere")).toBe(true);
    });
  });
});

describe("the current cluster", () => {
  it("remembers the choice, and survives storage that throws", () => {
    inRoot(() => {
      const store = new Map<string, string>();
      const working: Storage = {
        getItem: (key) => store.get(key) ?? null,
        setItem: (key, value) => void store.set(key, value),
        removeItem: (key) => void store.delete(key),
        clear: () => store.clear(),
        key: () => null,
        length: 0,
      };
      const chosen = createCurrentCluster({ storage: working });
      chosen.select("prod");
      flush();
      expect(store.get(CurrentClusterStorageKey)).toBe("prod");
      expect(createCurrentCluster({ storage: working }).selected()).toBe("prod");

      const hostile: Storage = {
        ...working,
        getItem: () => {
          throw new Error("blocked");
        },
        setItem: () => {
          throw new Error("blocked");
        },
      };
      const degraded = createCurrentCluster({ storage: hostile });
      degraded.select("staging");
      flush();
      expect(degraded.selected()).toBe("staging");
    });
  });

  it("chooses for a deployment with exactly one cluster and never for one with two", () => {
    expect(soleClusterChoice([{ id: "only" }], undefined)).toBe("only");
    expect(soleClusterChoice([{ id: "a" }, { id: "b" }], undefined)).toBeUndefined();
    /* An existing choice is never overridden; this only fills in a blank. */
    expect(soleClusterChoice([{ id: "only" }], "other")).toBeUndefined();
  });
});
