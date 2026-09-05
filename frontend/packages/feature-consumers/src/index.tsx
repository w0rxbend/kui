/**
 * The consumers feature: the group list (screenshot `04`), the group detail, and the offset-reset
 * wizard.
 *
 * Everything exported here is presentational and takes its data as props. Nothing in this package
 * fetches; the data layer maps the generated OpenAPI types onto the view models in `model.ts` and
 * `detail.ts` and hands them down. That is what makes every rendering in the feature — including
 * the four not-happy ones and the wizard's five steps — reachable from a story and from a test
 * without a server, which is the only way the states that are hard to produce ever get looked at.
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

import { GroupList } from "./GroupList.jsx";
import { SAMPLE_GROUPS } from "./fixtures.js";

/**
 * The feature's route entry — the group list, on the fixtures, until the data layer is wired.
 *
 * It draws the real screen rather than a placeholder sentence so that the loading seam is proved
 * with the thing it will actually load.
 */
export default function Consumers() {
  return <GroupList rows={SAMPLE_GROUPS} hrefFor={(id) => `#/consumer-groups/${encodeURIComponent(id)}`} />;
}
