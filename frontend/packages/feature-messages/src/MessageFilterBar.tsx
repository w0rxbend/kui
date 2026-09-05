/**
 * The row above the record list: where to start, which partitions, what text, and whether to follow.
 *
 * ## This bar is built once and never rebuilt
 *
 * A drawer must not rebuild while somebody is typing in it — a defect this project shipped, where
 * the filter box lost its caret every time results arrived. In Solid 2 the rule is concrete: the
 * bar is **not** inside a `<Show>` or `<Loading>` whose condition flips when records land, and the
 * record list is **not** a sibling that shares a keyed parent with it. Fine-grained reactivity
 * gives you that for free *if* the form stays outside the boundary that re-renders; putting it
 * inside is how the defect returns. `MessagesTab` keeps that arrangement and `messages.test.tsx`
 * asserts it by holding on to the input element and checking it is the same node after a record
 * arrives.
 *
 * ## Nothing here starts a browse
 *
 * Every control reports upwards and the screen decides. That is not indirection for its own sake:
 * changing *where* to read has to stop whatever is running (a browse in flight is reading a
 * different range from the one the controls now describe, and letting it keep delivering would mix
 * two ranges in one list with nothing on screen to say why), and that decision belongs to the thing
 * that owns the session.
 *
 * ## The bar wraps; it never scrolls sideways
 *
 * A control that has slid off the right edge of a bar is a control nobody finds.
 */

import type { JSX } from "@solidjs/web";
import { For, Show, createSignal, createUniqueId, onCleanup, untrack } from "solid-js";
import { Checkbox, Icon, Select, StatusPill, TextField } from "@kui/kernel";
import {
  offsetOf,
  partitionSummary,
  seekFor,
  seekKind,
  timestampOf,
  type SeekKind,
  type SeekMode,
} from "./browse.js";

/** Whether live tailing can be offered at all, and why not when it cannot. */
export type LiveAvailability = { readonly available: true } | { readonly available: false; readonly reason: string };

export interface MessageFilterBarProps {
  readonly seek: SeekMode;
  readonly onSeekChange: (seek: SeekMode) => void;
  /** How many partitions the topic has. Drives the summary and the picker's list. */
  readonly partitionCount: number;
  /** Empty means every partition — which is what the server means by the parameter being absent. */
  readonly partitions: readonly number[];
  readonly onPartitionsChange: (partitions: readonly number[]) => void;
  readonly filter: string;
  readonly onFilterChange: (filter: string) => void;
  /** Fired when the filter is committed — Enter, or the debounce elapsing. */
  readonly onFilterCommit: (filter: string) => void;
  readonly live: boolean;
  readonly onLiveChange: (live: boolean) => void;
  readonly liveAvailability?: LiveAvailability | undefined;

  /**
   * The smart filter running on this browse, and the way to change it.
   *
   * Optional as a whole. A deployment whose cluster has no filter engine is refused with
   * `KUI-UNSUPPORTED` — the quickstart is one — and on such a cluster the control is offered
   * **disabled with that reason** rather than left out, because a missing control reads as a
   * product that cannot do the thing at all.
   */
  readonly smartFilter?: SmartFilterSlot | undefined;

  /** Anything the screen wants at the right of the bar, before the LIVE pill. */
  readonly children?: JSX.Element;
}

/**
 * The smart-filter control's state, as the screen owns it.
 *
 * The bar shows what is running and asks for the editor; it never registers, tests or clears
 * anything itself. That is the same rule every other control here follows and it is in the header:
 * changing what a browse reads has to stop what is running, and only the screen can do that.
 */
export interface SmartFilterSlot {
  /** The expression currently applied, if any. Shown so it can be read without opening anything. */
  readonly source?: string | undefined;
  readonly onOpen: () => void;
  /** Removes it from the browse. */
  readonly onClear?: (() => void) | undefined;
  /** Why smart filtering cannot be used here — an absent filter engine, or a missing permission. */
  readonly unavailableReason?: string | undefined;
}

const SEEK_OPTIONS: readonly { readonly value: SeekKind; readonly label: string }[] = [
  { value: "latest", label: "Latest" },
  { value: "beginning", label: "Earliest" },
  { value: "offset", label: "Offset" },
  { value: "timestamp", label: "Timestamp" },
];

/**
 * How long the filter box waits before committing.
 *
 * Long enough that typing `orderId` is one browse rather than seven, short enough that a person who
 * has stopped typing does not wonder whether the control works. Enter commits immediately, which is
 * what somebody who knows what they are looking for will press.
 */
export const FILTER_DEBOUNCE_MS = 350;

export function MessageFilterBar(props: MessageFilterBarProps): JSX.Element {
  const kind = (): SeekKind => seekKind(props.seek);

  /* The offset and timestamp boxes hold text, not a parsed value. A control that reparsed on every
   * keystroke would erase a half-typed `1` the moment it became `1_`, and an operator pasting an
   * offset would watch it be rewritten under the cursor. They are committed on change. */
  /* `untrack`, and Solid 2 insists on it: reading `props.seek` in the component body is a reactive
   * read outside a tracking scope, which the framework's strict mode reports because such a read
   * silently never updates. Here it genuinely is a one-off — these are the *initial* contents of two
   * text boxes, which the user owns from then on. Rewriting them from the prop on every change is
   * exactly the "the field changed under my cursor" behaviour they must not have. */
  const [offsetText, setOffsetText] = createSignal(untrack(() => offsetOf(props.seek) ?? ""));
  const [timestampText, setTimestampText] = createSignal(
    untrack(() => isoLocal(timestampOf(props.seek))),
  );

  let debounce: ReturnType<typeof setTimeout> | undefined;
  onCleanup(() => {
    if (debounce !== undefined) clearTimeout(debounce);
  });

  function changeSeekKind(next: SeekKind): void {
    props.onSeekChange(seekFor(next, offsetText(), epochOf(timestampText())));
  }

  function commitFilter(value: string): void {
    if (debounce !== undefined) clearTimeout(debounce);
    props.onFilterCommit(value);
  }

  return (
    <div class="kui-browse-bar">
      <Select<SeekKind>
        label="Seek"
        /* The visible name of this control is its *prefix* ("Seek:"), which is what the design
         * draws inside the trigger. Without this the control drew "Seek" above itself and "Seek:"
         * inside itself — the same word twice, once as a stray line of text nothing was aligned
         * to. The accessible name is unchanged; only the second drawing of it goes. */
        labelHidden
        prefix="Seek:"
        size="sm"
        options={SEEK_OPTIONS}
        value={kind()}
        onChange={changeSeekKind}
      />

      {/* Choosing Offset or Timestamp reveals an extra input inline, and the bar wraps to a second
          line rather than scrolling. `<Show>` is safe around *these* — they are not the field
          somebody is typing a filter into, and their condition changes only when the operator
          changes the seek mode themselves. */}
      <Show when={kind() === "offset"}>
        <TextField
          label="Start at offset"
          /* The label is for the accessibility tree only. On screen the control is named by the
             seek control immediately to its left, which reads "Seek: Offset" — and a visible label
             stacked above this one box made it the only two-line control in a bar of 26px ones,
             which is what the design draws as a single row. */
          labelHidden
          size="sm"
          mono
          placeholder="0"
          value={offsetText()}
          onInput={(value) => {
            /* Digits only, filtered rather than rejected: an offset is a 64-bit integer carried as
               a string everywhere in this frontend, and a box that accepted `1e6` would send the
               server something it has to refuse. */
            const digits = value.replace(/\D/g, "");
            setOffsetText(digits);
            props.onSeekChange(seekFor("offset", digits, undefined));
          }}
        />
      </Show>

      <Show when={kind() === "timestamp"}>
        <label class="kui-browse-bar__stamp">
          <span class="kui-visually-hidden">Start at time</span>
          <input
            type="datetime-local"
            class="kui-browse-bar__stamp-input kui-focusable"
            value={timestampText()}
            onInput={(event) => {
              const raw = event.currentTarget.value;
              setTimestampText(raw);
              const epoch = epochOf(raw);
              if (epoch !== undefined) props.onSeekChange({ kind: "timestamp", epochMillis: epoch });
            }}
          />
        </label>
      </Show>

      <PartitionPicker
        total={props.partitionCount}
        selected={props.partitions}
        onChange={props.onPartitionsChange}
      />

      <TextField
        label="Filter by key or value"
        labelHidden
        size="sm"
        icon="filter"
        placeholder="Filter by key or value…"
        value={props.filter}
        onInput={(value) => {
          props.onFilterChange(value);
          if (debounce !== undefined) clearTimeout(debounce);
          debounce = setTimeout(() => commitFilter(value), FILTER_DEBOUNCE_MS);
        }}
        onKeyDown={(event) => {
          if (event.key === "Enter") commitFilter(props.filter);
        }}
      />

      <Show when={props.smartFilter}>
        {(slot) => <SmartFilterControl slot={slot()} />}
      </Show>

      <div class="kui-browse-bar__end">
        {props.children}
        <LiveToggle
          live={props.live}
          availability={props.liveAvailability ?? { available: true }}
          onChange={props.onLiveChange}
        />
      </div>
    </div>
  );
}

/**
 * The smart filter, as one control in the bar.
 *
 * Two different things depending on whether one is running, because they are two different pieces of
 * news. With no filter it is a plain button that opens the editor. With one applied it shows the
 * **expression itself**, truncated, with a clear beside it — an operator who comes back to a tab an
 * hour later has to be able to see why the list is short without opening a dialog, and a control that
 * only said "Filter ✓" would leave them to guess.
 *
 * When the cluster has no filter engine the button stays and is disabled with the server's reason.
 * Removing it would teach the operator KUI cannot filter, when in fact this cluster cannot.
 */
function SmartFilterControl(props: { readonly slot: SmartFilterSlot }): JSX.Element {
  const reason = (): string | undefined => props.slot.unavailableReason;
  const applied = (): string | undefined =>
    props.slot.source === undefined || props.slot.source === "" ? undefined : props.slot.source;

  return (
    <div class="kui-browse-bar__smart">
      <button
        type="button"
        class={[
          "kui-smart-chip",
          "kui-focusable",
          ...(applied() === undefined ? [] : ["kui-smart-chip--applied"]),
        ]}
        disabled={reason() !== undefined}
        title={reason() ?? applied() ?? "Filter with an expression evaluated on the server"}
        aria-label={
          applied() === undefined
            ? "Filter with an expression"
            : `Filtering by ${applied() ?? ""}. Edit the expression.`
        }
        onClick={() => props.slot.onOpen()}
      >
        <Icon name="filter" size="14px" />
        <Show when={applied()} fallback={<span>Expression…</span>}>
          {(source) => <code class="kui-smart-chip__source">{source()}</code>}
        </Show>
      </button>

      {/* Clearing is its own control, never a second meaning for clicking the chip. The chip opens
          the editor; a chip that sometimes removed the filter instead would be a control whose
          effect depends on state the operator cannot see. */}
      <Show when={applied() !== undefined && props.slot.onClear !== undefined}>
        <button
          type="button"
          class="kui-smart-chip__clear kui-focusable"
          aria-label="Stop filtering by this expression"
          title="Stop filtering by this expression"
          onClick={() => props.slot.onClear?.()}
        >
          <Icon name="close" size="12px" />
        </button>
      </Show>
    </div>
  );
}

/**
 * The LIVE pill: a toggle, not a light.
 *
 * On, it is the success pill with a pulsing dot and new records arrive at the top. Off, it is
 * neutral and reads PAUSED — which is a different word from LIVE rather than the same word dimmed,
 * because "dimmed green" and "green" are one distinction and it is a colour one.
 *
 * Unavailable it stays in the bar, disabled, and reads `LIVE unavailable` with the reason as its
 * title. Removing it would tell the operator the product cannot tail at all.
 */
function LiveToggle(props: {
  readonly live: boolean;
  readonly availability: LiveAvailability;
  readonly onChange: (live: boolean) => void;
}): JSX.Element {
  return (
    <Show
      when={props.availability.available}
      fallback={
        <StatusPill
          tone="neutral"
          dot
          disabled
          title={props.availability.available ? "" : props.availability.reason}
        >
          LIVE unavailable
        </StatusPill>
      }
    >
      <StatusPill
        tone={props.live ? "success" : "neutral"}
        dot
        pulsing={props.live}
        pressed={props.live}
        onClick={() => props.onChange(!props.live)}
        title={props.live ? "Stop following the end of the topic" : "Follow the end of the topic"}
      >
        {props.live ? "LIVE" : "PAUSED"}
      </StatusPill>
    </Show>
  );
}

/**
 * Which partitions to read.
 *
 * A custom listbox rather than a native `<select multiple>`, for the reason the spec gives for every
 * control in this product: the native one cannot be styled to look like the rest of the page, and a
 * control that looks like nothing else on the screen reads as broken. It is built from a real
 * checkbox per row — the kernel's, which is a visually-hidden `<input type="checkbox">` behind a
 * drawn box — so every native keyboard and form behaviour survives.
 *
 * **Empty is "all", never "none".** The server reads an absent `partition` parameter as every
 * partition, an *explicitly empty* one as a client bug, and this control cannot express the second.
 * "Clear" therefore means "select all", which is what the summary says.
 */
function PartitionPicker(props: {
  readonly total: number;
  readonly selected: readonly number[];
  readonly onChange: (partitions: readonly number[]) => void;
}): JSX.Element {
  const [open, setOpen] = createSignal(false, { ownedWrite: true });
  const listId = createUniqueId();
  let root: HTMLDivElement | undefined;

  /* A topic with one partition still shows the selector, disabled: hiding it would make an
     operator think this topic is different in some way they cannot see. */
  const single = (): boolean => props.total <= 1;

  const isSelected = (partition: number): boolean =>
    props.selected.length === 0 || props.selected.includes(partition);

  function toggle(partition: number, on: boolean): void {
    const current = props.selected.length === 0 ? range(props.total) : props.selected;
    const next = on ? [...current, partition] : current.filter((p) => p !== partition);
    const distinct = [...new Set(next)].sort((a, b) => a - b);
    /* Everything selected is the same request as nothing selected, and the shorter URL is the one
       a person can read. Emitting `[]` for both is what keeps the two from being different states
       that look identical. */
    props.onChange(distinct.length === props.total ? [] : distinct);
  }

  const close = (event: PointerEvent): void => {
    if (!open()) return;
    if (root !== undefined && event.target instanceof Node && root.contains(event.target)) return;
    setOpen(false);
  };
  document.addEventListener("pointerdown", close, true);
  onCleanup(() => document.removeEventListener("pointerdown", close, true));

  return (
    <div class="kui-partition-picker" ref={(el: HTMLDivElement) => (root = el)}>
      <button
        type="button"
        class="kui-partition-picker__trigger kui-focusable"
        aria-expanded={open() ? "true" : "false"}
        aria-controls={open() ? listId : undefined}
        aria-haspopup="true"
        disabled={single()}
        title={single() ? "This topic has one partition." : undefined}
        onClick={() => setOpen((was) => !was)}
      >
        <span class="kui-partition-picker__prefix">Partitions:</span>
        <span class="kui-partition-picker__value">
          {partitionSummary(props.selected, props.total)}
        </span>
        <Icon name="chevron-down" size="14px" class="kui-partition-picker__chevron" />
      </button>

      <Show when={open()}>
        <div id={listId} class="kui-partition-picker__menu" role="group" aria-label="Partitions">
          <div class="kui-partition-picker__actions">
            <button
              type="button"
              class="kui-partition-picker__action kui-focusable"
              onClick={() => props.onChange([])}
            >
              Select all
            </button>
          </div>
          <div class="kui-partition-picker__list">
            <For each={range(props.total)}>
              {(partition) => (
                <Checkbox
                  label={`Partition ${String(partition)}`}
                  checked={isSelected(partition)}
                  onChange={(on) => toggle(partition, on)}
                />
              )}
            </For>
          </div>
        </div>
      </Show>
    </div>
  );
}

function range(size: number): number[] {
  return Array.from({ length: Math.max(0, size) }, (_, index) => index);
}

/**
 * An epoch millisecond as `<input type="datetime-local">` spells it, in the viewer's own zone.
 *
 * The zone matters and is not a detail: a browse seeking "09:00" means nine o'clock where the
 * person typing it is sitting, and converting through UTC would silently move it by however far
 * they are from Greenwich.
 */
function isoLocal(epochMillis: number | undefined): string {
  if (epochMillis === undefined) return "";
  const at = new Date(epochMillis);
  const pad = (value: number): string => String(value).padStart(2, "0");
  return (
    `${String(at.getFullYear())}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}` +
    `T${pad(at.getHours())}:${pad(at.getMinutes())}`
  );
}

function epochOf(local: string): number | undefined {
  if (local === "") return undefined;
  const at = new Date(local).getTime();
  return Number.isFinite(at) ? at : undefined;
}
