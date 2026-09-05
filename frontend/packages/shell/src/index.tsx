import { render } from "@solidjs/web";

import { App } from "./App.jsx";

/*
 * The product's stylesheet: tokens, reset, kernel primitives, chrome and screens, in the one order
 * the cascade is allowed to have (`packages/kernel/styles/index.css` explains the order at length).
 *
 * This import is what puts the design system in the *bundle*. Until it was added, the entry pulled
 * in `./proof.css` — 0.4 kB of styling for the scaffold's proof page — and nothing else, so the
 * built application shipped with no tokens, no reset and no component styles at all. It went
 * unnoticed because Storybook loads this same file from its own `preview.tsx`: every component and
 * every screen looked correct in Storybook and would have rendered as unstyled HTML in the product.
 * A design system that is only in the component workshop is not in the product.
 *
 * `proof.css` is gone with it: `.kui-proof` is rendered by nothing now that `App` draws the real
 * shell.
 */
import "@kui/kernel/styles/index.css";

/**
 * The browser entry point: the one module `index.html` loads.
 *
 * Everything else the shell needs is reached from here, and every *feature* is reached only
 * through `lazy(() => import("@kui/feature-…"))` — never a static import — so that a feature the
 * user cannot use is never downloaded (ADR-012, and the manifest check of SOL-010).
 */
const mountPoint = document.getElementById("app");

if (mountPoint === null) {
  // The mount point is part of the document the gateway serves. If it is missing, the page the
  // browser received is not the page this bundle was built for — most likely the single-page
  // fallback answering with something else entirely — and a blank screen with a clean console is
  // the worst possible way to report that.
  throw new Error("KUI cannot start: the document has no #app element to render into.");
}

render(() => <App />, mountPoint);
