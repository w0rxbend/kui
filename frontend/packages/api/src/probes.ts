import type { KuiApiClient } from "./client.js";
import { decodeSection, sectionData } from "./section.js";
import type { components } from "./schema.js";

/**
 * Call sites that exist so that a contract change fails the build.
 *
 * ## Why a file of unused functions is the point
 *
 * ADR-048 §3 claims that renaming a field on the server fails `tsc` in the browser. That claim is
 * only true where the browser actually *reads* the field: generated types on their own assert
 * nothing, because a type nobody uses cannot be wrong. Until the kernel and the feature packages
 * exist, nothing reads anything, and the guarantee would be unproven for as long as the migration
 * takes — which is exactly the window in which a silent drift would go unnoticed.
 *
 * So this file names one representative shape from each service the browser talks to and reads a
 * field off it. `tsc --build` checks it like any other source, and `./mill frontend.typecheck` runs
 * that. A field renamed in a Tapir endpoint therefore fails the build here on the day it happens,
 * with an error naming the field and the path.
 *
 * ## What happens to it in the bundle
 *
 * Nothing: it is not exported from `index.ts` and nothing imports it, so Rollup never reaches it and
 * no byte of it ships. It is a build-time artefact that happens to be written in the product's
 * language.
 *
 * ## What to do when this file stops compiling
 *
 * Read the error. It names a field the server has renamed, removed or re-typed. Fix the call site
 * the same way every other call site will have to be fixed, and — this is the important half — check
 * whether the change was intended before you make the probe agree with it.
 *
 * ## When to delete it
 *
 * When the four feature packages read these same shapes in earnest, the probes are redundant. Until
 * then they are the only thing holding the guarantee up, so they stay.
 */

/** The cluster list, and the timestamp the switcher shows beside it. */
export async function probeClusters(api: KuiApiClient): Promise<string | undefined> {
  const answer = await api.get("/api/v1/clusters");
  return answer.ok ? answer.value.generatedAt : undefined;
}

/**
 * One consumer group's lag, which the consumers list sorts by.
 *
 * It goes through `decodeSection` because `GroupsResponse.groups` is one of the fifteen properties
 * the server documents as `Schema.any` — see `section.ts`. The named type on the left of the call is
 * what makes this a contract check even so: `GroupSummaryDto` is generated, so renaming `totalLag`
 * on the server still fails this line.
 */
export async function probeGroupLag(api: KuiApiClient, cluster: string): Promise<number | undefined> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/consumer-groups", {
    params: { path: { clusterId: cluster } },
  });
  if (!answer.ok) return undefined;
  const groups = sectionData(
    decodeSection<readonly components["schemas"]["GroupSummaryDto"][]>(answer.value.groups),
  );
  return groups?.[0]?.totalLag;
}

/**
 * One row of the cluster list, as the switcher renders it.
 *
 * Note what this probe cannot do, because it is the sharper half of the same finding. The topic
 * list's element type has **no name in the document at all**: `TopicsResponse.topics` is
 * `Schema.any`, so the DTO inside it was never registered as a component and there is nothing to
 * import. `ClusterRowDto` survives only because another endpoint mentions it by name. Until the
 * server's section schema is fixed (`BLOCKERS.md`), the topic list is untypeable in the browser and
 * no amount of TypeScript can change that.
 */
export async function probeClusterRow(api: KuiApiClient): Promise<string | undefined> {
  const answer = await api.get("/api/v1/clusters");
  if (!answer.ok) return undefined;
  const clusters = sectionData(
    decodeSection<readonly components["schemas"]["ClusterRowDto"][]>(answer.value.clusters),
  );
  return clusters?.[0]?.name;
}

/**
 * A mutation, which is where the CSRF header and the request body are both checked.
 *
 * The header is *not* named here: the client adds it, from the generated constant, on every
 * non-`GET`. That is the property {@link import("./csrf.js").CsrfTokens} exists to guarantee, and a
 * call site that had to remember it would be a call site that could forget it.
 */
export async function probeRefresh(api: KuiApiClient, cluster: string): Promise<boolean> {
  const answer = await api.post("/api/v1/clusters/{clusterId}/refresh", {
    params: { path: { clusterId: cluster } },
  });
  return answer.ok;
}

/** The session, which is where the CSRF token itself comes from. */
export async function probeSession(api: KuiApiClient): Promise<string | undefined> {
  const answer = await api.get("/api/v1/auth/me");
  return answer.ok ? answer.value.csrfToken : undefined;
}

/** The capability fold the navigation's five states are computed from (ADR-039). */
export async function probeCapabilities(api: KuiApiClient): Promise<number | undefined> {
  const answer = await api.get("/api/v1/capabilities");
  return answer.ok ? answer.value.entries?.length : undefined;
}

/**
 * The error envelope, read as a type rather than through a call: every failing response in the
 * document uses it, so a change to its shape must fail here too.
 */
export type ProbeEnvelope = components["schemas"]["ErrorEnvelope"];
const probeEnvelopeFields: ReadonlyArray<keyof ProbeEnvelope> = [
  "code",
  "message",
  "correlationId",
  "retryable",
];
export const ProbeEnvelopeFieldCount = probeEnvelopeFields.length;
