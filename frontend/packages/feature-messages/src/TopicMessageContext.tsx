import type { JSX } from "@solidjs/web";
import { PageHeader, StatusPill, TabStrip, useKui, type PillTone } from "@kui/kernel";

import type { TopicHealth } from "./topic.js";

export interface TopicMessageContextProps {
  readonly clusterId: string;
  readonly topicName: string;
  readonly partitionCount?: number | undefined;
  readonly health: TopicHealth;
  readonly children: JSX.Element;
}

const HEALTH: Record<TopicHealth, { readonly label: string; readonly tone: PillTone }> = {
  "in-sync": { label: "in sync", tone: "success" },
  "under-replicated": { label: "under-replicated", tone: "warning" },
  offline: { label: "offline", tone: "danger" },
  unknown: { label: "not described", tone: "neutral" },
};

/** Keeps the topic identity and its sibling destinations around the dedicated message feature. */
export function TopicMessageContext(props: TopicMessageContextProps): JSX.Element {
  const kui = useKui();
  const topicHref = () => kui.paths.topic(props.clusterId, props.topicName);
  const health = () => HEALTH[props.health];

  return (
    <section class="kui-topic-messages-page" aria-label={`Messages in ${props.topicName}`}>
      <PageHeader
        title={props.topicName}
        crumbs={[
          { label: "Topics", href: kui.paths.topics(props.clusterId) },
          { label: props.topicName },
        ]}
        chip={
          <StatusPill tone={health().tone} dot>
            {health().label}
          </StatusPill>
        }
        testId="topic-message-head"
      />
      <TabStrip
        label="Topic sections"
        currentId="messages"
        tabs={[
          { id: "overview", label: "Overview", icon: "info", href: topicHref() },
          {
            id: "partitions",
            label: "Partitions",
            icon: "partitions",
            href: `${topicHref()}?tab=partitions`,
            ...(props.partitionCount === undefined ? {} : { count: props.partitionCount }),
          },
          {
            id: "messages",
            label: "Messages",
            icon: "messages",
            href: kui.paths.topicMessages(props.clusterId, props.topicName),
          },
          {
            id: "consumers",
            label: "Consumers",
            icon: "consumers",
            href: `${topicHref()}?tab=consumers`,
          },
          {
            id: "settings",
            label: "Settings",
            icon: "sliders",
            href: `${topicHref()}?tab=settings`,
          },
        ]}
      />
      <div class="kui-topic-messages-page__body">{props.children}</div>
    </section>
  );
}
