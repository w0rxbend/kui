/**
 * Rendering, interaction and accessibility for the chart family.
 *
 * Every case below is attached either to a statement in `.agent/design/SPEC.md` or to a defect this
 * project has already paid for, and the ones that matter most are the states nobody looks at: a
 * maximum of zero, a value that is unknown rather than absent, a gap in a series, an empty range.
 *
 * Nothing here asserts a colour, a size or a position. jsdom has no layout engine, so a test that
 * did would be asserting numbers jsdom invented; those are judged by looking at the stories against
 * the design screenshots. What *is* asserted is the shape of the drawing — how many marks, how wide
 * a fill was asked to be, which element carries which role — because that is where the arithmetic
 * defects live.
 */

import { createSignal, flush } from "solid-js";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { describeViolations, findViolations, mount } from "../testing.js";
import { BarChart } from "./BarChart.jsx";
import { Donut } from "./Donut.jsx";
import { LineChart } from "./LineChart.jsx";
import { MagnitudeBarList } from "./MagnitudeBarList.jsx";
import { Sparkline } from "./Sparkline.jsx";
import { RingGauge } from "./RingGauge.jsx";
import { Histogram } from "./Histogram.jsx";
import { StackedBar } from "./StackedBar.jsx";
import { ProgressBar } from "./ProgressBar.jsx";
import { RangeSelector } from "./RangeSelector.jsx";
import { ABSENT, formatCount, formatPercent, fraction, levelFor } from "./format.js";
import { defaultTicks, isPlotEmpty, seriesMax, topRoundedRect } from "./plot.js";

const LONG_LABEL =
  "orders.payments.reconciliation.eu-central-1.replay-2026-09-05T11:02:44Z.attempt-3.shadow-consumer";

/** Fails with the axe report rather than with "expected 1 to be 0". */
async function expectNoViolations(container: HTMLElement): Promise<void> {
  const violations = await findViolations(container);
  expect(describeViolations(violations)).toBe("");
}

/* --- The arithmetic ---------------------------------------------------------------------------
 *
 * These four functions are where every "a zero drew as full" defect either happens or does not, so
 * they are tested directly as well as through the components that use them.
 */

describe("fraction", () => {
  it("returns zero for a maximum of zero rather than NaN or Infinity", () => {
    // The whole defect in one line: `40 / 0` is Infinity, and a browser handed `width: Infinity%`
    // clamps it to a full bar instead of throwing.
    expect(fraction(40, 0)).toBe(0);
    expect(fraction(0, 0)).toBe(0);
    expect(fraction(1, -5)).toBe(0);
  });

  it("returns zero when either side is unknown", () => {
    expect(fraction(undefined, 100)).toBe(0);
    expect(fraction(40, undefined)).toBe(0);
    expect(fraction(Number.NaN, 100)).toBe(0);
  });

  it("clamps to the unit interval", () => {
    expect(fraction(150, 100)).toBe(1);
    expect(fraction(-10, 100)).toBe(0);
    expect(fraction(25, 100)).toBe(0.25);
  });
});

describe("formatting", () => {
  it("prints an em dash for an unknown value and a zero for a zero", () => {
    expect(formatCount(0)).toBe("0");
    expect(formatCount(undefined)).toBe(ABSENT);
    expect(formatPercent(undefined)).toBe(ABSENT);
    expect(formatPercent(0)).toBe("0%");
  });

  it("groups thousands, as SPEC §6 rule 6 requires", () => {
    expect(formatCount(4212)).toMatch(/4.212/u);
  });
});

describe("levelFor", () => {
  it("uses the product's single pair of thresholds", () => {
    expect(levelFor(74)).toBe("normal");
    expect(levelFor(75)).toBe("warning");
    expect(levelFor(89)).toBe("warning");
    expect(levelFor(90)).toBe("critical");
  });
});

describe("plot helpers", () => {
  it("ignores gaps when finding the maximum, and treats an all-gap series as empty", () => {
    expect(seriesMax([{ label: "a", tone: "series-1", points: [1, null, 9] }])).toBe(9);
    expect(isPlotEmpty([{ label: "a", tone: "series-1", points: [null, null] }])).toBe(true);
    expect(isPlotEmpty([{ label: "a", tone: "series-1", points: [0] }])).toBe(false);
  });

  it("clamps a bar's corner radius so a one-pixel bar cannot fold its own path", () => {
    const path = topRoundedRect(0, 0, 2, 1, 6);
    expect(path).not.toContain("NaN");
    expect(path.startsWith("M 0 1")).toBe(true);
  });

  it("puts ticks at the first, middle and last bucket by default", () => {
    expect(defaultTicks(24)).toEqual([0, 11, 23]);
    expect(defaultTicks(1)).toEqual([0]);
    expect(defaultTicks(0)).toEqual([]);
  });
});

/* --- RangeSelector --------------------------------------------------------------------------- */

describe("RangeSelector", () => {
  const OPTIONS = [
    { value: "24h", label: "24h" },
    { value: "7d", label: "7d" },
    { value: "30d", label: "30d" },
  ];

  it("is a radio group of real radios with the current range checked", () => {
    const { container, dispose } = mount(() => (
      <RangeSelector label="Throughput range" options={OPTIONS} value="7d" onChange={() => {}} />
    ));
    const group = container.querySelector("[role='radiogroup']")!;
    expect(group.getAttribute("aria-label")).toBe("Throughput range");
    const radios = container.querySelectorAll<HTMLInputElement>("input[type='radio']");
    expect(radios.length).toBe(3);
    expect([...radios].filter(r => r.checked).map(r => r.value)).toEqual(["7d"]);
    dispose();
  });

  it("gives each instance its own radio name, so two selectors are not one group", () => {
    const { container, dispose } = mount(() => (
      <>
        <RangeSelector label="A" options={OPTIONS} value="24h" onChange={() => {}} />
        <RangeSelector label="B" options={OPTIONS} value="7d" onChange={() => {}} />
      </>
    ));
    const names = new Set([...container.querySelectorAll<HTMLInputElement>("input")].map(r => r.name));
    expect(names.size).toBe(2);
    dispose();
  });

  it("reports the range the operator picked", async () => {
    const chosen: string[] = [];
    const { container, dispose } = mount(() => (
      <RangeSelector label="Throughput range" options={OPTIONS} value="24h" onChange={v => chosen.push(v)} />
    ));
    await userEvent.click(container.querySelectorAll("label")[2]!);
    flush();
    expect(chosen).toEqual(["30d"]);
    dispose();
  });

  it("moves the selection with the arrow keys, because it is a real radio group", async () => {
    const [value, setValue] = createSignal("24h");
    const { container, dispose } = mount(() => (
      <RangeSelector label="Throughput range" options={OPTIONS} value={value()} onChange={setValue} />
    ));
    const first = container.querySelector<HTMLInputElement>("input")!;
    first.focus();
    await userEvent.keyboard("{ArrowRight}");
    flush();
    expect(value()).toBe("7d");
    dispose();
  });

  it("keeps a range the backend cannot serve, disabled and with its reason readable", async () => {
    const { container, dispose } = mount(() => (
      <RangeSelector
        label="Throughput range"
        value="24h"
        onChange={() => {}}
        options={[
          { value: "24h", label: "24h" },
          { value: "30d", label: "30d", disabled: true, disabledReason: "Metrics are retained for 7 days." },
        ]}
      />
    ));
    // Present, not omitted: removing it would make the retention limit invisible.
    expect(container.querySelectorAll("input").length).toBe(2);
    const disabled = container.querySelectorAll<HTMLInputElement>("input")[1]!;
    expect(disabled.disabled).toBe(true);
    // The reason reaches a keyboard user, who never hovers the tooltip.
    const described = container.querySelector(`#${CSS.escape(disabled.getAttribute("aria-describedby")!)}`);
    expect(described!.textContent).toContain("retained for 7 days");
    await expectNoViolations(container);
    dispose();
  });

  it("has no accessibility violations", async () => {
    const { container, dispose } = mount(() => (
      <RangeSelector label="Throughput range" options={OPTIONS} value="24h" onChange={() => {}} />
    ));
    await expectNoViolations(container);
    dispose();
  });
});

/* --- ProgressBar ----------------------------------------------------------------------------- */

describe("ProgressBar", () => {
  it("exposes the value through the progressbar role", () => {
    const { container, dispose } = mount(() => (
      <ProgressBar label="broker-1.kyiv disk usage" caption="disk" value={61} />
    ));
    const bar = container.querySelector("[role='progressbar']")!;
    expect(bar.getAttribute("aria-label")).toBe("broker-1.kyiv disk usage");
    expect(bar.getAttribute("aria-valuenow")).toBe("61");
    expect(bar.getAttribute("aria-valuemax")).toBe("100");
    dispose();
  });

  it("draws an unknown value differently from a zero, in the picture and in the text", () => {
    const zero = mount(() => <ProgressBar label="zero" value={0} />);
    const unknown = mount(() => <ProgressBar label="unknown" value={undefined} />);

    // A zero has a fill of zero width; an unknown has no fill element at all.
    expect(zero.container.querySelector(".kui-progress__fill")).not.toBeNull();
    expect(unknown.container.querySelector(".kui-progress__fill")).toBeNull();

    expect(zero.container.querySelector(".kui-progress__value")!.textContent).toBe("0%");
    expect(unknown.container.querySelector(".kui-progress__value")!.textContent).toBe(ABSENT);

    // ARIA spells "we do not know" as the absence of aria-valuenow, not as zero.
    expect(unknown.container.querySelector("[role='progressbar']")!.hasAttribute("aria-valuenow")).toBe(false);
    zero.dispose();
    unknown.dispose();
  });

  it("turns amber past 75% and red past 90%, in the figure as well as the bar", () => {
    for (const [value, level] of [
      [61, "normal"],
      [83, "warning"],
      [96, "critical"],
    ] as const) {
      const { container, dispose } = mount(() => <ProgressBar label="disk" value={value} />);
      expect(container.querySelector(`.kui-progress__track--${level}`)).not.toBeNull();
      // Colour is never the only signal: the figure carries the level too.
      expect(container.querySelector(`.kui-progress__value--${level}`)).not.toBeNull();
      dispose();
    }
  });

  it("cannot be made to draw a full bar by a maximum of zero", () => {
    const { container, dispose } = mount(() => <ProgressBar label="queue" value={40} max={0} />);
    const fill = container.querySelector<HTMLElement>(".kui-progress__fill")!;
    expect(fill.style.width).toBe("0%");
    dispose();
  });

  it("clamps a value beyond its maximum rather than overflowing the track", () => {
    const { container, dispose } = mount(() => <ProgressBar label="partitions" value={1800} max={1536} />);
    expect(container.querySelector<HTMLElement>(".kui-progress__fill")!.style.width).toBe("100%");
    dispose();
  });

  it("has no accessibility violations, known or unknown", async () => {
    const { container, dispose } = mount(() => (
      <>
        <ProgressBar label="broker-1 disk usage" caption="disk" value={83} />
        <ProgressBar label="broker-2 disk usage" caption="disk" value={undefined} />
      </>
    ));
    await expectNoViolations(container);
    dispose();
  });
});

/* --- MagnitudeBarList ------------------------------------------------------------------------- */

describe("MagnitudeBarList", () => {
  it("draws every bar at zero and says so when there is nothing to compare", () => {
    const { container, dispose } = mount(() => (
      <MagnitudeBarList
        entries={[
          { label: "a", value: 0 },
          { label: "b", value: 0 },
        ]}
      />
    ));
    for (const fill of container.querySelectorAll<HTMLElement>(".kui-magnitude-list__fill")) {
      // Not `max(3px, NaN%)`, and not a missing declaration that leaves the track's own width.
      expect(fill.style.width).toBe("0px");
    }
    expect(container.textContent).toContain("Nothing is behind.");
    dispose();
  });

  it("keeps a small value visible instead of drawing it as nothing", () => {
    const { container, dispose } = mount(() => (
      <MagnitudeBarList
        entries={[
          { label: "big", value: 4_000_000 },
          { label: "small", value: 1 },
        ]}
      />
    ));
    const fills = container.querySelectorAll<HTMLElement>(".kui-magnitude-list__fill");
    expect(fills[1]!.style.width).toContain("3px");
    dispose();
  });

  it("draws no bar at all for an unknown value, and prints the em dash", () => {
    const { container, dispose } = mount(() => (
      <MagnitudeBarList
        entries={[
          { label: "known", value: 100 },
          { label: "unknown", value: undefined },
        ]}
      />
    ));
    const entries = container.querySelectorAll(".kui-magnitude-list__entry");
    expect(entries[1]!.querySelector(".kui-magnitude-list__fill")).toBeNull();
    // The *track* too, which is what "no bar at all" means and what this assertion used to miss.
    // The fill alone being absent still left a grey track on the row, and a grey track is exactly
    // what a zero draws — so the two states this test exists to separate looked identical on
    // screen while the test passed.
    expect(entries[1]!.querySelector(".kui-magnitude-list__track")).toBeNull();
    expect(entries[1]!.querySelector(".kui-magnitude-list__value")!.textContent).toBe(ABSENT);
    // And the known one still draws, so the two are visibly different pictures.
    expect(entries[0]!.querySelector(".kui-magnitude-list__fill")).not.toBeNull();
    dispose();
  });

  it("draws a zero differently from an unknown, which is the whole point of the pair", () => {
    const { container, dispose } = mount(() => (
      <MagnitudeBarList
        entries={[
          { label: "busy", value: 100 },
          { label: "caught up", value: 0 },
          { label: "could not ask", value: undefined },
        ]}
      />
    ));
    const [, zero, unknown] = [...container.querySelectorAll(".kui-magnitude-list__entry")];

    // Zero is a measurement: it keeps its track, and prints a digit.
    expect(zero!.querySelector(".kui-magnitude-list__track")).not.toBeNull();
    expect(zero!.querySelector(".kui-magnitude-list__value")!.textContent).toBe("0");

    // Unknown is an absence: no track, and an em dash.
    expect(unknown!.querySelector(".kui-magnitude-list__track")).toBeNull();
    expect(unknown!.querySelector(".kui-magnitude-list__value")!.textContent).toBe(ABSENT);
    dispose();
  });

  it("hides the bars from a screen reader, because the figure beside them says the same thing", () => {
    const { container, dispose } = mount(() => <MagnitudeBarList entries={[{ label: "a", value: 10 }]} />);
    expect(container.querySelector(".kui-magnitude-list__track")!.getAttribute("aria-hidden")).toBe("true");
    dispose();
  });

  it("says its emptiness in words rather than by being empty", () => {
    const { container, dispose } = mount(() => (
      <MagnitudeBarList entries={[]} emptyMessage="No consumer groups are behind." />
    ));
    expect(container.textContent).toContain("No consumer groups are behind.");
    dispose();
  });

  it("has no accessibility violations, including with a name long enough to truncate", async () => {
    const { container, dispose } = mount(() => (
      <MagnitudeBarList
        entries={[
          { label: LONG_LABEL, value: 9_007_199_254_740_991, tone: "danger" },
          { label: "b", value: undefined },
        ]}
      />
    ));
    await expectNoViolations(container);
    dispose();
  });
});

/* --- Donut ------------------------------------------------------------------------------------ */

describe("Donut", () => {
  const HEALTHY = [
    { label: "In sync", value: 1522, tone: "success" },
    { label: "Under-replicated", value: 12, tone: "warning" },
    { label: "Offline", value: 2, tone: "danger" },
  ] as const;

  it("draws one arc per non-zero segment, over a track", () => {
    const { container, dispose } = mount(() => <Donut segments={[...HEALTHY]} centreCaption="in sync" />);
    // One track plus three arcs.
    expect(container.querySelectorAll("circle").length).toBe(4);
    expect(container.querySelector(".kui-donut__figure")!.textContent).toBe("99.1%");
    dispose();
  });

  it("never draws a full healthy ring for missing data", () => {
    const { container, dispose } = mount(() => (
      <Donut
        centreCaption="in sync"
        segments={[
          { label: "In sync", value: 0, tone: "success" },
          { label: "Offline", value: 0, tone: "danger" },
        ]}
      />
    ));
    // The track, and nothing else.
    expect(container.querySelectorAll("circle").length).toBe(1);
    expect(container.querySelector(".kui-donut__figure")!.textContent).toBe(ABSENT);
    expect(container.querySelector(".kui-donut__caption")!.textContent).toBe("no partitions");
    dispose();
  });

  it("colours the centre figure by how much is healthy, not by how green the ring looks", () => {
    const degraded = mount(() => (
      <Donut
        segments={[
          { label: "In sync", value: 980, tone: "success" },
          { label: "Offline", value: 20, tone: "danger" },
        ]}
      />
    ));
    expect(degraded.container.querySelector(".kui-donut__figure--warning")).not.toBeNull();
    degraded.dispose();

    const critical = mount(() => (
      <Donut
        segments={[
          { label: "In sync", value: 900, tone: "success" },
          { label: "Offline", value: 100, tone: "danger" },
        ]}
      />
    ));
    expect(critical.container.querySelector(".kui-donut__figure--critical")).not.toBeNull();
    critical.dispose();
  });

  it("gives a segment below the minimum arc a visible one anyway", () => {
    const { container, dispose } = mount(() => (
      <Donut
        segments={[
          { label: "In sync", value: 99_999, tone: "success" },
          { label: "Offline", value: 1, tone: "danger" },
        ]}
      />
    ));
    const arcs = [...container.querySelectorAll("circle")].slice(1);
    const dash = arcs[1]!.getAttribute("stroke-dasharray")!;
    expect(Number.parseFloat(dash.split(" ")[0]!)).toBeGreaterThanOrEqual(1.5);
    dispose();
  });

  it("hides the ring from a screen reader and lets the legend carry the numbers", async () => {
    const { container, dispose } = mount(() => <Donut segments={[...HEALTHY]} centreCaption="in sync" />);
    expect(container.querySelector("svg")!.getAttribute("aria-hidden")).toBe("true");
    const legend = container.querySelector(".kui-chart-legend")!;
    expect(legend.textContent).toContain("In sync");
    expect(legend.textContent).toMatch(/1.522/u);
    await expectNoViolations(container);
    dispose();
  });
});

/* --- BarChart --------------------------------------------------------------------------------- */

describe("BarChart", () => {
  const CATEGORIES = ["00:00", "06:00", "12:00", "18:00", "now"];
  const SERIES = [
    { label: "produce", tone: "series-1", points: [62, 58, 71, 66, 54] },
    { label: "consume", tone: "series-2", points: [55, 51, 66, 59, 47] },
  ] as const;

  it("draws one bar per series per bucket", () => {
    const { container, dispose } = mount(() => (
      <BarChart label="Throughput" categories={CATEGORIES} series={[...SERIES]} />
    ));
    expect(container.querySelectorAll(".kui-plot__bar").length).toBe(10);
    dispose();
  });

  it("draws nothing for a gap, and does not treat it as a zero", () => {
    const { container, dispose } = mount(() => (
      <BarChart
        label="Throughput"
        categories={CATEGORIES}
        series={[{ label: "produce", tone: "series-1", points: [62, null, 71, 66, 54] }]}
      />
    ));
    expect(container.querySelectorAll(".kui-plot__bar").length).toBe(4);
    dispose();
  });

  it("draws no bars at all when every value is zero", () => {
    const { container, dispose } = mount(() => (
      <BarChart
        label="Throughput"
        categories={CATEGORIES}
        series={[{ label: "produce", tone: "series-1", points: [0, 0, 0, 0, 0] }]}
      />
    ));
    // Not full-height columns, which is what an unguarded `value / max` produces.
    expect(container.querySelectorAll(".kui-plot__bar").length).toBe(0);
    dispose();
  });

  it("keeps the axis and says the range is empty, rather than rendering nothing", () => {
    const { container, dispose } = mount(() => (
      <BarChart
        label="Throughput"
        categories={CATEGORIES}
        series={[{ label: "produce", tone: "series-1", points: [null, null, null, null, null] }]}
        emptyMessage="No throughput in the last 24 hours."
      />
    ));
    expect(container.querySelector(".kui-plot__empty")!.textContent).toContain("No throughput");
    expect(container.querySelectorAll(".kui-plot__tick").length).toBeGreaterThan(0);
    dispose();
  });

  it("moves a highlighted bucket with the arrow keys and announces it", async () => {
    const { container, dispose } = mount(() => (
      <BarChart
        label="Throughput"
        categories={CATEGORIES}
        series={[...SERIES]}
        format={(v: number) => `${v} MB/s`}
      />
    ));
    const surface = container.querySelector<HTMLElement>(".kui-plot__surface")!;
    surface.focus();
    await userEvent.keyboard("{ArrowRight}{ArrowRight}");
    flush();

    expect(container.querySelectorAll(".kui-plot__group--active").length).toBe(1);
    const live = container.querySelector("[role='status']")!;
    expect(live.textContent).toContain("06:00");
    expect(live.textContent).toContain("produce 58 MB/s");

    // End goes to the last bucket; Escape gives the highlight up.
    await userEvent.keyboard("{End}");
    flush();
    expect(container.querySelector("[role='status']")!.textContent).toContain("now");
    await userEvent.keyboard("{Escape}");
    flush();
    expect(container.querySelectorAll(".kui-plot__group--active").length).toBe(0);
    dispose();
  });

  it("publishes the same numbers as a table, and points at it from the plot", async () => {
    const { container, dispose } = mount(() => (
      <BarChart
        label="Throughput over the last 24 hours"
        categories={CATEGORIES}
        series={[{ label: "produce", tone: "series-1", points: [62, null, 71, 66, 54] }]}
        format={(v: number) => `${v} MB/s`}
      />
    ));
    const surface = container.querySelector(".kui-plot__surface")!;
    const table = container.querySelector<HTMLTableElement>(`#${CSS.escape(surface.getAttribute("aria-describedby")!)}`)!;
    expect(table.tagName).toBe("TABLE");
    expect(table.querySelector("caption")!.textContent).toBe("Throughput over the last 24 hours");
    // The gap is an em dash in the table too, not a zero.
    expect(table.querySelectorAll("tbody td")[1]!.textContent).toBe(ABSENT);
    expect(table.querySelectorAll("tbody td")[0]!.textContent).toBe("62 MB/s");
    await expectNoViolations(container);
    dispose();
  });

  it("survives a single bucket", () => {
    const { container, dispose } = mount(() => (
      <BarChart label="Throughput" categories={["now"]} series={[{ label: "produce", tone: "series-1", points: [86] }]} />
    ));
    const path = container.querySelector(".kui-plot__bar")!.getAttribute("d")!;
    expect(path).not.toContain("NaN");
    dispose();
  });
});

/* --- LineChart -------------------------------------------------------------------------------- */

describe("LineChart", () => {
  const CATEGORIES = ["-60 min", "-45 min", "-30 min", "-15 min", "now"];

  it("draws one line for a series with no gaps", () => {
    const { container, dispose } = mount(() => (
      <LineChart
        label="p99 latency"
        categories={CATEGORIES}
        series={[{ label: "produce", tone: "series-1", points: [16, 15, 17, 16, 14] }]}
      />
    ));
    expect(container.querySelectorAll(".kui-plot__line").length).toBe(1);
    dispose();
  });

  it("breaks the line at a gap rather than interpolating across it", () => {
    const { container, dispose } = mount(() => (
      <LineChart
        label="p99 latency"
        categories={CATEGORIES}
        series={[{ label: "produce", tone: "series-1", points: [16, null, 17, 16, 14] }]}
      />
    ));
    // Two runs: one of a single point (drawn as a dot, no line) and one of three.
    expect(container.querySelectorAll(".kui-plot__line").length).toBe(1);
    const paths = [...container.querySelectorAll(".kui-plot__line")].map(p => p.getAttribute("d")!);
    expect(paths[0]!.split("L").length).toBe(3);
    dispose();
  });

  it("draws a lone surviving measurement as a dot instead of dropping it", () => {
    const { container, dispose } = mount(() => (
      <LineChart
        label="p99 latency"
        categories={CATEGORIES}
        series={[{ label: "produce", tone: "series-1", points: [null, null, 14, null, null] }]}
      />
    ));
    expect(container.querySelectorAll(".kui-plot__line").length).toBe(0);
    expect(container.querySelectorAll("circle").length).toBeGreaterThan(0);
    dispose();
  });

  it("draws a flat line for a series of zeros, because zero is a measurement", () => {
    const { container, dispose } = mount(() => (
      <LineChart
        label="p99 latency"
        categories={["-1 min", "now"]}
        series={[{ label: "produce", tone: "series-1", points: [0, 0] }]}
      />
    ));
    expect(container.querySelector(".kui-plot__empty")).toBeNull();
    expect(container.querySelectorAll(".kui-plot__line").length).toBe(1);
    dispose();
  });

  it("says the range is empty when every point is a gap", () => {
    const { container, dispose } = mount(() => (
      <LineChart
        label="p99 latency"
        categories={CATEGORIES}
        series={[{ label: "produce", tone: "series-1", points: [null, null, null, null, null] }]}
        emptyMessage="No latency samples in the last hour."
      />
    ));
    expect(container.querySelector(".kui-plot__empty")!.textContent).toContain("No latency samples");
    dispose();
  });

  it("is reachable by keyboard and describes itself with a table", async () => {
    const { container, dispose } = mount(() => (
      <LineChart
        label="p99 latency over the last hour"
        categories={CATEGORIES}
        series={[{ label: "produce", tone: "series-1", points: [16, 15, 17, 16, 14] }]}
        format={(v: number) => `${v}ms`}
      />
    ));
    const surface = container.querySelector<HTMLElement>(".kui-plot__surface")!;
    expect(surface.getAttribute("tabindex")).toBe("0");
    surface.focus();
    await userEvent.keyboard("{ArrowRight}");
    flush();
    expect(container.querySelector("[role='status']")!.textContent).toContain("produce 16ms");
    await expectNoViolations(container);
    dispose();
  });
});

/* --- Sparkline -------------------------------------------------------------------------------- */

describe("Sparkline", () => {
  it("draws one polyline for a series with no gaps", () => {
    const { container, dispose } = mount(() => <Sparkline points={[61, 64, 63, 68, 72]} />);
    expect(container.querySelectorAll("polyline").length).toBe(1);
    dispose();
  });

  it("breaks the mark at a gap rather than running a line across the outage", () => {
    const { container, dispose } = mount(() => <Sparkline points={[62, 58, null, 78, 74]} />);
    // Two polylines, not one: the same rule LineChart obeys, in a mark a twelfth its size.
    expect(container.querySelectorAll("polyline").length).toBe(2);
    dispose();
  });

  it("draws a lone surviving measurement as a dot, because a one-point line is nothing", () => {
    const { container, dispose } = mount(() => <Sparkline points={[null, null, 4.1, null]} />);
    expect(container.querySelectorAll("polyline").length).toBe(0);
    expect(container.querySelectorAll("circle").length).toBe(1);
    dispose();
  });

  it("draws nothing at all when there is nothing to trend", () => {
    // The design's rule for a card with no series: not a flat line at zero, which would assert a
    // measured zero. No SVG is emitted at all.
    const empty = mount(() => <Sparkline points={[]} />);
    expect(empty.container.querySelector("svg")).toBeNull();
    empty.dispose();

    const gaps = mount(() => <Sparkline points={[null, null, null]} />);
    expect(gaps.container.querySelector("svg")).toBeNull();
    gaps.dispose();
  });

  it("draws a flat series down the middle instead of dividing by a zero range", () => {
    const { container, dispose } = mount(() => <Sparkline points={[99.1, 99.1, 99.1]} />);
    const points = container.querySelector("polyline")!.getAttribute("points")!;
    const ys = points.split(" ").map(pair => Number.parseFloat(pair.split(",")[1]!));
    expect(ys.every(y => Number.isFinite(y))).toBe(true);
    expect(new Set(ys).size).toBe(1);
    dispose();
  });

  it("is hidden from a screen reader, because the card beside it prints the same figure", async () => {
    const { container, dispose } = mount(() => <Sparkline points={[61, 64, 63]} />);
    expect(container.querySelector("svg")!.getAttribute("aria-hidden")).toBe("true");
    // And it publishes no table: a second announcement of the card's own figure helps nobody.
    expect(container.querySelector("table")).toBeNull();
    await expectNoViolations(container);
    dispose();
  });
});

/* --- RingGauge -------------------------------------------------------------------------------- */

describe("RingGauge", () => {
  it("reads a high-is-good scalar as success", () => {
    const { container, dispose } = mount(() => (
      <RingGauge value={64} caption="io idle" goodDirection="high" />
    ));
    expect(container.querySelector(".kui-gauge")!.getAttribute("data-tone")).toBe("success");
    expect(container.querySelector(".kui-gauge__figure")!.textContent).toBe("64%");
    dispose();
  });

  it("reads the same number as a warning when low is the good end", () => {
    // 64% idle is good news and 38% purgatory is bad news in the same card. A component with
    // Donut's warnBelow/criticalBelow cannot say that, which is why goodDirection exists.
    const { container, dispose } = mount(() => (
      <RingGauge value={38} caption="purgatory" goodDirection="low" warnAbove={35} />
    ));
    expect(container.querySelector(".kui-gauge")!.getAttribute("data-tone")).toBe("warning");
    dispose();
  });

  it("alarms past the critical threshold in either direction", () => {
    const low = mount(() => <RingGauge value={74} goodDirection="low" warnAbove={35} criticalAbove={60} />);
    expect(low.container.querySelector(".kui-gauge")!.getAttribute("data-tone")).toBe("danger");
    low.dispose();

    const high = mount(() => <RingGauge value={6} goodDirection="high" />);
    expect(high.container.querySelector(".kui-gauge")!.getAttribute("data-tone")).toBe("danger");
    high.dispose();
  });

  it("draws the plain track and an em dash for an unmeasured value, never a full ring", () => {
    const { container, dispose } = mount(() => (
      <RingGauge value={undefined} caption="purgatory" goodDirection="low" />
    ));
    expect(container.querySelector(".kui-gauge__figure")!.textContent).toBe(ABSENT);
    // The arc is the only <path> in the component, so counting them is the honest test for
    // "did this gauge claim a measurement".
    expect(container.querySelectorAll("path").length).toBe(0);
    expect(container.querySelectorAll("circle").length).toBe(1);
    expect(container.querySelector(".kui-gauge")!.getAttribute("data-tone")).toBe("absent");
    dispose();
  });

  it("draws a measured zero differently from an absence, which is the whole point of the pair", () => {
    const { container, dispose } = mount(() => <RingGauge value={0} goodDirection="high" />);
    expect(container.querySelector(".kui-gauge__figure")!.textContent).toBe("0%");
    // No arc, because there is no share to draw — but the figure says a number, not a dash.
    expect(container.querySelectorAll("path").length).toBe(0);
    dispose();
  });

  it("scales a value into an explicit domain rather than assuming per cent", () => {
    const { container, dispose } = mount(() => (
      <RingGauge value={4212} max={10_000} goodDirection="low" valueText="4,212" />
    ));
    const arc = container.querySelector<SVGPathElement>("path")!;
    // `pathLength="100"` makes the dash units per cent, so the arc's length is literally 42.12%
    // of a 10,000-message domain. Compared as numbers, because binary floating point renders it
    // as 42.120000000000005 and that is not a defect in the gauge.
    const dash = arc.getAttribute("stroke-dasharray")!.split(" ").map(Number.parseFloat);
    expect(dash[0]).toBeCloseTo(42.12, 6);
    expect(dash[1]).toBeCloseTo(57.88, 6);
    expect(container.querySelector(".kui-gauge__figure")!.textContent).toBe("4,212");
    dispose();
  });

  it("cannot be made to draw a full ring by a domain of zero width", () => {
    const { container, dispose } = mount(() => (
      <RingGauge value={40} min={10} max={10} goodDirection="high" />
    ));
    expect(container.querySelectorAll("path").length).toBe(0);
    dispose();
  });

  it("hides the ring and lets the figure and caption carry the reading", async () => {
    const { container, dispose } = mount(() => (
      <RingGauge value={71} caption="network idle" goodDirection="high" />
    ));
    expect(container.querySelector("svg")!.getAttribute("aria-hidden")).toBe("true");
    expect(container.textContent).toContain("71%");
    expect(container.textContent).toContain("network idle");
    await expectNoViolations(container);
    dispose();
  });

  /**
   * The gauge names itself, and the name carries both facts.
   *
   * `role="img"` makes the subtree presentational, so the caption and the figure stop being read
   * as loose text — which is the point: three gauges in one panel produce six unlabelled fragments
   * in the reading order and no way to tell which figure belongs to which caption.
   */
  it("is one image whose name carries both the caption and the reading", () => {
    const { container, dispose } = mount(() => (
      <RingGauge value={64} caption="IDLE" goodDirection="high" />
    ));
    const root = container.querySelector(".kui-gauge")!;
    expect(root.getAttribute("role")).toBe("img");
    expect(root.getAttribute("aria-label")).toBe("IDLE: 64%");
    dispose();
  });

  /**
   * The unmeasured gauge says so in words.
   *
   * The centre still prints the em dash, because that is the drawing; the name may not, because a
   * screen reader announces `—` as "dash", or as "em dash", or as nothing, and every one of those
   * is a rendering of "not measured" that does not mean it.
   */
  it("says the value is not measured rather than putting an em dash in its name", () => {
    const { container, dispose } = mount(() => (
      <RingGauge value={undefined} caption="purgatory" goodDirection="low" />
    ));
    const root = container.querySelector(".kui-gauge")!;
    expect(root.getAttribute("aria-label")).toBe("purgatory: not measured");
    expect(root.getAttribute("aria-label")).not.toContain(ABSENT);
    // The drawing is unchanged: the dash is still what the centre shows.
    expect(container.querySelector(".kui-gauge__figure")!.textContent).toBe(ABSENT);
    dispose();
  });

  /** A caption is optional, and a nameless `role="img"` is an axe violation as well as a silence.
   * Without one the reading is the whole name — poorer than two facts, and not nothing. */
  it("still names itself when it has no caption", async () => {
    const { container, dispose } = mount(() => (
      <RingGauge value={4212} max={10_000} goodDirection="low" valueText="4,212" />
    ));
    expect(container.querySelector(".kui-gauge")!.getAttribute("aria-label")).toBe("4,212");
    await expectNoViolations(container);
    dispose();
  });
});

/* --- Histogram -------------------------------------------------------------------------------- */

describe("Histogram", () => {
  const BUCKETS = [
    { from: 256, to: 512, count: 1_240 },
    { from: 512, to: 1024, count: 3_180 },
    { from: 1024, to: 2048, count: 9_420 },
    { from: 2048, to: 4096, count: 880 },
    { from: 4096, count: 210 },
  ];

  it("gives the modal bucket the accent ink and leaves its neighbours muted", () => {
    const { container, dispose } = mount(() => (
      <Histogram label="Message size distribution" buckets={BUCKETS} />
    ));
    const fills = [...container.querySelectorAll(".kui-plot__bar")].map(b => b.getAttribute("fill"));
    expect(fills[2]).toBe("var(--kui-color-accent)");
    expect(fills[1]).toBe("var(--kui-color-text-muted)");
    expect(fills[3]).toBe("var(--kui-color-text-muted)");
    dispose();
  });

  it("lets an explicit per-bar tone win, which is what the oversize tail needs", () => {
    // The three inks in one series are the reason this is not BarChart, where tone belongs to a
    // series and every bar of it is painted alike.
    const { container, dispose } = mount(() => (
      <Histogram
        label="Message size distribution"
        buckets={BUCKETS.map((b, i) => (i >= 3 ? { ...b, tone: "warning" as const } : b))}
      />
    ));
    const fills = [...container.querySelectorAll(".kui-plot__bar")].map(b => b.getAttribute("fill"));
    expect(fills[3]).toBe("var(--kui-color-warning)");
    expect(fills[4]).toBe("var(--kui-color-warning)");
    dispose();
  });

  it("highlights nothing when two buckets tie for tallest", () => {
    const { container, dispose } = mount(() => (
      <Histogram
        label="Message size distribution"
        buckets={[
          { from: 0, to: 1024, count: 500 },
          { from: 1024, to: 2048, count: 9_420 },
          { from: 2048, to: 4096, count: 9_420 },
        ]}
      />
    ));
    const fills = [...container.querySelectorAll(".kui-plot__bar")].map(b => b.getAttribute("fill"));
    expect(fills.some(f => f === "var(--kui-color-accent)")).toBe(false);
    dispose();
  });

  it("draws no bar for a bucket that counted nothing", () => {
    const { container, dispose } = mount(() => (
      <Histogram
        label="Message size distribution"
        buckets={[
          { from: 0, to: 1024, count: 400 },
          { from: 1024, to: 2048, count: 0 },
          { from: 2048, to: 4096, count: 900 },
        ]}
      />
    ));
    expect(container.querySelectorAll(".kui-plot__bar").length).toBe(2);
    // The bucket keeps its column, so the axis still says where it was.
    expect(container.querySelectorAll(".kui-plot__group").length).toBe(3);
    dispose();
  });

  it("derives its tick labels from the bucket boundaries, not from category strings", () => {
    const { container, dispose } = mount(() => (
      <Histogram
        label="Message size distribution"
        buckets={BUCKETS}
        formatBoundary={(v: number) => `${v} B`}
        tickEvery={2}
      />
    ));
    const ticks = [...container.querySelectorAll(".kui-plot__tick")].map(t => t.textContent);
    expect(ticks[0]).toBe("256 B");
    expect(ticks[1]).toBe("1024 B");
    // The open-ended tail has no served upper edge, so the last tick prints nothing rather than
    // inventing one.
    expect(ticks[ticks.length - 1]).toBe("");
    dispose();
  });

  it("says the range is empty rather than drawing an axis over nothing", () => {
    const { container, dispose } = mount(() => (
      <Histogram
        label="Message size distribution"
        buckets={BUCKETS.map(b => ({ ...b, count: 0 }))}
        emptyMessage="No messages produced in this range."
      />
    ));
    expect(container.querySelector(".kui-plot__empty")!.textContent).toContain("No messages produced");
    expect(container.querySelectorAll(".kui-plot__bar").length).toBe(0);
    dispose();
  });

  it("publishes its buckets as a table and moves a cursor with the keyboard", async () => {
    const { container, dispose } = mount(() => (
      <Histogram label="Message size distribution" buckets={BUCKETS} formatBoundary={(v: number) => `${v} B`} />
    ));
    const rows = container.querySelectorAll("table tbody tr");
    expect(rows.length).toBe(5);
    expect(rows[4]!.querySelector("th")!.textContent).toBe("4096 B+");

    const surface = container.querySelector<HTMLElement>(".kui-plot__surface")!;
    expect(surface.getAttribute("aria-describedby")).toBe(container.querySelector("table")!.id);
    surface.focus();
    await userEvent.keyboard("{ArrowRight}");
    flush();
    expect(container.querySelector("[role='status']")!.textContent).toContain("256 B – 512 B");
    await expectNoViolations(container);
    dispose();
  });
});

/* --- StackedBar ------------------------------------------------------------------------------- */

describe("StackedBar", () => {
  const SEGMENTS = [
    { label: "a", value: 1, tone: "series-1" },
    { label: "b", value: 1, tone: "series-2" },
    { label: "other", value: 2, tone: "series-6" },
  ] as const;

  const widths = (container: HTMLElement): string[] =>
    [...container.querySelectorAll<HTMLElement>(".kui-stacked__segment")].map(s => s.style.width);

  it("sizes each segment by its share of the capacity", () => {
    const { container, dispose } = mount(() => (
      <StackedBar label="broker-1 disk usage" segments={[...SEGMENTS]} capacity={4} />
    ));
    expect(widths(container)).toEqual(["25%", "25%", "50%"]);
    // One row per segment in the hidden table, which is the accessible rendering of the bar.
    expect(container.querySelectorAll("table tbody tr").length).toBe(3);
    dispose();
  });

  it("leaves the rest of the capacity as track rather than stretching to fill it", () => {
    const { container, dispose } = mount(() => (
      <StackedBar label="broker-1 disk usage" segments={[...SEGMENTS]} capacity={8} />
    ));
    expect(widths(container)).toEqual(["12.5%", "12.5%", "25%"]);
    dispose();
  });

  it("draws the neutral track and no fill when the capacity is unknown", () => {
    // A broker whose directories reported no capacity has no denominator, and a bar that drew its
    // segments anyway would be showing a ratio computed against nothing.
    const { container, dispose } = mount(() => (
      <StackedBar label="broker-5 disk usage" segments={[...SEGMENTS]} capacity={undefined} valueText={ABSENT} />
    ));
    expect(container.querySelectorAll(".kui-stacked__segment").length).toBe(0);
    expect(container.querySelector(".kui-stacked__track")).not.toBeNull();
    expect(container.querySelector(".kui-stacked__value--unknown")!.textContent).toBe(ABSENT);
    dispose();
  });

  it("cannot be made to overflow its track by segments that sum past the capacity", () => {
    const { container, dispose } = mount(() => (
      <StackedBar
        label="broker-8 disk usage"
        segments={[
          { label: "a", value: 3, tone: "series-1" },
          { label: "b", value: 3, tone: "series-2" },
        ]}
        capacity={4}
      />
    ));
    const total = widths(container).reduce((sum, w) => sum + Number.parseFloat(w), 0);
    expect(total).toBeCloseTo(100, 6);
    dispose();
  });

  it("inks the figure beside the bar by how full it is, not by the segments' own tones", () => {
    const warning = mount(() => (
      <StackedBar
        label="broker-1 disk usage"
        segments={[{ label: "a", value: 83, tone: "series-1" }]}
        capacity={100}
        valueText="347 GB"
      />
    ));
    expect(warning.container.querySelector(".kui-stacked__value--warning")).not.toBeNull();
    warning.dispose();

    const critical = mount(() => (
      <StackedBar
        label="broker-4 disk usage"
        segments={[{ label: "a", value: 95, tone: "series-1" }]}
        capacity={100}
        valueText="396 GB"
      />
    ));
    expect(critical.container.querySelector(".kui-stacked__value--critical")).not.toBeNull();
    critical.dispose();
  });

  it("names each segment for a hover and draws no legend unless asked", async () => {
    const bare = mount(() => (
      <StackedBar label="broker-1 disk usage" segments={[...SEGMENTS]} capacity={4} format={(v: number) => `${v} GB`} />
    ));
    // The design draws one legend under a stack of rows, so a bar does not bring its own.
    expect(bare.container.querySelector(".kui-chart-legend")).toBeNull();
    expect(bare.container.querySelectorAll(".kui-stacked__segment")[2]!.getAttribute("title")).toBe("other · 2 GB");
    await expectNoViolations(bare.container);
    bare.dispose();

    const withLegend = mount(() => (
      <StackedBar label="broker-1 disk usage" segments={[...SEGMENTS]} capacity={4} legend />
    ));
    expect(withLegend.container.querySelector(".kui-chart-legend")!.textContent).toContain("other");
    withLegend.dispose();
  });
});
