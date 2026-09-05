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
