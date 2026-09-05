/**
 * A topic's configuration: reading it, and saying which parts of it somebody chose.
 *
 * ## The distinction the whole screen turns on
 *
 * Kafka reports every configuration key for every topic — thirty-three of them on an ordinary topic
 * — and almost all of them hold the broker's default. Three of them, on the topic this was built
 * against, hold a value somebody set. Those three are the ones an operator came to look at, and the
 * wire tells them apart with `source`: `dynamic-topic` means set on this topic, anything else means
 * inherited.
 *
 * A screen that draws all thirty-three the same way is a screen where the three that matter are
 * invisible, and it is what makes "why is this topic behaving differently" a twenty-minute job.
 *
 * ## Sensitive keys carry no value at all
 *
 * The broker marks some keys sensitive and sends them with no value. That is not a value the browser
 * failed to read, and it must not render as empty — an empty box next to `Save` invites somebody to
 * overwrite a password with the empty string.
 */
import { decodeSection, type KuiApiClient } from "@kui/api";
import { apiFailure, fromSection, type Fetched } from "@kui/kernel";

/** Where a configuration value came from. */
export type ConfigSource =
  /** Set on this topic. The ones an operator came here to see. */
  | "topic"
  /** Inherited — the broker's default, or a static or cluster-wide setting. */
  | "inherited";

export interface ConfigEntry {
  readonly name: string;
  /** `null` for a key the broker marks sensitive: it sends no value, and that is not an empty one. */
  readonly value: string | null;
  /** What it would go back to if the override were removed. `null` when the broker did not say. */
  readonly defaultValue: string | null;
  readonly source: ConfigSource;
  /** The broker's own prose. Long, HTML, and the only documentation an operator has to hand. */
  readonly documentation: string | null;
  readonly sensitive: boolean;
  /** Kafka will refuse to change it. The control says so rather than failing on save. */
  readonly readOnly: boolean;
}

interface ConfigValuePayload {
  readonly name: string;
  readonly value?: string | null;
  readonly defaultValue?: string | null;
  readonly source?: string | null;
  readonly sensitive?: boolean;
  readonly readOnly?: boolean;
  readonly documentation?: string | null;
}

interface ConfigPayload {
  /** `entries` when the values are here; anything else means they are not. */
  readonly status?: string;
  readonly values?: readonly ConfigValuePayload[];
}

/**
 * Whether this value was set on the topic.
 *
 * Kafka's `source` vocabulary has six members and only one of them means "somebody set this here":
 * `DYNAMIC_TOPIC_CONFIG`, which this gateway spells `dynamic-topic`. Everything else — the broker
 * default, a static broker setting, a cluster-wide default — is inherited, and treating an unknown
 * word as an override would put an "overridden" badge on a key nobody has touched.
 */
export function sourceOf(raw: string | null | undefined): ConfigSource {
  return raw === "dynamic-topic" ? "topic" : "inherited";
}

function toEntry(payload: ConfigValuePayload): ConfigEntry {
  const sensitive = payload.sensitive === true;
  return {
    name: payload.name,
    // A sensitive key has no value on the wire. `null` keeps it out of an editable box.
    value: sensitive ? null : (payload.value ?? null),
    defaultValue: payload.defaultValue ?? null,
    source: sourceOf(payload.source),
    documentation: payload.documentation ?? null,
    sensitive,
    readOnly: payload.readOnly === true,
  };
}

export interface TopicConfig {
  readonly entries: readonly ConfigEntry[];
  /** How many were set on this topic rather than inherited. The screen's one summary figure. */
  readonly overridden: number;
}

export async function fetchTopicConfig(
  api: KuiApiClient,
  clusterId: string,
  topicName: string,
): Promise<Fetched<TopicConfig>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/topics/{topicName}/config", {
    params: { path: { clusterId, topicName } },
  });
  if (!answer.ok) return apiFailure(answer.error);

  const section = decodeSection<ConfigPayload>(answer.value.config);
  return fromSection(section, (payload) => {
    /*
     * A caller who may see the topic but not its configuration gets a `not_permitted` *view* rather
     * than a 403, so the rest of the topic page keeps working (ADR-039). It arrives as a section
     * with `ok` status and a payload that carries no values, which would otherwise render as a topic
     * with no configuration at all — a very different statement from "you may not see it".
     */
    const entries = (payload.values ?? []).map(toEntry);
    return {
      entries,
      overridden: entries.filter((entry) => entry.source === "topic").length,
    };
  });
}

/** Whether the configuration was withheld rather than being empty. */
export function isWithheld(config: TopicConfig): boolean {
  return config.entries.length === 0;
}
