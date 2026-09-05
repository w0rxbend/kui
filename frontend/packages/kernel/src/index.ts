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

/**
 * The session and its permissions, and which cluster is being looked at.
 */
export * from "./state/index.js";

/**
 * Feature registration and lazy loading — the static half a deep link resolves against, and the
 * bounded, retryable download of the dynamic half.
 */
export * from "./feature/index.js";

/**
 * How a quantity is printed. Thousands separators, signed deltas, guarded fractions, and the em
 * dash that means "no value" — shared because three screens printing the same kind of number must
 * not each decide how to group its thousands (SPEC §6.6).
 */
export { formatCount, formatRate, formatDelta, share, MISSING } from "./numbers.js";

/**
 * A media query as a signal — how a screen drops a column rather than hiding it (SPEC §7.5).
 */
export { createMediaQuery, NARROW_QUERY } from "./media.js";

/**
 * The capability picture: what every service can currently do, and the five states the shell renders
 * from it (ADR-032). Exported from the two modules directly rather than through a barrel, so that
 * adding one later is a merge of two lines rather than a conflict.
 */
export {
  type Capabilities,
  type CapabilitiesOptions,
  type CapabilityNotice,
  createCapabilities,
  POLL_INTERVAL_MS,
  DEDUP_WINDOW_MS,
} from "./data/capabilities/store.js";
export {
  type CapabilityEntry,
  type CapabilityFrame,
  type CapabilityKey,
  type CapabilityState,
  capabilityKeyOf,
  decodeCapabilityFrame,
  describeCapability,
  isUnavailable,
  stateMessage,
} from "./data/capabilities/frames.js";
export {
  type FeatureState,
  STARTING_MESSAGE,
  deriveFeatureState,
  isDimmed,
  isHidden,
  isNavigable,
} from "./data/capabilities/featureState.js";

/**
 * The data layer: the query cache, the server-sent-event client, and the capability, permission and
 * session stores. Behaviour, not components — a screen reads these and draws itself from what they
 * say, including when what they say is "this is not working and here is why".
 */
export * from "./data/index.js";

/**
 * Kafka Connect and ksqlDB.
 *
 * These have **no service behind them**: KUI has no connect or ksql backend, so nothing routes to
 * them and nothing fetches for them. They live here, exercised by stories against fixtures, so the
 * screens are designed and reviewed before the services are written rather than after — and so that
 * the rules the rest of the product follows (a disabled control states its reason; an unknown state
 * is never drawn as a healthy one; a figure nobody measured is never a zero) are built in from the
 * start instead of being retrofitted once.
 */
export { ConnectorCard, connectorChip, type ConnectorCardProps, type ConnectorState } from "./components/ConnectorCard.jsx";
export { TaskBar, describeTasks, type TaskBarProps, type TaskState } from "./components/TaskBar.jsx";
export { KsqlWorkspace, type KsqlObject, type KsqlWorkspaceProps } from "./components/KsqlWorkspace.jsx";

