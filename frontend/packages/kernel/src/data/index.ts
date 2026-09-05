/**
 * The data layer: caching, streaming, capabilities and permissions.
 *
 * Four things, and the seam between them is worth stating because it is what keeps one failure from
 * becoming another:
 *
 * - **{@link createQueryCache}** holds what a screen has asked the server for. A failure is a value
 *   in its state, next to the last good answer, so a page can keep showing yesterday's numbers with
 *   a badge on them instead of going blank.
 * - **{@link openEventSource} / {@link openFetchStream}** carry the streams. One bad frame never
 *   ends a stream; a terminal event ends it exactly once; a stream the server refused is reported as
 *   a refusal rather than as a stream that finished.
 * - **{@link createCapabilities}** holds what each service can currently do, from the capability
 *   stream and, when that cannot be held open, from a poller that says so. Nothing else writes into
 *   it: a feature's own request failing is that feature's business and must never dim a capability.
 * - **{@link createPermissions}** answers what the signed-in user may do. The grants themselves
 *   arrive with the identity, which `../state/session.ts` holds, because they change with it — so
 *   this module is the *rule* and the session is the *data*, and `grantsAllow` is the one function
 *   both of them ask.
 */

export {
  createQueryCache,
  queryKey,
  DEFAULT_MAX_ENTRIES,
  DEFAULT_STALE_AFTER_MS,
  NEGATIVE_STALE_AFTER_MS,
  type QueryCache,
  type QueryCacheOptions,
  type QueryState,
} from "./query/cache.js";

export {
  DEFAULT_EVENT_NAME,
  EMPTY_PARSER_STATE,
  feed,
  type ParserState,
  type RawSseEvent,
} from "./sse/parser.js";

export {
  backoff,
  backoffFor,
  openEventSource,
  openEventSourceWith,
  openFetchStream,
  openFetchStreamWith,
  type EventSourceLike,
  type SseConnection,
  type SseError,
  type SseHandle,
  type SseSubscriber,
  type StreamRequest,
  type StreamResponse,
  type StreamTransport,
} from "./sse/stream.js";

export {
  capabilityKeyOf,
  decodeCapabilityFrame,
  describeCapability,
  isUnavailable,
  stateMessage,
  type CapabilityEntry,
  type CapabilityFrame,
  type CapabilityKey,
  type CapabilityState,
} from "./capabilities/frames.js";

export {
  deriveFeatureState,
  isDimmed,
  isHidden,
  isNavigable,
  STARTING_MESSAGE,
  type FeatureState,
} from "./capabilities/featureState.js";

export {
  createCapabilities,
  DEDUP_WINDOW_MS,
  POLL_INTERVAL_MS,
  type Capabilities,
  type CapabilitiesOptions,
  type CapabilityNotice,
} from "./capabilities/store.js";

export {
  createPermissions,
  grantsAllow,
  grantsAllowAny,
  grantsFromWire,
  EVERY_CLUSTER,
  type PermissionDecision,
  type PermissionGrant,
  type Permissions,
} from "./permissions/store.js";

/**
 * What a screen is given: the data plus an honest account of its state, and the two mappings that
 * produce it from a section or from a transport failure.
 *
 * Six cases rather than three, because `stale`, `forbidden` and `not-configured` each call for a
 * different sentence and a different next action — and collapsing any of them into `failed` puts a
 * retry button in front of a user for whom retrying is pointless.
 */
export {
  apiFailure,
  figure,
  fromSection,
  valueOf,
  type Fetched,
} from "./fetched.js";
