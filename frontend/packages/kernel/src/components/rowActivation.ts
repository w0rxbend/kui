/**
 * When a click on a clickable table row is the operator asking to open it, and when it is not.
 *
 * ## The defect this exists to end
 *
 * A row with `onRowClick` is a control: the whole `<tr>` opens the object. Rows also contain
 * ordinary links — a topic name, a broker, a consumer group. Put those two together with no rule
 * and a ⌘-click on the link inside the row does **both**: the browser opens the link in a
 * background tab because that is what ⌘-click means, and the row handler navigates the tab the
 * operator is still looking at. They lose the list they were working through, and the thing they
 * asked for is behind a tab they have to go and find. Shift-click is worse — a new window *and* a
 * navigation — and ctrl-click is the same gesture on Linux and Windows, which is most of this
 * product's operators.
 *
 * A modified click is never a request to activate the row. It is a request to open something
 * somewhere else, and the row cannot honour that: `onRowClick` navigates the current document, so
 * there is no "open this row in a new tab" for it to do instead. Declining is the whole behaviour.
 *
 * `button !== 0` is here for the same reason and not for a middle-click: browsers deliver a middle
 * click as `auxclick` rather than `click`, so this clause fires for a synthesized event or a device
 * that reports something else. A row that opens on a right-hand button is a row that opens while a
 * context menu is being asked for.
 *
 * ## Why it is one function and not two copies
 *
 * `DataTable` and `VirtualizedTable` draw the same `<tr class="kui-table__row">`, are chosen by how
 * many rows a screen expects rather than by how a row behaves, and are already the subject of one
 * comment apologising that a guard was fixed in one of them and not the other. Two copies of a rule
 * is two chances to fix half of it.
 *
 * The keyboard path deliberately does not consult this. Enter and Space carry no button, and a
 * modifier held with them is not the platform gesture for "open elsewhere" —
 * `event.preventDefault()` on the keydown is the rule that matters there.
 */

/**
 * Whether this click asked for the row to be opened in the current tab.
 *
 * @param event the `click` the row's own handler received
 */
export function activatesRow(event: MouseEvent): boolean {
  return (
    !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey && event.button === 0
  );
}
