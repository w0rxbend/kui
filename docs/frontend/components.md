# Kernel components

The primitives every KUI screen is built from. They live in
`frontend/packages/kernel/src/components/` and are hand-written SolidJS components: ADR-024 rejected
a third-party widget library, because the design system is KUI's own and a third-party component set
would fight it.

> **A note on this document.** It was written against the Laminar implementation that ADR-048
> replaced on 2026-09-05. The *rules* below — the accessibility contracts, the focus and keyboard
> behaviour, the degraded-rendering rule, the class names and the tokens — were ported and are
> current. The *signatures* are not: where an entry shows a Scala type such as
> `Option[() => SvgElement]` or a Laminar `Signal`, read the obligation and check the component's own
> `.tsx` for the current props. The set of components has also moved on since this inventory was
> taken. Re-taking it is outstanding work.

## Rules that apply to every primitive

These are worth reading once, because the individual entries below do not repeat them.

**A component owns no application state.** It takes a `Var` or a `Signal` from its caller and writes
back through an `Observer`. There is no internal `useState` equivalent and no hidden copy of the
caller's data that can drift out of step with it.

**Every component takes an optional `testId`, rendered as `data-testid`.** End-to-end tests select on
that and never on a CSS class or on visible text, so restyling a button or rewording its label cannot
break a test. A test that fails should mean the behaviour broke; nothing else.

**A disabled control is disabled in the DOM**, not merely styled to look it. The browser itself
refuses the interaction, and the observer is guarded as well, so a synthetic click cannot get through
either.

**No component reads a colour except through a token.** There is not one hex value in
`component/`, and there must never be (see [`tokens.md`](tokens.md)).

**No component needs its stylesheet to work.** Function comes from the HTML — a `<button>`, a
`disabled` attribute, a `role` — and CSS supplies appearance only. See "degraded rendering" in
[`README.md`](README.md).

**Missing data renders as `—`, not as a gap.** An empty cell is ambiguous: it could mean zero, or
"not measured", or "the request failed". The em dash says "we have no value here" out loud.

## Button

```scala
Button(
  label   = Val("Delete topic"),
  onClick = Observer[Unit](_ => state.confirmDelete()),
  variant = ButtonVariant.Danger,
  size    = Size.Md,
  loading = state.deleting,
  testId  = Some("topic-delete")
)
```

| Parameter | Meaning |
| --- | --- |
| `label` | `Signal[String]` — plenty of buttons change their own text ("Retry", "Stop") |
| `onClick` | `Observer[Unit]` |
| `variant` | `Primary` (the one action a screen exists for, at most one per view), `Secondary` (the default), `Danger` (destructive; always behind a `ConfirmDialog`), `Ghost` (reads as text until hovered — toolbar and row actions) |
| `size` | `Sm`, `Md`, `Lg` |
| `disabled`, `loading` | both set the DOM `disabled` attribute |
| `icon` | a *function* returning the element, never an element — under Laminar because a DOM node can only be in one place at a time, and under Solid because a JSX expression evaluated once cannot be rendered in two places either |

**Accessibility contract.** A real `<button type="button">`: reachable by Tab, activated by Enter and
Space, with no JavaScript of ours involved. While `loading` it carries `aria-busy="true"`, is
disabled, and shows a spinner *in place of* any icon so the button does not change width and the
layout does not jump.

**Why it exists at all**, rather than a plain `button(...)`: a busy control must not fire twice — a
double-clicked "Delete topic" that sends two requests is a real bug — and it has to say it is busy to
a screen reader. Both are the kind of thing that gets left out when every caller has to remember it.

## TextInput

```scala
TextInput(
  value = form.topicName,
  label = "Topic name",
  hint  = Some("Lower case, dots and hyphens"),
  error = form.topicNameError
)
```

**Accessibility contract.** The `<label>` is tied to the `<input>` by `for`/`id`, so a screen reader
announces it as the field's name. `aria-invalid` is `"true"` only while `error` is `Some`.
`aria-describedby` names the hint and the error message — and only the ones actually in the document,
because pointing at an absent element makes some screen readers announce nothing at all. The error
message carries `role="alert"`, so it is read out when it appears.

The id is generated per instance and cannot be derived from the label, because two "Name" fields on
one screen are perfectly ordinary.

**Binding.** Two-way and `controlled`: typing writes to the `Var`, and writing to the `Var` from
anywhere else — a form reset, a value arriving from the server — updates what is on screen. Without
`controlled`, the DOM and the `Var` drift apart as soon as both change in the same tick.

## Select

```scala
Select(
  options  = state.partitions.map(_.map(p => p -> s"Partition ${p.value}")),
  selected = state.partition,
  label    = "Partition"
)
```

A native `<select>`. A custom dropdown would have to reimplement keyboard navigation, type-ahead,
screen-reader announcements, touch behaviour and rendering a list that escapes its scrolling
ancestor; the browser already does all of it, and on a phone it opens the platform picker. A
searchable combobox arrives in M2 with the topic filters, where one is actually needed.

`Select[A]` is typed, but a `<select>` deals in strings, so each option is rendered with its
*position* as the DOM value and mapped back on change. Positions rather than `toString`, because two
distinct values may print identically and using the label as a key would silently merge them.

`selected = None` renders the placeholder row, which is how a required field starts out with nothing
chosen rather than silently defaulting to the first option.

## Tag

```scala
Tag(Val("Rebalancing"), tone = Tone.Warning, dot = true, live = true)
```

A small coloured label: a cleanup policy, a broker role, a consumer group state, an applied filter.

Colour is never the only signal — a tag always carries text, and the optional `dot` adds a second,
non-colour cue. Around one man in twelve cannot reliably tell red from green.

`live = true` renders `role="status"`, so a screen reader announces the tag when its text changes
without focus moving. Use it for something that changes on its own (a group going `Stable` →
`Rebalancing`) and leave it off for a static label: a page full of announcing tags announces nothing
useful.

`onRemove` adds a real `<button>`, labelled "Remove <the tag's text>" — "×" alone is read out as
"times".

## Card

A bounded region: a surface, a border, consistent padding, and `header` / `body` / `footer` slots.
`elevated` swaps the border for a shadow.

Deliberately dumb. It contributes no landmark role and no heading, because a card is a visual
grouping and not a semantic one; inventing a `<section>` or an `<h2>` here would put structure into
the document outline that the page's author did not ask for and cannot see. Callers pass their own
heading at the level their page actually needs.

## Tabs

```scala
Tabs(
  tabs = Val(List(
    Tab("overview", "Overview", () => OverviewPanel(state)),
    Tab("messages", "Messages", () => MessagesPanel(state))
  )),
  selected = state.tab
)
```

**Lazy panels.** Only the selected panel exists in the DOM. `Tab.body` is a thunk, and that is not an
optimisation detail: a topic page's "Consumers" tab issues requests when it is created, so building
all five panels up front would fire five screens' worth of traffic for a user who looks at one.

**Keyboard contract** (the WAI-ARIA "tabs" pattern). The strip is a *single* stop in the Tab order,
not one stop per tab. Once focus is inside it:

| Key | Effect |
| --- | --- |
| `←` `→` `↑` `↓` | move to the previous/next tab, wrapping at the ends |
| `Home` / `End` | jump to the first / last tab |
| `Tab` | leave the strip and land in the panel |

That is a *roving tabindex*: the selected tab is `tabindex="0"` and every other tab is
`tabindex="-1"`, so exactly one of them is in the browser's Tab order. Arrow keys change the
selection as well as the focus ("automatic activation"), which is right when switching is cheap and
reversible.

The tab carries `aria-selected` and `aria-controls`; the panel carries `role="tabpanel"` and
`aria-labelledby`, and is itself focusable so a keyboard user can reach panel content that holds no
focusable element of its own.

**Degraded input.** An empty tab list renders an empty strip and no panel. A `selected` id matching
no tab renders no panel rather than quietly jumping to the first one — silently changing the caller's
state to make a render succeed hides the bug that produced the bad id.

## Icon

~18 inline SVG outlines on a 24×24 grid, sized in `em` so an icon always matches the text beside it,
and stroked in `currentColor` so it is automatically the right colour in both themes.

Inline SVG rather than an icon font (a screen reader may read a glyph out as a private-use character,
and a failed font load leaves an empty box) and rather than a sprite sheet (one more request that has
to arrive before the page looks finished).

Every icon is `aria-hidden="true"`. Icons are decoration; the control around them carries the
meaning. An icon that is the only content of a control needs *that control* labelled, not the icon.

They are methods, not values: a DOM node can only be in one place at a time, so a shared `val` would
move itself out of the first place it was used.

Available: `chevronDown`, `chevronUp`, `chevronLeft`, `chevronRight`, `close`, `check`, `plus`,
`warning`, `info`, `refresh`, `external`, `search`, `menu`, `sun`, `moon`, `copy`, `dot`, `spinner`.

## Dialog

```scala
Dialog(
  open    = state.deleteOpen,
  title   = Val("Delete topic"),
  body    = () => p("This cannot be undone."),
  actions = () => List(cancelButton, deleteButton)
)
```

`body` and `actions` are thunks, and nothing exists while the dialog is closed. A dialog rendered up
front and hidden with CSS keeps its subscriptions alive, keeps its requests firing, and appears on
the page if the stylesheet fails to load. "Closed" here means "absent from the document", which is a
claim a test can check and a stylesheet cannot break.

**Accessibility contract.** `role="dialog"`, `aria-modal="true"`, `aria-labelledby` on the title.
Focus is trapped while it is open and restored to whatever opened it when it closes. `Escape` closes
it, as does a click on the backdrop — but only a click *on the backdrop itself*, so dragging a text
selection out of the panel does not discard the dialog. `dismissible = false` turns both off, for a
dialog in the middle of something that must be finished or explicitly abandoned.

## ConfirmDialog

Wraps `Dialog` for destructive actions: a message, a confirm label, and `danger` styling.

**The destructive button is not the one focused.** A confirmation exists to introduce a deliberate
pause. If the dangerous button had the initial focus, a user who was already pressing Enter — because
that is how they submitted the form that opened it — would confirm the deletion without reading a
word. Cancel comes first and takes the focus.

## Drawer

The same modal rules as `Dialog` — absent while closed, focus trapped, focus restored, `Escape`
closes — in a panel that slides in from the `Left` or `Right`. A dialog interrupts to ask a question;
a drawer opens a second surface beside the thing you are looking at (message detail, produce a
message, edit a filter).

## FocusTrap

Not a component; a `Modifier` that `Dialog` and `Drawer` attach.

A modal covers the page, but the page underneath is still in the browser's Tab order. A mouse user
never notices; a keyboard user tabs off the last button and lands somewhere invisible behind the
overlay. Restoring focus on close is the half that is nearly always missing: after closing a dialog
opened from a row's Delete button, focus has to go back to that button, or the next Tab press starts
again from the top of a two-hundred-row table.

It does *not* make the background inert to a screen reader — that needs `inert` or `aria-hidden` on
the rest of the document, which is the shell's job because only the shell knows what "the rest" is.

## Toast and NotificationBus

```scala
NotificationBus.push(
  Notification(
    tone     = Tone.Danger,
    title    = "Schema registry unavailable",
    dedupKey = Some("cap:schema:production")
  )
)
```

`NotificationBus` is one of the five kernel-owned `Var`s the plan allows. It is global because a
notification's point is that it outlives the screen that raised it: a request started on the topics
page and failing after the user has moved to consumers must still be reported.

| Rule | Why |
| --- | --- |
| Same `dedupKey` within 30 s collapses into one | ADR-032: the capability stream can report the same service going down repeatedly in seconds, and three identical toasts say nothing one does not |
| The window is about the event, not the screen | Deriving it from what is currently displayed would let a repeat reappear the instant the user dismissed the first, which is what makes a flapping service unbearable |
| `Danger` never dismisses itself | The whole reason to report a failure is that it needs attention, so it has to survive the user looking away. Other tones clear after 6–10 s |
| At most 5 visible, the rest queued and *never dropped* | The bound is about how many are shown. Discarding the rest would lose exactly the tone the user must not miss, and errors arrive in bursts |
| Newest first | The most recent thing to go wrong is the one being read about |

**Announcement.** The toast stack is a live region. `role="status"` (polite) waits for a pause in
whatever the screen reader is saying; `role="alert"` (assertive) interrupts and is reserved for
`Danger`. Using assertive everywhere is the common mistake and makes a screen reader unusable during
a burst. Focus is never moved to a toast: that would yank the user out of the form they were filling
in, which is worse than what was being reported.

## Tooltip

```scala
Tooltip(Button(...), Val("Ask the gateway to probe this service again"))
```

Three rules keep a tooltip usable, and all three are enforced by the implementation:

- **It appears on focus, not only on hover.** A hover-only tooltip does not exist for a keyboard user
  and does not exist on a touch screen.
- **It never contains anything interactive.** There is no way to move a pointer onto a tooltip
  without crossing the gap that dismisses it, so anything inside it is unreachable.
- **It is never the only place the information appears.** A tooltip is a hint. ADR-032's unavailable
  panel is the worked example: the reason, the timestamp and the retry are on the page.

The content element is always in the document and toggled with `hidden`, because `aria-describedby`
has to point at an element that exists.

## Breadcrumbs

`<nav aria-label="Breadcrumb">` around an ordered list. The last crumb is not a link and carries
`aria-current="page"`; the separators are `aria-hidden`, because "slash" read out between every step
is noise.

KUI's URLs nest four or five levels deep, and without a trail the only way back to the topic list is
the browser's Back button, which does the wrong thing after a few in-page navigations.

## EmptyState

An empty region is ambiguous: "there is nothing here", "your filter matched nothing", or "the request
failed and nobody said so". Each wants a different next action, so an empty list always says which it
is and offers the action that fixes it.

## DataTable

```scala
DataTable(
  columns = List(
    Column("id", "ID", broker => broker.id.toString, sortable = true),
    Column("bytesIn", "Bytes in", broker => broker.bytesIn.fold(DataTable.missing)(format))
  ),
  rows   = state.brokers,
  rowKey = _.id.toString,
  sort   = state.sort
)
```

Plain and non-virtualized: every row is in the DOM. That suits lists of tens or a few hundred —
brokers, consumer groups, schema versions, connectors. For thousands of rows use `VirtualizedTable`
below; `DataTable` keeps its callers and is not going away, because a list of thirty brokers does not
need a window and paying for one buys nothing.

**Rows are keyed.** The list is rendered with a keyed mapping that matches each item to its existing element by `rowKey`, so a
list that arrives reordered moves the elements instead of rebuilding them. That is not only faster: a
rebuilt row loses focus, loses a text selection, and closes whatever the user had expanded.

**Sorting cycles ascending → descending → unsorted.** The third state matters: without it there is no
way back to the server's natural order, which for brokers is broker id and for messages is offset.

**Accessibility contract.** A real `<table>` with `<th scope="col">`. A sortable header is a
`<button>` *inside* the `<th>`, because a clickable `<th>` is invisible to the keyboard, and the
`<th>` carries `aria-sort`.

**Loading and empty.** Loading dims the rows already there and sets `aria-busy`; replacing them with
a spinner would collapse the table, jump the page, and jump it back. When there are no rows, the
empty state replaces the body and the header stays, so the columns still say what the table would
have held.

## VirtualizedTable

```scala
VirtualizedTable(
  rows    = state.topics,          // Signal[List[TopicRow]]
  columns = TopicColumns.all,      // the same List[Column[A]] DataTable takes
  rowKey  = _.name,
  compact = prefs.compact,
  sort    = state.sort             // written, never read back to reorder
)
```

The same table as `DataTable`, except that only the rows the viewport can show — plus a few either
side — are in the document. Everything else is stood in for by two empty spacer rows whose heights
add up to the space the missing rows would have taken, so the scrollbar is the length of the whole
list while the document stays about twenty rows long. Ten thousand topics scroll at the same cost as
twenty.

**It is not a grid, and that is a decision rather than a gap.** No column resizing, no reordering, no
grouping, no pinned columns, no cell editing, no expandable rows, and no variable row heights. When a
screen genuinely needs a grid, the answer is to decide that a grid is a product feature and build
one — not to grow this component until it is a bad one. It also does no fetching and no paging: it
draws the rows it is handed, and paging is `Pagination`, a sibling.

**The row height is given, not measured.** Measuring would allow rows of different heights and would
make every scroll event read layout back out of the browser, so the cost would depend on what is in
the rows — fast in a test, slow on the one cluster that matters. A fixed height makes the window pure
arithmetic, which lives in `Window.slice` and is tested without a DOM at all. That is where every
off-by-one in a virtualizer is, and every one of them shows on screen as a flickering row, a blank
strip or a lying scrollbar rather than as an exception.

**One number, one place.** The row height is written onto the root element as the
`--kui-vtable-row-height` custom property, from the same integer the arithmetic uses, and
`25-virtualized-table.css` reads it back. A stylesheet that disagreed with the arithmetic would leave
a blank strip under the last row with nothing wrong on either side taken alone.

**Density.** `compact` is the design's density switch applied per table: the row goes from 48px to
36px, which is the design's 15px of padding around an 18px line box becoming 9px. It moves nothing
else — not the type size, not the control heights — because shrinking those makes an interface harder
to hit, not denser.

**Sorting belongs to the caller.** The `sort` `Var` is written and never read back to reorder the
rows. The screens that use this sort on the server: the topic list sorts ten thousand rows it has
never seen, of which it holds five hundred, and a table that quietly re-sorted its own page would
show the right rows in an order no page boundary matches.

**Accessibility.** `aria-rowcount` is the length of the whole list and each row's `aria-rowindex` is
its position in that list, not in the window — without that a screen reader on ten thousand topics
announces "row 3 of 12", which is the most misleading thing a virtualized table can say. Arrow keys
move a row, Page Up and Page Down a viewport's worth, Home and End the ends; the focus is remembered
as an *index* rather than as an element, because the element is destroyed when its row leaves the
window, and it is reapplied when the row comes back. The spacer rows are `role="presentation"` and
`aria-hidden`: they are layout.

**Testing it outside a browser.** jsdom performs no layout and reports every element as zero pixels
tall, so a component that could only measure itself would render an empty window in every test and
the tests would pass while asserting nothing. The `viewportHeight` parameter is the value the
component normally fills in from the real element; a suite (and the benchmark harness) sets it
directly. This is still true under Vitest, which runs in jsdom for the same reasons.

## `SearchBox`

```scala
SearchBox(
  value       = state.query,          // a Signal the caller owns
  onQuery     = state.search,         // fired with the trimmed query, after the debounce
  placeholder = "Search topics",
  mode        = Some((state.mode, state.setMode))
)
```

**The debounce is 300 ms and lives on the outgoing edge only.** What the field shows updates on every
keystroke; what the caller is told waits until the typing stops. The reference product waits 500 ms,
which is tuned for a backend that scans on each keystroke; KUI answers from an in-memory snapshot, so
300 ms still collapses a burst of typing into one request while feeling immediate.

**Clearing fires `onQuery("")`.** "No filter" is a request, not the absence of one. A clear button
that fires nothing leaves the previous filter applied, and the user reads the button as broken.

**The mode toggle is `plain` / `fts`** (`kui.kernel.search.SearchMode`, ADR-038). It is always shown,
because KUI's index is always available; each half carries a one-sentence tooltip, because "fuzzy" is
not self-explanatory.

## `Pagination`

```scala
Pagination(page, pageCount, pageSize, onPage = …, onPageSize = …)
```

**It renders nothing at all when there is one page.** A disabled pagination bar under three rows is
chrome that teaches nothing and occupies the space where the next thing should be.

**"Go to page" refuses out-of-range input rather than clamping it.** Turning a typed 900 into 12
shows a page the user did not ask for while letting them believe they got the one they typed.

**Page size is a control, not a preference.** It lives in the URL like everything else, so a link to
"page 3" shows its recipient the same rows the sender was looking at.

## `Favourites`

```scala
private val favourites = new Favourites("kui.favourites.topics")
favourites.pin(clusterId, rows)(_.name.value)
```

**Favourites never reach the server, and they pin within the page that is on screen.** The lists they
decorate are server-paged; if a favourite changed which items were on which page, two tabs of the
same user would disagree about what page 3 contains and a shared link would not reproduce.

**Every read and write is wrapped.** In a browser with site data blocked, `window.localStorage`
throws on property access — before any read or write. A preference that cannot be stored degrades to
"nothing is starred", never to a broken page.

## URL as state (`UrlParams`)

A list screen's whole state — `q`, `mode`, `sort`, `page`, `pageSize`, `showInternal` — is in the
query string. That is what makes "send me the link to what you are looking at" work, what makes Back
undo a filter instead of leaving the application, and what makes a bookmark to page 3 still page 3
tomorrow.

**Every write is a read-modify-write.** Each control owns one parameter and knows nothing about the
others, so `UrlParams.set` merges rather than replacing. A control that wrote the whole query string
would erase whatever a control it has never heard of had put there — which is how a page-size
selector silently clears a search box.

## Feature slots (`FeatureSlots`, `GuestTabs`)

A slot is a place on one feature's screen where another feature can put a panel. The ids are declared
once, in `FeatureSlots`, and never as a literal on either side: a host and a guest that cannot see
each other typing the same string in two files is a defect no test on either side can catch — the
guest registers, the host renders, and the tab simply never appears.

`GuestTabs.merged(own, FeatureRegistry.loaded, host, FeatureSlots.TopicTabs, context)` gives a host
its own tabs followed by one tab per registered guest, ordered by the guests' sidebar order rather
than by the order their modules finished downloading. A guest registers a `PanelContribution` with a
`tabLabel` and appears; it never has to edit a file in the host's feature.

**A host page never causes a download.** The list is derived from the *loaded* features only, so a
guest's tab appears once its feature has loaded for some other reason and the strip is one tab
shorter until then.
