import createOpenApiClient, {
  type Client,
  type MaybeOptionalInit,
} from "openapi-fetch";
import type {
  HttpMethod,
  PathsWithMethod,
  SuccessResponseJSON,
} from "openapi-typescript-helpers";

import { apiBaseUrl, type Bootstrap } from "./bootstrap.js";
import { CsrfHeaderName, type CsrfTokens } from "./csrf.js";
import { decodeEnvelope, type ApiError } from "./errors.js";
import { err, ok, type ApiResult } from "./result.js";
import type { paths } from "./schema.js";

/**
 * The one way the browser talks to the gateway.
 *
 * A feature never builds a URL, never names a header and never touches `fetch`. It names a path from
 * the generated `paths` type and its parameters, and receives that path's response type or an
 * {@link ApiError}. Because `paths` is generated from `docs/api/openapi.browser.json`, which the
 * build regenerates from the gateway's own Tapir endpoints, a field renamed on the server fails
 * `tsc` here — which is what replaces the cross-compiled contract of ADR-011 (ADR-048 §3).
 *
 * Four things happen on every call that no feature should have to remember:
 *
 * - the session cookie travels, because the client is built with `credentials: "include"`;
 * - a mutation carries the CSRF header from the current session, waiting for it if start-up has not
 *   finished (ADR-019, and see {@link CsrfTokens} for the two defects that produced that rule);
 * - a `401` is reported to the session before the caller sees the failure, so a session that
 *   silently stopped working is re-established rather than looking like an outage;
 * - nothing throws. Every answer is an {@link ApiResult}.
 *
 * There is no retry policy here, deliberately. A browser that retries silently turns a five-minute
 * outage into a five-minute spinner and hides the one thing the user needed to know. Retrying is an
 * explicit action a user takes.
 */
export interface KuiApiClient {
  readonly get: ResultMethod<"get">;
  readonly post: ResultMethod<"post">;
  readonly put: ResultMethod<"put">;
  readonly delete: ResultMethod<"delete">;
  readonly patch: ResultMethod<"patch">;

  /**
   * The underlying `openapi-fetch` client, for the two things the wrapper cannot express: a
   * streaming response (server-sent events go through their own client) and a test that wants the
   * raw `Response`. Everything else uses the methods above, because they are the ones that cannot
   * throw.
   */
  readonly raw: Client<paths>;
}

/**
 * One method of the client: the same call signature `openapi-fetch` generates from the schema — so
 * the path, its parameters, its query and its body are all checked — with the answer replaced by an
 * {@link ApiResult} that cannot throw.
 */
type ResultMethod<Method extends HttpMethod> = <
  Path extends PathsWithMethod<paths, Method>,
  Init extends WithoutCsrf<MaybeOptionalInit<paths[Path], Method>>,
>(
  path: Path,
  ...init: InitParameter<Init>
) => Promise<ApiResult<SuccessBody<paths[Path][Method]>>>;

/**
 * The call's options with the CSRF header removed from what the caller must supply.
 *
 * Every mutating endpoint declares `X-Csrf-Token` as a *required* header parameter, because it is
 * one: the gateway refuses the request without it (ADR-019). But no call site supplies it and none
 * should — the client adds it on every non-`GET`, waiting for start-up if the token has not arrived
 * yet, which is the whole point of {@link CsrfTokens}. Leaving the generated requirement in place
 * would mean every mutation named a header it must not name, and the obvious way to satisfy the
 * compiler would be to pass a token the call site had to obtain for itself. That is the defect this
 * package already shipped twice.
 *
 * So the requirement is dropped here, at the one boundary that can honour it. A caller may still
 * pass other headers; only this one is taken off the list, and only because something else
 * guarantees it.
 */
type WithoutCsrf<Init> = Init extends { readonly params: infer Params }
  ? Omit<Init, "params"> & { readonly params: WithoutCsrfParams<Params> }
  : Init;

type WithoutCsrfParams<Params> = Params extends {
  readonly header: infer Header;
}
  ? [Exclude<keyof Header, typeof CsrfHeaderName>] extends [never]
    ? // The CSRF token was the *only* header. Drop `header` entirely rather than leaving an empty
      // object the caller would have to write out as `header: {}`.
      Omit<Params, "header">
    : Omit<Params, "header"> & {
        readonly header: Omit<Header, typeof CsrfHeaderName>;
      }
  : Params;

/**
 * Whether the options argument may be omitted, mirroring `openapi-fetch`'s own rule: a `GET` with no
 * parameters takes no second argument, and one with a required path parameter does.
 */
type InitParameter<Init> =
  RequiredKeys<Init> extends never
    ? [(Init & Record<string, unknown>)?]
    : [Init & Record<string, unknown>];

type RequiredKeys<T> = {
  [K in keyof T]-?: Record<string, unknown> extends Pick<T, K> ? never : K;
}[keyof T];

/**
 * The body of a successful response, computed from the generated schema.
 *
 * ## Why not `openapi-fetch`'s own return type
 *
 * It used to be `Extract<FetchResponse<…>, { data: unknown }>["data"]`, on the reasoning that taking
 * the answer's type from the library that produces the answer means the two cannot disagree. Sound
 * reasoning, wrong in this codebase, and wrong in a way that only a mutation revealed.
 *
 * `FetchResponse` passes the body through `openapi-typescript-helpers`' `Readable<T>`, whose job is
 * to strip `$Write`-marked fields from a response. Its array case is `T extends (infer E)[]` — a
 * *mutable* array. Our schema is generated with `immutable`, so every array in it is
 * `readonly E[]`, which does not match. The type therefore falls through to the object case and maps
 * over the array's own members, turning `map`, `filter` and `reduce` into `{}` — properties with no
 * call signatures. The result compiles as a type and cannot be used as an array:
 *
 *     answer.value.warnings.map(…)   // error: this expression is not callable, type '{}'
 *
 * Nothing caught it because every read path in the product decodes an ADR-039 section, whose payload
 * the server documents as `Schema.any`; those go through `decodeSection<T>` with hand-written types
 * and never touch this one. The first typed array in a response was a purge plan's warnings.
 *
 * `Readable` also has nothing to do here: `$Read` and `$Write` appear nowhere in the generated
 * schema, so the pass it exists to perform is a no-op with a side effect. Reading the success
 * response straight off the schema — which is already exactly as `readonly` as it should be — is
 * both correct and closer to the source of truth than the library's transform of it.
 */
type SuccessBody<Operation> =
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- the helpers' own constraint;
  // narrowing it to `unknown` makes every path fail to satisfy it. A type-level guard, not a value.
  Operation extends Record<string | number, any>
    ? SuccessResponseJSON<Operation>
    : never;

/** The shape every `openapi-fetch` method answers with, once its types are erased. */
type AnyFetchResponse = { data?: unknown; error?: unknown; response: Response };

/** What the client needs to exist. */
export interface ApiClientOptions {
  /** Where the API is, and under which deployment prefix. */
  readonly bootstrap: Bootstrap;
  /** The page's own origin. A parameter so a test can name one without a browser. */
  readonly origin: string;
  /** The session's CSRF token and the gate that waits for it. */
  readonly csrf: CsrfTokens;
  /**
   * Told when the server says the session has lapsed, before the caller sees the failure.
   *
   * The status is inspected rather than the body, because a `401` has to be noticed whether or not
   * the endpoint declared that status — and a session that silently stopped working is exactly the
   * failure a user cannot diagnose on their own.
   */
  readonly onUnauthorized?: () => void;
  /** A `fetch` to use instead of the browser's. Tests pass one; nothing else does. */
  readonly fetch?: (input: Request) => Promise<Response>;
}

const UnauthorizedStatus = 401;

/**
 * The gateway's own correlation id, on the response.
 *
 * The browser deliberately sends *no* correlation header of its own. Every header named `X-Kui-*`
 * is stripped at the edge before anything reads it (ADR-040), so a request id the browser invented
 * would be discarded by the gateway and appear in no log — a support aid that only appears to work
 * is worse than none. The gateway mints the authoritative id and returns it on the response, so this
 * is where a client-side failure gets something a user can quote.
 */
const CorrelationResponseHeader = "X-Kui-Correlation-Id";

export function createApiClient(options: ApiClientOptions): KuiApiClient {
  const raw = createOpenApiClient<paths>({
    baseUrl: apiBaseUrl(options.bootstrap, options.origin),

    // What makes the session cookie travel. Without it `fetch` omits cookies on anything it
    // considers cross-origin, and a reverse proxy that rewrites the origin is enough to make a
    // same-origin deployment look cross-origin to the browser — a failure that appears only in
    // production.
    credentials: "include",

    ...(options.fetch ? { fetch: options.fetch } : {}),
  });

  raw.use({
    /**
     * Adds the CSRF header to everything except `GET`.
     *
     * That boundary is ADR-019's: a `GET` cannot change anything, so requiring a token on one would
     * break the very case the mechanism exists to allow — a user pasting a deep link into a fresh
     * tab. A missing token is not an error here either; the gateway rejects the request and says
     * which header is missing, which is a far more debuggable failure than a request the browser
     * quietly refused to send.
     */
    async onRequest({ request }) {
      if (request.method === "GET" || request.method === "HEAD") return request;
      const token = await options.csrf.waitForToken();
      if (token !== undefined) request.headers.set(CsrfHeaderName, token);
      return request;
    },

    onResponse({ response }) {
      if (response.status === UnauthorizedStatus) {
        options.csrf.invalidate();
        options.onUnauthorized?.();
      }
      return response;
    },
  });

  /**
   * Runs one call and turns everything it can do into a value.
   *
   * `openapi-fetch` returns `{ data }` or `{ error }` for anything the server answered, and *rejects*
   * for anything it did not — a dropped connection, an aborted request, a browser that refused to
   * send. Both have to become an `ApiResult`, because a rejected promise inside a Solid computation
   * reaches the nearest `<Errored>` boundary and unmounts the page: the blank screen this codebase
   * has already shipped once.
   */
  const run = async (
    send: () => Promise<AnyFetchResponse>,
  ): Promise<ApiResult<unknown>> => {
    try {
      const answer = await send();
      if (answer.error !== undefined) {
        return err(
          withCorrelation(decodeEnvelope(answer.error), answer.response),
        );
      }
      // `data` is genuinely `undefined` for a `204 No Content`, which is a success and not an
      // absence. The value's real type is the one `ResultMethod` computes for this path.
      return ok(answer.data);
    } catch (failure: unknown) {
      return err(transportFailure(failure));
    }
  };

  /**
   * Forwards one `openapi-fetch` method's arguments untouched and converts its answer.
   *
   * The argument tuple is erased here and re-imposed by `ResultMethod` at the call site, which is
   * what lets one implementation serve five differently-typed methods. The types a caller sees are
   * the generated ones; only this three-line forwarder is untyped, and it is the only place in the
   * package that is.
   */
  const wrap = <Method extends HttpMethod>(
    send: (...args: readonly unknown[]) => Promise<AnyFetchResponse>,
  ): ResultMethod<Method> =>
    ((...args: readonly unknown[]) =>
      run(() => send(...args))) as ResultMethod<Method>;

  const erase = (
    send: unknown,
  ): ((...args: readonly unknown[]) => Promise<AnyFetchResponse>) =>
    send as (...args: readonly unknown[]) => Promise<AnyFetchResponse>;

  return {
    get: wrap<"get">(erase(raw.GET)),
    post: wrap<"post">(erase(raw.POST)),
    put: wrap<"put">(erase(raw.PUT)),
    delete: wrap<"delete">(erase(raw.DELETE)),
    patch: wrap<"patch">(erase(raw.PATCH)),
    raw,
  };
}

/**
 * Classifies a failure the transport reported rather than the server describing.
 *
 * `fetch` deliberately says "Failed to fetch" and nothing more for a request it refused, so that a
 * page cannot use the error to probe the network it is running on. The original text is kept in
 * `cause` for the console and is never shown to the user.
 */
export function transportFailure(failure: unknown): ApiError {
  if (failure instanceof DOMException && failure.name === "TimeoutError")
    return { kind: "timeout" };
  if (failure instanceof DOMException && failure.name === "AbortError") {
    // A cancelled request is not an outage: the tab navigated away, or the caller closed the
    // stream. It still has to be a value rather than a rejection, or it takes the page with it.
    return { kind: "unreachable", cause: "the request was cancelled" };
  }
  const cause = failure instanceof Error ? failure.message : String(failure);
  return { kind: "unreachable", cause };
}

/** Attaches the gateway's correlation id to an envelope that arrived without one. */
function withCorrelation(error: ApiError, response: Response): ApiError {
  if (error.kind !== "envelope" || error.correlationId.length > 0) return error;
  const fromHeader = response.headers.get(CorrelationResponseHeader);
  return fromHeader === null ? error : { ...error, correlationId: fromHeader };
}
