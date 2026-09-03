# ADR-025 — Frontend facades: CodeMirror 6, circe JSON viewer, uPlot, kernel virtualized table

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat uses Ace, `lossless-json`, JSONPath previews and no virtualization; Kouncil uses
Monaco with schema completion and renders all rows. KUI needs a code editor (JSON, Protobuf,
SQL, CEL), a JSON viewer, charts, and a table that survives thousands of rows.

## Decision

- **Code editor**: CodeMirror 6 (`@codemirror/state`, `view`, `lang-json`, `lang-sql`,
  `legacy-modes/protobuf`, `lint`, `search`, theme) behind a kernel `CodeEditor` trait with a
  hand-written facade (~150 lines, generated once with `mill-scalablytyped`, trimmed and
  vendored into `frontend/ui-kernel`). CEL gets a `StreamLanguage` keyword highlighter.
  JSON-Schema validation of produced messages is server-side; the editor shows results via
  `@codemirror/lint`.
- **JSON viewer**: a kernel Laminar tree component over `circe.Json` (collapsible, copy path,
  lazy children, "expand as columns" for the Kouncil-style table). No `JSON.parse` on
  payloads, so 64-bit numbers survive.
- **Charts**: uPlot with a ~60-line facade behind a kernel `Chart` trait; inline SVG
  sparklines for dashboard tiles.
- **Table**: kernel-native `VirtualizedTable` (fixed row height windowing, sticky header,
  column resize persisted in `WebStorageVar`, sort/filter state in `Page` params, selection,
  expandable rows, `role="grid"` keyboard navigation) plus a non-virtual `DataTable` for
  small lists; property tests on the window math.
- npm packages are served as static ES modules by the gateway via an import map; a Vite
  bundling step is introduced only if import maps prove insufficient.

## Evidence

- `research/scala/frontend-research.md` §5 (no maintained CM6/uPlot facades; Monaco size;
  ScalablyTyped guidance; Kafbat/Kouncil table facts), ADR-025 candidate; §1.3 (Kafbat has no
  virtualization).
- `research/kafbat/ui-analysis.md` IA.4 (kernel component inventory).

## Consequences

- ~300 lines of facades owned by KUI; Monaco's schema completion is replaced by server
  validation.
- `mill-scalablytyped` is a one-off tool, not part of the routine build (compatibility with
  Mill 1.1.x is verified in an M0 spike).

## Alternatives rejected

- Ace: dated, global script; Monaco: ~5 MB, AMD loader, workers.
- ECharts/Chart.js: heavier than uPlot for time series.
- A JS table library: brings React or bespoke DOM management that fights Laminar.

## Reversibility

High. Facades sit behind kernel traits.
