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
          /* The blur is deferred by a frame so that a click landing on a result row is processed
             before the overlay is removed; closing on the mousedown would make every result
             unclickable, which is the classic version of this bug. */
          onBlur={() => window.setTimeout(() => setFocused(false), 120)}
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
        id={listboxId}
        /* The listbox role is claimed only when there are options to put in it. A listbox whose
         * children are a spinner or a sentence is a lie to the accessibility tree, and it is the
         * kind of lie that makes a screen reader announce "list box, zero items" over a panel that
         * plainly says why it is empty. */
        role={status() === "ready" ? "listbox" : undefined}
        aria-label="Search results"
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
        <Show when={status() === "ready"}>
          <For each={props.results ?? []}>
            {(group) => (
              <div class="kui-global-search__group" role="group" aria-label={group.heading}>
                <p class="kui-global-search__group-heading">{group.heading}</p>
                <For each={group.items}>
                  {(item) => (
                    <a class="kui-global-search__result" href={item.href} role="option" aria-selected="false">
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
    </div>
  );
}
