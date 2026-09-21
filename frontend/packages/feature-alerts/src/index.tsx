/**
 * The alerts feature.
 *
 * One feed, read in three places: this screen, the card the dashboard can mount beside its others,
 * and the bell in the shell's chrome. The bell is not here — it is chrome, and chrome is the
 * shell's — which is why the store behind it belongs to `@kui/kernel`: a feature may not import the
 * shell, nothing may import `@kui/shell`, and the shell must not statically import a feature or the
 * microfrontend split collapses into one download.
 *
 * The `default` export is what the shell renders for this feature's routes and is the only export
 * `features/registry.ts` knows about. Everything else is here for whoever mounts the card beside
 * another one, and for the tests and stories that drive each piece on its own.
 */
export { AlertsFeed, type AlertsFeedProps } from "./AlertsFeed.jsx";
export { RuleReports, type RuleReportsProps } from "./RuleReports.jsx";
export { AlertsScreen, type AlertsScreenProps } from "./AlertsRoute.jsx";
export {
  acknowledgedBy,
  categoryWords,
  feedVoice,
  filterEvents,
  glyphIsKnown,
  glyphOf,
  isOpen,
  matches,
  openPill,
  pageCaption,
  ruleRefusal,
  severityChip,
  severityWords,
  toneIsKnown,
  toneOf,
  EVERY_EVENT,
  NOT_EVALUATED,
  NO_EVENTS,
  NO_MATCHES,
  NO_OPEN_COUNT,
  SEVERITIES,
  type AlertEvent,
  type AlertResolution,
  type AlertsFeedPage,
  type FeedFilter,
  type RuleReport,
  type Severity,
  type SeverityFilter,
  type StateFilter,
} from "./model.js";
/*
 * No feed reader and no feed address. Both are `@kui/kernel`'s: one `createAlerts` store answers
 * the bell in the shell's chrome and this screen's card, and a second decoder beside it is what
 * wave 5's producers wire cost. `data.ts`'s header says which side of the wire lives where.
 */
export { acknowledge, ACKNOWLEDGEMENT_PATH } from "./data.js";

export { default } from "./AlertsRoute.jsx";
