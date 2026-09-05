/**
 * `@kui/api` — the contract seam.
 *
 * Everything the browser knows about the server's shapes comes from here, and every one of those
 * shapes is generated from `docs/api/openapi.browser.json`, which the Scala build regenerates from
 * the gateway's own Tapir endpoints. Nothing in this package is a hand-written mirror of a server
 * type, and nothing outside it may be: that is the rule that replaces ADR-011's cross-compiled
 * contracts, and ADR-048 §3 is the argument for it.
 *
 * The three things a caller wants are the client ({@link createApiClient}), the result type it
 * answers with ({@link ApiResult}), and the failure data inside it ({@link ApiError}).
 */

export type { paths, components, operations } from "./schema.js";

export {
  CsrfHeaderName,
  ErrorCodes,
  AllErrorCodes,
  type ErrorCode,
  type KnownErrorCode,
  CapabilityStatuses,
  ReasonCodes,
  ReasonSentences,
  AllReasonCodes,
  type CapabilityStatus,
  type KnownReasonCode,
  SseEventNames,
  SharedSseEventNames,
  Resources,
  Actions,
  ConnectorFallbackActions,
  type ResourceName,
  type PermissionAction,
} from "./constants.generated.js";

export {
  type ApiError,
  type ErrorEnvelope,
  type ErrorDetail,
  decodeEnvelope,
  userMessage,
  userFacingSentence,
  isAuthFailure,
  isForbidden,
  isRetryable,
  isTransportFailure,
  correlationId,
  UnreachableMessage,
  TimeoutMessage,
  DecodingMessage,
} from "./errors.js";

export { type ApiResult, ok, err, mapResult, valueOr } from "./result.js";

export {
  type Bootstrap,
  BootstrapElementId,
  FallbackBootstrap,
  readBootstrap,
  apiBaseUrl,
} from "./bootstrap.js";

export { type CsrfTokens, createCsrfTokens } from "./csrf.js";

export {
  type Section,
  type SectionStatus,
  type SectionReason,
  decodeSection,
  sectionData,
} from "./section.js";

export {
  type KuiApiClient,
  type ApiClientOptions,
  createApiClient,
  transportFailure,
} from "./client.js";

export {
  type Branded,
  type ClusterId,
  type TopicName,
  type GroupId,
  type SubjectName,
  type ConnectorName,
  type InvalidIdentifier,
  type Validated,
  clusterId,
  topicName,
  groupId,
  subjectName,
  connectorName,
  trusted,
} from "./brand.js";
