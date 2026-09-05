import { render } from "@solidjs/web";

import { App } from "./App.jsx";
import "./proof.css";

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
