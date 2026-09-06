import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { StatCard } from "./StatCard.jsx";
import { RingGauge } from "./charts/RingGauge.jsx";
import { SegmentBar } from "./charts/SegmentBar.jsx";
import { Sparkline } from "./charts/Sparkline.jsx";

/**
 * The four cards across the top of the dashboard.
 *
 * The stories that matter here are the last five: a zero, an unknown, a pending value and the two
 * extremes. `0` and `—` mean opposite things, and the whole point of this component is that they
 * cannot be confused — which is only checkable by looking at them next to each other.
 */
const meta: Meta<typeof StatCard> = {
  title: "Surfaces/StatCard",
  component: StatCard,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof StatCard>;

const Grid = (props: { readonly children: unknown }) => (
  <div
    style={{
      display: "grid",
      "grid-template-columns": "repeat(auto-fit, minmax(240px, 1fr))",
      gap: "24px",
    }}
  >
    {props.children as never}
  </div>
);

/** The dashboard's four cards, exactly as the screenshots draw them. */
export const TheDashboardRow: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <Grid>
        <StatCard
          label="BROKERS ONLINE"
          icon="brokers"
          tone="success"
          figure={{ kind: "value", text: "3", unit: "/3" }}
          pill={{ text: "all in sync", tone: "success", icon: "check" }}
        />
        <StatCard
          label="TOPICS"
          icon="topics"
          tone="primary"
          figure={{ kind: "value", text: "128" }}
          pill={{ text: "1,536 partitions", tone: "neutral" }}
        />
        <StatCard
          label="PRODUCTION"
          icon="arrow-up-right"
          tone="accent"
          figure={{ kind: "value", text: "86.4", unit: "MB/s" }}
          pill={{ text: "12% vs last hour", tone: "accent", icon: "arrow-up-right" }}
        />
        <StatCard
          label="CONSUMER LAG"
          icon="lag"
          tone="warning"
          figure={{ kind: "value", text: "4,212" }}
          pill={{ text: "fashionably late", tone: "warning" }}
        />
      </Grid>
    </div>
  ),
};

/**
 * The three renderings of a figure, side by side. This is the comparison the component exists for.
 *
 * `0` is good news and is a digit. `—` is "we could not read this" and is never a zero. The
 * skeleton is "not yet", and is a third picture again — a pending value must not look like an
 * absent one.
 */
export const ZeroPendingAndUnknown: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="CONSUMER LAG"
        icon="lag"
        tone="warning"
        figure={{ kind: "value", text: "0" }}
        pill={{ text: "all caught up", tone: "success", icon: "check" }}
      />
      <StatCard label="CONSUMER LAG" icon="lag" tone="warning" figure={{ kind: "pending" }} />
      <StatCard
        label="CONSUMER LAG"
        icon="lag"
        tone="warning"
        figure={{ kind: "unknown" }}
        pill={{ text: "metrics unavailable", tone: "neutral" }}
      />
    </Grid>
  ),
};

/**
 * The failed card's pill is **neutral**, not red. The metrics service being unreachable is not the
 * cluster being unhealthy, and a red pill here teaches the operator to distrust red — after which
 * the red that matters is not read either.
 */
export const FailedIsNotUnhealthy: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="BROKERS ONLINE"
        icon="brokers"
        tone="success"
        figure={{ kind: "value", text: "2", unit: "/3" }}
        pill={{ text: "broker 3 offline", tone: "danger", icon: "warning" }}
      />
      <StatCard
        label="BROKERS ONLINE"
        icon="brokers"
        tone="success"
        figure={{ kind: "unknown" }}
        pill={{ text: "cluster not answering", tone: "neutral" }}
      />
    </Grid>
  ),
};

/** Every tile tone, so the set can be judged as a set rather than one at a time. */
export const EveryTone: Story = {
  render: () => (
    <Grid>
      <StatCard label="PRIMARY" icon="topics" tone="primary" figure={{ kind: "value", text: "128" }} />
      <StatCard label="ACCENT" icon="arrow-up-right" tone="accent" figure={{ kind: "value", text: "86.4", unit: "MB/s" }} />
      <StatCard label="SUCCESS" icon="brokers" tone="success" figure={{ kind: "value", text: "3", unit: "/3" }} />
      <StatCard label="WARNING" icon="lag" tone="warning" figure={{ kind: "value", text: "4,212" }} />
      <StatCard label="DANGER" icon="warning" tone="danger" figure={{ kind: "value", text: "2" }} />
      <StatCard label="NEUTRAL" icon="info" tone="neutral" figure={{ kind: "value", text: "0" }} />
    </Grid>
  ),
};

/** A whole card as a link: hover it, and tab to it, and check the focus ring is visible. */
export const AsALink: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="BROKERS ONLINE"
        icon="brokers"
        tone="success"
        figure={{ kind: "value", text: "3", unit: "/3" }}
        pill={{ text: "all in sync", tone: "success", icon: "check" }}
        href="#/brokers"
      />
    </Grid>
  ),
};

/**
 * The extremes: the largest number the wire can carry, the longest label anyone has written, and
 * a pill whose text will not fit. Nothing may overlap, and the figure must not push the card
 * wider than its grid column.
 */
export const TheExtremes: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="PARTITIONS UNDER MINIMUM IN-SYNC REPLICAS ACROSS EVERY CONFIGURED CLUSTER"
        icon="warning"
        tone="danger"
        figure={{ kind: "value", text: "18,446,744,073,709,551,615" }}
        pill={{ text: "this pill's text is far longer than the card it sits in", tone: "danger", icon: "warning" }}
      />
      <StatCard
        label="X"
        icon="dot"
        tone="neutral"
        figure={{ kind: "value", text: "1" }}
        pill={{ text: "ok", tone: "success" }}
      />
    </Grid>
  ),
};

/** The smallest window. Four cards become one column; nothing scrolls sideways. */
export const NarrowWindow: Story = {
  render: () => (
    <div style={{ width: "260px" }}>
      <StatCard
        label="CONSUMER LAG"
        icon="lag"
        tone="warning"
        figure={{ kind: "value", text: "4,212" }}
        pill={{ text: "fashionably late", tone: "warning" }}
      />
    </div>
  ),
};

/* --------------------------------------------------------------------------------------------
 *
 * The visual slot (`SCREENS-V4.md` §3.2).
 *
 * The design names five micro-visuals across its eight cards, and every one of them is a kernel
 * chart component that already exists: `Sparkline` for the three trend marks, `RingGauge` for the
 * two rings, `SegmentBar` for the two block strips. These stories draw the real ones. They used to
 * draw two local stand-ins, which was right while the charts were landing in a sibling packet and
 * is now two ways to draw one thing — and the second way is the one nobody maintains.
 *
 * `SegmentBar` is `width: 100%` by design, because its usual caller is a table row that gives it
 * one; inside the card's slot there is no such row, so the story states a width. `RingGauge`'s
 * default 72px is the **Request handlers** tile's diameter and is too large for a card, so the two
 * ring cards state one too. Neither is a decision this component makes — the slot is layout, and
 * what goes in it brings its own size.
 */

/** The design's three trend marks (`SCREENS-V4.md` §3.2): rising, flat, jagged. */
const RISING = [12, 14, 15, 19, 22, 26, 31, 34, 38, 41, 47, 52];
const FLAT = [99.0, 99.1, 99.1, 99.0, 99.1, 99.2, 99.1, 99.1, 99.0, 99.1, 99.1, 99.1];
const JAGGED = [61, 88, 54, 92, 47, 79, 96, 58, 71, 90, 63, 86];

/** The card ring's diameter. The gauge's own default is the 72px handler tile, not a card. */
const CARD_RING = 46;
/** The block strip's width. `SegmentBar` fills its container, and the slot is not one. */
const STRIP = "60px";

/**
 * All eight of the design's cards, each with the micro-visual §3.2 gives it.
 *
 * The thing to check is that the pill did not go anywhere. The visual and the pill answer
 * different questions — the pill says whether the number is all right now, the visual says how it
 * got here — and six of the design's eight cards carry both.
 */
export const WithVisuals: Story = {
  parameters: { layout: "fullscreen" },
  render: () => (
    <div style={{ padding: "24px" }}>
      <Grid>
        <StatCard
          label="BROKERS ONLINE"
          icon="brokers"
          tone="success"
          figure={{ kind: "value", text: "3", unit: "/3" }}
          pill={{ text: "all in sync", tone: "success", icon: "check" }}
          visual={
            <span style={{ width: STRIP }}>
              <SegmentBar
                height={20}
                segments={[{ state: "ok" }, { state: "ok" }, { state: "ok" }]}
              />
            </span>
          }
        />
        <StatCard
          label="TOPICS"
          icon="topics"
          tone="primary"
          figure={{ kind: "value", text: "128" }}
          pill={{ text: "1,536 partitions", tone: "neutral" }}
          visual={<Sparkline points={RISING} />}
        />
        <StatCard
          label="PARTITIONS IN SYNC"
          icon="partitions"
          tone="success"
          figure={{ kind: "value", text: "99.1", unit: "%" }}
          pill={{ text: "steady", tone: "success" }}
          visual={<Sparkline points={FLAT} />}
        />
        <StatCard
          label="PRODUCE RATE"
          icon="arrow-up-right"
          tone="accent"
          figure={{ kind: "value", text: "86.4", unit: "MB/s" }}
          pill={{ text: "12% vs last hour", tone: "accent", icon: "arrow-up-right" }}
          visual={<Sparkline points={JAGGED} />}
        />
        <StatCard
          label="CONSUME RATE"
          icon="lag"
          tone="primary"
          figure={{ kind: "value", text: "71.2", unit: "MB/s" }}
          pill={{ text: "keeping up", tone: "success", icon: "check" }}
          visual={<Sparkline points={[...JAGGED].reverse()} />}
        />
        <StatCard
          label="CONSUMER LAG"
          icon="lag"
          tone="warning"
          figure={{ kind: "value", text: "4,212" }}
          pill={{ text: "fashionably late", tone: "warning" }}
          /* The ring is the same quantity the card printed, against the threshold the operator is
             paged on: 4,212 of a 5,000-message budget. `goodDirection="low"` is why it is amber
             rather than nearly-green — a gauge that only knows how to worry about small numbers
             would call a nearly-full lag budget good news. */
          visual={
            <RingGauge
              value={4212}
              max={5000}
              goodDirection="low"
              diameter={CARD_RING}
              strokeWidth={6}
            />
          }
        />
        <StatCard
          label="UNDER-REPLICATED"
          icon="warning"
          tone="danger"
          figure={{ kind: "value", text: "12", unit: "partitions" }}
          pill={{ text: "1 broker behind", tone: "warning", icon: "warning" }}
          /* Eight equal blocks, one amber. `SegmentBar` sizes nothing by a quantity, which is the
             point: "one of these eight is unhappy" is what the strip says, and a bar sized by a
             share would say something the operator cannot act on. */
          visual={
            <span style={{ width: STRIP }}>
              <SegmentBar
                height={20}
                segments={[
                  { state: "ok" },
                  { state: "ok" },
                  { state: "ok" },
                  { state: "warning" },
                  { state: "ok" },
                  { state: "ok" },
                  { state: "ok" },
                  { state: "ok" },
                ]}
              />
            </span>
          }
        />
        <StatCard
          label="CONTROLLER UPTIME"
          icon="brokers"
          tone="success"
          figure={{ kind: "value", text: "99.98", unit: "%" }}
          pill={{ text: "over the last 24h", tone: "neutral" }}
          visual={
            <RingGauge
              value={99.98}
              goodDirection="high"
              decimals={2}
              diameter={CARD_RING}
              strokeWidth={6}
            />
          }
        />
      </Grid>
    </div>
  ),
};

/**
 * The rule the slot exists to make expressible, drawn as a pair.
 *
 * The left card has a series. The right card is the *same card* on a cluster whose metrics service
 * has never answered — and it draws no visual at all. It must not draw a flat line at zero, which
 * is a measured claim about a quantity nobody measured: the same lie as printing `0` for an
 * unknown, told in a picture instead of a digit.
 */
export const NoSeriesDrawsNoVisual: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="PRODUCE RATE"
        icon="arrow-up-right"
        tone="accent"
        figure={{ kind: "value", text: "86.4", unit: "MB/s" }}
        pill={{ text: "12% vs last hour", tone: "accent", icon: "arrow-up-right" }}
        visual={<Sparkline points={JAGGED} />}
      />
      <StatCard
        label="PRODUCE RATE"
        icon="arrow-up-right"
        tone="accent"
        figure={{ kind: "unknown" }}
        pill={{ text: "metrics unavailable", tone: "neutral" }}
      />
    </Grid>
  ),
};

/**
 * The two ways a caller says "no series", side by side.
 *
 * The middle card is the shape the absence actually arrives in — `visual={hasSeries && <...>}`,
 * which is `false` rather than `undefined` when there is nothing to draw. `JSX.Element` admits it,
 * so a presence test would have reserved an empty box here and this row would show three cards
 * with three different amounts of space beside the figure. All three of these are one card.
 */
export const AnAbsentSeries: Story = {
  render: () => {
    const hasSeries = false;
    return (
      <Grid>
        <StatCard
          label="CONSUME RATE"
          icon="lag"
          tone="primary"
          figure={{ kind: "value", text: "71.2", unit: "MB/s" }}
          visual={hasSeries && <Sparkline points={JAGGED} />}
        />
        <StatCard
          label="CONSUME RATE"
          icon="lag"
          tone="primary"
          figure={{ kind: "value", text: "71.2", unit: "MB/s" }}
          visual={null}
        />
        <StatCard
          label="CONSUME RATE"
          icon="lag"
          tone="primary"
          figure={{ kind: "value", text: "71.2", unit: "MB/s" }}
        />
      </Grid>
    );
  },
};

/**
 * Pending, with a visual present.
 *
 * The figure keeps its skeleton and the card keeps `aria-busy`: the visual is not the value and
 * cannot stand in for one that has not arrived. A card that dropped `aria-busy` because there was
 * something to look at would tell a screen reader the number had landed.
 */
export const PendingWithAVisual: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="CONSUME RATE"
        icon="lag"
        tone="primary"
        figure={{ kind: "pending" }}
        visual={<Sparkline points={RISING} />}
      />
    </Grid>
  ),
};

/**
 * The extremes, with a visual. The figure takes the space and the visual takes what is left: a
 * sparkline that pushed the number into an ellipsis would have hidden the one thing on the card
 * that carries the meaning.
 */
export const VisualAgainstALongFigure: Story = {
  render: () => (
    <Grid>
      <StatCard
        label="RECORDS PRODUCED SINCE THE CLUSTER WAS CREATED"
        icon="topics"
        tone="primary"
        figure={{ kind: "value", text: "18,446,744,073,709,551,615" }}
        visual={<Sparkline points={JAGGED} />}
      />
      <div style={{ width: "220px" }}>
        <StatCard
          label="TOPICS"
          icon="topics"
          tone="primary"
          figure={{ kind: "value", text: "128" }}
          visual={<Sparkline points={RISING} />}
        />
      </div>
    </Grid>
  ),
};
