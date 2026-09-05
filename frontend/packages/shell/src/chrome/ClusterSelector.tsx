import { For, Show, createSignal, createUniqueId, onSettled } from "solid-js";
import { Icon } from "@kui/kernel";
import type { ClusterSummary } from "./types.js";

/**
 * The top bar's cluster selector: which cluster the whole application is pointed at.
 *
 * ## Why this is a listbox and not a `<select>`
 *
 * Each row carries a health dot and a version as well as a name, and a native `<select>` can hold
 * only text. The cost of leaving the native control behind is that every keyboard behaviour it gave
 * away for free has to be written out: Up, Down, Home, End, Enter, Escape, and returning focus to
 * the button when the menu closes. All of it is here, and it is tested, because a control that
 * looks like a menu and does not behave like one is worse than a plain `<select>` would have been.
 *
 * ## Zero and one cluster are both real
 *
 * With one cluster the control still opens: the menu is where "add a cluster" lives, so a
 * deployment with a single cluster still needs a way in. With none it reads "no cluster" and opens
 * straight to the add form. Neither case is an error and neither is drawn as one.
 */
export type ClusterSelectorProps = {
  readonly clusters: readonly ClusterSummary[];
  readonly currentId?: string | undefined;
  readonly onSelect?: ((id: string) => void) | undefined;
  readonly onAdd?: (() => void) | undefined;
};

export function ClusterSelector(props: ClusterSelectorProps) {
  const id = createUniqueId();
  const menuId = `kui-cluster-menu-${id}`;
  const [open, setOpen] = createSignal(false);
  const [activeIndex, setActiveIndex] = createSignal(0);

  let trigger: HTMLButtonElement | undefined;
  let menu: HTMLDivElement | undefined;

  const current = () => props.clusters.find((c) => c.id === props.currentId) ?? props.clusters[0];
  const label = () => current()?.name ?? "no cluster";

  const close = (restoreFocus: boolean) => {
    setOpen(false);
    if (restoreFocus) trigger?.focus();
  };

  const choose = (index: number) => {
    const cluster = props.clusters[index];
    if (cluster) props.onSelect?.(cluster.id);
    close(true);
  };

  /* Clicking anywhere else closes the menu. Registered on the document because the click that
   * dismisses a popup very often lands on something that is not inside it — that is the whole point
   * of dismissing it — and cleaned up on disposal so a page with many of these does not accumulate
   * listeners. */
  onSettled(() => {
    const onDocumentPointerDown = (event: PointerEvent) => {
      const target = event.target as Node | null;
      if (!target) return;
      if (menu?.contains(target) === true || trigger?.contains(target) === true) return;
      setOpen(false);
    };
    document.addEventListener("pointerdown", onDocumentPointerDown);
    return () => document.removeEventListener("pointerdown", onDocumentPointerDown);
  });

  const openMenu = () => {
    const index = Math.max(
      0,
      props.clusters.findIndex((c) => c.id === props.currentId),
    );
    setActiveIndex(index);
    setOpen(true);
  };

  const onTriggerKeyDown = (event: KeyboardEvent) => {
    if (event.key === "ArrowDown" || event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      openMenu();
    }
  };

  const onMenuKeyDown = (event: KeyboardEvent) => {
    const last = props.clusters.length - 1;
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        setActiveIndex((i) => (i >= last ? 0 : i + 1));
        break;
      case "ArrowUp":
        event.preventDefault();
        setActiveIndex((i) => (i <= 0 ? last : i - 1));
        break;
      case "Home":
        event.preventDefault();
        setActiveIndex(0);
        break;
      case "End":
        event.preventDefault();
        setActiveIndex(last);
        break;
      case "Enter":
      case " ":
        event.preventDefault();
        choose(activeIndex());
        break;
      case "Escape":
        event.preventDefault();
        close(true);
        break;
      case "Tab":
        /* Tabbing out is a dismissal, not a cancellation: focus is already going somewhere the
         * user chose, so it is not taken back to the trigger. */
        close(false);
        break;
      default:
        break;
    }
  };

  return (
    <div class="kui-cluster-select" data-testid="cluster-selector">
      <button
        type="button"
        class="kui-cluster-select__trigger"
        ref={(el) => (trigger = el)}
        aria-haspopup="listbox"
        /* A string, not a boolean: `false` on a boolean attribute removes it, and a trigger with
         * no aria-expanded is announced as a button that does nothing rather than as one that
         * opens a list. ARIA states are strings, always. */
        aria-expanded={open() ? "true" : "false"}
        aria-controls={menuId}
        aria-label={`Cluster: ${label()}. Change cluster`}
        onClick={() => (open() ? close(false) : openMenu())}
        onKeyDown={onTriggerKeyDown}
        data-testid="cluster-selector-trigger"
      >
        <span
          class={["kui-cluster-select__dot", `kui-cluster-select__dot--${current()?.health ?? "unknown"}`]}
          aria-hidden="true"
        />
        <span class="kui-cluster-select__name">{label()}</span>
        <Icon name="chevron-down" size="14px" />
      </button>

      <Show when={open()}>
        <div class="kui-cluster-select__menu" ref={(el) => (menu = el)} data-testid="cluster-selector-menu">
          <div
            class="kui-cluster-select__options"
            id={menuId}
            role="listbox"
            aria-label="Clusters"
            tabindex={0}
            /* Focus stays on this container and the "active" row is pointed at rather than
             * focused. That is the listbox pattern: moving real focus row to row would fire a focus
             * event per arrow key and make a screen reader re-announce the whole container. */
            aria-activedescendant={
              props.clusters[activeIndex()] ? `${menuId}-${props.clusters[activeIndex()]!.id}` : undefined
            }
            ref={(el) => {
              /* Focus moves into the menu so that the arrow keys reach it. Deferred to the next
               * settle, because focusing a node in the same tick it is inserted is a race the
               * browser sometimes loses. */
              onSettled(() => {
                el.focus();
                return undefined;
              });
            }}
            onKeyDown={onMenuKeyDown}
          >
            <For each={props.clusters}>
              {(cluster, index) => (
                <div
                  class={[
                    "kui-cluster-select__option",
                    { "kui-cluster-select__option--active": index() === activeIndex() },
                  ]}
                  role="option"
                  id={`${menuId}-${cluster.id}`}
                  aria-selected={cluster.id === props.currentId ? "true" : "false"}
                  onClick={() => choose(index())}
                  onMouseEnter={() => setActiveIndex(index())}
                  data-testid={`cluster-option-${cluster.id}`}
                >
                  <span
                    class={["kui-cluster-select__dot", `kui-cluster-select__dot--${cluster.health}`]}
                    aria-hidden="true"
                  />
                  <span class="kui-cluster-select__option-name">{cluster.name}</span>
                  <span class="kui-cluster-select__option-detail">
                    {cluster.health}
                    {cluster.version ? ` \u00b7 ${cluster.version}` : ""}
                  </span>
                  {/* A tick as well as `aria-selected`, because the selected row must be
                      distinguishable without hearing the accessibility tree. */}
                  <Show when={cluster.id === props.currentId}>
                    <Icon name="check" size="14px" class="kui-cluster-select__check" />
                  </Show>
                </div>
              )}
            </For>
          </div>
          {/* Outside the listbox on purpose: a listbox may contain options and groups of options,
              and nothing else. A button living among the options is announced as an option that
              cannot be chosen. */}
          <button type="button" class="kui-cluster-select__add" onClick={() => props.onAdd?.()}>
            <Icon name="plus" size="14px" />
            Add a cluster
          </button>
        </div>
      </Show>
    </div>
  );
}
