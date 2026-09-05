import { CsrfHeaderName } from "./constants.generated.js";

export { CsrfHeaderName };

/**
 * The session's CSRF token, and the gate that keeps a request from being sent before there is one.
 *
 * ## Two shipped defects, one object
 *
 * Every mutating request KUI sends must carry the session's token in the header the gateway reads
 * (ADR-019). This product has got that wrong twice, in two different ways:
 *
 * 1. **The wrong header name.** The browser sent `X-Kui-Csrf` while the gateway read
 *    `X-Csrf-Token`, so every mutation came back `403` and looked like a permissions problem. The
 *    name is now generated from the one Scala constant both halves are built from
 *    (`constants.generated.ts`), so the two cannot be changed apart.
 * 2. **No token at all.** The only place a token ever comes from is the body of
 *    `GET /api/v1/auth/me`, and nothing else calls it. When start-up did not, the token stayed
 *    absent for the life of the page, the header was never sent, and every non-`GET` was refused —
 *    including the "Retry now" button in the degraded-feature panel, which therefore never worked.
 *
 * The second is why this is a *gate* and not a plain variable. A mutation issued while start-up is
 * still in flight **waits** for the token rather than being sent without it: sending it without one
 * produces a `403` the user cannot act on, whereas waiting produces the request they asked for a few
 * milliseconds later.
 *
 * ## What it deliberately does not do
 *
 * It does not fetch anything. The token arrives with the session, and the session belongs to the
 * kernel's auth state, which knows how to re-establish it when the server says it has lapsed. This
 * object is only the handover point between the two, which is what lets a test drive the header
 * behaviour with no server and no session at all.
 */
export interface CsrfTokens {
  /**
   * The token for the next mutating request, once there is one.
   *
   * Resolves immediately when a token is already known, and otherwise when {@link settle} is next
   * called. It resolves with `undefined` when start-up has finished and concluded there is no token
   * — an anonymous session, or a deployment with CSRF disabled — and also when the wait has run out
   * of patience.
   *
   * The deadline matters as much as the wait does. A gate with no deadline turns "the session call
   * is hanging" into "the button does nothing, forever", which is the failure mode this codebase has
   * a standing rule against: a bounded failure the user can see and retry beats an unbounded wait
   * they cannot. When the deadline passes the request is sent without the header, and the gateway
   * answers with a `403` that names the missing header — a bad outcome, but a legible one.
   */
  waitForToken(): Promise<string | undefined>;

  /** The token right now, without waiting. For tests and for diagnostics, not for sending. */
  currentToken(): string | undefined;

  /**
   * Records the outcome of a session fetch: the token, or its absence.
   *
   * "Settle" and not "set" because calling it is also the statement *start-up has finished*, which is
   * what releases anything waiting. An empty string is treated as no token: sending
   * `X-Csrf-Token: ` is rejected exactly as a missing header is, but with a far more confusing
   * message in the gateway's log.
   */
  settle(token: string | undefined): void;

  /**
   * Forgets the token and closes the gate again, after the server has said the session lapsed.
   *
   * The next mutation then waits for the refreshed session instead of being sent with a token that
   * is known to be dead.
   */
  invalidate(): void;
}

/**
 * @param waitTimeoutMs how long a mutation waits for start-up to produce a token before giving up
 *   and being sent without one. Ten seconds: long enough that a slow session call still works,
 *   short enough that a user who clicked a button gets an answer rather than a frozen control.
 */
export function createCsrfTokens(waitTimeoutMs = 10_000): CsrfTokens {
  let token: string | undefined;
  let settled = false;
  let waiters: Array<(value: string | undefined) => void> = [];

  const release = (): void => {
    const pending = waiters;
    waiters = [];
    for (const resolve of pending) resolve(token);
  };

  return {
    waitForToken(): Promise<string | undefined> {
      if (settled) return Promise.resolve(token);
      return new Promise<string | undefined>((resolve) => {
        const timer = setTimeout(() => {
          waiters = waiters.filter((waiter) => waiter !== settleWaiter);
          resolve(undefined);
        }, waitTimeoutMs);
        const settleWaiter = (value: string | undefined): void => {
          clearTimeout(timer);
          resolve(value);
        };
        waiters.push(settleWaiter);
      });
    },

    currentToken(): string | undefined {
      return token;
    },

    settle(next: string | undefined): void {
      token = next !== undefined && next.length > 0 ? next : undefined;
      settled = true;
      release();
    },

    invalidate(): void {
      token = undefined;
      settled = false;
    },
  };
}
