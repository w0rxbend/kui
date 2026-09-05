/**
 * Following one business event across several topics.
 *
 * The screen for "where did order 4711 go?" — an identifier, a handful of topics and a time window,
 * answered with the records in time order. See `track.ts` for why nothing happens until Search is
 * pressed and why the scanned count is on screen even when nothing matched.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Banner, Button, EmptyState, Select, TextField, type Mutation } from "@kui/kernel";
import {
  queryProblem,
  type MatchOperator,
  type MatchSource,
  type TrackQuery,
  type TrackResult,
} from "./track.js";

export interface TrackPageProps {
  readonly query: TrackQuery;
  readonly onQueryChange: (query: TrackQuery) => void;
  readonly onSearch: () => void;
  readonly state: Mutation<TrackResult>;
  /** Absent when this principal may read messages. Present disables Search and says why. */
  readonly disabledReason?: string | undefined;
}

const SOURCES: readonly { readonly value: MatchSource; readonly label: string }[] = [
  { value: "value", label: "the record's value" },
  { value: "key", label: "the record's key" },
  { value: "header", label: "a named header" },
];

const OPERATORS: readonly { readonly value: MatchOperator; readonly label: string }[] = [
  { value: "contains", label: "contains" },
  { value: "equals", label: "is exactly" },
  { value: "matches", label: "matches the expression" },
];

export function TrackPage(props: TrackPageProps): JSX.Element {
  const result = () => (props.state.kind === "done" ? props.state.value : undefined);
  const running = () => props.state.kind === "running";
  const problem = () => queryProblem(props.query);
  const failure = () =>
    props.state.kind === "failed" || props.state.kind === "forbidden" ? props.state : undefined;

  const patch = (change: Partial<TrackQuery>): void => {
    props.onQueryChange({ ...props.query, ...change });
  };

  return (
    <section class="kui-track" aria-label="Track a message">
      <h1 class="kui-track__title">Track a message</h1>
      <p class="kui-track__lede">
        Find one value across several topics inside a time window — where an order, a customer or a
        correlation id went, and in what order.
      </p>

      <form
        class="kui-track__form"
        onSubmit={(event) => {
          event.preventDefault();
          if (!running() && problem() === undefined) props.onSearch();
        }}
      >
        <TextField
          label="Topics"
          value={props.query.topics.join(", ")}
          onInput={(text) =>
            patch({
              topics: text
                .split(",")
                .map((one) => one.trim())
                .filter((one) => one !== ""),
            })
          }
          mono
          required
          placeholder="orders.v1, orders.payments.v2"
          /* Required, and the server says so too: a track is a full read of everything it is
             pointed at, and pointing it at a whole cluster during an incident is how somebody turns
             an investigation into a second outage. */
        />

        <div class="kui-track__match">
          <Select
            label="Look in"
            value={props.query.source}
            options={SOURCES}
            onChange={(source) => patch({ source: source as MatchSource })}
          />
          <Show when={props.query.source === "header"}>
            <TextField
              label="Header name"
              value={props.query.header}
              onInput={(header) => patch({ header })}
              mono
            />
          </Show>
          <Select
            label="that"
            value={props.query.operator}
            options={OPERATORS}
            onChange={(operator) => patch({ operator: operator as MatchOperator })}
          />
          <TextField
            label="Value"
            value={props.query.value}
            onInput={(value) => patch({ value })}
            mono
            required
          />
        </div>

        <div class="kui-track__window">
          <TextField
            label="From"
            value={props.query.from}
            onInput={(from) => patch({ from })}
            mono
          />
          <TextField label="To" value={props.query.to} onInput={(to) => patch({ to })} mono />
        </div>

        <div class="kui-track__actions">
          <Show
            when={problem() === undefined && props.disabledReason === undefined && !running()}
            fallback={
              <Button
                variant="primary"
                icon="search"
                busy={running()}
                disabled
                disabledReason={
                  running()
                    ? "The track is running."
                    : (props.disabledReason ?? problem() ?? "This search is not complete.")
                }
              >
                Search
              </Button>
            }
          >
            <Button variant="primary" icon="search" type="submit">
              Search
            </Button>
          </Show>
        </div>
      </form>

      <Show when={failure()}>
        {(problemState) => (
          <Banner
            tone="danger"
            message={problemState().message}
            code={
              problemState().kind === "failed"
                ? (problemState() as { code: string }).code
                : undefined
            }
          />
        )}
      </Show>

      <Show when={result()}>
        {(found) => (
          <>
            {/*
             * The read, in figures, and it is on screen whether or not anything matched.
             *
             * "Nothing matched" and "nothing was read" are the same screen without this line and
             * mean opposite things: the first says the value is not in those topics in that window,
             * the second says the window was empty and the operator should widen it before
             * concluding anything.
             */}
            <p class="kui-track__summary" role="status">
              Read {found().scanned.toLocaleString()} {found().scanned === 1 ? "record" : "records"}
              ; {found().matched.toLocaleString()} matched.
            </p>

            <Show when={found().truncated}>
              <Banner
                tone="warning"
                /* Not a footnote. A truncated read that says nothing is a read somebody draws a
                   conclusion from, and the conclusion may be exactly wrong. */
                message="The read stopped at its budget before finishing the window, so there may be more. Narrow the window or the topics and search again."
              />
            </Show>

            <Show
              when={found().hits.length > 0}
              fallback={
                <EmptyState
                  kind="filtered"
                  title="No record matched."
                  description={
                    found().scanned === 0
                      ? "Nothing was read at all: the window holds no records in these topics. Widen it before concluding the value is not there."
                      : "The records in this window do not contain that value."
                  }
                />
              }
            >
              <div class="kui-track__results kui-table-scroll" tabindex={0}>
                <table class="kui-track__table">
                  <caption class="kui-visually-hidden">Records matching this track</caption>
                  <thead>
                    <tr>
                      <th scope="col">Time</th>
                      <th scope="col">Topic</th>
                      <th scope="col">Partition</th>
                      <th scope="col">Offset</th>
                      <th scope="col">Key</th>
                      <th scope="col">Value</th>
                    </tr>
                  </thead>
                  <tbody>
                    <For each={found().hits}>
                      {(hit) => (
                        <tr>
                          {/* Time first, because the answer to "where did it go" is the order. */}
                          <td class="kui-track__time">{hit.timestamp ?? "—"}</td>
                          <th scope="row" class="kui-track__topic">
                            {hit.topic}
                          </th>
                          <td>{hit.partition ?? "—"}</td>
                          <td>{hit.offset ?? "—"}</td>
                          <td class="kui-track__payload">
                            <Absent value={hit.key} what="no key" />
                          </td>
                          <td class="kui-track__payload">
                            <Absent value={hit.value} what="a tombstone" />
                          </td>
                        </tr>
                      )}
                    </For>
                  </tbody>
                </table>
              </div>
            </Show>
          </>
        )}
      </Show>
    </section>
  );
}

/**
 * A payload, or what its absence means.
 *
 * `null` is never a blank cell here. A record with no key and a record whose key is the empty string
 * are different records, and a null *value* is a tombstone — a deletion — which is the single most
 * important thing a row on this screen can be.
 */
function Absent(props: { readonly value: string | null; readonly what: string }): JSX.Element {
  return (
    <Show
      when={props.value !== null}
      fallback={
        <span class="kui-table__cell-muted">
          <span aria-hidden="true">—</span>
          <span class="kui-visually-hidden">{props.what}</span>
        </span>
      }
    >
      <code>{props.value}</code>
    </Show>
  );
}
