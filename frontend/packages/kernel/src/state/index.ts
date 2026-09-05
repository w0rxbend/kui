/**
 * The kernel's state: what the browser knows about the deployment, as opposed to what it draws.
 *
 * Nothing here renders anything and nothing here fetches anything. Each store is created from
 * functions the composition root hands it — how to open a stream, how to poll, how to schedule a
 * timer — which is what lets every one of its interesting states, including the ones that only occur
 * when a service is down, be reached in a test with no server and no clock.
 */
export {
  type CapabilityEntry,
  type CapabilityFrame,
  type CapabilityKey,
  type CapabilityStateValue,
  type CapabilityStore,
  type CapabilityStoreOptions,
  type CapabilityStreamHandlers,
  type DegradedReason,
  type StreamState,
  DefaultPollIntervalMs,
  capabilityKeyOf,
  createCapabilityStore,
  decodeCapabilityEntry,
  decodeCapabilityFrame,
  decodeCapabilityState,
} from "./capability.js";

export {
  type FeatureState,
  StartingMessage,
  deriveFeatureState,
  explanation,
  isDimmed,
  isHidden,
  isNavigable,
  reasonSentence,
  startingReason,
  stateName,
} from "./featureState.js";

export {
  type SessionIdentity,
  type SessionOptions,
  type SessionState,
  AnonymousKind,
  AuthDisabled,
  createSession,
} from "./session.js";

export {
  type CurrentCluster,
  type CurrentClusterOptions,
  CurrentClusterStorageKey,
  createCurrentCluster,
  soleClusterChoice,
} from "./currentCluster.js";
