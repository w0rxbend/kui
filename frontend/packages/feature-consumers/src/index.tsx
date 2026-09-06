/**
 * The consumers feature: the group list (screenshot `04`), the group detail, and the offset-reset
 * wizard.
 *
 * Every *component* exported here is presentational and takes its data as props: the route entry at
 * the bottom of this file is the only thing in the package that fetches, and it does so through
 * `data.ts`, which maps the wire onto the view models in `model.ts` and `detail.ts`. That is what
 * makes every rendering in the feature — including the four not-happy ones and the wizard's five
 * steps — reachable from a story and from a test without a server, which is the only way the states
 * that are hard to produce ever get looked at.
 *
 * The module has a default export because the shell reaches it through
 * `lazy(() => import("@kui/feature-consumers"))` and Vite gives it a chunk of its own (ADR-012,
 * ADR-048 §4).
 */

export { GroupList, type GroupListProps } from "./GroupList.jsx";
export { GroupDetail, memberColumns, type GroupDetailProps } from "./GroupDetail.jsx";
export { ResetWizard, scopeSentence, type ResetStep, type ResetWizardProps } from "./ResetWizard.jsx";
export * from "./model.js";
export * from "./detail.js";

export { fetchGroups, stateOf, type GroupListResult } from "./data.js";

/**
 * The feature's route entry.
 *
 * It used to render `SAMPLE_GROUPS` — invented groups with invented lag, on a screen an operator
 * opens to find out whether a consumer is behind. It now fetches, through the `useKui()` seam.
 */
export { default } from "./ConsumersRoute.jsx";
