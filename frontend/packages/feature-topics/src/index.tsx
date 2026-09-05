/**
 * The topics feature: the list of every topic on a cluster, and the frame each topic's page sits in.
 *
 * The screens fetch nothing. `data.ts` maps the wire onto the view models and `TopicsRoute.tsx`
 * hands them down, which is what makes every state they can be in — including the ones that only
 * happen when a broker stops answering — reachable from a story and from a test without a cluster.
 *
 * The default export is the contract: the shell reaches this package through
 * `import("@kui/feature-topics")` and reads `default` as the feature's root. There was not one, so
 * every `/topics` address rendered the kernel's "this feature arrived without a screen" panel —
 * legible, and not a topic list.
 */

export { TopicListPage, matchCount, formatBytes, type TopicListPageProps } from "./TopicListPage.jsx";
export { TopicPage, healthChip, type TopicPageProps, type TopicAction } from "./TopicPage.jsx";
export type { TopicHealth, TopicRow, TopicTab } from "./types.js";

export { fetchTopics, fetchTopicOverview, healthOf, type PartitionRow, type TopicListResult, type TopicOverview } from "./data.js";

export { default } from "./TopicsRoute.jsx";
