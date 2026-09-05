/**
 * The schema feature's data layer.
 *
 * ## Two facts this screen exists to separate
 *
 * A schema registry has a *global* compatibility level and a per-subject one, and a subject either
 * has its own or inherits the global. That distinction is the single most consequential thing on
 * these screens: changing the global level changes what every inheriting subject will accept, and an
 * operator who cannot see which subjects inherit cannot know what they are about to change.
 *
 * So `inheritedFromGlobal` is carried through rather than flattened into a level string, and the
 * screens say "BACKWARD, inherited" and "BACKWARD, set on this subject" as different sentences.
 */
import type { ApiResult, KuiApiClient } from "@kui/api";
import { apiFailure, type Fetched } from "@kui/kernel";

/**
 * The compatibility levels a Confluent-compatible registry knows.
 *
 * A closed set, checked rather than passed through: the level decides whether tomorrow's schema is
 * accepted, and rendering a word the browser does not recognise as though it were a level would put
 * an unexplained value in front of somebody about to make a decision from it. `NONE` is in the list
 * and is the dangerous one — it means the registry checks nothing.
 */
export const COMPATIBILITY_LEVELS = [
  "BACKWARD",
  "BACKWARD_TRANSITIVE",
  "FORWARD",
  "FORWARD_TRANSITIVE",
  "FULL",
  "FULL_TRANSITIVE",
  "NONE",
] as const;

export type CompatibilityLevel = (typeof COMPATIBILITY_LEVELS)[number];

export function levelOf(raw: string | null | undefined): CompatibilityLevel | null {
  if (typeof raw !== "string") return null;
  const upper = raw.toUpperCase();
  return (COMPATIBILITY_LEVELS as readonly string[]).includes(upper)
    ? (upper as CompatibilityLevel)
    : null;
}

export interface Compatibility {
  readonly level: CompatibilityLevel | null;
  /** Whether this subject follows the global level rather than one of its own. */
  readonly inherited: boolean;
}

/** Where a page of subjects sits in the whole list. */
export interface PageInfo {
  readonly page: number;
  readonly pageSize: number;
  /** `undefined` when the registry did not count. Not zero. */
  readonly totalItems: number | undefined;
}

export interface SubjectListResult {
  readonly subjects: readonly string[];
  readonly page: PageInfo;
}

export interface SubjectQuery {
  readonly q?: string | undefined;
  readonly direction?: "asc" | "desc" | undefined;
  readonly page?: number | undefined;
  readonly pageSize?: number | undefined;
}

interface SubjectsPayload {
  readonly items?: readonly string[];
  readonly page?: {
    readonly page?: number;
    readonly pageSize?: number;
    readonly totalItems?: number;
  } | null;
}

export async function fetchSubjects(
  api: KuiApiClient,
  clusterId: string,
  query: SubjectQuery = {},
): Promise<Fetched<SubjectListResult>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/schemas/subjects", {
    params: {
      path: { clusterId },
      query: {
        ...(query.q === undefined || query.q === "" ? {} : { q: query.q }),
        ...(query.direction === undefined ? {} : { direction: query.direction }),
        ...(query.page === undefined ? {} : { page: query.page }),
        ...(query.pageSize === undefined ? {} : { pageSize: query.pageSize }),
      },
    },
  });
  if (!answer.ok) return apiFailure(answer.error);

  /*
   * Not a section: this endpoint answers with the page directly, because a subject list has nothing
   * to be partial about — either the registry answered or it did not, and "did not" is a transport
   * failure that `apiFailure` has already turned into a value.
   */
  const payload = answer.value as unknown as SubjectsPayload;
  return {
    kind: "ready",
    value: {
      subjects: payload.items ?? [],
      page: {
        page: payload.page?.page ?? 1,
        pageSize: payload.page?.pageSize ?? (payload.items?.length ?? 0),
        totalItems: typeof payload.page?.totalItems === "number" ? payload.page.totalItems : undefined,
      },
    },
  };
}

export async function fetchGlobalCompatibility(
  api: KuiApiClient,
  clusterId: string,
): Promise<Fetched<Compatibility>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/schemas/compatibility", {
    params: { path: { clusterId } },
  });
  if (!answer.ok) return apiFailure(answer.error);
  return {
    kind: "ready",
    value: {
      level: levelOf(answer.value.level),
      // The global level is by definition not inherited from anything; the wire says so and this
      // keeps it rather than assuming, because the two endpoints share a DTO.
      inherited: answer.value.inheritedFromGlobal === true,
    },
  };
}

export async function fetchSubjectCompatibility(
  api: KuiApiClient,
  clusterId: string,
  subject: string,
): Promise<Fetched<Compatibility>> {
  const answer = await api.get(
    "/api/v1/clusters/{clusterId}/schemas/subjects/{subject}/compatibility",
    { params: { path: { clusterId, subject } } },
  );
  if (!answer.ok) return apiFailure(answer.error);
  return {
    kind: "ready",
    value: {
      level: levelOf(answer.value.level),
      inherited: answer.value.inheritedFromGlobal === true,
    },
  };
}

export async function fetchVersions(
  api: KuiApiClient,
  clusterId: string,
  subject: string,
): Promise<Fetched<readonly number[]>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions", {
    params: { path: { clusterId, subject } },
  });
  if (!answer.ok) return apiFailure(answer.error);
  // Ascending on the wire; the screen wants newest first, because that is the one anybody opens.
  return { kind: "ready", value: [...(answer.value.versions ?? [])].sort((a, b) => b - a) };
}

/** One registered version. */
export interface SchemaVersion {
  readonly subject: string;
  readonly version: number;
  /** The registry's own id, which is what a record's header carries. */
  readonly id: number;
  readonly schemaType: string;
  readonly definition: string;
  readonly references: readonly { readonly name: string; readonly subject: string; readonly version: number }[];
}

export async function fetchSchema(
  api: KuiApiClient,
  clusterId: string,
  subject: string,
  version: string,
): Promise<Fetched<SchemaVersion>> {
  const answer = await api.get(
    "/api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions/{version}",
    { params: { path: { clusterId, subject, version } } },
  );
  if (!answer.ok) return apiFailure(answer.error);
  return {
    kind: "ready",
    value: {
      subject: answer.value.subject,
      version: answer.value.version,
      id: answer.value.id,
      schemaType: answer.value.schemaType,
      definition: answer.value.definition,
      references: (answer.value.references ?? []).map((reference) => ({
        name: reference.name,
        subject: reference.subject,
        version: reference.version,
      })),
    },
  };
}

/* ------------------------------------------------------------------------------------------------
 * Checking a schema before it is registered
 * ---------------------------------------------------------------------------------------------- */

/** A schema somebody has typed and not registered. `schemaType` is `AVRO`, `JSON` or `PROTOBUF`. */
export interface ProposedSchema {
  readonly schemaType: string;
  readonly definition: string;
}

/**
 * The registry's answer.
 *
 * `messages` is the registry's own prose and is reproduced word for word. It is not a formality: a
 * Confluent registry answers a refusal with the field path, the reader type, the writer type and the
 * whole schema it compared against, and every one of those is something the operator has to know to
 * fix the schema. Summarising it into "not backward compatible" would throw away the only part of
 * the answer that says what to change.
 *
 * It is also routinely **empty on a refusal**, which is why it is a separate fact from `compatible`
 * rather than the reason for it. Apicurio's Confluent-compatible API words its explanation under
 * `reason`, which KUI's registry client does not read, so against the quickstart's registry a
 * genuine refusal arrives as `{"compatible": false, "messages": []}`. A screen that assumed a
 * refusal always carries messages would render that as a blank space where the reason should be.
 */
export interface CompatibilityVerdict {
  readonly compatible: boolean;
  readonly messages: readonly string[];
}

/**
 * Which version the proposed schema is checked against.
 *
 * `latest` and not a number. The question the panel asks is "would this be accepted if I registered
 * it now", and what the registry compares a new version against is the current one — so `latest` is
 * the version that answers the question asked, and it stays the right answer if somebody registers a
 * version while the panel is open. Under a transitive level (`BACKWARD_TRANSITIVE` and its two
 * siblings) the registry widens the comparison to every version by itself; that is the registry's
 * decision and neither the word nor a number changes it.
 *
 * A number is a legitimate thing to ask the registry and is deliberately not offered here, because
 * "is this compatible with v2" is a question about history rather than about the change being made,
 * and against this gateway a version that does not exist is reported as `KUI-SCHEMA-NOT-FOUND`
 * saying the *subject* does not exist — an answer that would send an operator looking for the wrong
 * fault.
 */
const CheckAgainstVersion = "latest";

/**
 * Asks the registry whether it would accept this schema. Registers nothing.
 *
 * It returns an `ApiResult` rather than a `Fetched` because the panel runs it on a press and needs
 * the running/failed states a `Mutation` gives — not because it writes anything. The endpoint
 * carries no mutation marker on the server either, and is answered on a read-only cluster like any
 * other read.
 */
export async function checkCompatibility(
  api: KuiApiClient,
  clusterId: string,
  subject: string,
  proposed: ProposedSchema,
): Promise<ApiResult<CompatibilityVerdict>> {
  const answer = await api.post(
    "/api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions/{version}/compatibility",
    {
      params: { path: { clusterId, subject, version: CheckAgainstVersion } },
      body: { schemaType: proposed.schemaType, definition: proposed.definition },
    },
  );
  if (!answer.ok) return answer;
  return {
    ok: true,
    value: {
      compatible: answer.value.compatible,
      // Absent and empty are the same thing here — both mean the registry said nothing — and the
      // screen has to distinguish "no reason given" from "not asked", which it does from the verdict
      // being present at all rather than from this list.
      messages: answer.value.messages ?? [],
    },
  };
}

/**
 * The schema languages whose definition is a JSON document.
 *
 * Protobuf is deliberately not among them: a `.proto` definition is its own syntax and is not JSON,
 * so parsing it here would refuse every correct Protobuf schema anybody typed.
 */
const JSON_SCHEMA_TYPES: readonly string[] = ["AVRO", "JSON"];

/**
 * Why this text cannot be sent, or `undefined`.
 *
 * The registry validates it and its refusal is authoritative. This checks the same thing anyway, for
 * the reason `CreateTopicDialog` checks a topic name in the browser: a round trip that comes back
 * "Could not execute compatibility rule on invalid Avro schema" — which is verbatim what this
 * gateway answers for a definition that is not JSON — tells the operator less than the parser's own
 * position does, and tells it later.
 */
export function proposedSchemaProblem(
  schemaType: string,
  definition: string,
): string | undefined {
  if (definition.trim() === "") return undefined; // Not yet typed is not yet wrong.
  if (!JSON_SCHEMA_TYPES.includes(schemaType.toUpperCase())) return undefined;
  try {
    JSON.parse(definition);
    return undefined;
  } catch (problem) {
    // The parser's own message, which names the position. "Invalid JSON" names nothing.
    const stated = problem instanceof Error ? problem.message : String(problem);
    return `This is not valid JSON, so the registry cannot read it as ${schemaType.toUpperCase()}: ${stated}`;
  }
}

/**
 * Whether a check against this level can tell the operator anything.
 *
 * `NONE` means the registry checks nothing, so it answers `compatible: true` for every schema —
 * including one that breaks every existing reader. The recorded documents prove it: the verdict for
 * a schema that changes a field's type and adds a required field is byte-for-byte the verdict for
 * the schema that is already registered. Nothing in the answer distinguishes them, so the panel has
 * to know from the level, and this is the one place that decides it.
 */
export function checkIsMeaningful(level: CompatibilityLevel | null): boolean {
  return level !== "NONE";
}

/**
 * Why the check cannot be run, or `undefined`.
 *
 * A function rather than four conditions inside the panel, because the order of these four answers
 * is a decision and not an accident: the level comes first, since under `NONE` nothing typed in the
 * box could produce an answer worth having and saying "paste a schema first" would send somebody off
 * to do work that ends in a meaningless verdict. `Button`'s type requires that whatever this returns
 * be shown to the operator, which is the rule that stops a greyed-out control with no explanation.
 */
export function checkBlockedReason(input: {
  /** `undefined` while the level is still being read; not yet known is not yet `NONE`. */
  readonly level: CompatibilityLevel | null | undefined;
  readonly schemaType: string;
  readonly definition: string;
  readonly busy: boolean;
}): string | undefined {
  if (input.level !== undefined && !checkIsMeaningful(input.level)) {
    return (
      "This subject's compatibility level is NONE, so the registry accepts every schema. It would " +
      "answer “compatible” for whatever is typed here, which is not an answer worth having."
    );
  }
  if (input.definition.trim() === "") return "Paste the schema you want to check first.";
  const stated = proposedSchemaProblem(input.schemaType, input.definition);
  if (stated !== undefined) return stated;
  if (input.busy) return "Waiting for the registry to answer.";
  return undefined;
}

/** Sets the level for one subject, or for the registry as a whole. */
export async function setCompatibility(
  api: KuiApiClient,
  clusterId: string,
  level: CompatibilityLevel,
  subject?: string,
): Promise<ApiResult<unknown>> {
  if (subject === undefined) {
    return api.put("/api/v1/clusters/{clusterId}/schemas/compatibility", {
      params: { path: { clusterId } },
      body: { level },
    });
  }
  return api.put("/api/v1/clusters/{clusterId}/schemas/subjects/{subject}/compatibility", {
    params: { path: { clusterId, subject } },
    body: { level },
  });
}
