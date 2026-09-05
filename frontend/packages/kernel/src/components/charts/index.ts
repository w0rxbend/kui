/**
 * The chart family: everything on the dashboard that draws a quantity rather than printing one.
 *
 * They share three rules, and each of them is a defect this project has already paid for:
 *
 *  - a denominator is always guarded, so a zero maximum can never produce a full bar;
 *  - an unknown value never borrows the drawing of a zero;
 *  - the picture is never the only rendering — every chart's numbers are also available as text,
 *    to a screen reader and to a keyboard.
 */

export { ChartLegend, type ChartLegendProps, type LegendItem } from "./ChartLegend.jsx";
export { ChartDataTable, type ChartDataTableProps } from "./ChartDataTable.jsx";
export { RangeSelector, type RangeSelectorProps, type RangeOption } from "./RangeSelector.jsx";
export { ProgressBar, type ProgressBarProps } from "./ProgressBar.jsx";
export { MagnitudeBarList, type MagnitudeBarListProps, type MagnitudeEntry } from "./MagnitudeBarList.jsx";
export { Donut, type DonutProps, type DonutSegment } from "./Donut.jsx";
export { BarChart, type BarChartProps } from "./BarChart.jsx";
export { LineChart, type LineChartProps } from "./LineChart.jsx";
export { type Series, type PlotProps, seriesMax, isPlotEmpty, defaultTicks, topRoundedRect } from "./plot.js";
export {
  ABSENT,
  DEFAULT_THRESHOLDS,
  formatCount,
  formatPercent,
  fraction,
  levelFor,
  type MaybeNumber,
  type ThresholdLevel,
  type Thresholds,
} from "./format.js";
export { toneColor, toneAreaFill, type ChartTone } from "./tone.js";
export { useElementSize, type ElementSize, type UseElementSize } from "./elementSize.js";

/**
 * A row of equal segments, one per thing, coloured by that thing's state — a connector's tasks, a
 * cluster's brokers. Neither a progress bar (one quantity against a limit) nor a stacked bar
 * (parts sized by their share): the segments are equal because the things are.
 */
export { SegmentBar, type SegmentBarProps, type SegmentBarSegment, type SegmentState } from "./SegmentBar.jsx";
