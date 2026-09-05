/**
 * The clusters feature: the cluster list, the broker list, and one broker's log directories and
 * configuration.
 *
 * The screens are presentational: none of them fetches. `data.ts` maps the wire onto the view
 * models in `model.ts` and `ClustersRoute.tsx` hands them down, which is what makes every failing
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

export {
  brokerConfigSourceOf,
  fetchBrokerConfigs,
  fetchBrokerLogDirs,
  fetchBrokers,
  fetchClusters,
  healthOf,
} from "./data.js";

/**
 * The feature's route entry.
 *
 * It used to render `SAMPLE_CLUSTERS` — a screen that looked like it worked and showed invented
 * data, which is the most dangerous state this product can be in. It now fetches, through the
 * `useKui()` seam the shell provides.
 */
export { default } from "./ClustersRoute.jsx";
