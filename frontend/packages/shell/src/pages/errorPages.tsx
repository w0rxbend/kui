/**
 * The three surfaces KUI shows when a page cannot be shown: 404, 403, and "the gateway is not
 * answering".
 *
 * They are together in one file because what they have in common is the point. Each of them renders
 * with **no data at all** — no version, no principal, no capabilities, nothing fetched — because each
 * is what the user sees precisely when the thing that would have supplied that data is what failed.
 * A 404 page that needed a request to draw itself is a 404 page that can go blank.
 */
import { Show } from "solid-js";
import { Button, EmptyState, Icon } from "@kui/kernel";
import type { Connectivity } from "../health.js";

/**
 * The address does not exist.
 *
 * The navigation stays exactly where it was. That is the entire difference between a 404 page and a
 * dead end: a user who mistyped a URL, or followed a link to a page that has been renamed, is one
 * click from anywhere rather than reaching for the Back button and hoping.
 *
 * The attempted address is shown because it is often the answer: a truncated paste and a stale
 * bookmark look identical until you can see what was actually asked for.
 */
export function NotFoundPage(props: { readonly attempted: string; readonly homeHref: string }) {
  return (
    <div class="kui-shell__page kui-shell__error-page" data-testid="page-not-found">
      {/* A level-one heading, because this *is* the page. Screen-reader users navigate by heading,
          and a page whose main message is not a heading is one they have to read linearly to find. */}
      <h1>That page does not exist</h1>

      <EmptyState
        kind="empty"
        title="Nothing is served at this address."
        description="The link may be out of date, or the address may have been mistyped. The rest of KUI is working normally."
        action={<HomeLink href={props.homeHref} testId="not-found-home" />}
        testId="not-found-empty"
      />

      <p class="kui-shell__error-page-detail">
        You asked for <code data-testid="not-found-url">{props.attempted}</code>.
      </p>
    </div>
  );
}

/**
 * You are signed in, and you may not see this.
 *
 * ## The message must not depend on whether the thing exists
 *
 * This is the rule the page is built around. "You do not have permission to view topic `payroll`"
 * and "no such topic" are two different answers, and a user who is not allowed to know which topics
 * exist can learn the whole list by trying names and watching which message comes back. So the
 * wording is identical either way, and `subject` is a *category* — "this topic", "the schema
 * registry" — never an identifier.
 *
 * ## Who to ask
 *
 * A permission error a user cannot act on is a dead end. The support contact comes from the
 * deployment's configuration and is empty by default, in which case the sentence is left out rather
 * than replaced by a placeholder nobody can use.
 */
export function ForbiddenPage(props: {
  readonly subject: string;
  readonly homeHref: string;
  readonly supportContact?: string | undefined;
}) {
  return (
    <div class="kui-shell__page kui-shell__error-page" data-testid="page-forbidden">
      <h1>You do not have permission</h1>

      <EmptyState
        kind="forbidden"
        title="This is not available to your account."
        description="Your account does not have permission to view this. If you think it should, ask whoever administers KUI for your organisation."
        action={<HomeLink href={props.homeHref} testId="forbidden-home" />}
        testId="forbidden-empty"
      />

      <p class="kui-shell__error-page-detail" data-testid="forbidden-subject">
        {/* Deliberately the same sentence whatever the subject is, and whether or not it exists. */}
        You do not have permission to view {props.subject}.
      </p>

      <Show when={props.supportContact}>
        {(contact) => (
          <p class="kui-shell__error-page-detail" data-testid="forbidden-contact">
            Contact: {contact()}
          </p>
        )}
      </Show>
    </div>
  );
}

/**
 * The one full-screen state KUI has.
 *
 * ## When it appears, and when it must not
 *
 * Only when the *shell's own* calls cannot reach the gateway. A feature's call failing is the
 * feature's fallback panel's job (ADR-032), and confusing the two means throwing the user out of
 * everything that still worked because one endpoint is down.
 *
 * ## What it has to contain
 *
 * - **What happened**, in a sentence, so the user knows this is not their doing.
 * - **An automatic retry with a visible countdown.** The countdown is not decoration: a screen that
 *   says "cannot connect" and then sits still reads as frozen, and a frozen screen gets reloaded —
 *   which throws away every bit of state the application still had.
 * - **A manual "Try again"**, because a user who has just reconnected their wifi should not have to
 *   wait out a thirty-second timer they did not choose.
 * - **When contact was last made**, because "a moment ago" and "at 09:14" lead to very different
 *   decisions about whether to call somebody.
 */
export function GatewayUnreachablePage(props: {
  readonly state: Connectivity;
  readonly onRetry: () => void;
}) {
  return (
    <div
      class="kui-shell__unreachable"
      data-testid="gateway-unreachable"
      /* A dialog rather than a region: it covers everything and nothing behind it is usable, and
         `alertdialog` is what tells a screen reader to announce it immediately rather than when the
         user next happens to move there. */
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="kui-unreachable-title"
    >
      <div class="kui-shell__unreachable-card">
        <Icon name="warning" size="32px" class="kui-shell__unreachable-icon" />
        <h1 id="kui-unreachable-title">KUI cannot reach the server</h1>
        <p>
          The KUI gateway is not answering. This is not a problem with your browser, and nothing you
          were doing has been lost — KUI will pick up where it left off as soon as the server
          responds.
        </p>

        <p
          class="kui-shell__unreachable-countdown"
          data-testid="unreachable-countdown"
          /* Polite rather than assertive: it changes every second, and an assertive live region
             would interrupt a screen-reader user continuously. */
          aria-live="polite"
        >
          {countdown(props.state)}
        </p>

        <p class="kui-shell__unreachable-last-contact" data-testid="unreachable-last-contact">
          Last contact with the server: {clockTime(props.state.lastContact)}.
        </p>

        <Button icon="refresh" onClick={() => props.onRetry()} data-testid="unreachable-retry">
          Try again
        </Button>
      </div>
    </div>
  );
}

export function countdown(state: Connectivity): string {
  if (state.kind === "connected") {
    // Not reachable while this element is on screen, and the string still has to be something: a
    // live region that goes empty is announced as an emptying, which is worse than a stale line.
    return "Connected.";
  }
  return state.nextRetryInSeconds === 1
    ? "Trying again in 1 second."
    : `Trying again in ${state.nextRetryInSeconds} seconds.`;
}

/**
 * A wall-clock time, in the browser's own locale.
 *
 * The time of day rather than "three minutes ago" on purpose: a relative time has to be recomputed
 * to stay true, and a relative time that has silently stopped updating is a lie. An absolute one is
 * right for ever.
 */
function clockTime(at: Date): string {
  return at.toLocaleTimeString();
}

/**
 * The way out of an error page.
 *
 * A real link styled as a button, and not a button. "Open in a new tab" and "copy link address" are
 * things people do with a way out of an error page, and a button supports neither. The href carries
 * the deployment's mount prefix, because a hard-coded `/` sends a user behind a reverse proxy to a
 * path no gateway route matches — which broke this product once already.
 */
function HomeLink(props: { readonly href: string; readonly testId: string }) {
  return (
    <a class="kui-button kui-button--primary kui-button--md" href={props.href} data-testid={props.testId}>
      Go to the dashboard
    </a>
  );
}
