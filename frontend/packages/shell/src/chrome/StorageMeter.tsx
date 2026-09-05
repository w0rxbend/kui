/**
 * The card at the drawer's foot: how full the cluster's disks are, and which broker is worst.
 *
 * ## Why the segments are per broker and not one averaged bar
 *
 * This is the whole reason the component exists rather than a `ProgressBar` with a caption.
 *
 * A cluster at 67% overall, with two brokers at 58% and one at 83%, is a cluster with a problem —
 * the hot broker is what fills up, and when it does the partitions it leads stop accepting writes
 * whatever the other two are doing. A single averaged bar at 67% is a reassuring picture of that
 * situation, and reassuring pictures of bad situations are the specific failure this product tries
 * hardest not to produce.
 *
 * So there is one segment per broker, each taking *its own* threshold colour, and the caption names
 * the hottest one. The overall percentage is still shown, because it is the number people quote,
 * but it is never the only thing on screen.
 *
 * ## Unknown is a track, not a zero
 *
 * A cluster whose storage could not be read draws a single neutral track, `—` for the percentage,
 * and says so in the caption. It must not draw an empty bar, because an empty bar reads as 0% —
 * "your disks are empty" — which is both wrong and the most reassuring possible misreading.
 * `SegmentBar` renders exactly this when given no segments, which is why it is used here rather
 * than a bar drawn locally.
 *
 * ## It replaced the cluster status card
 *
 * The drawer's foot used to hold `ClusterStatusCard`, which said which cluster was selected and
 * whether it was healthy. That job moved: the environment rail now answers "which cluster" at a
 * glance and the drawer's head answers it in words, so the foot was saying a third time what two
 * things above it already said. The design put the one number that had nowhere else to live there
 * instead.
 */
import { Show } from "solid-js";
import { Icon, SegmentBar, formatBytes, type SegmentBarSegment } from "@kui/kernel";

/** One broker's disk, as the meter needs it. */
export type BrokerStorage = {
  readonly id: string;
  readonly usedBytes: number;
  readonly totalBytes: number;
};

export type StorageMeterProps = {
  /**
   * Empty means "not known", and draws the neutral track. It does not mean "no brokers": a cluster
   * with no brokers is a cluster that is not answering, and its storage is unknown too.
   */
  readonly brokers: readonly BrokerStorage[];
  /** Warn above this fraction, and call the broker hot. */
  readonly warnAt?: number | undefined;
  /** Danger above this fraction. */
  readonly dangerAt?: number | undefined;
  /**
   * The landmark's accessible name. Defaults to "Cluster storage", which is right when there is one
   * on the page — and in the frame there is exactly one, because there is one drawer.
   *
   * Overridable because two of these in one document would be two landmarks with the same name, and
   * a screen-reader user listing the landmarks would get two indistinguishable entries. Caught by
   * `scripts/a11y-stories.mjs` on the story that draws two side by side.
   */
  readonly label?: string | undefined;
  readonly testId?: string | undefined;
};

const DEFAULT_WARN = 0.75;
const DEFAULT_DANGER = 0.9;

/**
 * The state of one broker's disk.
 *
 * Exported because it is the arithmetic worth testing directly, and because a broker whose
 * `totalBytes` is zero — which is what an unconfigured log directory reports — must come back
 * `idle` rather than dividing by zero and painting the whole bar red.
 */
export function brokerState(
  broker: BrokerStorage,
  warnAt = DEFAULT_WARN,
  dangerAt = DEFAULT_DANGER,
): SegmentBarSegment["state"] {
  if (!Number.isFinite(broker.totalBytes) || broker.totalBytes <= 0) return "idle";
  const fraction = broker.usedBytes / broker.totalBytes;
  if (fraction >= dangerAt) return "failed";
  if (fraction >= warnAt) return "warning";
  return "ok";
}

export function StorageMeter(props: StorageMeterProps) {
  const known = () => props.brokers.filter((broker) => Number.isFinite(broker.totalBytes) && broker.totalBytes > 0);

  const used = () => known().reduce((sum, broker) => sum + broker.usedBytes, 0);
  const total = () => known().reduce((sum, broker) => sum + broker.totalBytes, 0);

  const percent = (): number | undefined => {
    const capacity = total();
    return capacity > 0 ? Math.round((used() / capacity) * 100) : undefined;
  };

  /** The fullest broker, which is the one the caption names. */
  const hottest = () =>
    known().reduce<BrokerStorage | undefined>(
      (worst, broker) =>
        worst === undefined || broker.usedBytes / broker.totalBytes > worst.usedBytes / worst.totalBytes
          ? broker
          : worst,
      undefined,
    );

  const segments = (): readonly SegmentBarSegment[] =>
    props.brokers.map((broker) => {
      const fraction =
        Number.isFinite(broker.totalBytes) && broker.totalBytes > 0
          ? `${Math.round((broker.usedBytes / broker.totalBytes) * 100)}%`
          : "capacity not known";
      return { state: brokerState(broker, props.warnAt, props.dangerAt), title: `${broker.id} · ${fraction}` };
    });

  const caption = (): string => {
    if (known().length === 0) return "Disk usage could not be read for this cluster.";
    const hot = hottest();
    const base = `${formatBytes(used())} of ${formatBytes(total())}`;
    // The hottest broker is only worth naming when it is actually hot. On a balanced cluster the
    // name is noise, and noise trains the reader to stop looking at the line that will one day
    // matter.
    return hot !== undefined && brokerState(hot, props.warnAt, props.dangerAt) !== "ok"
      ? `${base} · ${hot.id} hot`
      : base;
  };

  return (
    <section class="kui-storage" aria-label={props.label ?? "Cluster storage"} data-testid={props.testId ?? "storage-meter"}>
      {/* A `div`, not a `header` — see `Notifications.tsx`: there is one banner in this product and
          it is the top bar. */}
      <div class="kui-storage__head">
        <Icon name="disk" size="14px" />
        <h2 class="kui-storage__title">STORAGE</h2>
        <Show
          when={percent()}
          fallback={
            <span class="kui-storage__percent kui-storage__percent--unknown" title="Disk usage could not be read">
              —
            </span>
          }
        >
          {(value) => <span class="kui-storage__percent">{value()}%</span>}
        </Show>
      </div>

      {/* `SegmentBar` is `aria-hidden`; the caption below is what is actually announced, which is why
          the caption is a full sentence rather than a fragment. */}
      <SegmentBar height={8} segments={segments()} />

      <p class="kui-storage__caption">{caption()}</p>
    </section>
  );
}
