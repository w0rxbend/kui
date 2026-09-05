/**
 * A tooltip: a short explanation that appears beside a control when you point at it or focus it.
 *
 * ## What it is for, and what it is not for
 *
 * In this product a tooltip's main job is to say **why a control is disabled** — a permission the
 * operator does not have, a cluster in read-only mode, a metrics range the backend keeps no
 * history for. SPEC §4.13 is explicit that an action the principal may not take is rendered
 * disabled *with a reason* rather than hidden, because a hidden button makes an operator believe
 * the product cannot do the thing at all. A disabled control that says nothing is barely better.
 *
 * It is not for anything the user must read to do their job. A tooltip is invisible until pointed
 * at, unreachable on a touch screen, and gone the moment the pointer moves — so it may only ever
 * repeat or qualify, never carry the only copy of something.
 *
 * ## Three things this implementation is careful about
 *
 * **A disabled `<button>` fires no pointer events.** Hovering it produces nothing at all, so a
 * tooltip attached directly to one never appears. That is why the anchor is a wrapper element
 * that listens on its own behalf, and why `Button` marks a disabled action with `aria-disabled`
 * rather than the `disabled` attribute — `aria-disabled` keeps the control focusable, so the
 * reason is reachable from the keyboard too and not only from a mouse.
 *
 * **It is mounted in a portal.** A tooltip rendered inside a table cell is clipped by that cell's
 * `overflow: auto`, and the clipping only shows on the one row near the edge that nobody tests.
 * `position: fixed` in a portal on `<body>` cannot be clipped by an ancestor.
 *
 * **`Escape` closes it.** A tooltip that has appeared over the thing you were about to click, and
 * cannot be dismissed without moving the pointer away and losing your place, is a trap.
 */
import type { JSX } from "@solidjs/web";
import { Portal } from "@solidjs/web";
import { Show, createSignal, createUniqueId, onSettled } from "solid-js";

export interface TooltipProps {
  /** The sentence. Plain voice: a tooltip is read while something is going wrong. */
  readonly content: string;
  /**
   * A machine-readable reason, shown under the sentence in mono. SPEC §4.25: the sentence tells
   * the operator what is happening, the code tells whoever they ask for help which failure it
   * was. Neither replaces the other.
   */
  readonly code?: string | undefined;
  /** Where the tooltip prefers to sit. It flips if there is no room. */
  readonly placement?: "top" | "bottom" | undefined;
  /** A tooltip with no content is not rendered at all, rather than as an empty box. */
  readonly disabled?: boolean | undefined;
  readonly children: JSX.Element;
}

const GAP = 8;

export function Tooltip(props: TooltipProps): JSX.Element {
  const id = createUniqueId();
  // `ownedWrite` because these are the component's own private state, written from the event
  // handlers and the settle callback below rather than from anywhere outside.
  const [open, setOpen] = createSignal(false, { ownedWrite: true });
  const [pos, setPos] = createSignal({ left: 0, top: 0 }, { ownedWrite: true });

  let anchor: HTMLSpanElement | undefined;
  let bubble: HTMLDivElement | undefined;

  const active = (): boolean => open() && props.disabled !== true && props.content.length > 0;

  /**
   * Position the bubble.
   *
   * **This must run after the browser has laid the bubble out, not when the element is created.**
   * A `ref` callback fires the moment the element exists, and at that point
   * `getBoundingClientRect()` returns a zero-sized box — so the arithmetic below centred a
   * zero-width bubble (leaving it starting at the anchor's centre instead of centred on it) and
   * subtracted a zero height (leaving it on top of the anchor instead of above it). Both were
   * visible in the first screenshot of this component and neither is visible in the code, which is
   * exactly why it is worth the paragraph.
   *
   * It is the same mistake as measuring a virtualized table's container once at mount, and it has
   * the same shape of fix: measure when the browser has something to measure. `place` is therefore
   * called twice — once eagerly, so a bubble is never drawn at the origin, and once on the next
   * animation frame, when layout has happened.
   */
  function place(): void {
    if (anchor === undefined || bubble === undefined) return;
    const a = anchor.getBoundingClientRect();
    const b = bubble.getBoundingClientRect();
    // Prefer the requested side; flip only when the preferred side genuinely has no room, and
    // never flip into a side that has no room either — a tooltip half off the top of the window is
    // not an improvement on one half off the bottom.
    const fitsAbove = a.top - b.height - GAP >= 0;
    const fitsBelow = a.bottom + b.height + GAP <= window.innerHeight;
    const preferTop = (props.placement ?? "top") === "top";
    const above = preferTop ? fitsAbove || !fitsBelow : !fitsBelow && fitsAbove;
    const top = above ? a.top - b.height - GAP : a.bottom + GAP;
    // Clamp horizontally so a tooltip on a control at the right edge of the window does not go
    // off screen — where it would be invisible while still being announced.
    const raw = a.left + a.width / 2 - b.width / 2;
    const left = Math.max(GAP, Math.min(raw, window.innerWidth - b.width - GAP));
    setPos({ left, top });
  }

  onSettled(() => {
    /**
     * `aria-describedby` has to land on the element the user actually focuses, not on the wrapper
     * around it: a screen reader announces a description when focus reaches the described element,
     * and the wrapper is never focused. So the wrapper's first focusable descendant is found once
     * and labelled. If the child is not focusable — a plain span of truncated text — the wrapper
     * keeps the attribute and the tooltip is still a pointer affordance.
     */
    const focusable = anchor?.querySelector<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    );
    focusable?.setAttribute("aria-describedby", id);

    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key === "Escape" && open()) setOpen(false);
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  });

  return (
    <>
      <span
        class="kui-tooltip-anchor"
        ref={(el) => (anchor = el)}
        aria-describedby={id}
        onPointerEnter={() => setOpen(true)}
        onPointerLeave={() => setOpen(false)}
        onFocusIn={() => setOpen(true)}
        onFocusOut={() => setOpen(false)}
      >
        {props.children}
      </span>
      <Show when={active()}>
        <Portal mount={document.body}>
          <div
            id={id}
            role="tooltip"
            class="kui-tooltip"
            ref={(el) => {
              bubble = el;
              place();
              // Layout has not happened yet at ref time. One frame later it has.
              requestAnimationFrame(place);
            }}
            style={{ left: `${pos().left}px`, top: `${pos().top}px` }}
          >
            {props.content}
            <Show when={props.code}>{(code) => <code class="kui-tooltip__code">{code()}</code>}</Show>
          </div>
        </Portal>
      </Show>
    </>
  );
}
