import { ErrorCodes, type ErrorCode } from "./constants.generated.js";
import type { components } from "./schema.js";

/**
 * The error envelope every KUI failure takes, in every service and in every stream (ADR-034).
 *
 * Taken from the generated schema rather than declared here, so that a change to the envelope on the
 * server fails this file's compilation. `code` is a string on the wire on purpose: a browser built
 * against an older KUI must be able to decode a response from a newer one that has invented a code
 * and fall back to rendering its message.
 */
export type ErrorEnvelope = components["schemas"]["ErrorEnvelope"];

/** One request field and every rule it breaks. */
export type ErrorDetail = components["schemas"]["ErrorDetail"];

/**
 * Everything that can go wrong with one API call, as data.
 *
 * Four cases, because a caller genuinely treats them differently:
 *
 * - `envelope` — the server had an opinion. It says what went wrong and whether trying again helps.
 * - `unreachable` — nothing answered: the network is down, the browser is offline, the gateway is
 *   not running. The shell escalates this to its full-screen state when its own calls fail.
 * - `timeout` — something answered too late, or not at all inside the deadline.
 * - `decoding` — something answered, and it was not what the contract describes. That is a bug
 *   rather than an outage, so it must be loud rather than silently retried. The usual sources are a
 *   reverse proxy substituting an HTML error page for a JSON one, and a gateway and a browser built
 *   from different revisions of the contract.
 */
export type ApiError =
  | {
      readonly kind: "envelope";
      /** Stable and machine-readable. Branch on this, never on `message` (ADR-034). */
      readonly code: ErrorCode;
      readonly message: string;
      readonly details: readonly ErrorDetail[];
      readonly correlationId: string;
      readonly retryable: boolean;
    }
  | { readonly kind: "unreachable"; readonly cause: string }
  | { readonly kind: "timeout" }
  | { readonly kind: "decoding"; readonly cause: string };

/** What to show when nothing answered at all. */
export const UnreachableMessage = "KUI cannot reach the server.";
/** What to show when the answer did not arrive in time. */
export const TimeoutMessage = "The server took too long to answer.";
/** What to show when the answer did not match the contract. */
export const DecodingMessage = "The server sent something KUI could not read.";

/**
 * The sentence a screen shows, given the wire code and what the server said.
 *
 * Most of KUI's messages are written for the person reading them and name the thing the request was
 * about — "topic 'orders' does not exist" is exactly right, and no client-side string could do
 * better. A handful are not. An upstream failure's message describes KUI's own plumbing —
 * `kafka answered with status 502` — and putting that on screen tells an operator the wrong thing
 * twice over: no Kafka broker speaks HTTP, so there is no 502 to go and look for, and the real
 * problem (the broker is unreachable) is not stated anywhere. That is a defect this product shipped.
 *
 * So the rule is: show the server's message, *except* for the small set of codes that mean "something
 * KUI depends on is not working". The set is listed rather than derived, because every other code is
 * either a business failure whose message is the whole point, or a code this build has never heard
 * of — and an older browser must show a newer KUI's message rather than replace it with a guess.
 *
 * The code itself is never discarded: it stays on the `ApiError`, in the correlation line a user
 * quotes in a support request, and in the logs on both sides.
 */
export function userFacingSentence(code: ErrorCode, serverMessage: string): string {
  switch (code) {
    case ErrorCodes.UpstreamUnavailable:
      return "KUI cannot reach the cluster. It is unreachable, or it is not accepting connections.";
    case ErrorCodes.Timeout:
      return "The cluster did not answer in time. It may be overloaded.";
    case ErrorCodes.UpstreamAuth:
      return "KUI's credentials for the cluster were rejected. Its configuration has to change before this can work.";
    default:
      return serverMessage;
  }
}

/** What to put on screen for this failure. */
export function userMessage(error: ApiError): string {
  switch (error.kind) {
    case "envelope":
      return userFacingSentence(error.code, error.message);
    case "unreachable":
      return UnreachableMessage;
    case "timeout":
      return TimeoutMessage;
    case "decoding":
      return DecodingMessage;
  }
}

/**
 * Whether this failure means "we do not know who you are" — the signal to re-establish the session.
 *
 * Answered from the code rather than from an HTTP status, because the status is gone by the time a
 * caller holds an `ApiError`, and because the code is the thing ADR-034 makes stable.
 */
export function isAuthFailure(error: ApiError): boolean {
  return error.kind === "envelope" && error.code === ErrorCodes.Unauthenticated;
}

/** Whether the caller is known and simply not allowed. Rendered as the 403 page, never as a retry. */
export function isForbidden(error: ApiError): boolean {
  return error.kind === "envelope" && error.code === ErrorCodes.Forbidden;
}

/**
 * Whether this counts towards "the gateway is not there".
 *
 * A `decoding` failure is deliberately excluded: something answered, so the gateway is reachable, and
 * showing "cannot reach gateway" would send an operator to look at the network when the problem is in
 * the code.
 */
export function isTransportFailure(error: ApiError): boolean {
  return error.kind === "unreachable" || error.kind === "timeout";
}

/**
 * Whether asking again, unchanged, could work.
 *
 * The server's own answer is used where there is one, because only the server knows. A transport
 * failure is retryable by definition; a contract mismatch never is.
 */
export function isRetryable(error: ApiError): boolean {
  switch (error.kind) {
    case "envelope":
      return error.retryable;
    case "unreachable":
    case "timeout":
      return true;
    case "decoding":
      return false;
  }
}

/** The identifier a user quotes in a support request, when the failure has one. */
export function correlationId(error: ApiError): string | undefined {
  return error.kind === "envelope" ? error.correlationId : undefined;
}

/**
 * Reads an error envelope out of whatever the server sent, or says it could not.
 *
 * Hand-written and total rather than trusting the generated type, because this runs on the one
 * response the generated types cannot vouch for: the body of a failure, which may have been produced
 * by a reverse proxy, a load balancer or a captive portal rather than by KUI at all. Every field is
 * checked, and anything unrecognisable becomes a `decoding` failure that names what arrived — which
 * is what an operator needs, and is far more useful than a `TypeError` two components away.
 *
 * `timestamp` is deliberately dropped. It is the moment the *server* failed, and every place the
 * browser shows a time wants the moment the *user* saw the failure, which the browser knows and the
 * envelope does not.
 */
export function decodeEnvelope(body: unknown): ApiError {
  if (typeof body !== "object" || body === null) {
    return { kind: "decoding", cause: `expected an error envelope, got ${describe(body)}` };
  }

  const candidate = body as Record<string, unknown>;
  const code = candidate["code"];
  const message = candidate["message"];

  if (typeof code !== "string" || code.length === 0) {
    return { kind: "decoding", cause: `error envelope has no code (got ${describe(code)})` };
  }

  return {
    kind: "envelope",
    code,
    message: typeof message === "string" ? message : "",
    details: decodeDetails(candidate["details"]),
    // A missing correlation id is a degraded envelope, not an undecodable one: the user still needs
    // to be told what went wrong, and an empty support reference is better than a blank screen.
    correlationId: typeof candidate["correlationId"] === "string" ? candidate["correlationId"] : "",
    retryable: candidate["retryable"] === true,
  };
}

function decodeDetails(raw: unknown): readonly ErrorDetail[] {
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((entry: unknown): ErrorDetail[] => {
    if (typeof entry !== "object" || entry === null) return [];
    const detail = entry as Record<string, unknown>;
    const field = detail["field"];
    const restrictions = detail["restrictions"];
    return [
      {
        ...(typeof field === "string" ? { field } : {}),
        restrictions: Array.isArray(restrictions)
          ? restrictions.filter((item): item is string => typeof item === "string")
          : [],
      },
    ];
  });
}

/** A short, safe description of something unexpected, for a `decoding` failure's cause. */
function describe(value: unknown): string {
  if (value === null) return "null";
  if (typeof value === "string") return `a string (${truncate(value)})`;
  return typeof value;
}

function truncate(value: string): string {
  const limit = 120;
  return value.length <= limit ? value : `${value.slice(0, limit)}…`;
}
