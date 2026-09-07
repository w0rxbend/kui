import { For, Show, createSignal, createUniqueId } from "solid-js";
import { Icon } from "@kui/kernel";

/**
 * The top bar's search field, and the results overlay it opens.
 *
 * ## The field is built once and never rebuilt
 *
 * Everything that changes about this component — the results, the hint, the error row — lives
 * *outside* the `<input>`. The input itself is created when the component is created and is never
 * inside a conditional, because a conditional that re-creates it while somebody is typing throws
 * away the caret position, the composition state of an input-method editor, and the text itself.
 * That defect has been paid for once in this product already, in the produce-message drawer. There
 * is a test that asserts the input node's identity survives a results update, and it is there to
 * fail if somebody later wraps this in a `<Show>`.
 *
 * ## The keyboard hint is not decoration
 *
 * The hint reads `⌘K` on Apple platforms and `Ctrl K` everywhere else. Showing a Mac glyph to a
 * Linux operator teaches them a shortcut that does not exist, and this is a product whose users are
 * overwhelmingly on Linux. The platform is detected once and can be overridden by a prop, which is
 * how the stories show both without pretending to be a different machine.
 *
 * ## When search is broken, the box still works
 *
 * If the search service is unavailable, the field stays enabled and typing produces a single row
 * saying so, with a retry. Disabling the box would teach the operator that the shortcut is broken
 * and they would stop reaching for it; a box that explains itself is a box they will try again.
 *
 * ## Half an answer says which half is missing
 *
 * The search is a fold at the gateway over three services, and one of them not being routed is a
 * *normal* deployment rather than a failure — the distributed stack has no schema service. So the
 * overlay can hold results and a line naming what was not asked at the same time, which is what
 * {@link SearchFieldProps.unavailable} is for. Dropping the line and showing the two lists that did
 * answer would tell an operator their subject does not exist, and there is no way to tell that
 * answer from a true one.
 *
 * ## The listbox is the results element, not the panel
 *
 * `role="listbox"` sits on the element that holds the option rows and nothing else. It used to be
 * on the whole overlay, which was fine while the overlay held only rows and stops being fine the
 * moment there is a sentence beside them: a listbox whose children are not options is what
 * `aria-required-children` reports, and it leaves a screen-reader user with a list box that
 * announces a count that does not match what is in it.
 */
export type SearchResultGroup = {
  readonly heading: string;
  readonly items: readonly SearchResult[];
};

export type SearchResult = {
  readonly id: string;
  readonly label: string;
  readonly href: string;
  /** Optional second line: the topic a group belongs to, the address of a broker. */
  readonly detail?: string | undefined;
};

export type SearchFieldProps = {
  readonly value: string;
  readonly onInput: (value: string) => void;
  readonly placeholder?: string | undefined;
  /**
   * The longest query the box accepts, when the caller has a limit.
   *
   * The shell passes the search endpoint's own maximum. It is a `maxlength` on the input rather
   * than a validation message because the endpoint answers a longer `q` with a 400, and the only
   * failure this overlay can draw says "search is not answering" — which is a sentence that sends
   * somebody to look at a gateway that is working.
   */
  readonly maxLength?: number | undefined;
  /**
   * What to show under the field once it is focused and has text. `undefined` means "nothing has
   * been asked for yet"; an empty array means "we asked and there is nothing", and those are
   * different pictures — see the `status` prop.
   */
  readonly results?: readonly SearchResultGroup[] | undefined;
  /**
   * Which of the four things is true right now. "searching" draws skeleton rows, "empty" says so in
   * words, "failed" offers a retry. An empty region on its own is ambiguous — it could mean there is
   * nothing here, or that your filter matched nothing, or that the request failed and nobody said
   * so — and each of those wants a different next action.
   */
  readonly status?: "idle" | "searching" | "ready" | "empty" | "failed" | undefined;
  /**
   * The services that could not be asked, in words — "Schema Registry", not "schema".
   *
   * Reported rather than omitted, and reported *beside* the results rather than instead of them:
   * the two lists that answered are still worth showing, and a reader who searched for a subject
   * has to know that the registry was not among them. Empty or absent means everybody answered.
   */
  readonly unavailable?: readonly string[] | undefined;
  readonly onRetry?: (() => void) | undefined;
  /** Overrides platform detection. Stories set it; the product does not. */
  readonly platform?: "apple" | "other" | undefined;
  /**
   * Hands the input element to the caller, so that something outside this component can focus it.
   *
   * This exists for exactly one caller: the application frame, which binds the `⌘K` the hint above
   * advertises. Focusing by `document.querySelector` instead would work and would also mean the
   * shortcut silently stops working the day this markup changes, with no test and no compile error
   * to notice — a shortcut that fails quietly is the thing this prop is here to prevent.
   */
  readonly inputRef?: ((el: HTMLInputElement) => void) | undefined;
};

/**
 * How long the overlay stays open after the field loses focus, in milliseconds.
 *
 * A pointer press on a result focuses the link, which blurs the input, which closes the panel — and
 * the `click` only arrives after the button comes back up. Closing on the blur itself therefore
 * removes the row from under the cursor before it can be clicked, and every result in the overlay
 * becomes unclickable while looking perfectly normal. The grace period is what lets the click land.
 *
 * Named rather than typed into the handler so the case that pins the rule can say what it is
 * waiting for, and so the number is deletable only by deleting the rule.
 */
export const RESULT_CLICK_GRACE_MS = 120;

/** `⌘K` on Apple platforms, `Ctrl K` everywhere else. */
export function shortcutHint(platform: "apple" | "other"): string {
  return platform === "apple" ? "⌘K" : "Ctrl K";
}

/** Best-effort platform detection. `navigator.platform` is deprecated, so the modern hint is tried
 * first and the old one is the fallback; when neither answers we assume not-Apple, because being
 * wrong that way shows a shortcut that reads correctly on any keyboard. */
export function detectPlatform(): "apple" | "other" {
  if (typeof navigator === "undefined") return "other";
  const modern = (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData?.platform;
  const legacy = navigator.platform;
  const value = `${modern ?? ""} ${legacy ?? ""}`.toLowerCase();
  return value.includes("mac") || value.includes("iphone") || value.includes("ipad") ? "apple" : "other";
}

export function SearchField(props: SearchFieldProps) {
  const id = createUniqueId();
  const listboxId = `kui-global-search-results-${id}`;
  const [focused, setFocused] = createSignal(false);

  const platform = () => props.platform ?? detectPlatform();
  const open = () => focused() && props.value.length > 0;
  const status = () => props.status ?? "idle";

  return (
    <div class="kui-global-search" data-testid="search">
      {/* A real label, visually hidden. A placeholder is not a label: it disappears the moment
          there is text, which is exactly when somebody re-reading the page needs to know what the
          box was for. */}
      <label class="kui-visually-hidden" for={`kui-global-search-input-${id}`}>
        Search topics, groups, anything
      </label>
      <div class="kui-global-search__field">
        <Icon name="search" size="16px" class="kui-global-search__icon" />
        <input
          /* A ref callback, which is the only ref shape Solid 2 has. It is passed straight through
             and is allowed to be absent, so a story or a test that does not care omits it. */
          ref={(el: HTMLInputElement) => props.inputRef?.(el)}
          id={`kui-global-search-input-${id}`}
          class="kui-global-search__input"
          type="search"
          autocomplete="off"
          spellcheck={false}
          maxlength={props.maxLength}
          placeholder={props.placeholder ?? "Search topics, groups, anything…"}
          value={props.value}
          role="combobox"
          /* A string, not a boolean. In Solid 2 a boolean attribute value means presence or
           * absence, so `false` would *remove* aria-expanded — and a combobox without it is
           * announced as a plain text field with no menu. ARIA states are strings. */
          aria-expanded={open() ? "true" : "false"}
          aria-controls={listboxId}
          aria-autocomplete="list"
          onInput={(event) => props.onInput(event.currentTarget.value)}
          onFocus={() => setFocused(true)}
          /* Deferred, and by {@link RESULT_CLICK_GRACE_MS} rather than by a frame: the click on a
             result has to be processed before the overlay is removed from under it. Closing on the
             blur is the classic version of this bug. */
          onBlur={() => window.setTimeout(() => setFocused(false), RESULT_CLICK_GRACE_MS)}
          onKeyDown={(event) => {
            if (event.key === "Escape") setFocused(false);
          }}
          data-testid="search-input"
        />
        {/* The hint hides on focus: once you are in the box you do not need to be told how to get
            into the box, and the space is better spent on the text you are typing. */}
        <Show when={!focused()}>
          <span class="kui-global-search__hint" aria-hidden="true">
            {shortcutHint(platform())}
          </span>
        </Show>
      </div>

      <div
        class={["kui-global-search__results", { "kui-global-search__results--open": open() }]}
        hidden={!open()}
      >
        <Show when={status() === "failed"}>
          <div class="kui-global-search__message kui-global-search__message--failed" role="alert">
            <span>Search is not answering right now.</span>
            <button type="button" class="kui-global-search__retry" onClick={() => props.onRetry?.()}>
              Try again
            </button>
          </div>
        </Show>
        <Show when={status() === "searching"}>
          {/* Skeleton rows, not a spinner: a skeleton at the size of the thing it stands in for says
              "results are coming and they will be about this big", and it does not look like the
              empty state. */}
          <div class="kui-global-search__message" aria-live="polite">
            <span class="kui-visually-hidden">Searching</span>
            <span class="kui-skeleton kui-global-search__skeleton" aria-hidden="true" />
            <span class="kui-skeleton kui-global-search__skeleton" aria-hidden="true" />
          </div>
        </Show>
        <Show when={status() === "empty"}>
          <p class="kui-global-search__message" role="status">
            Nothing matches “{props.value}”.
          </p>
        </Show>
        {/* Always present, so that the `aria-controls` above never points at an element that is not
            in the document. The listbox *role* is claimed only when there are options to put in it:
            a listbox whose children are a spinner or a sentence is a lie to the accessibility tree,
            and it is the kind of lie that makes a screen reader announce "list box, zero items"
            over a panel that plainly says why it is empty. */}
        {/* A plain block with no styling of its own: the overlay above is the box, and this exists
            only to draw the boundary the accessibility tree needs. */}
        <div
          id={listboxId}
          role={status() === "ready" ? "listbox" : undefined}
          /* Named only while it is a listbox. `aria-label` on a plain `div` is
             `aria-prohibited-attr`: an element with no role has nothing for a name to be the name
             of, and axe reports it as a violation rather than as a nicety. */
          aria-label={status() === "ready" ? "Search results" : undefined}
        >
          <Show when={status() === "ready"}>
            <For each={props.results ?? []}>
              {(group) => (
                <div class="kui-global-search__group" role="group" aria-label={group.heading}>
                  <p class="kui-global-search__group-heading">{group.heading}</p>
                  <For each={group.items}>
                    {(item) => (
                      <a
                        class="kui-global-search__result"
                        href={item.href}
                        role="option"
                        aria-selected="false"
                      >
                        <span class="kui-global-search__result-label">{item.label}</span>
                        <Show when={item.detail}>
                          <span class="kui-global-search__result-detail">{item.detail}</span>
                        </Show>
                      </a>
                    )}
                  </For>
                </div>
              )}
            </For>
          </Show>
        </div>

        {/* Outside the listbox, for the reason the header gives, and outside the `ready` guard as
            well: a search that reached nobody at all still has to say who was not reached. */}
        <Show when={(props.unavailable ?? []).length > 0}>
          <p class="kui-global-search__partial" role="status">
            Not searched: {(props.unavailable ?? []).join(", ")}. Results from{" "}
            {(props.unavailable ?? []).length === 1 ? "it" : "them"} are missing, not empty.
          </p>
        </Show>
      </div>
    </div>
  );
}
