/**
 * What a route renders instead of a feature that cannot be used (ADR-032).
 *
 * ## The four things on it, and why all four
 *
 * - **The reason, as a sentence.** A reason *code* is for a log; an operator needs to know whether to
 *   wait or to go and fix something, and "the service refused KUI's credentials" and "the service is
 *   not responding" lead to completely different next actions.
 * - **When it started, twice.** "Down for two minutes" and "down since Tuesday" call for very
 *   different reactions, and neither format alone gives both: a relative time answers "is this new?"
 *   at a glance, and an absolute one is what gets pasted into a ticket or matched against a deploy
 *   log.
 * - **A retry that actually retries.** It asks the gateway to probe the service again. Never a page
 *   reload: a reload throws away every other feature's loaded state and the user's place in the
 *   application, in order to re-ask a question one request can answer.
 * - **What still works.** The single most useful sentence here, and the one only the shell can write,
 *   because it is about the *other* features. A user who came to look at topics and finds the cluster
 *   service down needs to know whether the trip was wasted.
 */
import { For, Show } from "solid-js";
import { Button, Icon } from "@kui/kernel";

export type FallbackPanelProps = {
  readonly featureLabel: string;
  /** The sentence to show. Already resolved from the gateway's message or the reason code. */
  readonly reason: string;
  /** The stable code, for whoever the operator escalates to. Never swallowed. */
  readonly code?: string | undefined;
  /** RFC 3339, when the gateway said when. */
  readonly since?: string | undefined;
  readonly onRetry: () => void;
  readonly retrying?: boolean | undefined;
  /**
   * What the last retry failed with, if it did.
   *
   * Shown inline, next to the button that caused it, and deliberately not as a toast: a user pressing
   * "retry" on a service that stays down would otherwise produce a stack of identical notifications,
   * which is how a notification area becomes something people dismiss without reading.
   */
  readonly retryError?: string | undefined;
  readonly stillWorking: readonly string[];
  /** The clock. A parameter so the relative time in a test is not the time the test ran. */
  readonly now?: (() => Date) | undefined;
};

export function FallbackPanel(props: FallbackPanelProps) {
  const sinceAt = () => {
    if (props.since === undefined) return undefined;
    const parsed = new Date(props.since);
    return Number.isNaN(parsed.getTime()) ? undefined : parsed;
  };

  return (
    <section
      class="kui-shell__fallback"
      data-testid="feature-fallback"
      /* A landmark with a name, so a screen-reader user who lands here by following a dimmed link is
         told what this region is rather than being dropped into unlabelled prose. */
      aria-label={`${props.featureLabel} is unavailable`}
    >
      <h1 class="kui-shell__fallback-title">{props.featureLabel} is unavailable</h1>

      <p class="kui-shell__fallback-reason" data-testid="fallback-reason">
        <Icon name="warning" class="kui-shell__fallback-reason-icon" />
        <span>{props.reason}</span>
      </p>

      <Show when={props.code}>
        {(code) => (
          <p class="kui-shell__fallback-code" data-testid="fallback-code">
            <code>{code()}</code>
          </p>
        )}
      </Show>

      <Show when={sinceAt()}>
        {(at) => (
          <p class="kui-shell__fallback-since" data-testid="fallback-since">
            Since {relative(at(), (props.now ?? (() => new Date()))())} (
            {/* A machine-readable attribute as well, so the absolute value can be read by a tool as
                well as by a person. */}
            <time datetime={props.since}>{at().toISOString()}</time>)
          </p>
        )}
      </Show>

      <div class="kui-shell__fallback-actions">
        <Button
          icon="refresh"
          busy={props.retrying === true}
          onClick={() => props.onRetry()}
          data-testid="fallback-retry"
        >
          {props.retrying === true ? "Checking…" : "Retry now"}
        </Button>

        <Show when={props.retryError}>
          {(detail) => (
            <p class="kui-shell__fallback-error" data-testid="fallback-retry-error" role="alert">
              KUI could not re-check the service: {detail()}
            </p>
          )}
        </Show>
      </div>

      <div class="kui-shell__fallback-still-works" data-testid="fallback-still-works">
        <h2 class="kui-shell__fallback-still-works-title">What still works</h2>
        <Show
          when={props.stillWorking.length > 0}
          fallback={<p>Nothing else is available right now either.</p>}
        >
          <ul>
            <For each={props.stillWorking}>{(label) => <li>{label}</li>}</For>
          </ul>
        </Show>
      </div>
    </section>
  );
}

/**
 * How long ago, in words.
 *
 * Coarse on purpose. The panel is answering "is this new, or has it been like this all morning?",
 * and a count of seconds would change while the user reads it, which makes the whole line look
 * unstable.
 */
export function relative(since: Date, at: Date): string {
  const seconds = Math.max(0, Math.floor((at.getTime() - since.getTime()) / 1000));
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);

  if (seconds < 60) return "less than a minute ago";
  if (minutes < 60) return plural(minutes, "minute");
  if (hours < 24) return plural(hours, "hour");
  return plural(days, "day");
}

function plural(count: number, unit: string): string {
  return count === 1 ? `1 ${unit} ago` : `${count} ${unit}s ago`;
}
