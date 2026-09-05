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
  type SmartFilterSlot,
} from "./MessageFilterBar.jsx";

/**
 * Smart filters, and the preview that keeps a compiled expression from being trusted.
 *
 * Registering answers "does this compile"; testing answers "does this do what I mean". They ship
 * together because an expression can pass the first and fail the second — `record.offset` compiles
 * and is not a predicate — and a browse is a poor place to find that out.
 */
export { SmartFilterDialog, type SmartFilterDialogProps } from "./SmartFilterDialog.jsx";
export {
  registerFilter,
  testFilter,
  verdictOf,
  filterProblem,
  FILTER_EXAMPLES,
  FILTER_VARIABLES,
  MAX_FILTER_SOURCE_BYTES,
  type FilterVerdict,
  type RegisteredFilter,
} from "./filters.js";

/** Copying a range of records into another topic, and reading the two figures it answers with. */
export { ResendDialog, type ResendDialogProps } from "./ResendDialog.jsx";
export {
  resend,
  readingOf,
  rangeSize,
  draftSize,
  resendDraftProblem,
  MAX_RESEND_RECORDS,
  RESEND_WARNINGS,
  type ResendDraft,
  type ResendRange,
  type ResendOutcome,
  type ResendReading,
} from "./resend.js";
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
export { toRecord, toDto, LARGE_VALUE_BYTES, type MessageDto } from "./wire.js";

/**
 * The real transport, and the reason it is not `openEventSource`: a browse must be cancellable, and
 * the native `EventSource` cannot be aborted — a stopped browse would leave a Kafka consumer
 * assigned on the message service until its budget expired.
 */
export { createBrowseTransport } from "./transport.js";

/**
 * The feature's route entry.
 *
 * There was no default export, so every `/messages` address rendered the kernel's "this feature
 * arrived without a screen" panel — the screen, the session, the URL grammar and (since the commit
 * that added `transport.ts`) the network all existed, and nothing joined them.
 */
export { default } from "./MessagesRoute.jsx";
