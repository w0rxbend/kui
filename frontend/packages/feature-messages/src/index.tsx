/**
 * The messages feature: the browser an operator lives in.
 *
 * The screen (`MessagesTab`) is told what to draw and handed a session; the session (`session.ts`)
 * owns the stream and its bounded live tail; the grammar (`browse.ts`) turns a browse into a URL
 * and back. They are separate because only the first needs a DOM, only the second needs a network,
 * and the third needs neither — which is why the rules that are easiest to get silently wrong are
 * the ones that are plain functions.
 */

export { MessagesTab, type MessagesTabProps } from "./MessagesTab.jsx";
export {
  MessageFilterBar,
  FILTER_DEBOUNCE_MS,
  type MessageFilterBarProps,
  type LiveAvailability,
} from "./MessageFilterBar.jsx";
export {
  createBrowseSession,
  decodeBrowseEvent,
  MAX_ROWS,
  type BrowseSession,
  type BrowseSessionOptions,
  type BrowseTransport,
  type BrowseHandle,
  type BrowseEvent,
  type BrowseProgress,
  type BrowseConnection,
  type BrowseFailure,
  type Consumed,
} from "./session.js";
export {
  BROWSE_PARAM,
  DEFAULT_BROWSE,
  SERDE_CHOICES,
  decodeSeek,
  encodeSeek,
  fromParams,
  offeredSerde,
  offsetOf,
  partitionSummary,
  queryString,
  seekFor,
  seekKind,
  timestampOf,
  type BrowseQuery,
  type SeekKind,
  type SeekMode,
  type SerdeName,
} from "./browse.js";
export { toRecord, LARGE_VALUE_BYTES, type MessageDto } from "./wire.js";
