/**
 * The dialog's veil, and the one property another package's destructive confirmation depends on.
 *
 * ## Why this is a file of its own rather than five more lines in `surfaces.test.tsx`
 *
 * `surfaces.test.tsx` owns the nine surfaces between them and asks of the dialog what every
 * surface is asked: that it opens, names itself, traps focus and closes. `closeOnScrimClick` is
 * not that kind of question. It is a **contract other packages opt into** — `feature-ksql`'s
 * `ConfirmStatement` sets `closeOnScrimClick={false}` and `feature-topics`' delete dialogue gets
 * the same behaviour through `ConfirmDialog` — and the thing it protects is one stray click
 * between a typed statement and a deleted Kafka topic. A property with callers in other packages
 * is worth a file that says so in its name, so that the next person who reads the veil handler
 * finds the cases by looking for them.
 *
 * ## The rule this file owns, and where the product applies it
 *
 * **A dialogue that sets `closeOnScrimClick={false}` cannot be dismissed by a click on the veil,
 * and still has its other two ways out.** `Dialog.tsx` is where the product applies it: it decides
 * whether the caller's opt-out reaches `scrimClickHandler` at all. `overlay.ts`'s handler is the
 * mechanism; `Dialog`'s prop plumbing is the part with a caller, and it is the part that had no
 * case. Deleting the conditional spread that forwards the prop — so that every dialogue keeps the
 * closing default — leaves `scrimClickHandler`'s own behaviour untouched and, measured, **467
 * kernel cases over 23 files green**: two of the three cases below are the only things in this
 * package that go red.
 *
 * W9-A1 found the hole and could not close it from where it was standing: a case that drove the
 * veil through `feature-ksql`'s screen harness stayed green under the mutation, so it was deleted
 * rather than shipped as a decoy. That was the right call, and this is the suite it named.
 *
 * ## The click is dispatched, not `userEvent.click`ed
 *
 * `userEvent` decides where a click lands from layout, and jsdom has none — so it never reaches an
 * element that covers the window, and a veil case written with it passes by never running the
 * handler. Every click below is the `MouseEvent` the browser would deliver, on the element it
 * would deliver it to. The same note sits on `surfaces.test.tsx`'s veil case, for the same reason.
 */

import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";

import { ConfirmDialog, Dialog } from "./Dialog.jsx";
import { mount } from "./testing.js";

/** A real click on a real element, bubbling, the way the browser delivers one. */
function clickOn(element: HTMLElement): void {
  element.dispatchEvent(new MouseEvent("click", { bubbles: true }));
}

const disposers: (() => void)[] = [];

/** Mounts and registers the teardown, so that a case which fails early still tears down. */
function render(component: Parameters<typeof mount>[0]): void {
  disposers.push(mount(component).dispose);
}

function scrim(): HTMLElement {
  const found = document.querySelector(".kui-modal-scrim");
  if (found === null) throw new Error("the dialog drew no veil");
  return found as HTMLElement;
}

afterEach(() => {
  /*
   * Torn down here rather than at the end of each case, and that is a correction rather than a
   * style. Every surface here is portalled onto `<body>`, so a case that fails before its own
   * `dispose()` leaves a live veil in the document — and the next case's `document.querySelector`
   * then finds the *previous* case's veil, drives it, and passes while asserting nothing about
   * itself. Measured: under the mutation this file exists for, the third case passed in a
   * whole-file run and failed on its own.
   */
  while (disposers.length > 0) disposers.pop()?.();
  expect(document.querySelectorAll(".kui-modal-scrim")).toHaveLength(0);
  // A case that mounts a modal surface has to hand the page back unlocked, or the next case
  // inherits a `<body>` nothing in it opened.
  document.body.style.overflow = "";
});

describe("the dialog's veil", () => {
  it("closes the dialogue when nothing was said about the veil", async () => {
    /*
     * The default, stated here because the opt-out below is only meaningful against it. A dialogue
     * that holds nothing the reader typed is dismissable by clicking away from it, which is what
     * people expect of a modal and what the drawer deliberately does not do.
     */
    let closed = 0;
    render(() => (
      <Dialog open onClose={() => (closed += 1)} title="Create topic">
        <p data-testid="body">body</p>
      </Dialog>
    ));
    await Promise.resolve();

    clickOn(scrim());
    expect(closed, "a dialogue with no opt-out ignored a click on its veil").toBe(1);
  });

  it("ignores the veil when the caller opted out, and keeps the other two ways out", async () => {
    /*
     * The owned rule. `feature-ksql`'s `ConfirmStatement` and every `ConfirmDialog` in this
     * product are asking for exactly this, and what they are protecting is a statement somebody
     * typed and a confirmation they are still reading in order to decide.
     *
     * The second half matters as much as the first: an opt-out implemented by disabling the veil
     * handler *and* the escape key would satisfy the first assertion and leave a dialogue with one
     * way out. So the close button and `Escape` are pressed here too, and both must still work.
     */
    const user = userEvent.setup();
    let closed = 0;
    render(() => (
      <Dialog
        open
        onClose={() => (closed += 1)}
        title="Run this statement?"
        closeOnScrimClick={false}
      >
        <p data-testid="body">DROP STREAM ORDERS DELETE TOPIC;</p>
      </Dialog>
    ));
    await Promise.resolve();

    clickOn(scrim());
    expect(closed, "a stray click on the veil dismissed a dialogue that opted out of that").toBe(0);

    // Clicking the surface itself never closed anything either — a click that landed inside and
    // bubbled out to the veil must not be read as a click on the veil.
    clickOn(document.querySelector('[data-testid="body"]') as HTMLElement);
    expect(closed).toBe(0);

    await user.keyboard("{Escape}");
    expect(closed, "Escape is a deliberate act and still closes the dialogue").toBe(1);

    (document.querySelector(".kui-modal__close") as HTMLElement).click();
    expect(closed, "the close button is the other way out and still closes the dialogue").toBe(2);
  });

  it("holds the confirmation's veil shut, where the cost of losing it is paid", async () => {
    /*
     * `ConfirmDialog` sets the opt-out for every destructive confirmation this product ships, and
     * it sets it for a reason the generic dialogue cannot state: the veil is not an answer to a
     * question, and the box below the question usually holds a topic name somebody has half typed.
     *
     * Asserted through `ConfirmDialog` rather than by reading its props, because the prop is only
     * a decision — this is the dialogue actually refusing the click, with the typed text still in
     * the box afterwards.
     */
    let closed = 0;
    let confirmed = 0;
    render(() => (
      <ConfirmDialog
        open
        onClose={() => (closed += 1)}
        onConfirm={() => (confirmed += 1)}
        title="Delete orders.payments.v2?"
        consequence={
          "This deletes 1,536 partitions' worth of records — about 128 GB. It cannot be undone."
        }
        confirmLabel="Delete topic"
        confirmIcon="trash"
        typeToConfirm="orders.payments.v2"
      />
    ));
    await Promise.resolve();

    const typed = document.querySelector(".kui-confirm__input") as HTMLInputElement;
    typed.value = "orders.payments";
    typed.dispatchEvent(new Event("input", { bubbles: true }));

    clickOn(scrim());
    expect(closed, "a stray click on the veil threw away a half-typed confirmation").toBe(0);
    expect(confirmed).toBe(0);
    expect(
      (document.querySelector(".kui-confirm__input") as HTMLInputElement).value,
      "the dialogue survived the click but the text in it did not",
    ).toBe("orders.payments");
  });
});
