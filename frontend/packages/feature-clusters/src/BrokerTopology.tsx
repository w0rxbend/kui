/**
 * The cluster's brokers, laid out by rack: which nodes share a rack, and which one is the
 * controller.
 *
 * A picture and not a table: the tiles above this already say the aggregate figures and the card
 * list below already says everything a broker's settings hold. What neither says is *shape* — how
 * many racks this cluster spans and how the brokers split across them — and shape is the thing an
 * operator reaches for before touching replica placement or a rack-aware setting. So this draws
 * groups, not rows: one lane per rack, one node per broker, the controller marked and nothing
 * computed here that {@link Broker} does not already hold.
 *
 * A cluster with no rack declared on any broker draws one unlabelled lane rather than a lane headed
 * "No rack declared" repeated once per broker — the label would be a fact about every node stated
 * as though it were a grouping, which is a distinction with no group behind it.
 */
import { For, Show, createMemo } from "solid-js";
import type { JSX } from "@solidjs/web";
import { IconTile, Tag } from "@kui/kernel";
import { healthLabel, healthTone, type Broker } from "./model.js";

export interface BrokerTopologyProps {
  readonly brokers: readonly Broker[];
  readonly hrefFor: (brokerId: number) => string;
}

interface RackGroup {
  readonly rack: string | null;
  readonly brokers: readonly Broker[];
}

/** One lane per declared rack, in the order first seen; unracked brokers share one lane, last. */
function groupByRack(brokers: readonly Broker[]): readonly RackGroup[] {
  const order: string[] = [];
  const byRack = new Map<string, Broker[]>();
  const unracked: Broker[] = [];
  for (const broker of brokers) {
    if (broker.rack === null) {
      unracked.push(broker);
      continue;
    }
    if (!byRack.has(broker.rack)) {
      order.push(broker.rack);
      byRack.set(broker.rack, []);
    }
    byRack.get(broker.rack)?.push(broker);
  }
  const groups = order.map((rack): RackGroup => ({ rack, brokers: byRack.get(rack) ?? [] }));
  if (unracked.length > 0) groups.push({ rack: null, brokers: unracked });
  return groups;
}

export function BrokerTopology(props: BrokerTopologyProps): JSX.Element {
  const groups = createMemo(() => groupByRack(props.brokers));
  /* One decision for the whole diagram, the same way `BrokerList`'s `rackAware` decides once for
     every card: a mix of labelled and unlabelled lanes would read as though the unlabelled one is
     itself a rack called "unlabelled" rather than the absence of the fact. */
  const rackAware = createMemo(() => groups().some((group) => group.rack !== null));

  return (
    <div class="kui-brk-topology" data-testid="brokers-topology" role="group" aria-label="Broker topology, by rack">
      <For each={groups()}>
        {(group) => (
          <div class="kui-brk-topology__lane">
            <Show when={rackAware()}>
              <p class="kui-brk-topology__rack">{group.rack ?? "No rack declared"}</p>
            </Show>
            <div class="kui-brk-topology__nodes">
              <For each={group.brokers}>
                {(broker) => (
                  <a
                    class="kui-brk-topology__node kui-focusable"
                    href={props.hrefFor(broker.id)}
                    data-testid={`topology-node-${broker.id}`}
                  >
                    <IconTile
                      icon="brokers"
                      tone={broker.isController ? "primary" : healthTone(broker.health)}
                    />
                    <span class="kui-brk-topology__id">broker {broker.id}</span>
                    <span
                      class="kui-brk-topology__health"
                      data-tone={healthTone(broker.health)}
                    >
                      {healthLabel(broker.health)}
                    </span>
                    <Show when={broker.isController}>
                      <Tag tone="info">controller</Tag>
                    </Show>
                  </a>
                )}
              </For>
            </div>
          </div>
        )}
      </For>
    </div>
  );
}
