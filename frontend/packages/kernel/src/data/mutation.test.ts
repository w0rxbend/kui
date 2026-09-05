import { describe, expect, it, vi } from "vitest";
import { createRoot } from "solid-js";
import { ErrorCodes, type ApiError, type ErrorCode } from "@kui/api";
import { createMutation, writeBlockedReason } from "./mutation.js";

/** One error envelope, with the fields the server always sends filled in. */
function envelope(code: ErrorCode | string, message: string): ApiError {
  return {
    kind: "envelope",
    code: code as ErrorCode,
    message,
    details: [],
    correlationId: "test",
    retryable: false,
  };
}

/** Runs a body inside a reactive owner, so signals may be created and disposed. */
function withRoot<T>(body: () => T): T {
  return createRoot((dispose) => {
    const value = body();
    dispose();
    return value;
  });
}

describe("createMutation", () => {
  it("refuses to start a second run while the first is out", async () => {
    /*
     * The point of the whole type. These are POSTs and DELETEs against somebody's cluster: two
     * clicks on "Create topic" is two creates, and the second one comes back "topic already
     * exists" — the first one's *success*, reported to the operator as a failure.
     */
    let release: (() => void) | undefined;
    const call = vi.fn(
      async () =>
        new Promise<{ ok: true; value: string }>((resolve) => {
          release = () => resolve({ ok: true, value: "made" });
        }),
    );

    const { first, second, mutation } = withRoot(() => {
      const mutation = createMutation(call);
      return { first: mutation.run(), second: mutation.run(), mutation };
    });

    expect(call).toHaveBeenCalledTimes(1);
    // The refused call answers with the state rather than throwing or hanging: a caller awaiting it
    // gets an answer, and the answer is "one is already running".
    expect((await second).kind).toBe("running");
    expect(mutation.busy()).toBe(true);

    release?.();
    expect((await first).kind).toBe("done");
    expect(mutation.busy()).toBe(false);
  });

  it("keeps a failure as a value rather than throwing", async () => {
    const state = await withRoot(() =>
      createMutation(async () => ({
        ok: false as const,
        error: envelope("KUI-TOPIC-EXISTS", "A topic with that name already exists."),
      })).run(),
    );
    expect(state.kind).toBe("failed");
    if (state.kind !== "failed") return;
    expect(state.code).toBe("KUI-TOPIC-EXISTS");
  });

  it("keeps forbidden apart from failed", async () => {
    // A retry button on a 403 is a button that cannot work. The two states exist so the control can
    // say "you may not do this" instead of offering an action that will fail identically.
    const state = await withRoot(() =>
      createMutation(async () => ({
        ok: false as const,
        error: envelope(ErrorCodes.Forbidden, "You may not create topics on this cluster."),
      })).run(),
    );
    expect(state.kind).toBe("forbidden");
  });

  it("stores a function result rather than calling it", async () => {
    // A signal setter given a function calls it. A `done` value that happens to be a function would
    // be invoked and its return value stored, which is a bug that only appears for one shape of T.
    const answer = () => "value";
    const state = await withRoot(() =>
      createMutation(async () => ({ ok: true as const, value: answer })).run(),
    );
    expect(state.kind).toBe("done");
    if (state.kind !== "done") return;
    expect(state.value).toBe(answer);
  });
});

describe("writeBlockedReason", () => {
  it("blames the cluster, not the operator, when the cluster is read-only", () => {
    // Telling somebody to ask an administrator for a permission they already hold wastes their
    // afternoon. Read-only is a property of the deployment (ADR-047), not of the principal.
    const reason = writeBlockedReason({
      permitted: true,
      readOnly: true,
      action: "create a topic",
    });
    expect(reason).toMatch(/read-only/);
  });

  it("says nothing when the action is available", () => {
    expect(
      writeBlockedReason({
        permitted: true,
        readOnly: false,
        action: "create a topic",
      }),
    ).toBeUndefined();
  });
});
