/**
 * The ksqlDB feature.
 *
 * The `default` export is what the shell renders for this feature's routes and is the only export
 * `shell/src/features/registry.ts` knows about — reached through a bare
 * `import("@kui/feature-ksql")`, which is the split point the bundler works from and which
 * `frontend/scripts/bundle-shape.mjs` asserts against the build manifest.
 *
 * Everything else is exported for the stories, the cases, and `e2e/ksql.spec.ts`, which asks the
 * gateway for these addresses itself rather than spelling a path a second time. The paths, the
 * section key and the row event name are constants for that reason: a wire name written down twice
 * is the shape that cost this project a milestone.
 */
export { ConfirmStatement, type ConfirmStatementProps } from "./ConfirmStatement.jsx";
export { KsqlResult, type KsqlResultProps } from "./KsqlResult.jsx";
export { KsqlScreen, type KsqlScreenProps } from "./KsqlRoute.jsx";
export { RunningQueries, type RunningQueriesProps } from "./RunningQueries.jsx";
export {
  fetchClusterWriteState,
  fetchObjects,
  openPushQuery,
  planStatement,
  pushQueryAddress,
  runStatement,
  CLUSTER_PATH,
  KSQL_OBJECTS_PATH,
  KSQL_PLAN_PATH,
  KSQL_STATEMENTS_PATH,
  KSQL_STREAM_PATH,
  STATEMENT_PARAM,
  UNREADABLE_MESSAGE,
  type ClusterWriteState,
  type PushQuerySubscriber,
} from "./data.js";
export {
  appendRow,
  cellText,
  endLive,
  interrupt,
  isLive,
  ksqlVoice,
  regionFor,
  rowSentence,
  withColumns,
  CELL_ABSENT,
  EXECUTE_ACTION,
  IDLE,
  MAX_RESULT_ROWS,
  NOT_CONFIGURED,
  NO_OBJECTS,
  NO_ROWS,
  NO_ROWS_YET,
  OFFSET_RESET_NOT_SETTABLE,
  OPENING,
  type ResultRegion,
} from "./model.js";
export {
  decodeObjects,
  decodePlan,
  decodeQueryHeader,
  decodeQueryRow,
  decodeResult,
  objectKind,
  sectionOf,
  statementShape,
  KSQL_OBJECTS_SECTION_KEY,
  KSQL_ROW_EVENT_NAME,
  Unreadable,
  type KsqlCell,
  type KsqlObjectKind,
  type KsqlObjectRow,
  type KsqlObjects,
  type KsqlRow,
  type StatementPlan,
  type StatementResult,
  type StatementShape,
} from "./wire.js";

export { default } from "./KsqlRoute.jsx";
