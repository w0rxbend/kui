/**
 * The one place `unknown` is turned into a typed value, and the reason it has to exist.
 *
 * ## What a section is
 *
 * KUI's aggregated responses are partial by design (ADR-039): a topic page asks for six things from
 * five services and renders whatever came back, with a per-section status, rather than failing whole
 * because the schema registry is down. Each part arrives as a *section* — a small envelope carrying
 * one of five statuses and, for the two that succeeded, the data.
 *
 * ## Why this file is not generated
 *
 * It should be, and one day it will be. The server documents `Section[A]` with `Schema.any`, because
 * Tapir cannot see inside a union of five case classes and the author judged that a vague schema is
 * better than a lying one. Under ADR-011 that cost nothing: the browser compiled against the Scala
 * `Section[A]` itself, so the type was exact no matter what the document said. Under ADR-048 the
 * document *is* the browser's type, so `Schema.any` becomes `unknown` — and it lands on precisely
 * the fifteen properties the four feature screens are built from (`TopicsResponse.topics`,
 * `GroupsResponse.groups`, `ClusterOverviewDto.clusters`, and twelve more).
 *
 * That gap is recorded in `BLOCKERS.md` with a proposed schema, and it is a server-side fix. Until
 * it lands, this file is the *single* boundary at which those `unknown`s become typed: features call
 * {@link decodeSection} once per section and are typed from there on. When the server's schema is
 * fixed, the generated types replace this and the call sites keep working — `decodeSection` becomes a
 * no-op check and then a deletion.
 *
 * Concentrating the gap in one function is the point. Fifteen call sites each writing their own cast
 * is fifteen places to get it wrong and no place to fix it.
 */

/** The five states a section can be in, exactly as the server writes them. */
export type SectionStatus = "ok" | "stale" | "unavailable" | "forbidden" | "not_configured";

/** Why a section is not `ok`, when the server said. */
export interface SectionReason {
  /** A machine-readable reason a screen can branch on. */
  readonly code: string;
  /** What to show a person. */
  readonly message?: string;
  /** When the failure started, RFC 3339. */
  readonly since?: string;
}

/** One part of an aggregated response. */
export type Section<T> =
  | { readonly status: "ok"; readonly data: T; readonly fetchedAt: string }
  | {
      readonly status: "stale";
      readonly data: T;
      readonly fetchedAt: string;
      readonly reason: SectionReason;
    }
  | { readonly status: "unavailable"; readonly reason: SectionReason }
  | { readonly status: "forbidden" }
  | { readonly status: "not_configured" }
  /** The server sent something this build does not recognise. Rendered as unavailable, never dropped. */
  | { readonly status: "unreadable"; readonly reason: SectionReason };

/**
 * Reads a section out of an `unknown` property, checking everything except the payload's own shape.
 *
 * The payload is *not* validated. It came from the server, which produced it from the same contract
 * the browser generated its types from, and re-validating every field in the browser would be a
 * second schema to keep in step — the thing this migration exists to avoid. What is checked is the
 * envelope: the status is one of the five, and `data` is present when the status says it should be.
 *
 * Anything else becomes `unreadable`, which the navigation renders exactly as `unavailable` with the
 * reason shown. That is deliberate: a section this build cannot read is a section the user cannot
 * see, and saying so is the behaviour ADR-032 requires — never a blank panel, never a thrown error.
 */
export function decodeSection<T>(raw: unknown): Section<T> {
  if (typeof raw !== "object" || raw === null) {
    return unreadable(`expected a section object, got ${raw === null ? "null" : typeof raw}`);
  }

  const candidate = raw as Record<string, unknown>;
  const status = candidate["status"];
  const fetchedAt = typeof candidate["fetchedAt"] === "string" ? candidate["fetchedAt"] : "";

  switch (status) {
    case "ok":
      if (!("data" in candidate)) return unreadable("an 'ok' section carried no data");
      return { status: "ok", data: candidate["data"] as T, fetchedAt };
    case "stale":
      if (!("data" in candidate)) return unreadable("a 'stale' section carried no data");
      return {
        status: "stale",
        data: candidate["data"] as T,
        fetchedAt,
        reason: readReason(candidate),
      };
    case "unavailable":
      return { status: "unavailable", reason: readReason(candidate) };
    case "forbidden":
      return { status: "forbidden" };
    case "not_configured":
      return { status: "not_configured" };
    default:
      return unreadable(`unknown section status ${JSON.stringify(status)}`);
  }
}

/** The data a section carries, or `undefined` when it carries none. */
export function sectionData<T>(section: Section<T>): T | undefined {
  return section.status === "ok" || section.status === "stale" ? section.data : undefined;
}

function readReason(candidate: Record<string, unknown>): SectionReason {
  const reason = candidate["reason"];
  const message = candidate["message"];
  const since = candidate["since"];
  return {
    code: typeof reason === "string" ? reason : "unknown",
    ...(typeof message === "string" ? { message } : {}),
    ...(typeof since === "string" ? { since } : {}),
  };
}

function unreadable<T>(cause: string): Section<T> {
  return { status: "unreadable", reason: { code: "unreadable", message: cause } };
}
