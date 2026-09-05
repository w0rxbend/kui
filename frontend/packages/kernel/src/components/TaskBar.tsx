/**
 * A connector's tasks, as one segment each.
 *
 * ## Why this is a bar and not a count
 *
 * "3 of 4 tasks running" is a sentence somebody reads and forgets. Four segments with one of them
 * red is a shape somebody sees from across a room, and a connector's health is exactly the sort of
 * thing that has to be visible without being read.
 *
 * ## The empty case is not the failed case
 *
 * A connector with no tasks at all draws a single full-width track in the neutral overlay colour,
 * which is visibly different from a row of failed tasks — and it has to be, because "this connector
 * has no tasks" and "every one of this connector's tasks has failed" are different problems with
 * different causes, and a bar that drew them alike would send somebody to look at the wrong one.
 *
 * ## Colour is not the only signal
 *
 * The bar is decorative to a screen reader — `aria-hidden` — and the accessible name is a sentence
 * beside it saying how many are in each state. A row of coloured rectangles carries nothing at all
 * to somebody who cannot see it, and nothing to somebody who cannot tell red from green either.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";

export type TaskState = "running" | "failed" | "paused" | "unknown";

export interface TaskBarProps {
  /** One entry per task, in the order the connector reports them. */
  readonly tasks: readonly TaskState[];
  readonly testId?: string | undefined;
}

export function TaskBar(props: TaskBarProps): JSX.Element {
  return (
    <div class="kui-taskbar" data-testid={props.testId}>
      <Show
        when={props.tasks.length > 0}
        fallback={
          /* Not zero segments and not a red one: an empty track, which reads as "there is nothing
             here" rather than as "everything here is broken". */
          <div class="kui-taskbar__track kui-taskbar__track--empty" aria-hidden="true" />
        }
      >
        <div class="kui-taskbar__row" aria-hidden="true">
          <For each={props.tasks}>
            {(state) => <span class={["kui-taskbar__task", `kui-taskbar__task--${state}`]} />}
          </For>
        </div>
      </Show>
      {/* The bar's meaning, for everyone who is not reading colours off it. */}
      <span class="kui-visually-hidden">{describeTasks(props.tasks)}</span>
    </div>
  );
}

/** What the bar says, in words. */
export function describeTasks(tasks: readonly TaskState[]): string {
  if (tasks.length === 0) return "This connector has no tasks.";

  const count = (state: TaskState): number => tasks.filter((task) => task === state).length;
  const parts: string[] = [];
  const say = (n: number, word: string): void => {
    if (n > 0) parts.push(`${n} ${word}`);
  };
  say(count("running"), "running");
  say(count("failed"), "failed");
  say(count("paused"), "paused");
  say(count("unknown"), "in an unknown state");

  const total = tasks.length === 1 ? "1 task" : `${tasks.length} tasks`;
  return `${total}: ${parts.join(", ")}.`;
}
