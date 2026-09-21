/**
 * The Kafka Connect feature.
 *
 * The `default` export is what the shell renders for this feature's routes and is the only export
 * `shell/src/features/registry.ts` knows about — reached through a bare
 * `import("@kui/feature-connect")`, which is the split point the bundler works from and which
 * `frontend/scripts/bundle-shape.mjs` asserts against the build manifest.
 *
 * Everything else is exported for the stories, the cases, and `e2e/connect.spec.ts`, which asks the
 * gateway for the list address itself rather than a path spelled a second time. The paths and the
 * section key are constants for that reason: a wire name written down twice is the shape that cost
 * this project a milestone.
 */
export { ConnectorList, type ConnectorListProps } from "./ConnectorList.jsx";
export { ConnectorPanel, NotDescribedPanel, type ConnectorPanelProps } from "./ConnectorPanel.jsx";
export { ConnectScreen, type ConnectScreenProps } from "./ConnectRoute.jsx";
export {
  command,
  fetchConnectors,
  CONNECTORS_PATH,
  PAUSE_PATH,
  RESTART_PATH,
  RESUME_PATH,
  type ConnectorCommand,
  type ConnectorRef,
} from "./data.js";
export {
  allConnectors,
  allNotDescribed,
  cardActions,
  connectVoice,
  connectorLabel,
  failureReason,
  operateAction,
  operateSubject,
  pillState,
  segmentsOf,
  taskSegment,
  toggleOf,
  workersThatDidNotAnswer,
  NOT_CONFIGURED,
  NOT_DESCRIBED,
  NO_CONNECTORS,
  NO_REASON_REPORTED,
  THROUGHPUT_NOT_MEASURED,
  type CardActions,
  type FailureReason,
} from "./model.js";
export {
  connectorState,
  decodeConnectorListing,
  decodePage,
  decodeWorkerSection,
  CONNECTORS_SECTION_KEY,
  REBALANCING_REASON_CODE,
  Unreadable,
  type Connector,
  type ConnectorListing,
  type ConnectorRunState,
  type ConnectorsPage,
  type ConnectorTask,
  type TaskRunState,
  type WorkerAnswer,
  type WorkerRow,
} from "./wire.js";

export { default } from "./ConnectRoute.jsx";
