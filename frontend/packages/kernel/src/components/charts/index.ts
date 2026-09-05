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
