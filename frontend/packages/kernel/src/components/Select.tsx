/**
 * A select the product draws itself.
 *
 * ## Why not `<select>`
 *
 * The native control cannot be styled to match this design in any browser, and — more to the
 * point — its dropdown is drawn by the operating system in the operating system's palette, so a
 * dark KUI opens a light menu over itself. The seek and partition controls in the screenshots are
 * custom listboxes for exactly that reason (SPEC §4.16).
 *
 * What a custom control owes in exchange is everything the native one gave away for free, and the
 * list is longer than people expect: an accessible role, a name, the arrow keys, `Home` and `End`,
 * type-ahead, `Escape` returning focus to where it came from, and a focus ring you can actually
 * see. All of it is below. A drawn select that does not do these is not a select, it is a picture
 * of one — and this project has already shipped controls that were correct in the accessibility
 * tree and invisible to everyone else, so both halves are checked here.
 *
 * ## Focus stays on the trigger
 *
 * The listbox is navigated with `aria-activedescendant` rather than by moving DOM focus into it.
 * Moving focus into a floating list means every close path has to put focus back, and the one
 * path somebody forgets — clicking away, the list re-rendering, `Escape` during a re-render —
 * drops focus onto `<body>`, where the next `Tab` starts from the top of the page. Keeping focus
 * on the trigger makes that unrepresentable.
 */
import type { JSX } from "@solidjs/web";
import { For, Show, createSignal, createUniqueId, merge, onSettled } from "solid-js";
import { Icon } from "../icon.jsx";

export interface SelectOption<T extends string> {
  readonly value: T;
  readonly label: string;
  readonly disabled?: boolean | undefined;
}

export interface SelectProps<T extends string> {
  /** The control's name. Rendered above it, or visually hidden when the prefix already says it. */
  readonly label: string;
  readonly labelHidden?: boolean | undefined;
  /** A quiet in-control prefix: `Seek:`, `Partitions:`. Decoration for the label, not a substitute. */
  readonly prefix?: string | undefined;
  readonly options: readonly SelectOption<T>[];
  readonly value?: T | undefined;
  readonly placeholder?: string | undefined;
  readonly disabled?: boolean | undefined;
  /** Why it is disabled. A dead control with no explanation is worse than no control. */
  readonly disabledReason?: string | undefined;
  readonly size?: "sm" | "md" | undefined;
  /** What to say when there is nothing to choose from. Never an empty menu. */
  readonly emptyMessage?: string | undefined;
  readonly class?: string | undefined;
  readonly onChange?: (value: T) => void;
}

export function Select<T extends string>(props: SelectProps<T>): JSX.Element {
  const uid = createUniqueId();
  const labelId = `kui-select-label-${uid}`;
  const listId = `kui-select-list-${uid}`;
  const optionId = (index: number): string => `kui-select-opt-${uid}-${index}`;

  const [open, setOpen] = createSignal(false, { ownedWrite: true });
  const [active, setActive] = createSignal(-1, { ownedWrite: true });
  const p = merge({ size: "md", emptyMessage: "Nothing to choose from." } as const, props);

  let root: HTMLDivElement | undefined;
  let trigger: HTMLButtonElement | undefined;
  let typed = "";
  let typedAt = 0;

  const selectedIndex = (): number => props.options.findIndex((o) => o.value === props.value);
  const selectedLabel = (): string | undefined => props.options[selectedIndex()]?.label;

  function openList(): void {
    if (props.disabled === true) return;
    // Open with the current choice under the cursor, so the first arrow press moves from where
    // you are rather than from the top of a list you did not ask to be at the top of.
    setActive(selectedIndex() >= 0 ? selectedIndex() : firstEnabled());
    setOpen(true);
  }

  function closeList(): void {
    setOpen(false);
    setActive(-1);
    trigger?.focus();
  }

  const firstEnabled = (): number => props.options.findIndex((o) => o.disabled !== true);

  /** Step over disabled options rather than landing on them and appearing stuck. */
  function step(from: number, delta: number): number {
    const n = props.options.length;
    if (n === 0) return -1;
    let i = from;
    for (let tries = 0; tries < n; tries += 1) {
      i = (i + delta + n) % n;
      if (props.options[i]?.disabled !== true) return i;
    }
    return from;
  }

  function commit(index: number): void {
    const option = props.options[index];
    if (option === undefined || option.disabled === true) return;
    props.onChange?.(option.value);
    closeList();
  }

  function onKeyDown(event: KeyboardEvent): void {
    if (props.disabled === true) return;

    if (!open()) {
      if (event.key === "ArrowDown" || event.key === "ArrowUp" || event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        openList();
      }
      return;
    }

    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        setActive(step(active(), 1));
        return;
      case "ArrowUp":
        event.preventDefault();
        setActive(step(active(), -1));
        return;
      case "Home":
        event.preventDefault();
        setActive(step(-1, 1));
        return;
      case "End":
        event.preventDefault();
        setActive(step(0, -1));
        return;
      case "Enter":
      case " ":
        event.preventDefault();
        commit(active());
        return;
      case "Escape":
        event.preventDefault();
        closeList();
        return;
      case "Tab":
        // Tab is not trapped: it means "I am leaving". Closing first keeps the menu from being
        // left open over a page the user has moved on from.
        setOpen(false);
        setActive(-1);
        return;
      default:
        break;
    }

    // Type-ahead. A list of 128 topics is unusable with arrows alone, and the native control this
    // one replaces had this behaviour, so removing it would be a regression nobody wrote down.
    if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      const now = Date.now();
      typed = now - typedAt > 800 ? event.key : typed + event.key;
      typedAt = now;
      const needle = typed.toLowerCase();
      const found = props.options.findIndex(
        (o) => o.disabled !== true && o.label.toLowerCase().startsWith(needle),
      );
      if (found >= 0) setActive(found);
    }
  }

  onSettled(() => {
    // Clicking away closes the menu. `pointerdown` rather than `click`, so the menu is gone before
    // whatever was clicked behind it reacts — otherwise the first click on a button elsewhere is
    // spent dismissing the menu and the operator has to press it twice.
    const onPointerDown = (event: PointerEvent): void => {
      if (!open()) return;
      if (root !== undefined && event.target instanceof Node && root.contains(event.target)) return;
      setOpen(false);
      setActive(-1);
    };
    document.addEventListener("pointerdown", onPointerDown, true);
    return () => document.removeEventListener("pointerdown", onPointerDown, true);
  });

  return (
    <div
      class={["kui-select", `kui-select--${p.size}`, props.class]}
      ref={(el) => (root = el)}
    >
      <span id={labelId} class={["kui-select__label", { "kui-visually-hidden": props.labelHidden === true }]}>
        {props.label}
      </span>
      <button
        type="button"
        ref={(el) => (trigger = el)}
        class="kui-select__trigger kui-focusable"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open() ? "true" : "false"}
        aria-controls={open() ? listId : undefined}
        aria-labelledby={labelId}
        aria-activedescendant={open() && active() >= 0 ? optionId(active()) : undefined}
        aria-describedby={props.disabled === true && props.disabledReason !== undefined ? `${uid}-why` : undefined}
        disabled={props.disabled === true}
        onClick={() => (open() ? closeList() : openList())}
        onKeyDown={onKeyDown}
      >
        <Show when={props.prefix}>{(prefix) => <span class="kui-select__prefix">{prefix()}</span>}</Show>
        <span
          class={[
            "kui-select__value",
            { "kui-select__value--placeholder": selectedLabel() === undefined },
          ]}
        >
          {/* An em dash would be wrong here: a select with nothing chosen is not a field with no
              value, it is a field waiting for one, and those are different pictures (SPEC §4.0). */}
          {selectedLabel() ?? props.placeholder ?? "Choose…"}
        </span>
        <Icon name="chevron-down" class="kui-select__chevron" />
      </button>
      <Show when={props.disabled === true && props.disabledReason !== undefined}>
        <span id={`${uid}-why`} class="kui-visually-hidden">
          {props.disabledReason}
        </span>
      </Show>
      <Show when={open()}>
        <ul id={listId} role="listbox" class="kui-select__listbox" aria-labelledby={labelId}>
          <Show
            when={props.options.length > 0}
            fallback={<li class="kui-select__empty">{p.emptyMessage}</li>}
          >
            <For each={props.options}>
              {(option, index) => (
                <li
                  id={optionId(index())}
                  role="option"
                  class="kui-select__option"
                  aria-selected={option.value === props.value ? "true" : "false"}
                  aria-disabled={option.disabled === true ? "true" : undefined}
                  data-active={index() === active() ? "true" : "false"}
                  // `pointerdown` and not `click`: a click would first blur the trigger, and the
                  // outside-pointerdown handler above would have closed the list underneath it.
                  onPointerDown={(event) => {
                    event.preventDefault();
                    commit(index());
                  }}
                  onPointerEnter={() => setActive(index())}
                >
                  <span class="kui-select__option-check">
                    <Show when={option.value === props.value}>
                      <Icon name="check" />
                    </Show>
                  </span>
                  {option.label}
                </li>
              )}
            </For>
          </Show>
        </ul>
      </Show>
    </div>
  );
}
