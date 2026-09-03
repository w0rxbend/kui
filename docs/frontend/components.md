# Kernel components

The primitives every KUI screen is built from. They live in
`frontend/ui-kernel/src/kui/ui/kernel/component/` and are hand-written Laminar components: ADR-024
rejected a third-party widget library for M0, because the design system is KUI's own and a
third-party component set would fight it.

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
| `icon` | `Option[() => SvgElement]` — a thunk, because a DOM node can only be in one place at a time |

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
