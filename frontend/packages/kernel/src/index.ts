/**
 * The kernel: the design system, the API client, the query cache, the SSE client and the
 * capability and permission stores (lanes B and C of `docs/plans/SOLID/DEVPLAN.md`).
 */

export * from "./components/index.js";

/**
 * The icon set. Drawn inline as SVG so that nothing is fetched at run time — KUI is installed in
 * private and air-gapped networks — and so that every icon is `aria-hidden` by construction.
 */
export { Icon, iconNames, type IconName, type IconProps } from "./icon.jsx";

/**
 * Theme, accent and density. Three attributes on `<html>`, and the stylesheet does the rest — no
 * colour or measurement is ever computed in TypeScript (ADR-024, ADR-048 §5).
 */
export * from "./theme/index.js";
