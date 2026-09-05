/**
 * Observe an element's size for as long as the component lives.
 *
 * ## Why this is not `el.getBoundingClientRect()` at mount
 *
 * This is the defect the brief names first, and it was found in the virtualized table: measuring
 * once at mount drew five rows for a twelve-partition topic. At the moment a component's setup
 * code runs the element is in the document but the browser has not necessarily laid it out, so
 * the rectangle it reports can be 0×0 or a stale intermediate. Even when the first measurement is
 * right it stops being right the moment a drawer opens, a window is resized, or a font finishes
 * loading and reflows the card the chart sits in.
 *
 * A chart drawn from a single stale measurement fails in a way nobody reports: the SVG simply
 * does not fill its card, which reads as "that panel is a bit small" rather than as a bug.
 *
 * So the size is *observed*, not sampled, for the lifetime of the component.
 *
 * ## The Solid 2 details that matter here
 *
 * - Setup goes in `onSettled`, which is 2.0's replacement for `onMount`, and the cleanup is the
 *   function it returns rather than a separate `onCleanup` call.
 * - Writing a signal from inside an owned scope throws in dev. A `ResizeObserver` callback is
 *   not an owned scope — it is a browser callback — so the writes below are legal where the same
 *   write in a component body or a memo would not be.
 * - Solid 2 batches updates on a microtask, so `setSize(...)` followed by `size()` returns the
 *   *old* value until the queue flushes. Nothing here reads back what it just wrote; the chart
 *   geometry is derived reactively from the signal instead, which is the only shape of this code
 *   that is correct without anyone having to remember the rule.
 */
import { createSignal, onSettled, type Accessor } from "solid-js";

export interface ElementSize {
  readonly width: number;
  readonly height: number;
}

export interface UseElementSize {
  /** Attach to the element to measure: `<div ref={size.ref}>`. */
  readonly ref: (el: HTMLElement) => void;
  /** The last observed content-box size. Starts at the fallback until the first observation. */
  readonly size: Accessor<ElementSize>;
  /** False until the element has actually been measured, so callers can hold off drawing. */
  readonly measured: Accessor<boolean>;
}

/**
 * @param fallback the size to report before the first observation, and in environments with no
 *        `ResizeObserver` at all (jsdom, and any server-side render). A chart that draws itself
 *        at a sensible default and then corrects is better than one that draws nothing.
 */
export function useElementSize(fallback: ElementSize = { width: 640, height: 220 }): UseElementSize {
  const [size, setSize] = createSignal<ElementSize>(fallback);
  const [measured, setMeasured] = createSignal(false);
  let element: HTMLElement | undefined;

  const ref = (el: HTMLElement): void => {
    element = el;
  };

  onSettled(() => {
    const el = element;
    if (!el) return undefined;

    // jsdom has no ResizeObserver. Fall back to one direct measurement rather than throwing:
    // the tests that run there assert on structure and semantics, not on pixel geometry.
    if (typeof ResizeObserver === "undefined") {
      const rect = el.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        setSize({ width: rect.width, height: rect.height });
        setMeasured(true);
      }
      return undefined;
    }

    const observer = new ResizeObserver(entries => {
      const entry = entries[entries.length - 1];
      if (!entry) return;
      // `contentRect` is the content box — padding and border excluded — which is what the plot
      // is drawn into. Zero-sized observations happen while an element is display:none and must
      // not be committed, or a chart in a hidden tab comes back with no geometry.
      const { width, height } = entry.contentRect;
      if (width <= 0 || height <= 0) return;
      setSize({ width, height });
      setMeasured(true);
    });

    observer.observe(el);
    return () => observer.disconnect();
  });

  return { ref, size, measured };
}
