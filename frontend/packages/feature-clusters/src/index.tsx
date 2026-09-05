/**
 * The clusters feature: the cluster list, the broker list, and one broker's log directories and
 * configuration.
 *
 * Presentational, like the consumers feature. Nothing here fetches; the data layer maps the wire
 * onto the view models in `model.ts` and hands them down, which is what makes every failing
 * rendering reachable from a story without a broken cluster to hand.
 *
 * The module has a default export because the shell reaches it through
 * `lazy(() => import("@kui/feature-clusters"))` and Vite gives it a chunk of its own (ADR-012,
 * ADR-048 §4).
 */

export { ClusterList, type ClusterListProps } from "./ClusterList.jsx";
export { BrokerList, type BrokerListProps } from "./BrokerList.jsx";
export { BrokerDetail, type BrokerDetailProps, type BrokerTabKey, type Loaded } from "./BrokerDetail.jsx";
export * from "./model.js";

import { ClusterList } from "./ClusterList.jsx";
import { SAMPLE_CLUSTERS } from "./fixtures.js";

/**
 * The feature's route entry — the cluster list, on the fixtures, until the data layer is wired.
 *
 * It draws the real screen rather than a placeholder sentence, so the lazy-loading seam is proved
 * with the thing it will actually load.
 */
export default function Clusters() {
  return <ClusterList clusters={SAMPLE_CLUSTERS} hrefFor={(id) => `#/clusters/${encodeURIComponent(id)}`} />;
}
