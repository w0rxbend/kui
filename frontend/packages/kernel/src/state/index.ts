/**
 * The kernel's state: what the browser knows about the deployment, as opposed to what it draws.
 *
 * Nothing here renders anything and nothing here fetches anything. Each store is created from
 * functions the composition root hands it, which is what lets every one of its interesting states be
 * reached in a test with no server and no clock.
 *
 * The capability picture is not here: it lives in `../data/capabilities`, next to the SSE client that
 * feeds it.
 */
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
