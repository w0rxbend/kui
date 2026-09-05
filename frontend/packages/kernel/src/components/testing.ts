/**
 * The three lines of setup every kernel component test needs.
 *
 * ## Why this is hand-written rather than a testing library
 *
 * `@solidjs/testing-library` is written against Solid 1.x, where `render` lives in `solid-js/web`.
 * In Solid 2 it lives in `@solidjs/web`, and the reactive model underneath it changed: updates are
 * batched to a microtask, so a read taken straight after a write returns the old value until
 * `flush()`. Rather than depend on a wrapper that has caught up with neither change, the mounting
 * is done directly against the renderer — four lines, and no version to be behind.
 *
 * ## Why this file exists twice
 *
 * `packages/shell/src/chrome/testing.ts` is the same helper. It is duplicated rather than shared
 * because the kernel must not depend on the shell (that edge is the one the microfrontend split
 * exists to prevent) and a `@kui/testing` package for eight lines would be worse than both. If a
 * third copy is ever wanted, that is the moment to make the package.
 *
 * ## The two rules a Solid 2 test has to obey
 *
 * `flush()` before asserting: a setter queues, and the DOM catches up on the next microtask. A test
 * that asserts immediately reads the previous value and fails in a way that looks like a broken
 * component rather than like a missing `flush()`.
 *
 * Dispose at the end: reactive primitives need an owner, and `render` returns the function that
 * tears theirs down. Leaving them alive leaks listeners between cases, and the symptom is a later
 * test failing because of an earlier one.
 */

import { render } from "@solidjs/web";
import type { JSX } from "@solidjs/web/jsx-runtime";
import axe from "axe-core";

export interface Mounted {
  readonly container: HTMLElement;
  readonly dispose: () => void;
}

/** Mounts a component into a container that is really in the document, and hands back the teardown. */
export function mount(component: () => JSX.Element): Mounted {
  const container = document.createElement("div");
  /* Attached rather than left floating: focus, `:focus-visible`, `document`-level listeners and
   * axe all behave differently — or not at all — on a detached tree. */
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
 * Runs axe over a subtree and returns the violations.
 *
 * `color-contrast` is disabled, and for a reason rather than for convenience: it needs real layout
 * and painted colours, and jsdom has neither — it reports every element as black on transparent and
 * then produces confident nonsense. Contrast is checked by the token-level contrast suite, which
 * resolves all eight theme-and-accent combinations, and by looking at the stories.
 */
export async function findViolations(container: HTMLElement): Promise<axe.Result[]> {
  const results = await axe.run(container, { rules: { "color-contrast": { enabled: false } } });
  return results.violations;
}

/** A violation formatted so the failure says what to fix, not merely that something failed. */
export function describeViolations(violations: axe.Result[]): string {
  return violations
    .map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.html).join(", ")})`)
    .join("\n");
}
