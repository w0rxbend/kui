/**
 * Reading one frame of the capability stream (ADR-032, ADR-035).
 *
 * ## Why there is a hand-written decoder here at all
 *
 * Everywhere else in this frontend a wire shape is a generated type and nothing checks it at run
 * time, because the server produced it from the same contract the types were generated from. A
 * stream frame is the one place that argument does not reach: it arrives as a `data:` line, so
 * something has to turn a string into a value, and the *snapshot* and the *delta* travel under one
 * event name and are told apart by their shape.
 *
 * So this file decodes, and it does it against the generated types rather than beside them: every
 * field name below is read off `components["schemas"]["CapabilitySnapshot"]` and friends, and every
 * status literal off the generated `CapabilityStatuses`. Rename a field on the server, regenerate,
 * and this file stops compiling — which is the property the migration exists to keep.
 *
 * ## The rule the decoder obeys
 *
 * A frame it cannot read is reported and dropped, and the picture is left exactly as it was. Wiping
 * the navigation because of one bad frame would be the worst possible failure mode: every feature
 * would go from "working" to "unknown" because of a typo in one delta.
 */
import { CapabilityStatuses, ReasonCodes, type components } from "@kui/api";

type SnapshotDto = components["schemas"]["CapabilitySnapshot"];
type EntryDto = components["schemas"]["CapabilityEntry"];
type KeyDto = components["schemas"]["CapabilityKey"];
type DegradedReasonDto = components["schemas"]["DegradedReason"];

/**
 * What one capability is doing.
 *
 * A discriminated union, which the generated type is not: Tapir documents the four cases as a bare
 * union and leaves the `status` field it actually writes out of the schema (its Circe codec is what
 * writes it). The literals below are taken from the generated `CapabilityStatuses` so that the
 * discriminator is still generated even though the union is assembled here.
 */
export type CapabilityState =
  | { readonly status: typeof CapabilityStatuses.Available }
  | { readonly status: typeof CapabilityStatuses.Degraded; readonly reason: DegradedReasonDto }
  | {
      readonly status: typeof CapabilityStatuses.Unavailable;
      /** Why, as a machine-readable code — one of the generated `ReasonCodes`. */
      readonly reason: string;
      readonly message: string;
      /** When the failure started, RFC 3339. Sticky: it is the gateway's, never the browser's clock. */
      readonly since: string;
    }
  | { readonly status: typeof CapabilityStatuses.NotConfigured };

/** One capability, its state, and when the gateway last decided it. */
export interface CapabilityEntry {
  readonly key: CapabilityKey;
  readonly state: CapabilityState;
  /** RFC 3339. The gateway's clock, so two browsers agree about when something changed. */
  readonly updatedAt: string;
  /** What a person calls this thing, when the gateway knows: a cluster's display name. */
  readonly name: string | undefined;
}

/** Which capability an entry is about: a service, optionally on one cluster. */
export interface CapabilityKey {
  readonly service: string;
  readonly cluster: string | undefined;
}

/** One frame: either the whole picture, or a change to it. */
export type CapabilityFrame =
  | { readonly kind: "snapshot"; readonly entries: readonly CapabilityEntry[]; readonly generatedAt: string }
  | {
      readonly kind: "delta";
      readonly entry: CapabilityEntry;
      /**
       * What the gateway says it transitioned *from*.
       *
       * Preferred over what this browser happens to hold, because it is the truth even when a frame
       * was missed — and a missed frame is exactly when a transition would otherwise be reported
       * wrongly, or not at all.
       */
      readonly previous: CapabilityState | undefined;
    };

/** One key, as the string the store maps by. */
export function capabilityKeyOf(key: CapabilityKey): string {
  return `${key.service}/${key.cluster ?? "-"}`;
}

/** What to call a capability in a sentence a person reads. */
export function describeCapability(key: CapabilityKey): string {
  return key.cluster === undefined ? key.service : `${key.service} on ${key.cluster}`;
}

/** Whether this state means the feature cannot be used at all right now. */
export function isUnavailable(state: CapabilityState): boolean {
  return state.status === CapabilityStatuses.Unavailable;
}

/** The sentence explaining a state, when it has one. */
export function stateMessage(state: CapabilityState): string | undefined {
  switch (state.status) {
    case CapabilityStatuses.Unavailable:
      return state.message;
    case CapabilityStatuses.Degraded:
      return state.reason.message;
    default:
      return undefined;
  }
}

/**
 * Reads one `capabilities` frame.
 *
 * The two shapes are told apart by trying the snapshot first and falling back to the delta, rather
 * than by a discriminator field, because the wire format is the server's DTOs as they stand and
 * adding a discriminator would be a contract change made for the client's convenience. They are not
 * ambiguous: a snapshot has `entries` and `generatedAt`, a delta has `entry`.
 */
export function decodeCapabilityFrame(
  data: string,
): { readonly ok: true; readonly value: CapabilityFrame } | { readonly ok: false; readonly cause: string } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(data);
  } catch (cause) {
    return { ok: false, cause: `the frame was not JSON: ${String(cause)}` };
  }

  if (typeof parsed !== "object" || parsed === null) {
    return { ok: false, cause: "the frame was not an object" };
  }

  const candidate = parsed as Partial<SnapshotDto> & { entry?: unknown; previous?: unknown };

  if (Array.isArray(candidate.entries)) {
    const entries: CapabilityEntry[] = [];
    for (const raw of candidate.entries) {
      const entry = decodeEntry(raw);
      if (!entry.ok) return entry;
      entries.push(entry.value);
    }
    return {
      ok: true,
      value: {
        kind: "snapshot",
        entries,
        generatedAt: typeof candidate.generatedAt === "string" ? candidate.generatedAt : "",
      },
    };
  }

  if (candidate.entry !== undefined) {
    const entry = decodeEntry(candidate.entry);
    if (!entry.ok) return entry;
    const previous =
      candidate.previous === undefined || candidate.previous === null
        ? undefined
        : decodeState(candidate.previous);
    if (previous !== undefined && !previous.ok) return previous;
    return {
      ok: true,
      value: {
        kind: "delta",
        entry: entry.value,
        previous: previous?.ok === true ? previous.value : undefined,
      },
    };
  }

  return { ok: false, cause: "the frame was neither a snapshot nor a delta" };
}

type Decoded<A> = { readonly ok: true; readonly value: A } | { readonly ok: false; readonly cause: string };

function decodeEntry(raw: unknown): Decoded<CapabilityEntry> {
  if (typeof raw !== "object" || raw === null) return { ok: false, cause: "an entry was not an object" };
  const candidate = raw as Partial<EntryDto>;

  const key = decodeKey(candidate.key);
  if (!key.ok) return key;

  const state = decodeState(candidate.state);
  if (!state.ok) return state;

  return {
    ok: true,
    value: {
      key: key.value,
      state: state.value,
      updatedAt: typeof candidate.updatedAt === "string" ? candidate.updatedAt : "",
      // Absent rather than null in every frame an older gateway sends, so it reads as "no name was
      // given" instead of failing the whole frame and blanking the navigation.
      name: typeof candidate.name === "string" ? candidate.name : undefined,
    },
  };
}

function decodeKey(raw: unknown): Decoded<CapabilityKey> {
  if (typeof raw !== "object" || raw === null) return { ok: false, cause: "an entry had no key" };
  const candidate = raw as Partial<KeyDto>;
  if (typeof candidate.service !== "string" || candidate.service.length === 0) {
    return { ok: false, cause: "a capability key named no service" };
  }
  return {
    ok: true,
    value: {
      service: candidate.service,
      cluster: typeof candidate.cluster === "string" ? candidate.cluster : undefined,
    },
  };
}

function decodeState(raw: unknown): Decoded<CapabilityState> {
  if (typeof raw !== "object" || raw === null) return { ok: false, cause: "a state was not an object" };
  const candidate = raw as Record<string, unknown>;
  const status = candidate["status"];

  switch (status) {
    case CapabilityStatuses.Available:
      return { ok: true, value: { status: CapabilityStatuses.Available } };
    case CapabilityStatuses.NotConfigured:
      return { ok: true, value: { status: CapabilityStatuses.NotConfigured } };
    case CapabilityStatuses.Degraded:
      return { ok: true, value: { status: CapabilityStatuses.Degraded, reason: degradedReason(candidate["reason"]) } };
    case CapabilityStatuses.Unavailable:
      return {
        ok: true,
        value: {
          status: CapabilityStatuses.Unavailable,
          // A reason this build has never heard of is still a reason: an older browser must read a
          // newer gateway's frame rather than throw the whole picture away.
          reason: typeof candidate["reason"] === "string" ? candidate["reason"] : ReasonCodes.Unknown,
          message: typeof candidate["message"] === "string" ? candidate["message"] : "",
          since: typeof candidate["since"] === "string" ? candidate["since"] : "",
        },
      };
    default:
      return { ok: false, cause: `'${String(status)}' is not a capability status` };
  }
}

function degradedReason(raw: unknown): DegradedReasonDto {
  const candidate = (typeof raw === "object" && raw !== null ? raw : {}) as Record<string, unknown>;
  return {
    code: typeof candidate["code"] === "string" ? candidate["code"] : ReasonCodes.Unknown,
    message: typeof candidate["message"] === "string" ? candidate["message"] : "",
    ...(typeof candidate["p95Ms"] === "number" ? { p95Ms: candidate["p95Ms"] } : {}),
    ...(typeof candidate["suggestedPollIntervalMs"] === "number"
      ? { suggestedPollIntervalMs: candidate["suggestedPollIntervalMs"] }
      : {}),
  };
}
