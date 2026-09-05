/**
 * The topics feature: the list of every topic on a cluster, and the frame each topic's page sits in.
 *
 * Nothing here fetches. Both screens are told what to draw, which is what makes every state they
 * can be in — including the ones that only happen when a broker stops answering — reachable from a
 * story and from a test without a cluster.
 */

export { TopicListPage, matchCount, formatBytes, type TopicListPageProps } from "./TopicListPage.jsx";
export { TopicPage, healthChip, type TopicPageProps, type TopicAction } from "./TopicPage.jsx";
export type { TopicHealth, TopicRow, TopicTab } from "./types.js";
