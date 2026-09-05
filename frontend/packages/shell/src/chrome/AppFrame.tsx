/**
 * The application frame: the rail, the drawer, the top band and the one region that scrolls.
 *
 * ## Why this is a component and not markup inside `App`
 *
 * It was markup inside `App`, and that made the frame the one part of the product with no story.
 * Everything it is responsible for — the seam between the rail and the drawer, what happens to the
 * layout at 900px, whether the top band stays put when a page scrolls, whether the drawer keeps its
 * foot when a deployment has thirty destinations — is a question about *arrangement*, and
 * arrangement is exactly what a unit test in jsdom cannot answer and a story can.
 *
 * Taking slots rather than data is what makes that possible: the frame knows nothing about
 * clusters, capabilities or routes, so a story can fill it with fixtures and a test can fill it with
 * nothing at all.
 *
 * ## The frame always renders
 *
 * This is the rule the whole shell is built around and it belongs here, at the top of the thing that
 * enforces it. If the gateway's capability call fails, the frame still draws and every destination
 * inside it takes its own unavailable rendering. The shell never becomes a blank page because a
 * service is down — a blank page tells an operator nothing, and the frame is what carries the
 * navigation that gets them somewhere that does work.
 *
 * ## The skip link is first, and it stays first
 *
 * It is the first focusable element in the document. Without it a keyboard user tabs through the
 * rail and every navigation entry before reaching the page content — on every page, every time. It
 * is invisible until focused, which is why it costs sighted users nothing, and which is also why it
 * must not be moved down the file "because it is invisible anyway".
 */
import type { JSX } from "@solidjs/web";

export type AppFrameProps = {
  /** The environment rail: 48px, on the page ground, full height. */
  readonly rail: JSX.Element;
  /** The navigation drawer: 182px, raised. */
  readonly drawer: JSX.Element;
  /** The top band: 58px, no fill. */
  readonly topbar: JSX.Element;
  readonly children: JSX.Element;
};

export function AppFrame(props: AppFrameProps): JSX.Element {
  return (
    <div class="kui-frame">
      <a class="kui-shell__skip" href="#kui-content">
        Skip to content
      </a>

      <div class="kui-frame__rail">{props.rail}</div>
      <div class="kui-frame__drawer">{props.drawer}</div>
      <div class="kui-frame__topbar">{props.topbar}</div>

      <main
        id="kui-content"
        class="kui-frame__content"
        /* `0`, not `-1`.
         *
         * `-1` is the usual skip-link pattern: focusable programmatically, not in the tab order.
         * It is wrong for *this* element because this element is also the page's scroll container,
         * and a scroll container outside the tab order cannot be scrolled from the keyboard at all.
         * On a page whose content is all focusable that goes unnoticed, because tabbing through the
         * links scrolls it; on a long page of text and tables — a partition list, a schema — a
         * keyboard user simply cannot reach the bottom.
         *
         * The cost is one extra tab stop per page. The benefit is that the page can be read without
         * a mouse. axe reports the `-1` version as `scrollable-region-focusable`, and it is right.
         */
        tabindex={0}
      >
        {props.children}
      </main>
    </div>
  );
}
