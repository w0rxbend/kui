/**
 * Type-level regressions for the two defects that only a mutation could reveal.
 *
 * These are checked by `tsc`, not by a runner: every line below is a compile error if the type is
 * wrong, and the `it` at the bottom is only there so the file is a legal test. Both bugs produced code
 * that *ran* correctly — the client always sent the CSRF header, and the arrays were always arrays
 * at runtime — while making the call site impossible to write, so nothing but the compiler can
 * notice either of them coming back.
 */
import { describe, expect, it } from "vitest";
import type { KuiApiClient } from "./client.js";

declare const api: KuiApiClient;

/**
 * Everything below is inside a function that is never called.
 *
 * `api` is a declaration, not a value: there is no client here and no server to talk to. The point
 * is that these lines *type-check*, and calling them would only produce a `TypeError` that says
 * nothing about the thing being tested.
 */
async function callsThatMustCompile(): Promise<void> {
  /*
   * `POST …/topics` declares `X-Csrf-Token` as a required header parameter, because the gateway
   * requires it. The client supplies it on every non-GET, waiting for start-up if it must. If
   * `WithoutCsrf` stops dropping the requirement, this call stops compiling — and the tempting fix
   * at that point is for the call site to obtain a token of its own, which is how this product
   * shipped a browser that sent the wrong header name to begin with.
   */
  void api.post("/api/v1/clusters/{clusterId}/topics", {
    params: { path: { clusterId: "quickstart" } },
    body: { name: "orders", config: {} },
  });

  const answer = await api.post(
    "/api/v1/clusters/{clusterId}/topics/{topicName}/messages/purge/plan",
    {
      params: { path: { clusterId: "quickstart", topicName: "orders" } },
    },
  );
  if (!answer.ok) return;

  /*
   * The bug: `openapi-typescript-helpers`' `Readable<T>` matches `(infer E)[]` — a mutable array —
   * and our schema is generated `immutable`, so every array in it is `readonly E[]` and falls
   * through to the object case. Mapping over an array's members turns `map` into `{}`, and the
   * line below fails with "this expression is not callable".
   *
   * It went unnoticed for the whole of the read work because ADR-039 sections are `Schema.any` on
   * the wire and are decoded through hand-written types. This is the first typed array a screen
   * needed.
   */
  const codes: readonly string[] = (answer.value.warnings ?? []).map(
    (warning) => warning.code,
  );
  const partitions: number = (answer.value.partitions ?? []).filter(
    (partition) => partition.highWatermark > partition.lowWatermark,
  ).length;

  void codes;
  void partitions;
}

describe("the client's call signatures", () => {
  it("compile", () => {
    // The assertion is that this file compiled at all. `tsc` is the runner; this keeps the file a
    // legal test so it cannot be quietly dropped from the suite.
    expect(callsThatMustCompile).toBeInstanceOf(Function);
  });
});
