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
    expect(entries[1]!.querySelector(".kui-magnitude-list__value")!.textContent).toBe(ABSENT);
    // And the known one still draws, so the two are visibly different pictures.
    expect(entries[0]!.querySelector(".kui-magnitude-list__fill")).not.toBeNull();
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
