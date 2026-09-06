/**
 * Filter presets: the `PRESETS` row of `SCREENS-V4.md` §3.12.
 *
 * ## Why these are local and not a server list
 *
 * The design draws four named chips — `Declined only`, `Big tickets`, `Refunds`, `Non-UAH` — and
 * §3.12 lists the preset set as one of five controls with **no server parameter behind them**. It
 * is the fourth of the five; three are in `predicates.ts`, which carries the whole accounting,
 * and the fifth — the status facet — is not built. That is exactly right, and the endpoint roster
 * confirms it for this one: `POST …/messages/filters` registers an expression and answers with its
 * id, `POST …/messages/filters/test` tries one against a record, and there is no `GET`. A
 * registered filter is not a saved filter — the id is `sha256(source)`, so registering is a compile
 * and not a write, and nothing on the server holds a name for one.
 *
 * So a preset is a name this browser has given to a set of predicates, kept where the rest of this
 * product keeps a per-reader preference. Applying one still goes through the endpoints that exist:
 * the predicates compile to CEL and are registered exactly as a hand-typed expression is.
 *
 * ## An unconfigured preset set draws no row
 *
 * §3.12 again, in as many words: "a preset set that is not configured draws no `PRESETS` row rather
 * than an empty one". {@link readPresets} therefore answers an empty list for every failure mode —
 * no store, a store that throws, a store holding something this build cannot read — and the bar
 * renders nothing rather than an empty group with a heading.
 *
 * ## Every read and write is total
 *
 * `localStorage` throws rather than returning `null` in a browser with site data blocked, and in a
 * private window it can be present and empty. Neither is a state this screen may fail in: a message
 * browser that will not render because a preference store is unavailable is a much worse product
 * than one with no preset chips.
 */

import { NO_PREDICATES, type Predicates } from "./predicates.js";

/** A named set of predicates, and the expression they sit beside. */
export interface FilterPreset {
  readonly name: string;
  readonly predicates: Predicates;
  /** The hand-written expression, when the preset was saved with one applied. */
  readonly expression?: string | undefined;
}

/** How many a browser keeps. Past this the chip row is longer than the bar it sits under. */
export const MAX_PRESETS = 8;

/**
 * Where they live.
 *
 * Per cluster, because a preset is written about the shape of the documents on one cluster's topics
 * and `record.value.status == "CAPTURED"` means nothing on a cluster whose payloads are protobuf.
 */
export function presetsKey(clusterId: string): string {
  return `kui.messages.presets.${clusterId}`;
}

/** What this browser has saved for this cluster. Empty for every kind of "nothing there". */
export function readPresets(clusterId: string): readonly FilterPreset[] {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(presetsKey(clusterId));
  } catch {
    /* Site data blocked. The bar draws no PRESETS row, which is the documented absent state. */
    return [];
  }
  if (raw === null || raw === "") return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.flatMap(asPreset) : [];
  } catch {
    return [];
  }
}

/** Saves the list, and says nothing when it cannot. A failed write must not fail a browse. */
export function writePresets(clusterId: string, presets: readonly FilterPreset[]): void {
  try {
    const kept = JSON.stringify(presets.slice(0, MAX_PRESETS));
    window.localStorage.setItem(presetsKey(clusterId), kept);
  } catch {
    /* Nothing to do and nothing to say: the chips stay as they are for this session. */
  }
}

/**
 * The list with this preset in it, replacing one of the same name.
 *
 * Replacing rather than appending, because a name is how an operator refers to a preset and two
 * chips reading `Refunds` are two chips nobody can choose between. The newest goes first, which is
 * where somebody looks for the thing they just saved.
 */
export function withPreset(
  presets: readonly FilterPreset[],
  preset: FilterPreset,
): readonly FilterPreset[] {
  return [preset, ...presets.filter((one) => one.name !== preset.name)].slice(0, MAX_PRESETS);
}

export function withoutPreset(
  presets: readonly FilterPreset[],
  name: string,
): readonly FilterPreset[] {
  return presets.filter((one) => one.name !== name);
}

/**
 * One entry of the store, or nothing.
 *
 * `flatMap` over this drops an entry a newer build wrote in a shape this one cannot read, rather
 * than failing the whole list — the same rule the URL grammar follows, for the same reason: one
 * unreadable saved thing should cost the operator that one thing.
 */
function asPreset(raw: unknown): readonly FilterPreset[] {
  if (typeof raw !== "object" || raw === null) return [];
  const candidate = raw as Record<string, unknown>;
  const name = candidate["name"];
  if (typeof name !== "string" || name === "") return [];
  const predicates = candidate["predicates"];
  const expression = candidate["expression"];
  return [
    {
      name,
      predicates:
        typeof predicates === "object" && predicates !== null
          ? (predicates as Predicates)
          : NO_PREDICATES,
      ...(typeof expression === "string" && expression !== "" ? { expression } : {}),
    },
  ];
}
