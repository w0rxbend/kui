/**
 * One broker, as a card that opens: identity and figures collapsed, tags and configuration expanded.
 *
 * ## Why a card per broker rather than a row per broker
 *
 * The table this replaces could not show a disk bar — a stadium track four pixels wide is a smudge —
 * so the screen carried a separate health panel to draw the bars, and the operator had to read the
 * same three brokers twice in two places to answer one question. The design's card puts the bar
 * beside the figures it belongs to, and the count of brokers a cluster has (three, five, nine) is
 * small enough that losing the table's alignment costs nothing.
 *
 * That trade reverses somewhere above about thirty brokers, where cards stop being scannable. No
 * cluster in this product's target has thirty brokers; if one arrives, the answer is a table with a
 * disk *column*, not a smaller card.
 *
 * ## The configuration is inside the disclosure, and that is deliberate
 *
 * A broker has around two hundred settings. Collapsed, the card answers "is this broker all right?";
 * expanded, it answers "why is it behaving like that?". Those are different questions asked at
 * different moments, and putting the second one's answer on screen permanently is what makes the
 * first one hard.
 *
 * ## Every figure can be absent, and absent is never zero
 *
 * `leaderPartitions`, `replicaPartitions` and the disk are all nullable on the wire, because a
 * cluster that answers `describeCluster` but refuses `describeLogDirs` is an ordinary managed-service
 * configuration. Each draws an em dash with a title saying what could not be read. A `0` here would
 * claim the broker leads no partitions, which on a live cluster is an emergency rather than a
 * missing figure.
 */
import { For, Show, createSignal, createUniqueId } from "solid-js";
import type { JSX } from "@solidjs/web";
import {
  ConfigChip,
  ConfigChips,
  Icon,
  IconTile,
  MISSING,
  ProgressBar,
  Tag,
  formatCount,
} from "@kui/kernel";
import { DISK_THRESHOLDS, brokerName, diskPercent, type Broker } from "./model.js";

/** One setting, as the broker-configs endpoint reports it. */
export type BrokerConfig = {
  readonly name: string;
  readonly value: string | undefined;
  /** The broker's value differs from the cluster default. */
  readonly overridden?: boolean | undefined;
  readonly description?: string | undefined;
};

export type BrokerCardProps = {
  readonly broker: Broker;
  /**
   * The broker version and protocol, e.g. `v3.7.0 · KRaft`. Optional because it is not on the
   * broker DTO: the design shows it, the wire does not carry it yet, and inventing it would be
   * worse than leaving the tag out.
   */
  readonly version?: string | undefined;
  /** `uptime 41d`, already in words. Absent when it is not known. */
  readonly uptime?: string | undefined;
  /**
   * The settings, when they have been fetched. `undefined` means "not asked for yet" and draws a
   * hint rather than an empty block — an empty configuration block reads as "this broker has no
   * settings", which is never true.
   */
  readonly configs?: readonly BrokerConfig[] | undefined;
  /** Why the settings could not be read. Shown in place of them; never an empty list. */
  readonly configsError?: string | undefined;
  /**
   * Whether to draw the rack figure at all.
   *
   * A cluster that is not rack-aware has no rack on any broker, so the figure would be an em dash on
   * every card — and a column that is always empty is what teaches people to stop reading columns.
   * The list decides this once for the whole cluster rather than each card deciding for itself, so
   * that a single rack-less broker in a rack-aware cluster still shows its gap honestly.
   */
  readonly showRack?: boolean | undefined;
  readonly expanded?: boolean | undefined;
  readonly onToggle?: ((expanded: boolean) => void) | undefined;
  readonly href?: string | undefined;
  readonly testId?: string | undefined;
};

/** A figure with its label, as the card draws them across its middle. */
function Figure(props: { readonly label: string; readonly children: JSX.Element }): JSX.Element {
  return (
    <div class="kui-brkcard__figure">
      <span class="kui-brkcard__figure-label">{props.label}</span>
      <span class="kui-brkcard__figure-value">{props.children}</span>
    </div>
  );
}

function Unknown(props: { readonly what: string }): JSX.Element {
  return <span title={`The ${props.what} could not be read`}>{MISSING}</span>;
}

export function BrokerCard(props: BrokerCardProps): JSX.Element {
  // Uncontrolled unless the caller says otherwise, so the common case — a list where each card opens
  // on its own — needs no state at the call site.
  const [ownExpanded, setOwnExpanded] = createSignal(false);
  const expanded = () => props.expanded ?? ownExpanded();
  const panelId = createUniqueId();

  const broker = () => props.broker;
  const percent = () => diskPercent(broker().diskUsedBytes, broker().diskTotalBytes);

  const toggle = (): void => {
    const next = !expanded();
    setOwnExpanded(next);
    props.onToggle?.(next);
  };

  return (
    <section
      class={["kui-brkcard", { "kui-brkcard--expanded": expanded() }]}
      data-testid={props.testId ?? `broker-${broker().id}`}
      aria-label={`Broker ${brokerName(broker())}`}
    >
      <div class="kui-brkcard__head">
        <IconTile icon="brokers" tone={broker().isController ? "primary" : "success"} />

        <div class="kui-brkcard__identity">
          <Show
            when={props.href}
            fallback={<span class="kui-brkcard__name">{broker().host}</span>}
          >
            {(href) => (
              <a class="kui-brkcard__name kui-focusable" href={href()}>
                {broker().host}
              </a>
            )}
          </Show>
          <span class="kui-brkcard__address">
            {broker().host}:{broker().port}
          </span>
        </div>

        <div class={["kui-brkcard__figures", { "kui-brkcard__figures--two": props.showRack === false }]}>
          <Figure label="LEADERS">
            {broker().leaderPartitions === null ? (
              <Unknown what="leader count" />
            ) : (
              formatCount(broker().leaderPartitions ?? 0)
            )}
          </Figure>

          <Figure label="REPLICAS">
            {broker().replicaPartitions === null ? (
              <Unknown what="replica count" />
            ) : (
              formatCount(broker().replicaPartitions ?? 0)
            )}
          </Figure>

          <Show when={props.showRack !== false}>
            <Figure label="RACK">
              {/* An em dash rather than the word "none": a broker with no rack in a rack-aware
                  cluster has a gap worth seeing, and "none" would read as a rack called none. */}
              {broker().rack === null ? <Unknown what="rack" /> : broker().rack}
            </Figure>
          </Show>
        </div>

        <div class="kui-brkcard__disk">
          <ProgressBar
            label={`${brokerName(broker())} disk usage`}
            caption="disk"
            value={percent()}
            max={100}
            thresholds={DISK_THRESHOLDS}
            valueText={percent() === undefined ? undefined : `${Math.round(percent() ?? 0)}%`}
          />
        </div>

        <button
          type="button"
          class="kui-brkcard__toggle kui-focusable"
          aria-expanded={expanded() ? "true" : "false"}
          aria-controls={panelId}
          aria-label={expanded() ? `Hide ${brokerName(broker())} details` : `Show ${brokerName(broker())} details`}
          onClick={toggle}
        >
          <Icon name={expanded() ? "chevron-up" : "chevron-down"} />
        </button>
      </div>

      <Show when={expanded()}>
        <div class="kui-brkcard__body" id={panelId}>
          <div class="kui-brkcard__tags">
            {/* The controller tag is primary and the follower tag is neutral, so the one broker that
                matters in a leadership question is findable without reading. Both carry their word:
                colour is never the only signal. */}
            <Tag tone={broker().isController ? "info" : "neutral"}>
              {broker().isController ? "active controller" : "follower"}
            </Tag>
            <Show when={props.version}>{(version) => <Tag tone="neutral">{version()}</Tag>}</Show>
            <Show when={props.uptime}>{(uptime) => <Tag tone="neutral">{uptime()}</Tag>}</Show>
          </div>

          <h3 class="kui-brkcard__section">CONFIGURATION</h3>

          <Show
            when={props.configsError === undefined}
            fallback={
              <p class="kui-brkcard__config-state">
                {props.configsError} — the settings could not be read, which is not the same as this
                broker having none.
              </p>
            }
          >
            <Show
              when={props.configs !== undefined}
              fallback={<p class="kui-brkcard__config-state">Fetching this broker's settings…</p>}
            >
              <Show
                when={(props.configs ?? []).length > 0}
                fallback={<p class="kui-brkcard__config-state">This broker reported no settings.</p>}
              >
                <ConfigChips label={`${brokerName(broker())} configuration`}>
                  <For each={props.configs}>
                    {(config) => (
                      <ConfigChip
                        name={config.name}
                        value={config.value}
                        overridden={config.overridden}
                        description={config.description}
                      />
                    )}
                  </For>
                </ConfigChips>
              </Show>
            </Show>
          </Show>
        </div>
      </Show>
    </section>
  );
}
