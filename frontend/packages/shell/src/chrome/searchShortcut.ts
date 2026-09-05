/**
 * The keyboard shortcut the search field advertises.
 *
 * ## Why this exists at all
 *
 * `SearchField` draws a `⌘K` hint in its trailing corner, and before this file nothing anywhere in
 * the application listened for that key. The hint was decoration. A control that advertises a
 * shortcut it does not implement is worse than one that says nothing: the reader tries it, nothing
 * happens, and what they learn is not "that shortcut is missing" but "keyboard shortcuts in this
 * product do not work", which they then stop trying. The hint is a promise, and this is the promise
 * being kept.
 *
 * ## Why the matching is a separate, pure function
 *
 * {@link matchesSearchShortcut} takes the four fields of a keyboard event it cares about rather than
 * an event, so every branch below — the modifier, the wrong modifier, the key already being typed
 * into a text box — is testable without a DOM and without synthesising events. The listener that
 * wraps it is then four lines with nothing in it worth testing.
 */

/** The parts of a key press this decision is made from. */
export interface KeyStroke {
  readonly key: string;
  /** The Command key on Apple keyboards. */
  readonly metaKey: boolean;
  readonly ctrlKey: boolean;
  readonly altKey: boolean;
}

/**
 * Whether this key press is the search shortcut.
 *
 * Both `⌘K` and `Ctrl K` are accepted regardless of platform, deliberately. The *hint* is
 * platform-specific because it has to name one key and naming the wrong one is confusing; the
 * *binding* is not, because a person on a Mac with an external PC keyboard, or on Linux inside a
 * VM, will press whichever one their fingers know, and refusing the other one gains nothing.
 *
 * `altKey` is rejected: `⌥⌘K` and `Ctrl Alt K` are distinct chords that other software binds, and
 * swallowing them would make this shortcut shadow somebody else's.
 *
 * The key is compared case-insensitively because `event.key` is `"K"` when Shift is held, and a
 * user with Caps Lock on is not asking for different behaviour.
 */
export function matchesSearchShortcut(stroke: KeyStroke): boolean {
  if (stroke.altKey) return false;
  if (!stroke.metaKey && !stroke.ctrlKey) return false;
  return stroke.key.toLowerCase() === "k";
}

/**
 * Whether the key press happened somewhere that a plain keystroke belongs to the user's typing.
 *
 * This guard is *not* applied to the shortcut itself — `⌘K` while typing in a filter box is still a
 * request to search, and that is what every editor and browser does — but it is exported because
 * the same question is asked by any future single-letter binding, and the wrong answer there is the
 * classic bug where pressing `t` inside a text field navigates to Topics and eats the letter.
 */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (target === null || !(target instanceof Element)) return false;
  const tag = target.tagName.toLowerCase();
  if (tag === "input" || tag === "textarea" || tag === "select") return true;
  return target.getAttribute("contenteditable") === "" || target.getAttribute("contenteditable") === "true";
}

/**
 * Installs the shortcut, and returns the function that removes it.
 *
 * The return-a-cleanup shape is what `onSettled` wants, so the caller is one line and cannot forget
 * to unbind — a listener left on `document` after the frame unmounts would keep a reference to a
 * disposed component and keep stealing the key from whatever replaced it.
 *
 * It listens on `document` rather than on the frame element. A shortcut bound to a subtree only
 * fires when focus is inside that subtree, which excludes exactly the case the shortcut is for:
 * focus on the page body, or in a dialog rendered through a portal, with the user wanting to get to
 * search from wherever they are.
 */
export function installSearchShortcut(
  focusSearch: () => void,
  target: Pick<Document, "addEventListener" | "removeEventListener"> = document,
): () => void {
  const listener = (event: Event): void => {
    const key = event as KeyboardEvent;
    if (!matchesSearchShortcut(key)) return;
    // The browser's own "search this page" and Firefox's `⌘K` address-bar focus are both bound to
    // this chord. Inside an application the page's search is the one the user meant, and taking it
    // is the convention every web application with a `⌘K` hint follows.
    event.preventDefault();
    focusSearch();
  };

  target.addEventListener("keydown", listener);
  return () => target.removeEventListener("keydown", listener);
}
