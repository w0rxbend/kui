import { createSignal, flush } from "solid-js";

/**
 * The part of `localStorage` this module needs, so that a test can supply its own and a browser
 * that has none is an ordinary case rather than a crash.
 */
export interface PreferenceStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export interface RootPreferenceOptions<A extends string> {
  /** The attribute the stylesheet keys off, for example `data-accent`. */
  readonly attribute: string;
  /** The `localStorage` key. Namespaced, because a KUI deployment may share an origin. */
  readonly storageKey: string;
  /** Every value the preference can take. Anything else read back from storage is ignored. */
  readonly values: readonly A[];
  /** What an unset, unreadable or unrecognised preference means. */
  readonly fallback: A;
  /**
   * The attribute value to write for a choice, or `null` to remove the attribute entirely.
   *
   * A choice whose values are the ones plain `:root` already declares maps to `null`, so the
   * default writes no attribute at all rather than one the stylesheet would then have to match.
   */
  readonly attributeValue: (chosen: A) => string | null;
  /**
   * Where the choice is remembered. Defaults to `localStorage` when there is one. Pass `null` for
   * a preference that deliberately does not persist.
   */
  readonly storage?: PreferenceStorage | null;
  /** The element the attribute is written on. `<html>` in the application. */
  readonly root?: Element;
}

export interface RootPreference<A extends string> {
  /** What the user asked for. A signal, so anything that displays the choice follows it. */
  readonly choice: () => A;
  /** Records a new choice: remembers it, and repaints by writing the attribute. */
  readonly select: (chosen: A) => void;
  /** Writes the current choice onto the root element. Called once during start-up. */
  readonly install: () => void;
}

/**
 * A user preference whose entire effect is one attribute on the `<html>` element.
 *
 * ## The mechanism, in one sentence
 *
 * TypeScript writes an attribute and the stylesheet does everything else. No colour, no length and
 * no font size is ever computed here: `10-tokens.css` declares the light palette on `:root`, the
 * dark palette twice (once under `prefers-color-scheme` for the system preference and once under
 * `:root[data-theme="dark"]` for an explicit choice), the three non-default accent palettes under
 * `[data-accent="…"]`, and the compact row padding under `[data-density="compact"]`. Every one of
 * those is a selector on the root element, so changing one attribute repaints the product.
 *
 * That is the whole reason this survived the move from Scala.js to TypeScript unchanged in
 * substance: there was nothing framework-specific in it to lose.
 *
 * ## Why the attribute is written from `select`, not from an effect
 *
 * Solid 2 batches updates into a microtask, and writing to a signal from inside an owned
 * computation throws in development, so an effect is the wrong instrument here twice over: it
 * would paint the new theme a microtask after the click, and it would have to be owned by
 * something. `select` is called from event handlers, which is where writes belong, so it does the
 * whole job there — set the signal, flush the queue so every reader agrees immediately, remember
 * the choice, write the attribute.
 *
 * ## Storage that is allowed to fail
 *
 * Safari in a private window throws on the first write, and enterprise policy can disable storage
 * outright. Every access is guarded, and a browser that refuses gives a working switcher that
 * forgets the choice on reload, rather than an application that fails to start. Reading back a
 * value this version does not recognise is an ordinary case too: `localStorage` outlives upgrades,
 * so a value written by a later KUI — or typed into the developer console — can be read by an
 * earlier one, and falling back to the default beats failing.
 */
export function createRootPreference<A extends string>(
  options: RootPreferenceOptions<A>,
): RootPreference<A> {
  const storage =
    options.storage === undefined ? defaultStorage() : options.storage;
  const root = options.root ?? documentRoot();

  // `createSignal`'s first overload asks for `Exclude<A, Function>`, because a bare function
  // argument means the *derived* form of the primitive rather than an initial value. `A extends
  // string`, so a function can never be one of its members and the two types are the same set —
  // TypeScript just cannot prove that for an unresolved type parameter. The assertion says so.
  const [choice, setChoice] = createSignal<A>(
    restore(storage, options) as Exclude<A, Function>,
  );

  const paint = (chosen: A): void => {
    if (root === undefined) return;
    const value = options.attributeValue(chosen);
    if (value === null) root.removeAttribute(options.attribute);
    else root.setAttribute(options.attribute, value);
  };

  return {
    choice,
    select(chosen: A): void {
      setChoice(() => chosen);
      // Solid 2 queues signal updates into a microtask, so without this `choice()` would still
      // return the previous value for the rest of the click — and a control that reads the theme
      // to decide what to draw would draw the old one. `flush` applies the queue synchronously,
      // which is what a direct user action wants: by the time the handler returns, the preference
      // has changed everywhere. It is called here rather than left to callers because forgetting
      // it produces a bug that looks like a rendering glitch rather than like a stale read.
      flush();
      remember(storage, options.storageKey, chosen);
      paint(chosen);
    },
    install(): void {
      paint(choice());
    },
  };
}

function restore<A extends string>(
  storage: PreferenceStorage | null,
  options: RootPreferenceOptions<A>,
): A {
  if (storage === null) return options.fallback;
  let raw: string | null = null;
  try {
    raw = storage.getItem(options.storageKey);
  } catch {
    // A browser that refuses to be read is a browser with no remembered preference.
    return options.fallback;
  }
  return options.values.find((value) => value === raw) ?? options.fallback;
}

function remember(
  storage: PreferenceStorage | null,
  key: string,
  value: string,
): void {
  if (storage === null) return;
  try {
    storage.setItem(key, value);
  } catch {
    // Nothing to do and nothing to report: the choice still applies to this page, it just will not
    // survive a reload. Failing the click would be a worse answer than forgetting.
  }
}

function defaultStorage(): PreferenceStorage | null {
  try {
    return typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    // Merely *touching* `localStorage` throws where storage is blocked by policy.
    return null;
  }
}

function documentRoot(): Element | undefined {
  return typeof document === "undefined" ? undefined : document.documentElement;
}
