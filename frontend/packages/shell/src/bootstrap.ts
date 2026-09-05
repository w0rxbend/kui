/**
 * Reading the configuration the gateway embeds in the page it serves.
 *
 * A browser has no configuration file, so it cannot know where KUI is mounted. The gateway
 * answers that question in the document itself: `kui.gateway.api.static.IndexHtml` replaces the
 * `<!--KUI_BOOTSTRAP-->` marker in `index.html` with
 *
 * ```html
 * <script id="kui-bootstrap" type="application/json">
 *   {"basePath":"/kui","apiBase":"/kui/api/v1","buildVersion":"1.2.3"}
 * </script>
 * ```
 *
 * and the `<!--KUI_BASE_HREF-->` marker with `<base href="/kui/ui/">`. Both markers survive the
 * move to Vite untouched (ADR-048 §6) — Vite copies unknown HTML comments through — which is
 * why nothing on the server side had to change to serve this build.
 *
 * The element id is a contract with the server: `StaticRoutesSuite` asserts on this exact
 * string. Changing it here without changing `IndexHtml` breaks every deployment silently.
 *
 * This lives in the shell because the shell is what needs it first — it is the only thing that has
 * to know where the router is mounted before anything else runs. `@kui/api` reads the same block
 * for the API client (SOL-005); when both exist, one of the two wins and the other imports it.
 */

/** Where KUI is mounted, where its API is, and which build served the page. */
export interface Bootstrap {
  /** The path prefix every route is served under: `""` at the root, or e.g. `"/kui"`. */
  readonly basePath: string;
  /** Where API calls go, already carrying the base path: e.g. `"/api/v1"` or `"/kui/api/v1"`. */
  readonly apiBase: string;
  /** The version shown in the shell's footer. */
  readonly buildVersion: string;
}

/** The id of the element the gateway writes the block into. Mirrors `IndexHtml.BootstrapElementId`. */
export const bootstrapElementId = "kui-bootstrap";

/**
 * What to assume when the block is missing or unreadable.
 *
 * Missing means one of two things, and both are development situations: the page came from a
 * gateway that never had the marker substituted, or `index.html` was opened straight off disk.
 * Neither is worth a blank screen, so the shell falls back to a root-mounted deployment and the
 * caller can say so on the page.
 */
const rootDeployment: Bootstrap = { basePath: "", apiBase: "/api/v1", buildVersion: "unknown" };

/**
 * Reads the block, or falls back to a root-mounted deployment.
 *
 * Takes the document rather than reaching for the global one so that a test can hand it a
 * fixture without a browser.
 */
export function readBootstrap(from: Document = document): Bootstrap {
  const element = from.getElementById(bootstrapElementId);
  if (element === null || element.textContent === null) return rootDeployment;

  try {
    const parsed: unknown = JSON.parse(element.textContent);
    if (typeof parsed !== "object" || parsed === null) return rootDeployment;

    const candidate = parsed as Partial<Record<keyof Bootstrap, unknown>>;
    return {
      basePath: stringOr(candidate.basePath, rootDeployment.basePath),
      apiBase: stringOr(candidate.apiBase, rootDeployment.apiBase),
      buildVersion: stringOr(candidate.buildVersion, rootDeployment.buildVersion),
    };
  } catch {
    // A malformed block is a server-side bug, but throwing here would replace a working page
    // with an empty one. The fallback is visible on the page instead.
    return rootDeployment;
  }
}

function stringOr(value: unknown, fallback: string): string {
  return typeof value === "string" ? value : fallback;
}
