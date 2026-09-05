/**
 * The three lines of setup every chrome test needs, in one place.
 *
 * ## Why this is hand-written rather than a testing library
 *
 * `@solidjs/testing-library` is written against Solid 1.x, where `render` lives in `solid-js/web`.
 * In Solid 2 it lives in `@solidjs/web` and the reactive model underneath it changed — updates are
 * batched to a microtask, so a read taken straight after a write returns the old value until
 * `flush()`. Rather than depend on a wrapper that has not caught up with either change, the
 * mounting is done directly against the renderer, which is four lines and has no version to be
 * behind.
 *
 * ## The two rules a Solid 2 test has to obey
 *
 * `flush()` before asserting. `setValue("x")` does not update anything synchronously; it queues, and
 * the DOM catches up on the next microtask. A test that asserts immediately reads the previous
 * value and fails in a way that looks like a broken component.
 *
 * Dispose at the end. Reactive primitives need an owner, and `render` returns the function that
 * tears theirs down. Leaving them alive leaks listeners between test cases, and the symptom is a
 * later test failing because of an earlier one.
 */

import { render } from "@solidjs/web";
import type { JSX } from "@solidjs/web/jsx-runtime";
import axe from "axe-core";

export type Mounted = {
  readonly container: HTMLElement;
  readonly dispose: () => void;
};

/** Mounts a component into a detached-but-attached container and hands back the teardown. */
export function mount(component: () => JSX.Element): Mounted {
  const container = document.createElement("div");
  /* Attached to the document rather than left floating: focus, `:focus-visible`, `document`-level
   * event listeners and axe all behave differently — or not at all — on a detached tree. */
  document.body.appendChild(container);
  const dispose = render(component, container);
  return {
    container,
    dispose: () => {
      dispose();
      container.remove();
    },
  };
}

/**
 * Runs axe over a container and returns the violations.
 *
 * The rules disabled here are disabled for a reason and not for convenience. `color-contrast` needs
 * real layout and painted colours, and jsdom has neither: it reports every element as black on
 * transparent and produces confident nonsense. Contrast is checked instead by the token-level
 * contrast suite, which resolves all eight theme-and-accent combinations, and by looking at the
 * stories.
 */
export async function findViolations(container: HTMLElement): Promise<axe.Result[]> {
  const results = await axe.run(container, {
    rules: { "color-contrast": { enabled: false } },
  });
  return results.violations;
}

/** A violation formatted so the failure message says what to fix rather than only that it failed. */
export function describeViolations(violations: axe.Result[]): string {
  return violations
    .map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.html).join(", ")})`)
    .join("\n");
}
