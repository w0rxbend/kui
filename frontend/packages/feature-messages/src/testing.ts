/**
 * The three lines of setup every test in this package needs.
 *
 * Duplicated from `@kui/kernel`'s copy rather than shared, for the reason its own header gives: the
 * kernel must not export a testing surface into the product bundle, and a `@kui/testing` package for
 * eight lines would be worse than either. If a fourth copy is ever wanted, that is the moment to
 * make the package.
 *
 * The two rules a Solid 2 test has to obey. `flush()` before asserting: a setter queues, and the DOM
 * catches up on the next microtask, so a test that asserts immediately reads the previous value and
 * fails in a way that looks like a broken component rather than like a missing `flush()`. And
 * dispose at the end: reactive primitives need an owner, and leaving them alive leaks listeners
 * between cases — the symptom is a later test failing because of an earlier one.
 */

import { render } from "@solidjs/web";
import type { JSX } from "@solidjs/web/jsx-runtime";

export interface Mounted {
  readonly container: HTMLElement;
  readonly dispose: () => void;
}

/** Mounts a component into a container that is really in the document, and hands back the teardown. */
export function mount(component: () => JSX.Element): Mounted {
  const container = document.createElement("div");
  /* Attached rather than left floating: focus, `:focus-visible` and `document`-level listeners
   * behave differently — or not at all — on a detached tree, and this screen has both. */
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
